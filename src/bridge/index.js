/**
 * 硬件 Bridge 统一入口
 *
 * 运行时检测顺序：
 *   1. window.Capacitor 存在 → 自研 Capacitor 壳模式
 *   2. 其他                 → 浏览器开发模式（插件注册但不可用）
 *
 * 导出的插件接口与 Capacitor registerPlugin 返回值完全相同。
 * 注：X8 安卓壳（PDAJsBridge）适配器已随 X8 设备退役删除。
 */

import { registerPlugin } from '@capacitor/core';

const isCap = typeof window !== 'undefined' && !!window.Capacitor;

export const bridgeMode = isCap ? 'capacitor' : 'dev';

export const ScanPlugin  = registerPlugin('ScanPlugin');
export const PrintPlugin = registerPlugin('PrintPlugin');
export const NfcPlugin   = registerPlugin('KaihangNfc');
export const UpdatePlugin = registerPlugin('UpdatePlugin');
export const ClientConfigPlugin = registerPlugin('ClientConfigPlugin');
