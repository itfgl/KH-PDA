/**
 * 硬件 Bridge 统一入口
 *
 * 运行时检测顺序：
 *   1. window.PDAJsBridge 存在 → X8 安卓壳子模式（PDA adapter）
 *   2. window.Capacitor   存在 → 自研 Capacitor 壳模式
 *   3. 其他               → 浏览器开发模式（插件注册但不可用）
 *
 * 导出的 ScanPlugin / PrintPlugin / NfcPlugin 接口与 Capacitor registerPlugin
 * 返回值完全相同，modules/ 目录下的业务模块无需感知硬件差异。
 */

import { registerPlugin } from '@capacitor/core';
import * as pdaAdapter from './pda-adapter.js';

const isPDA = typeof window !== 'undefined' && typeof window.PDAJsBridge !== 'undefined';
const isCap = !isPDA && typeof window !== 'undefined' && !!window.Capacitor;

export const bridgeMode = isPDA ? 'pda' : (isCap ? 'capacitor' : 'dev');

// X8 PDA 模式：使用适配器（把 PDAJsBridge 包成 Capacitor 同款接口）
// Capacitor / Dev 模式：直接注册原生插件（Dev 环境下插件调用会静默失败）
export const ScanPlugin  = isPDA ? pdaAdapter.ScanPlugin  : registerPlugin('ScanPlugin');
export const PrintPlugin = isPDA ? pdaAdapter.PrintPlugin : registerPlugin('PrintPlugin');
export const NfcPlugin   = isPDA ? pdaAdapter.NfcPlugin   : registerPlugin('KaihangNfc');
export const UpdatePlugin = registerPlugin('UpdatePlugin');
