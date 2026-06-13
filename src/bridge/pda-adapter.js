/**
 * X8 安卓壳子 → Capacitor 同款接口适配器
 *
 * X8 通信机制：
 *   H5 → 原生：PDAJsBridge.SendControlCommand(JSON.stringify(cmd))
 *   原生 → H5：window.ReceiveControlCommand({ name, data })
 *
 * 本文件把上面的命令/回调模式包成与 Capacitor registerPlugin 返回值
 * 完全相同的接口，使 scanner.js / printer.js / nfc.js 无需任何修改。
 */

// ── 中央事件分发 ─────────────────────────────────────────────────────────────
// ReceiveControlCommand 是单一全局入口，所有插件通过此处多路复用

const _subs = {}; // { eventName: [handler, ...] }

function _installReceiver() {
  if (window.__pdaReceiverInstalled) return;
  window.__pdaReceiverInstalled = true;
  window.ReceiveControlCommand = (action) => {
    (_subs[action.name] || []).forEach(fn => fn(action.data));
  };
}

function _sub(name, fn) {
  if (!_subs[name]) _subs[name] = [];
  _subs[name].push(fn);
  return {
    remove() { _subs[name] = _subs[name].filter(f => f !== fn); },
  };
}

function _send(cmd) {
  window.PDAJsBridge.SendControlCommand(JSON.stringify(cmd));
}

function wrapLabeledText(label, text, maxUnitsPerLine) {
  const safeText = String(text || '').trim();
  const lines = [];
  let current = label;
  let units = textUnits(label);
  for (const ch of safeText) {
    const next = charUnits(ch);
    if (units + next > maxUnitsPerLine && current !== label) {
      lines.push(current);
      current = '      ' + ch;
      units = textUnits('      ') + next;
    } else {
      current += ch;
      units += next;
    }
  }
  lines.push(current);
  return lines;
}

function textUnits(text) {
  let total = 0;
  for (const ch of text) total += charUnits(ch);
  return total;
}

function charUnits(ch) {
  return ch.charCodeAt(0) <= 0x7f ? 1 : 2;
}

// ── ScanPlugin ───────────────────────────────────────────────────────────────
// Capacitor 接口：addListener('scanResult', cb({ value })) / startScan() / stopScan()

export const ScanPlugin = {
  _ready: false,

  async addListener(event, handler) {
    _installReceiver();
    if (!this._ready) {
      _send({ name: 'setOnScan', data: 'onScan' });
      this._ready = true;
    }
    if (event === 'scanResult') {
      return _sub('onScan', (value) => handler({ value }));
    }
    return { remove() {} };
  },

  async startScan() {
    _send({ name: 'scan' });
  },

  async stopScan() {
    // X8 无显式停止命令
  },

  async removeAllListeners() {
    _subs['onScan'] = [];
  },
};

// ── PrintPlugin ──────────────────────────────────────────────────────────────
// Capacitor 接口：
//   addListener('printStatus', cb({ connection?, status?, flag? }))
//   connect() / prepareToPrintLabel() / printBatchLabel(p) / printMachineQR(p)
//
// X8 打印状态字符串 → Capacitor connection/status 字段映射

const _CONN_MAP = {
  PRINTER_CONNECT_SUCCESS: { connection: 'connected' },
  PRINTER_CONNECT_FAILED:  { connection: 'failed' },
  PRINTER_CLOSED:          { connection: 'closed' },
};

export const PrintPlugin = {
  async addListener(event, handler) {
    _installReceiver();
    if (event === 'printStatus') {
      return _sub('onPrint', (raw) => {
        handler(_CONN_MAP[raw] ?? { status: raw });
      });
    }
    return { remove() {} };
  },

  async connect() {
    _installReceiver();
    _send({ name: 'setOnPrint', data: 'onPrint' });
    // X8 没有异步握手，注册完即视为就绪，模拟 connected 事件
    setTimeout(() => (_subs['onPrint'] || []).forEach(fn => fn('PRINTER_CONNECT_SUCCESS')), 80);
  },

  /** 走纸到下一张标签起始位置（X8 = checkBlack） */
  async prepareToPrintLabel() {
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { h.remove(); reject(new Error('prepareToPrintLabel timeout')); }, 8000);
      const h = _sub('onPrint', (raw) => {
        if (raw === 'PREPARE_LABEL_OK') {
          clearTimeout(t); h.remove(); resolve();
        } else if (raw.startsWith('PREPARE_LABEL_')) {
          clearTimeout(t); h.remove(); reject(new Error(raw));
        }
      });
      _send({ name: 'checkBlack' });
    });
  },

  /**
   * 批次标签（一维码）
   * 与 PrintPlugin.java 的 printBatchLabel 布局一致：
   *   384×644  一维码(10,0 364×140)  批次码(0,180)  机器(0,234)  日期(0,282)
   *             品类最多两行(0,336...)  穴号、周期、栏号依次紧随其后；批次间额外走纸 96
   */
  async printBatchLabel({ batchNo, machineId = '', productType = '', cavityNo = '', date = '', periodLabel = '', laneNo = '' }) {
    const productLines = wrapLabeledText('品类：', productType, 18);
    const data = [
      { printType: 1, text: batchNo,
        desiredWidth: 364, desiredHeight: 140, displayCode: false, left: 10, top: 0 },
      { printType: 0, text: batchNo,             textSize: 38, x: 0, y: 180 },
      { printType: 0, text: `机器：${machineId}`, textSize: 34, x: 0, y: 234 },
      { printType: 0, text: `日期：${date}`,      textSize: 34, x: 0, y: 282 },
    ];
    let y = 336;
    for (const line of productLines) {
      data.push({ printType: 0, text: line, textSize: 34, x: 0, y });
      y += 42;
    }
    y += 16;
    data.push({ printType: 0, text: `穴号：${cavityNo}`,    textSize: 34, x: 0, y });
    data.push({ printType: 0, text: `周期：${periodLabel}`, textSize: 34, x: 0, y: y + 42 });
    data.push({ printType: 0, text: `栏号：${laneNo}`,      textSize: 34, x: 0, y: y + 84 });
    _send({
      name: 'printBmpLabel',
      width: 384, height: 644, top: 8, concentration: 15, forwardMorePaper: 96,
      data,
    });
  },

  /**
   * 机器标签（二维码）
   * 与 PrintPlugin.java 的 printMachineQR 布局一致：
   *   384×300  QR(92,8 200×200)  机器/品类/日期三行
   */
  async printMachineQR({ machineId = '', productType = '', date = '' }) {
    _send({
      name: 'printBmpLabel',
      width: 384, height: 300, top: 8, concentration: 15,
      data: [
        { printType: 2, text: machineId,
          desiredWidth: 200, desiredHeight: 200, displayCode: false, left: 92, top: 8 },
        { printType: 0, text: `机 器：${machineId}`,   textSize: 24, x: 16, y: 224 },
        { printType: 0, text: `品 类：${productType}`, textSize: 22, x: 16, y: 252 },
        { printType: 0, text: `日 期：${date}`,        textSize: 22, x: 16, y: 278 },
      ],
    });
  },

  /**
   * 每张 PRINT_OK 后走纸到下一张起始位（PDA 模式与 prepareToPrintLabel 等价）
   */
  async checkBlack() {
    return this.prepareToPrintLabel();
  },

  async removeAllListeners() {
    _subs['onPrint'] = [];
  },
};

// ── NfcPlugin ────────────────────────────────────────────────────────────────
// X8 不支持 NFC，返回存根让 nfc.js 正常 init（catch 后降级）

export const NfcPlugin = {
  async addListener() {
    throw new Error('NFC 不可用（X8 设备不支持）');
  },
  async writeNdef()      { throw new Error('NFC 不可用'); },
  async writeMifareRaw() { throw new Error('NFC 不可用'); },
  async clearTag()       { throw new Error('NFC 不可用'); },
  async removeAllListeners() {},
};
