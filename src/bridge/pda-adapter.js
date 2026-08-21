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
//   connect() / prepareToPrintLabel() / printMachineQR(p) / printLabel(p)
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
   * 机器标签（二维码）
   * 与 PrintPlugin.java 的 printMachineQR 布局一致：
   *   384×344  QR(76,8 232×232)  机器/品类/日期三行
   */
  async printMachineQR({ machineId = '', productType = '', date = '' }) {
    _send({
      name: 'printBmpLabel',
      width: 384, height: 344, top: 8, concentration: 15,
      data: [
        { printType: 2, text: machineId,
          desiredWidth: 232, desiredHeight: 232, displayCode: false, left: 76, top: 8 },
        { printType: 0, text: `机 器：${machineId}`,   textSize: 24, x: 16, y: 264 },
        { printType: 0, text: `品 类：${productType}`, textSize: 22, x: 16, y: 292 },
        { printType: 0, text: `日 期：${date}`,        textSize: 22, x: 16, y: 318 },
      ],
    });
  },

  /**
   * 通用标签：
   * - qrCodeValue: 二维码内容
   * - textValue: 多行正文
   */
  async printLabel({ qrCodeValue = '', textValue = '', paperType = 'thermal', layoutPreset = 'standard' }) {
    const preset = String(layoutPreset || '').trim().toLowerCase();
    const isBlackMark = String(paperType || '').trim().toLowerCase() === 'black_mark';
    const layout = preset === 'compact'
      ? { qrWidth: 184, qrHeight: 184, qrLeft: 100, textSize: 22, lineHeight: 28, minHeight: 244, textLeft: 8 }
      : preset === 'large'
        ? { qrWidth: 232, qrHeight: 232, qrLeft: 76, textSize: 26, lineHeight: 34, minHeight: 308, textLeft: 8 }
        : { qrWidth: 208, qrHeight: 208, qrLeft: 88, textSize: 24, lineHeight: 32, minHeight: 280, textLeft: 8 };
    const lines = String(textValue || '').replace(/\r/g, '').split('\n');
    const data = [];
    if (qrCodeValue) {
      data.push({
        printType: 2,
        text: qrCodeValue,
        desiredWidth: layout.qrWidth,
        desiredHeight: layout.qrHeight,
        displayCode: false,
        left: layout.qrLeft,
        top: 8,
      });
    }
    const mediaBottom = qrCodeValue ? 8 + layout.qrHeight : 0;
    let y = mediaBottom > 0 ? mediaBottom + 24 : 16;
    for (const line of lines) {
      data.push({ printType: 0, text: String(line || ''), textSize: layout.textSize, x: layout.textLeft, y });
      y += layout.lineHeight;
    }
    const payload = {
      name: 'printBmpLabel',
      width: 384,
      height: Math.max(y + 16, layout.minHeight),
      top: 8,
      concentration: 15,
      data,
    };
    if (!isBlackMark) {
      payload.forwardMorePaper = 96;
    }
    _send(payload);
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
