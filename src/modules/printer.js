/**
 * 打印机模块
 *
 * 状态由此模块持有，通过事件通知 UI：
 *   printer:status  { connection?, status?, flag? }
 *
 * 对外方法：
 *   init()          首次连接，注册监听
 *   reset()         断开并重置状态（页面退出/重新进入时调用）
 *   printBatch(p)   打印批次标签
 *   isConnected()   当前是否已连接
 */
import { emit } from './events.js';

let _plugin    = null;
let _listener  = null;
let _connected = false;

export function isConnected() { return _connected; }

/**
 * 等待特定的 printStatus 事件（resolve），遇到错误状态时 reject。
 * 必须在调用 SDK 方法之前先创建 Promise，确保监听器在事件触发前已注册。
 */
function waitPrintStatus(wanted) {
  const ERRORS = [
    'NO_PAPER', 'PRINTER_CLOSED', 'SEND_DATA_FAILED', 'PRINT_FAILED',
    'BLACK_FLAG_NOT_FOUND',
    'PREPARE_LABEL_NO_PAPER', 'PREPARE_LABEL_BLACK_FLAG_NOT_FOUND',
    'PREPARE_LABEL_FAILED', 'PREPARE_LABEL_PRINTER_CLOSED',
    'PREPARE_LABEL_SEND_DATA_FAILED',
  ];
  return new Promise((resolve, reject) => {
    let sub = null;
    _plugin.addListener('printStatus', ({ status }) => {
      if (!status) return;
      if (status === wanted)          { sub?.remove(); resolve(); }
      else if (ERRORS.includes(status)) { sub?.remove(); reject(new Error(status)); }
    }).then(s => { sub = s; });
  });
}

export async function init(PrintPlugin) {
  _plugin = PrintPlugin;
  // 每次 init 都清掉旧监听，保证状态干净
  await reset();
  try {
    _listener = await _plugin.addListener('printStatus', (data) => {
      const { connection, status } = data;
      if (connection === 'connected') _connected = true;
      if (connection === 'failed' || connection === 'closed') _connected = false;
      emit('printer:status', data);
    });
    await _plugin.connect();
  } catch(e) {
    emit('printer:status', { connection: 'failed', error: e.message });
  }
}

export async function reset() {
  _connected = false;
  if (_listener) { try { _listener.remove(); } catch(_) {} _listener = null; }
  emit('printer:status', { connection: 'reset' });
}

/**
 * 打印批次标签（一维码），处理完整的多张打印流程。
 *
 * 流程：
 *   prepareToPrintLabel → PREPARE_LABEL_OK
 *     → printBatchLabel → PRINT_OK
 *     → checkBlack → PREPARE_LABEL_OK（若还有下一张）
 *     → ... 循环
 *
 * @param {{ batchNo, machineId, productType, date }} params
 *   - batchNo     15位批次码
 *   - machineId   机器编号（3位，如 M05）
 *   - productType 产品名称（人可读，如"电容"）
 *   - date        YYMMDD（从批次码中提取：batchNo.slice(3,9)）
 * @param {number} [count=1]  打印张数
 * @param {function} [onProgress]  进度回调 (current, total) => void
 */
export async function printBatches(params, count = 1, onProgress) {
  if (!_plugin) throw new Error('打印机未初始化');
  if (!_connected) throw new Error('打印机未连接，请先重连');

  for (let i = 0; i < count; i++) {
    onProgress?.(i + 1, count);
    const pPrint = waitPrintStatus('PRINT_OK');
    await _plugin.printBatchLabel(params);
    await pPrint;

    // 最后一张不需要走纸
    if (i < count - 1) {
      const pNext = waitPrintStatus('PREPARE_LABEL_OK');
      await _plugin.checkBlack();
      await pNext;
    }
  }
}

/** @deprecated 请改用 printBatches(params, 1) */
export async function printBatch(params) {
  return printBatches(params, 1);
}

/**
 * 打印机器标签（二维码）
 * 用于设置页绑定机器 NFC 时同步打印一张机器标识标签。
 * @param {{ machineId, productType, date }} params
 */
export async function printMachineLabel(params) {
  if (!_plugin) throw new Error('打印机未初始化');
  if (!_connected) throw new Error('打印机未连接，请先重连');
  await _plugin.printMachineQR(params);
}
