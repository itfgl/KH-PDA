/**
 * 机器 + 批次业务逻辑
 *
 * 监听 nfc:detected / scanner:result，查询机器信息，
 * 若机器生产中则自动创建批次。
 *
 * 事件：
 *   machine:loading  { code }               开始查询
 *   machine:loaded   { machine }            查询成功
 *   machine:error    { msg }                查询失败
 *   batch:creating   {}                     开始创建批次
 *   batch:created    { batch, machine }     批次创建成功
 *   batch:error      { msg }                批次创建失败
 */
import { on, emit } from './events.js';
import { getMachineByCode, createBatch } from './api.js';

export function init() {
  on('nfc:detected',    ({ code }) => handleCode(code));
  on('scanner:result',  ({ value }) => handleCode(value));
}

async function handleCode(code) {
  emit('machine:loading', { code });
  try {
    const machine = await getMachineByCode(code);
    emit('machine:loaded', { machine });

    if (machine.status === 'running' && machine.current_product_type) {
      emit('batch:creating', {});
      try {
        const product_name = machine.current_product_name
          || machine.current_product_type
          || 'unknown';
        const batch = await createBatch({
          machine_id:   machine.id,
          product_type: machine.current_product_type,
          product_name,
        });
        emit('batch:created', { batch, machine });
      } catch(e) {
        emit('batch:error', { msg: e.message });
      }
    }
  } catch(e) {
    emit('machine:error', { msg: e.message });
  }
}
