import { registerPlugin } from '@capacitor/core';
import { CapacitorNfc } from '@capgo/capacitor-nfc';

window.ScanPlugin  = registerPlugin('ScanPlugin');
window.PrintPlugin = registerPlugin('PrintPlugin');
window.NFC = CapacitorNfc;   // 统一用 window.NFC，HTML 层不用改
