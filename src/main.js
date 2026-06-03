import { registerPlugin } from '@capacitor/core';
import { NFC } from '@capgo/capacitor-nfc';

// 自定义插件通过 registerPlugin 拿到代理对象
window.ScanPlugin  = registerPlugin('ScanPlugin');
window.PrintPlugin = registerPlugin('PrintPlugin');
window.NFC = NFC;
