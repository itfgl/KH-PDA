// 验证 runtime 拼接脚本可完整执行（模拟 ClientRuntimeScriptBuilder 注入链路）
const fs = require('fs');
const vm = require('vm');
const path = require('path');

const dir = path.resolve(__dirname, '../android/app/src/main/assets/runtime');
const order = ['core', 'print', 'diagnostics', 'action-events', 'nocobase-events', 'bootstrap'];

// 模拟 ClientRuntimeScriptBuilder.build 的 prelude
const prelude = "(function(){window.__khRuntimeValues={" +
  "buildTime:'test'," +
  "versionName:'test'," +
  "versionCode:1," +
  "pageActionsApi:'/api/test'," +
  "defaultServerBase:'http://x'," +
  "defaultUpdateBase:'http://x'," +
  "shouldBootstrap:false," +
  "khToken:'',khAuth:'basic',khRole:'',khApp:'',khPaper:'',redirect:''," +
  "nocobaseStoragePrefix:'NOCOBASE_'," +
  "defaultStorageAppName:'main'" +
  "};})();";

let script = prelude;
for (const name of order) {
  script += fs.readFileSync(path.join(dir, `client-runtime.${name}.js`), 'utf8');
}

const errors = [];
function mkStorage() {
  const m = new Map();
  return { getItem: k => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: k => m.delete(k) };
}
const listeners = {};
const win = {
  __khRuntimeValues: undefined,
  location: { href: 'http://x/admin/t1', origin: 'http://x', pathname: '/admin/t1', search: '', hash: '', replace() {}, assign() {} },
  localStorage: mkStorage(),
  sessionStorage: mkStorage(),
  navigator: { userAgent: 'test-ua' },
  addEventListener(type) { listeners[type] = true; },
  removeEventListener() {},
  dispatchEvent() {},
  KaihangNativeBridge: {
    printLabel() { return true; },
    previewLabel() { return true; },
    connectPrinter() { return true; },
    shouldPreviewPrint() { return false; },
  },
  setTimeout, clearTimeout,
  XMLHttpRequest: function () { this.prototype = XMLHttpRequest.prototype; },
};
const documentMock = {
  readyState: 'loading',
  addEventListener() {},
  removeEventListener() {},
  getElementById() { return null; },
  querySelector() { return null; },
  querySelectorAll() { return []; },
  createElement() { return { style: {}, textContent: '', appendChild() {}, addEventListener() {}, setAttribute() {}, remove() {} }; },
  body: null,
  documentElement: {},
};
win.document = documentMock;
win.window = win;

const context = vm.createContext(win);
try {
  vm.runInContext(script, context, { filename: 'injected-runtime.js' });
} catch (e) {
  console.error('注入脚本同步执行抛异常:', e.message);
  process.exit(1);
}

// 等微任务链跑完
setTimeout(() => {
  const kh = win.__khClientRuntime;
  if (!kh) { console.error('kh 不存在'); process.exit(1); }
  const mustHave = [
    'resolveTemplateToken', 'applyTemplate', 'applyTemplateRich', 'renderTableTextTemplateRich',
    'runPrintAction', 'runSinglePrintPayload', 'enqueuePrintJob', 'getPrintPlugin',
    'waitPrintStatus', 'waitWorkflowSuccess', 'resolveWaitWorkflow', 'resolvePrintIntervalMs',
    'ensurePrintConnected', 'resolveTablePrintItems', 'resolvePrintConfig', 'delayMs',
    'diagnoseAction', '_diagnoseActionBase', 'buildDiagnosticReport', 'runSettingsDiagnostics',
    'execAction', 'bootOnce', 'refreshCurrentPage',
  ];
  const missing = mustHave.filter(k => typeof kh[k] !== 'function');
  if (missing.length) {
    console.error('缺失函数:', missing.join(', '));
    process.exit(1);
  }
  // 装饰链验证：diagnoseAction 应是"打印装饰版"（warning 注入 PRINT_DIAGNOSIS_ONLY）
  const d = kh.diagnoseAction({ id: 't1', enabled: true, actionType: 'print_label', triggerType: 'button', pagePath: '', options: {} }, { href: 'http://x/admin/t1' });
  const hasPrintBadge = (d.warnings || []).some(w => w.code === 'PRINT_DIAGNOSIS_ONLY');
  if (!hasPrintBadge) {
    console.error('打印诊断装饰未生效，warnings:', JSON.stringify(d.warnings));
    process.exit(1);
  }
  // 模板渲染验证：块格式 {'前缀':{字段}}
  const rich = kh.applyTemplateRich("{'机台号：':{machine}}", '', { options: { machine: 'M1' } });
  if (rich.text !== '机台号：M1') {
    console.error('模板渲染异常:', JSON.stringify(rich));
    process.exit(1);
  }
  console.log('OK: 注入脚本完整执行，打印/诊断/动作函数全部就位，装饰链与模板渲染正常');
  process.exit(0);
}, 300);
