/**
 * 机器 + 批次业务逻辑
 *
 * 触发来源：nfc:detected / scanner:result（机器码）
 *
 * 流程：
 *   1. 查询机器详情（GET /api/machines/code/{code}）
 *   2. 若机器有最新批次 → 查批次详情（GET /api/batches/{batch_no}）
 *        - status = created / inspecting → 展示现有批次，不重复创建
 *        - status = done / failed        → 自动创建新批次（上一轮已完成）
 *   3. 若机器没有批次
 *        - running 且有产品类型 → 自动创建第一个批次
 *        - 否则 → 展示空状态
 *   4. 非 running 机器：仅展示最新批次（只读，不创建）
 *
 * 事件（由此模块 emit，UI 层订阅）：
 *   machine:loading  { code }              开始查询
 *   machine:loaded   { machine }           机器信息就绪
 *   machine:error    { msg }               机器查询失败
 *   batch:creating   {}                    正在创建批次
 *   batch:created    { batch, machine }    新批次创建完成
 *   batch:loaded     { batch, machine }    已有批次加载完成
 *   batch:none       { machine }           无批次且不会自动创建
 *   batch:error      { msg }               批次操作失败
 */
import { on, emit } from './events.js';
import { getMachineByCode, getBatchByNo, createBatch } from './api.js';

export function init() {
  on('nfc:detected',   ({ code })  => handleCode(code));
  on('scanner:result', ({ value }) => handleCode(value));
}

// ── 主流程 ────────────────────────────────────────────────────────────────────

async function handleCode(code) {
  emit('machine:loading', { code });

  let machine;
  try {
    machine = await getMachineByCode(code);
    emit('machine:loaded', { machine });
  } catch(e) {
    emit('machine:error', { msg: e.message });
    return;
  }

  // 非运行状态：只展示最新批次，不自动创建
  if (machine.status !== 'running') {
    if (machine.latest_batch_no) {
      await loadBatch(machine.latest_batch_no, machine);
    } else {
      emit('batch:none', { machine });
    }
    return;
  }

  // 运行中：无批次 → 直接创建
  if (!machine.latest_batch_no) {
    await autoCreateBatch(machine);
    return;
  }

  // 运行中：有批次 → 看状态决定展示还是重新创建
  let batch;
  try {
    batch = await getBatchByNo(machine.latest_batch_no);
  } catch(e) {
    emit('batch:error', { msg: '批次查询失败：' + e.message });
    return;
  }

  if (batch.status === 'done' || batch.status === 'failed') {
    // 上一轮已结束，开始新一轮
    await autoCreateBatch(machine);
  } else {
    // created / inspecting：继续使用当前批次
    emit('batch:loaded', { batch, machine });
  }
}

// ── 辅助 ─────────────────────────────────────────────────────────────────────

async function loadBatch(batchNo, machine) {
  try {
    const batch = await getBatchByNo(batchNo);
    emit('batch:loaded', { batch, machine });
  } catch(e) {
    emit('batch:error', { msg: '批次查询失败：' + e.message });
  }
}

async function autoCreateBatch(machine) {
  if (!machine.current_product_type) {
    emit('batch:none', { machine });
    return;
  }
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
    emit('batch:error', { msg: '批次创建失败：' + e.message });
  }
}
