import { registerPlugin } from '@capacitor/core';
import { BrowserMultiFormatReader } from '@zxing/browser';

window.ScanPlugin        = registerPlugin('ScanPlugin');
window.PrintPlugin       = registerPlugin('PrintPlugin');
window.KaihangNfc        = registerPlugin('KaihangNfc');
window.BUILD_TIME        = __BUILD_TIME__;
window.ZXingReader       = BrowserMultiFormatReader;
