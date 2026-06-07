/**
 * 机器 + 批次业务逻辑（简化版）
 *
 * 触发来源：nfc:detected / scanner:result（机器码）
 *
 * 流程：
 *   1. 查机器详情（GET /api/machines/code/{code}）→ machine:loaded
 *   2. 若有 latest_batch_no → 查批次详情（GET /api/batches/{no}）→ batch:loaded
 *   3. 若没有批次 → batch:none
 *   批次创建由 BatchEntryPage 负责，此处不自动创建。
 *
 * 发出的事件：
 *   machine:loading  { code }
 *   machine:loaded   { machine }
 *   machine:error    { msg }
 *   batch:loaded     { batch, machine }
 *   batch:none       { machine }
 *   batch:error      { msg }
 */
import { on, emit } from './events.js';
import { getMachineByCode, getBatchByNo } from './api.js';

export function init() {
  on('nfc:detected',   ({ code })  => handleCode(code));
  on('scanner:result', ({ value }) => handleCode(value));
}

const NFC_PREFIX = 'kaihang://nfc/';

function normalizeCode(raw) {
  // 剥 URI 前缀
  let s = raw.startsWith(NFC_PREFIX) ? raw.slice(NFC_PREFIX.length) : raw;
  // 取第一段（兼容 M53|—|260607 格式）
  s = s.split('|')[0];
  // 剥开头的非字母数字字符（兼容扫码枪 GS1 前缀噪音，如 \000026 M53）
  s = s.replace(/^[^A-Za-z0-9]+/, '').trim();
  return s;
}

async function handleCode(raw) {
  const code = normalizeCode(raw);
  emit('machine:loading', { code });

  let machine;
  try {
    machine = await getMachineByCode(code);
    emit('machine:loaded', { machine });
  } catch(e) {
    emit('machine:error', { msg: e.message });
    return;
  }

  if (!machine.latest_batch_no) {
    emit('batch:none', { machine });
    return;
  }

  try {
    const batch = await getBatchByNo(machine.latest_batch_no);
    emit('batch:loaded', { batch, machine });
  } catch(e) {
    emit('batch:error', { msg: '批次查询失败：' + e.message });
  }
}
