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
 *   batch:group      { batches }  扫机器码时，当前批次按穴号拆分出的全部子批次（无拆分则不发）
 *   batch:none       { machine }
 *   batch:error      { msg }
 */
import { on, emit } from './events.js';
import { getMachineByCode, getMachineById, getBatchByNo, getBatchesByParent } from './api.js';

// 扫码捕获钩子：表单中 scan/nfc 类型字段聚焦时由 UI 设置。
// 钩子返回 true 表示扫码结果已被表单字段消费，不再走机器/批次查询。
let _capture = null;
export function setCapture(fn) { _capture = fn; }

export function init() {
  on('nfc:detected',   ({ code })  => dispatch(code));
  on('scanner:result', ({ value }) => dispatch(value));
}

function dispatch(raw) {
  if (_capture && _capture(raw)) return;
  handleCode(raw);
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

/** 批次码 15 位（机器码3 + 日期6 + 产品码2 + 流水4），机器码最长 4 位 */
function isBatchNo(code) {
  return code.length >= 10;
}

async function handleCode(raw) {
  const code = normalizeCode(raw);
  emit('machine:loading', { code });

  try {
    let machine, batch;

    if (isBatchNo(code)) {
      // ── 批次码路径（扫码枪扫批次标签）────────────────────────────────
      batch   = await getBatchByNo(code);
      machine = await getMachineById(batch.machine_id);
      emit('machine:loaded', { machine });
      emit('batch:loaded', { batch, machine });
    } else {
      // ── 机器码路径（PDA 扫机器 NFC 或机器条码）────────────────────────
      machine = await getMachineByCode(code);
      emit('machine:loaded', { machine });

      if (!machine.latest_batch_no) {
        emit('batch:none', { machine });
        return;
      }

      batch = await getBatchByNo(machine.latest_batch_no);
      emit('batch:loaded', { batch, machine });

      // 开机按穴号拆分过：取同组全部子批次，供整组补打标签
      if (batch.parent_batch_no) {
        const group = await getBatchesByParent(batch.parent_batch_no);
        if (group.length) emit('batch:group', { batches: group });
      }
    }
  } catch(e) {
    emit('machine:error', { msg: e.message });
  }
}
