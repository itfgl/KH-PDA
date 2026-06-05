/**
 * NFC 模块（全程持续监听）
 *
 * [FIX] reader mode 由原生 KaihangNfcPlugin.load() 自动激活，
 *       JS 层只负责事件分发，不调用 startScanning()
 *
 * nfcMode 控制贴卡时的行为：
 *   'scan'  → 提取机器码，emit('nfc:detected') 或 emit('nfc:blank')
 *   'write' → 由 writeTag() 的一次性监听处理
 *   'clear' → 由 clearTag() 的一次性监听处理
 *
 * 事件：
 *   nfc:detected  { code }    卡片含凯航数据
 *   nfc:blank     {}          空白卡
 *   nfc:written   {}          写卡成功
 *   nfc:cleared   {}          清卡成功
 *   nfc:error     { msg }     操作失败
 *   nfc:ready     {}          监听器注册成功
 *
 * 对外方法：
 *   init(KaihangNfc)   启动全局监听（App 启动时调用一次）
 *   setMode(mode)      切换 nfcMode（外部慎用）
 *   writeTag(data)     写入机器码，返回 Promise
 *   clearTag()         清空卡片，返回 Promise
 */
import { emit } from './events.js';

const NFC_PREFIX = 'kaihang://nfc/';
let _plugin  = null;
let _mode    = 'scan'; // 'scan' | 'write' | 'clear'

export function getMode() { return _mode; }
export function setMode(m) { _mode = m; }

function extractCode(event) {
  if (event.ndefMessage) {
    for (const r of event.ndefMessage) {
      if (r.tnf === 1 && new TextDecoder().decode(new Uint8Array(r.type)) === 'U') {
        const uri = new TextDecoder().decode(new Uint8Array(r.payload).slice(1));
        if (uri.startsWith(NFC_PREFIX)) return uri.replace(NFC_PREFIX, '').trim();
      }
    }
  }
  return event.mifareData?.trim() || null;
}

export async function init(KaihangNfc) {
  _plugin = KaihangNfc;
  try {
    // [FIX] 全程持续监听，不能 remove，详见 NFC_RULES.md
    await _plugin.addListener('nfcEvent', async (event) => {
      if (_mode === 'scan') {
        const code = extractCode(event);
        if (code) emit('nfc:detected', { code });
        else      emit('nfc:blank', {});
      }
      // write / clear 模式由各自方法内的一次性监听处理
    });
    emit('nfc:ready', {});
  } catch(e) {
    emit('nfc:error', { msg: '监听启动失败：' + e.message });
  }
}

export function writeTag(data) {
  return new Promise(async (resolve, reject) => {
    if (!_plugin) { reject(new Error('NFC 未初始化')); return; }
    _mode = 'write';
    let listener = null;
    try {
      listener = await _plugin.addListener('nfcEvent', async (event) => {
        listener.remove(); listener = null;
        _mode = 'scan';
        try {
          if (event.isNdef)             await _plugin.writeNdef({ data, allowFormat: true });
          else if (event.isMifareUltralight) await _plugin.writeMifareRaw({ data });
          else throw new Error('不支持的卡类型');
          emit('nfc:written', {});
          resolve();
        } catch(e) {
          emit('nfc:error', { msg: '写卡失败：' + e.message });
          reject(e);
        }
      });
    } catch(e) {
      _mode = 'scan'; reject(e);
    }
  });
}

export function clearTag() {
  return new Promise(async (resolve, reject) => {
    if (!_plugin) { reject(new Error('NFC 未初始化')); return; }
    _mode = 'clear';
    let listener = null;
    try {
      listener = await _plugin.addListener('nfcEvent', async () => {
        listener.remove(); listener = null;
        _mode = 'scan';
        try {
          await _plugin.clearTag();
          emit('nfc:cleared', {});
          resolve();
        } catch(e) {
          emit('nfc:error', { msg: '清卡失败：' + e.message });
          reject(e);
        }
      });
    } catch(e) {
      _mode = 'scan'; reject(e);
    }
  });
}
