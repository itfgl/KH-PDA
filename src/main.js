import { registerPlugin } from '@capacitor/core';
import { BrowserMultiFormatReader } from '@zxing/browser';

// ── Capacitor 插件代理 ────────────────────────────────────────────────────────
window.ScanPlugin  = registerPlugin('ScanPlugin');
window.PrintPlugin = registerPlugin('PrintPlugin');
window.KaihangNfc  = registerPlugin('KaihangNfc');
window.BUILD_TIME  = __BUILD_TIME__;

// ZXing（IIFE 格式静态打包，不支持代码分割，详见 HTML_RULES.md）
window.ZXingReader = BrowserMultiFormatReader;

// ── 业务模块（挂到 window 供 HTML 使用）───────────────────────────────────────
import * as Events  from './modules/events.js';
import * as API     from './modules/api.js';
import * as Printer from './modules/printer.js';
import * as Scanner from './modules/scanner.js';
import * as NFC     from './modules/nfc.js';
import * as Machine from './modules/machine.js';

window.Events  = Events;
window.API     = API;
window.Printer = Printer;
window.Scanner = Scanner;
window.NFC     = NFC;
window.Machine = Machine;
