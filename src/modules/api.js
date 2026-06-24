/**
 * 服务端 API 调用封装
 * 所有接口统一走 apiFetch，非 2xx 抛出含 detail 的 Error
 */
const DEFAULT_SERVER_BASE = 'http://115.29.178.34:2974';
const SERVER_BASE_KEY = 'kh_server_base_url';
const UPDATE_BASE_KEY = 'kh_update_base_url';
const PRINT_PAPER_TYPE_KEY = 'kh_print_paper_type';
const PRINT_LAYOUT_PRESET_KEY = 'kh_print_layout_preset';
const DEFAULT_AUTHENTICATOR = 'basic';
const STORAGE_APP_NAME = 'main';
const ROLE_ROUTES_API_PATH = '/api/client_role_routes:list?pageSize=200';
const DEFAULT_PRINT_PAPER_TYPE = 'thermal';
const DEFAULT_PRINT_LAYOUT_PRESET = 'standard';

// ── 登录态（token + 当前用户，持久化到 localStorage）──────────────────────────
const TOKEN_KEY = 'kh_token';
const USER_KEY  = 'kh_user';
const ROLE_KEY = 'kh_role';
const AUTHENTICATOR_KEY = 'kh_authenticator';
const ROLE_ROUTE_KEY = 'kh_role_route';
const ROLE_ROUTES_KEY = 'kh_role_routes_json';

export const getToken = () => localStorage.getItem(TOKEN_KEY) || '';
export const getCurrentRole = () => localStorage.getItem(ROLE_KEY) || '';
export const getAuthenticator = () => localStorage.getItem(AUTHENTICATOR_KEY) || DEFAULT_AUTHENTICATOR;

function normalizeBaseUrl(value) {
  const raw = String(value ?? '').trim();
  if (!raw) return DEFAULT_SERVER_BASE;
  return raw.replace(/\/+$/, '');
}

export function getServerBase() {
  return normalizeBaseUrl(localStorage.getItem(SERVER_BASE_KEY) || DEFAULT_SERVER_BASE);
}

export function setServerBase(value) {
  const base = normalizeBaseUrl(value);
  localStorage.setItem(SERVER_BASE_KEY, base);
  return base;
}

export function getUpdateBase() {
  return normalizeBaseUrl(localStorage.getItem(UPDATE_BASE_KEY) || getServerBase());
}

export function setUpdateBase(value) {
  const base = normalizeBaseUrl(value);
  localStorage.setItem(UPDATE_BASE_KEY, base);
  return base;
}

function normalizePrintPaperType(value) {
  const raw = String(value ?? '').trim().toLowerCase();
  return raw === 'black_mark' ? 'black_mark' : DEFAULT_PRINT_PAPER_TYPE;
}

function normalizePrintLayoutPreset(value) {
  const raw = String(value ?? '').trim().toLowerCase();
  if (['compact', 'large'].includes(raw)) return raw;
  return DEFAULT_PRINT_LAYOUT_PRESET;
}

export function getPrintPaperType() {
  return normalizePrintPaperType(localStorage.getItem(PRINT_PAPER_TYPE_KEY) || DEFAULT_PRINT_PAPER_TYPE);
}

export function setPrintPaperType(value) {
  const paperType = normalizePrintPaperType(value);
  localStorage.setItem(PRINT_PAPER_TYPE_KEY, paperType);
  return paperType;
}

export function getPrintLayoutPreset() {
  return normalizePrintLayoutPreset(localStorage.getItem(PRINT_LAYOUT_PRESET_KEY) || DEFAULT_PRINT_LAYOUT_PRESET);
}

export function setPrintLayoutPreset(value) {
  const preset = normalizePrintLayoutPreset(value);
  localStorage.setItem(PRINT_LAYOUT_PRESET_KEY, preset);
  return preset;
}

function getRoleRouteMap() {
  try {
    const value = JSON.parse(localStorage.getItem(ROLE_ROUTES_KEY) || '{}');
    return value && typeof value === 'object' ? value : {};
  } catch {
    return {};
  }
}

export function getRoleRoute(roleName = '') {
  const routeMap = getRoleRouteMap();
  const normalizedRole = String(roleName || getCurrentRole()).trim().toLowerCase();
  const route = normalizedRole ? routeMap[normalizedRole] : '';
  const fallback = route || routeMap.default || '/admin/dufm0qvyxcn';
  if (/^https?:\/\//i.test(fallback)) return fallback;
  return fallback.startsWith('/') ? fallback : `/${fallback}`;
}

export function getRolePageUrl(roleName = '') {
  const route = getRoleRoute(roleName);
  if (/^https?:\/\//i.test(route)) return route;
  return `${getServerBase()}${route}`;
}

export function getRoleBootstrapUrl(roleName = '') {
  const targetUrl = getRolePageUrl(roleName);
  const url = new URL('/signin', `${getServerBase()}/`);
  url.searchParams.set('redirect', targetUrl);
  url.searchParams.set('kh_token', getToken());
  url.searchParams.set('kh_auth', getAuthenticator());
  url.searchParams.set('kh_role', roleName || getCurrentRole());
  url.searchParams.set('kh_app', STORAGE_APP_NAME);
  url.searchParams.set('kh_paper', getPrintPaperType());
  url.searchParams.set('kh_layout', getPrintLayoutPreset());
  return url.toString();
}

export function getCurrentUser() {
  try { return JSON.parse(localStorage.getItem(USER_KEY) || 'null'); }
  catch { return null; }
}

function setSession(token, user, roleName = '', authenticator = DEFAULT_AUTHENTICATOR) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, JSON.stringify({ ...(user || {}), role: roleName || user?.role || '' }));
  localStorage.setItem(ROLE_KEY, roleName || user?.role || '');
  localStorage.setItem(AUTHENTICATOR_KEY, authenticator || DEFAULT_AUTHENTICATOR);
  localStorage.setItem(ROLE_ROUTE_KEY, getRoleRoute(roleName || user?.role || ''));
}

export function logout() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  localStorage.removeItem(ROLE_KEY);
  localStorage.removeItem(AUTHENTICATOR_KEY);
  localStorage.removeItem(ROLE_ROUTE_KEY);
}

/** 401 时抛出此错误，外层据此跳回登录页 */
export class AuthError extends Error {}

/** 登录失效时广播全局事件，UI 层据此弹回登录页（与 DOM 解耦） */
function notifyAuthExpired() {
  try { window.dispatchEvent(new CustomEvent('kh:auth-expired')); } catch { /* 非浏览器环境忽略 */ }
}

function authHeaders() {
  const t = getToken();
  const headers = {};
  const authenticator = getAuthenticator();
  if (authenticator) headers['X-Authenticator'] = authenticator;
  if (t) headers.Authorization = `Bearer ${t}`;
  return headers;
}

function unwrapResponseData(payload) {
  if (payload && typeof payload === 'object' && payload.data !== undefined) return payload.data;
  return payload;
}

function normalizeRoleName(value) {
  if (!value) return '';
  if (typeof value === 'string') return value.trim();
  if (typeof value === 'object') return String(value.name || value.title || value.role || '').trim();
  return String(value).trim();
}

function extractRoleName(authPayload, rolesPayload) {
  const authData = unwrapResponseData(authPayload);
  const rolesData = unwrapResponseData(rolesPayload);

  const candidates = [];
  if (authData && typeof authData === 'object') {
    candidates.push(
      authData.role,
      authData.roleName,
      authData.currentRole,
      authData.current_role,
      authData.user?.role,
      authData.user?.roleName,
      authData.user?.currentRole,
      authData.user?.current_role,
    );
  }

  for (const candidate of candidates) {
    const roleName = normalizeRoleName(candidate);
    if (roleName) return roleName;
  }

  const items = Array.isArray(rolesData)
    ? rolesData
    : Array.isArray(rolesData?.roles)
      ? rolesData.roles
      : Array.isArray(rolesData?.items)
        ? rolesData.items
        : Array.isArray(rolesData?.data)
          ? rolesData.data
          : [];

  for (const item of items) {
    if (item?.current) {
      const roleName = normalizeRoleName(item.name);
      if (roleName) return roleName;
    }
  }
  for (const item of items) {
    if (item?.default) {
      const roleName = normalizeRoleName(item.name);
      if (roleName) return roleName;
    }
  }
  for (const item of items) {
    const roleName = normalizeRoleName(item?.name || item);
    if (roleName) return roleName;
  }
  return '';
}

function extractToken(payload) {
  const data = unwrapResponseData(payload);
  if (typeof data === 'string') return data;
  return String(
    data?.token ||
    data?.jwt ||
    data?.accessToken ||
    data?.access_token ||
    data?.user?.token ||
    '',
  ).trim();
}

function extractUser(payload) {
  const data = unwrapResponseData(payload);
  if (!data || typeof data !== 'object') return null;
  if (data.user && typeof data.user === 'object') return data.user;
  return data;
}

function extractListPayload(payload) {
  const data = unwrapResponseData(payload);
  if (Array.isArray(data)) return data.filter((item) => item && typeof item === 'object');
  if (data && typeof data === 'object') {
    for (const key of ['items', 'rows', 'data']) {
      if (Array.isArray(data[key])) return data[key].filter((item) => item && typeof item === 'object');
    }
  }
  return [];
}

function normalizeRoleRoute(item, index) {
  if (!item || typeof item !== 'object') return null;
  let options = item.options;
  if (typeof options === 'string' && options.trim()) {
    try { options = JSON.parse(options); } catch { options = {}; }
  }
  if (!options || typeof options !== 'object') options = {};

  const roleName = normalizeRoleName(
    item.role_name ??
    item.roleName ??
    item.role ??
    options.role_name ??
    options.role ??
    '',
  ).toLowerCase();
  const routePath = String(
    item.route_path ??
    item.routePath ??
    item.path ??
    item.page_path ??
    item.pagePath ??
    options.route_path ??
    options.page_path ??
    '',
  ).trim();
  const platform = String(item.platform ?? options.platform ?? '').trim().toLowerCase();
  const enabled = item.enabled !== undefined ? item.enabled : true;
  const sortOrder = Number.parseInt(item.sort ?? item.sortOrder ?? options.sort ?? index, 10);

  if (!routePath) return null;

  return {
    roleName,
    routePath: /^https?:\/\//i.test(routePath) || routePath.startsWith('/') ? routePath : `/${routePath}`,
    platform,
    enabled: enabled !== false && enabled !== 'false' && enabled !== 0 && enabled !== '0',
    sortOrder: Number.isFinite(sortOrder) ? sortOrder : index,
  };
}

function buildRoleRouteMap(items = []) {
  const map = {};
  const routes = items
    .map((item, index) => normalizeRoleRoute(item, index))
    .filter(Boolean)
    .sort((a, b) => a.sortOrder - b.sortOrder);

  for (const route of routes) {
    if (!route.enabled) continue;
    if (!['', 'android', 'mobile', 'all'].includes(route.platform)) continue;
    const key = route.roleName || 'default';
    if (!(key in map)) map[key] = route.routePath;
  }
  return map;
}

async function refreshRoleRoutes() {
  try {
    const payload = await apiFetch(ROLE_ROUTES_API_PATH);
    const routeMap = buildRoleRouteMap(extractListPayload(payload));
    localStorage.setItem(ROLE_ROUTES_KEY, JSON.stringify(routeMap));
    return routeMap;
  } catch {
    return getRoleRouteMap();
  }
}

export async function apiFetch(path, opts = {}) {
  const headers = { 'Content-Type': 'application/json', ...authHeaders(), ...(opts.headers || {}) };
  const url = path.startsWith('http') ? path : `${getServerBase()}${path}`;
  const res = await fetch(url, { ...opts, headers });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) { logout(); notifyAuthExpired(); throw new AuthError(data.detail ?? '登录已失效'); }
  if (!res.ok) throw new Error(data.detail ?? `请求失败 ${res.status}`);
  return data;
}

/** 用户名/密码登录，成功后持久化 token + 用户信息并返回 user */
export async function login(username, password) {
  const authenticator = getAuthenticator() || DEFAULT_AUTHENTICATOR;
  const res = await fetch(`${getServerBase()}/api/auth:signIn`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Authenticator': authenticator,
    },
    body: JSON.stringify({ account: username, password }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.detail ?? `登录失败 ${res.status}`);

  const token = extractToken(data);
  if (!token) throw new Error('登录成功，但未返回 token');

  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(AUTHENTICATOR_KEY, authenticator);

  const [authInfo, rolesInfo] = await Promise.all([
    apiFetch('/api/auth:check'),
    apiFetch('/api/roles:check').catch(() => []),
  ]);
  const roleName = extractRoleName(authInfo, rolesInfo);
  const user = extractUser(authInfo) || extractUser(data) || { username };
  await refreshRoleRoutes();
  setSession(token, user, roleName, authenticator);
  return getCurrentUser();
}

/** 用已存 token 拉当前用户，校验登录是否仍有效（失败抛 AuthError） */
export async function fetchMe() {
  if (!getToken()) throw new AuthError('未登录');
  const [authInfo, rolesInfo] = await Promise.all([
    apiFetch('/api/auth:check'),
    apiFetch('/api/roles:check').catch(() => []),
  ]);
  const roleName = extractRoleName(authInfo, rolesInfo);
  const user = extractUser(authInfo) || getCurrentUser() || {};
  await refreshRoleRoutes();
  setSession(getToken(), user, roleName, getAuthenticator());
  return getCurrentUser();
}

/** 角色列表（用于「代办批次」的模拟角色筛选） */
export async function getRoles() {
  const payload = await apiFetch('/api/roles:check');
  const data = unwrapResponseData(payload);
  if (Array.isArray(data)) return data;
  if (Array.isArray(data?.roles)) return data.roles;
  if (Array.isArray(data?.items)) return data.items;
  if (Array.isArray(data?.data)) return data.data;
  return [];
}

/**
 * 代办批次列表：按模拟角色 / 机器筛选。
 * @param {{ responsible?: string, machine_id?: number, include_simulation?: boolean }} params
 */
export function getTodoBatches({ responsible, machine_id, include_simulation } = {}) {
  const q = new URLSearchParams();
  if (responsible) q.set('responsible', responsible);
  if (machine_id != null) q.set('machine_id', String(machine_id));
  if (include_simulation) q.set('include_simulation', 'true');
  const qs = q.toString();
  return apiFetch('/api/batches/todos' + (qs ? '?' + qs : ''));
}

export const getMachineByCode = (code) =>
  apiFetch('/api/machines/code/' + encodeURIComponent(code));

export const getMachineById = (id) =>
  apiFetch('/api/machines/' + id);

/** 打印批次标签前查询：当前 2 小时周期标注 + 建议续打的栏号 */
export const getPrintInfo = (machineId) =>
  apiFetch('/api/machines/' + machineId + '/print-info');

/** 打印完成后回报实际使用的栏号，更新该机器该周期内的最大栏号 */
export const postPrintInfo = (machineId, periodKey, laneNo) =>
  apiFetch('/api/machines/' + machineId + '/print-info', {
    method: 'POST',
    body: JSON.stringify({ period_key: periodKey, lane_no: laneNo }),
  });

/** 按批次码查批次详情（含产品名/类型/状态/质检结果） */
export const getBatchByNo = (batchNo) =>
  apiFetch('/api/batches/' + encodeURIComponent(batchNo));

/** 查同一开机批次按穴号拆分出的全部子批次 */
export const getBatchesByParent = (parentBatchNo) =>
  apiFetch('/api/batches?parent_batch_no=' + encodeURIComponent(parentBatchNo));

/** 查批次当前流程状态（活跃节点 + 可触发事件） */
export const getProcessState = (batchNo) =>
  apiFetch('/api/process/' + encodeURIComponent(batchNo));

/** 按需求值某节点 display[index] 的表达式（SEARCH() 检索历史关联记录按钮点击时调用） */
export const getDisplaySearch = (batchNo, nodeId, index) =>
  apiFetch('/api/process/' + encodeURIComponent(batchNo) + '/nodes/' + encodeURIComponent(nodeId) + '/display/' + index);

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
  const res = await fetch(`${getServerBase()}/api/events/upload`, {
    method: 'POST', body: fd, headers: { ...authHeaders() },
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) { logout(); notifyAuthExpired(); throw new AuthError(data.detail ?? '登录已失效'); }
  if (!res.ok) throw new Error(data.detail ?? `上传失败 ${res.status}`);
  return data;
}
