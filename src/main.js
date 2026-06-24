import { BrowserMultiFormatReader } from '@zxing/browser';
import { ScanPlugin, PrintPlugin, NfcPlugin, UpdatePlugin, ClientConfigPlugin, bridgeMode } from './bridge/index.js';

import * as Events  from './modules/events.js';
import * as API     from './modules/api.js';
import * as Printer from './modules/printer.js';
import * as Scanner from './modules/scanner.js';
import * as NFC     from './modules/nfc.js';
import * as Machine from './modules/machine.js';

// ── 插件代理挂到 window（供 HTML 内联脚本访问）──────────────────────────────
window.ScanPlugin  = ScanPlugin;
window.PrintPlugin = PrintPlugin;
window.KaihangNfc  = NfcPlugin;
window.UpdatePlugin = UpdatePlugin;
window.ClientConfigPlugin = ClientConfigPlugin;

// ── 元信息 ───────────────────────────────────────────────────────────────────
window.BUILD_TIME  = __BUILD_TIME__;
window.BRIDGE_MODE = bridgeMode;   // 'pda' | 'capacitor' | 'dev'

// ── ZXing 相机扫码（IIFE 格式，不能代码分割）────────────────────────────────
window.ZXingReader = BrowserMultiFormatReader;

// ── 业务模块（挂到 window 供 HTML 使用）──────────────────────────────────────
window.Events  = Events;
window.API     = API;
window.Printer = Printer;
window.Scanner = Scanner;
window.NFC     = NFC;
window.Machine = Machine;
