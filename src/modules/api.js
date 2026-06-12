/**
 * 服务端 API 调用封装
 * 所有接口统一走 apiFetch，非 2xx 抛出含 detail 的 Error
 */
const SERVER_BASE = 'http://115.29.178.34:2974';

export async function apiFetch(path, opts = {}) {
  const headers = { 'Content-Type': 'application/json', ...(opts.headers || {}) };
  const url = path.startsWith('http') ? path : `${SERVER_BASE}${path}`;
  const res = await fetch(url, { ...opts, headers });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.detail ?? `请求失败 ${res.status}`);
  return data;
}

export const getMachineByCode = (code) =>
  apiFetch('/api/machines/code/' + encodeURIComponent(code));

export const getMachineById = (id) =>
  apiFetch('/api/machines/' + id);

/** 按批次码查批次详情（含产品名/类型/状态/质检结果） */
export const getBatchByNo = (batchNo) =>
  apiFetch('/api/batches/' + encodeURIComponent(batchNo));

/** 查某台机器的最近批次列表（按创建时间倒序） */
export const getBatchesByMachine = (machineId) =>
  apiFetch('/api/batches?machine_id=' + machineId);

export const createBatch = ({ machine_id, product_type, product_name }) =>
  apiFetch('/api/batches', {
    method: 'POST',
    body: JSON.stringify({ machine_id, product_type, product_name }),
  });

/** 查批次当前流程状态（活跃节点 + 可触发事件） */
export const getProcessState = (batchNo) =>
  apiFetch('/api/process/' + encodeURIComponent(batchNo));

/**
 * 提交流程事件，推进批次到下一个节点。
 * @param {{ batch_no, event_type, actor, from_node?, payload? }} params
 */
export const postEvent = ({ batch_no, event_type, actor, from_node, payload = {} }) =>
  apiFetch('/api/events', {
    method: 'POST',
    body: JSON.stringify({ batch_no, event_type, actor, from_node, payload }),
  });

/**
 * 上传表单 photo/file 字段的附件，返回 { filename, original_filename, url }。
 * filename 应作为该字段在事件 payload 中的值。
 */
export async function uploadEventFile(batchNo, file) {
  const fd = new FormData();
  fd.append('batch_no', batchNo);
  fd.append('file', file);
  const res = await fetch(`${SERVER_BASE}/api/events/upload`, { method: 'POST', body: fd });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.detail ?? `上传失败 ${res.status}`);
  return data;
}
