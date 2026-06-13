/**
 * 服务端 API 调用封装
 * 所有接口统一走 apiFetch，非 2xx 抛出含 detail 的 Error
 */
const SERVER_BASE = 'http://115.29.178.34:2974';

// ── 登录态（token + 当前用户，持久化到 localStorage）──────────────────────────
const TOKEN_KEY = 'kh_token';
const USER_KEY  = 'kh_user';

export const getToken = () => localStorage.getItem(TOKEN_KEY) || '';

export function getCurrentUser() {
  try { return JSON.parse(localStorage.getItem(USER_KEY) || 'null'); }
  catch { return null; }
}

function setSession(token, user) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function logout() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

/** 401 时抛出此错误，外层据此跳回登录页 */
export class AuthError extends Error {}

/** 登录失效时广播全局事件，UI 层据此弹回登录页（与 DOM 解耦） */
function notifyAuthExpired() {
  try { window.dispatchEvent(new CustomEvent('kh:auth-expired')); } catch { /* 非浏览器环境忽略 */ }
}

function authHeaders() {
  const t = getToken();
  return t ? { Authorization: `Bearer ${t}` } : {};
}

export async function apiFetch(path, opts = {}) {
  const headers = { 'Content-Type': 'application/json', ...authHeaders(), ...(opts.headers || {}) };
  const url = path.startsWith('http') ? path : `${SERVER_BASE}${path}`;
  const res = await fetch(url, { ...opts, headers });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) { logout(); notifyAuthExpired(); throw new AuthError(data.detail ?? '登录已失效'); }
  if (!res.ok) throw new Error(data.detail ?? `请求失败 ${res.status}`);
  return data;
}

/** 用户名/密码登录，成功后持久化 token + 用户信息并返回 user */
export async function login(username, password) {
  const res = await fetch(`${SERVER_BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.detail ?? `登录失败 ${res.status}`);
  setSession(data.token, data.user);
  return data.user;
}

/** 用已存 token 拉当前用户，校验登录是否仍有效（失败抛 AuthError） */
export async function fetchMe() {
  if (!getToken()) throw new AuthError('未登录');
  const user = await apiFetch('/api/auth/me');
  localStorage.setItem(USER_KEY, JSON.stringify(user));
  return user;
}

export const getMachineByCode = (code) =>
  apiFetch('/api/machines/code/' + encodeURIComponent(code));

export const getMachineById = (id) =>
  apiFetch('/api/machines/' + id);

/** 按批次码查批次详情（含产品名/类型/状态/质检结果） */
export const getBatchByNo = (batchNo) =>
  apiFetch('/api/batches/' + encodeURIComponent(batchNo));

/** 查同一开机批次按穴号拆分出的全部子批次 */
export const getBatchesByParent = (parentBatchNo) =>
  apiFetch('/api/batches?parent_batch_no=' + encodeURIComponent(parentBatchNo));

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
  // 不要手设 Content-Type，让浏览器带上 multipart 边界；仅注入鉴权头
  const res = await fetch(`${SERVER_BASE}/api/events/upload`, {
    method: 'POST', body: fd, headers: { ...authHeaders() },
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) { logout(); notifyAuthExpired(); throw new AuthError(data.detail ?? '登录已失效'); }
  if (!res.ok) throw new Error(data.detail ?? `上传失败 ${res.status}`);
  return data;
}
