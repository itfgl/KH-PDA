import { registerPlugin } from '@capacitor/core';
import { BrowserMultiFormatReader } from '@zxing/browser';

// Capacitor 插件代理
window.ScanPlugin  = registerPlugin('ScanPlugin');
window.PrintPlugin = registerPlugin('PrintPlugin');
window.KaihangNfc  = registerPlugin('KaihangNfc');
window.BUILD_TIME  = __BUILD_TIME__;

// ZXing 已打包在 plugins.js 中（IIFE 格式不支持代码分割）
// 用法：const reader = new window.ZXingReader()
window.ZXingReader = BrowserMultiFormatReader;
