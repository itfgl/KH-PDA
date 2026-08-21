import { BrowserMultiFormatReader } from '@zxing/browser';
import { ScanPlugin, PrintPlugin, NfcPlugin, UpdatePlugin, ClientConfigPlugin, bridgeMode } from './bridge/index.js';

import * as API from './modules/api.js';

// ── 插件代理挂到 window（供 HTML 内联脚本访问）──────────────────────────────
window.ScanPlugin  = ScanPlugin;
window.PrintPlugin = PrintPlugin;
window.KaihangNfc  = NfcPlugin;
window.UpdatePlugin = UpdatePlugin;
window.ClientConfigPlugin = ClientConfigPlugin;

// ── 元信息 ──────────────────────────────────────────────────────────────────
window.BUILD_TIME  = __BUILD_TIME__;
window.BRIDGE_MODE = bridgeMode;   // 'capacitor' | 'dev'

// ── ZXing 相机扫码（IIFE 格式，不能代码分割）────────────────────────────────
window.ZXingReader = BrowserMultiFormatReader;

// ── 业务模块（挂到 window 供 HTML 使用）──────────────────────────────────────
window.API = API;
