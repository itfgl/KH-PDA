/**
 * 服务端 API 调用封装
 * 所有接口统一走 apiFetch，非 2xx 抛出含 detail 的 Error
 */
export async function apiFetch(path, opts = {}) {
  const headers = { 'Content-Type': 'application/json', ...(opts.headers || {}) };
  const res = await fetch(path, { ...opts, headers });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.detail ?? `请求失败 ${res.status}`);
  return data;
}

export const getMachineByCode = (code) =>
  apiFetch('/api/machines/code/' + encodeURIComponent(code));

/** 按批次码查批次详情（含产品名/类型/状态/质检结果） */
export const getBatchByNo = (batchNo) =>
  apiFetch('/api/batches/' + encodeURIComponent(batchNo));

export const createBatch = ({ machine_id, product_type, product_name }) =>
  apiFetch('/api/batches', {
    method: 'POST',
    body: JSON.stringify({ machine_id, product_type, product_name }),
  });
