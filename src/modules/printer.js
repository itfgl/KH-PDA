/**
 * 打印机模块
 *
 * 状态由此模块持有，通过事件通知 UI：
 *   printer:status  { connection?, status?, flag? }
 *
 * 对外方法：
 *   init()              首次连接，注册监听
 *   reset()             断开并重置状态（页面退出/重新进入时调用）
 *   printMachineLabel() 打印机器二维码标签
 *   isConnected()       当前是否已连接
 *
 * 批次标签等业务打印已迁移到动作表（print_label / print_batch_label），
 * 由 client-runtime 走通用二维码路径，不再使用本模块的一维码批次接口。
 */
import { emit } from './events.js';

let _plugin    = null;
let _listener  = null;
let _connected = false;

export function isConnected() { return _connected; }

export async function init(PrintPlugin) {
  _plugin = PrintPlugin;
  // 每次 init 都清掉旧监听，保证状态干净
  await reset();
  try {
    _listener = await _plugin.addListener('printStatus', (data) => {
      const { connection } = data;
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
 * 打印机器标签（二维码）
 * 用于设置页绑定机器 NFC 时同步打印一张机器标识标签。
 * @param {{ machineId, productType, date }} params
 */
export async function printMachineLabel(params) {
  if (!_plugin) throw new Error('打印机未初始化');
  if (!_connected) throw new Error('打印机未连接，请先重连');
  await _plugin.printMachineQR(params);
}
