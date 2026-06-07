/**
 * 扫码枪模块
 *
 * 事件：
 *   scanner:result  { value }   扫到内容
 *   scanner:status  { msg, type } 状态变化（info / ok / err）
 *
 * 对外方法：
 *   init(ScanPlugin)   注册广播监听（App 启动时调用一次）
 *   trigger()          主动触发一次扫码
 *   stop()             停止扫码
 *   reset()            清除上次扫码结果状态
 */
import { emit } from './events.js';

let _plugin   = null;
let _inited   = false;

export async function init(ScanPlugin) {
  if (_inited) return;
  _plugin = ScanPlugin;
  emit('scanner:log', { msg: '[Scanner] init 开始', type: 'info' });
  try {
    await _plugin.addListener('scanResult', ({ value }) => {
      emit('scanner:log', { msg: '[Scanner] 收到结果: ' + value, type: 'ok' });
      emit('scanner:result', { value });
      emit('scanner:status', { msg: '就绪', type: '' });
      // X8: setOnScan 永久注册，物理扳机无需软件重新 arm
    });
    _inited = true;
    emit('scanner:log', { msg: '[Scanner] addListener 注册成功，插件就绪', type: 'ok' });
    emit('scanner:status', { msg: '扫码枪就绪', type: 'ok' });
  } catch(e) {
    emit('scanner:log', { msg: '[Scanner] init 失败: ' + (e?.message ?? e), type: 'err' });
    emit('scanner:status', { msg: '扫码枪不可用（非 X8 设备）', type: 'warn' });
  }
}

export async function trigger() {
  if (!_plugin) return;
  emit('scanner:log', { msg: '[Scanner] trigger → startScan', type: 'info' });
  emit('scanner:status', { msg: '等待扫码枪…', type: 'info' });
  try {
    await _plugin.startScan();
    emit('scanner:log', { msg: '[Scanner] startScan 已发出', type: 'ok' });
  } catch(e) {
    emit('scanner:log', { msg: '[Scanner] startScan 失败: ' + (e?.message ?? e), type: 'err' });
    emit('scanner:status', { msg: '触发失败：' + e.message, type: 'err' });
  }
}

export async function stop() {
  if (!_plugin) return;
  try { await _plugin.stopScan(); } catch(_) {}
}

export function reset() {
  emit('scanner:status', { msg: '就绪', type: '' });
}
