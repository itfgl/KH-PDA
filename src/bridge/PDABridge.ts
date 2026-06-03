/**
 * PDAJsBridge 类型化封装
 *
 * 硬件型号：X8（安卓壳子 H5 对接模式）
 * 通信方式：
 *   H5 → 原生：PDAJsBridge.SendControlCommand(JSON.stringify(cmd))
 *   原生 → H5：原生调用全局函数 window.ReceiveControlCommand({name, data})
 */

// ─── 原生 Bridge 全局声明 ─────────────────────────────────────────────────────

declare global {
  interface Window {
    PDAJsBridge: {
      SendControlCommand(json: string): void;
    };
    /** 原生回调入口，必须挂载到 window 上，由原生主动调用 */
    ReceiveControlCommand(action: BridgeCallback): void;
  }
}

// ─── 类型定义 ─────────────────────────────────────────────────────────────────

export type PrintStatus =
  | 'PRINTER_CONNECT_SUCCESS'
  | 'PRINTER_CONNECT_FAILED'
  | 'PRINTER_CLOSED'
  | 'NO_CONTENT'
  | 'SEND_DATA_FAILED'
  | 'NO_PAPER'
  | 'PRINT_FAILED'
  | 'PRINT_OK'
  | 'BLACK_FLAG_NOT_FOUND'
  | 'PREPARE_LABEL_PRINTER_CLOSED'
  | 'PREPARE_LABEL_SEND_DATA_FAILED'
  | 'PREPARE_LABEL_NO_PAPER'
  | 'PREPARE_LABEL_BLACK_FLAG_NOT_FOUND'
  | 'PREPARE_LABEL_FAILED'
  | 'PREPARE_LABEL_OK';

export interface BridgeCallback {
  name: 'onScan' | 'onPrint' | 'version' | 'onBack' | string;
  data: string;
}

/** 标签纸打印项（printBmpLabel 使用，绝对坐标布局） */
export type LabelPrintItem =
  | { printType: 0; text: string; textSize?: number; x: number; y: number }
  | { printType: 1; text: string; desiredWidth?: number; desiredHeight?: number; displayCode?: boolean; left: number; top: number }
  | { printType: 2; text: string; desiredWidth?: number; desiredHeight?: number; displayCode?: boolean; left: number; top: number }
  | { printType: 3; text: string; left: number; top: number };

export interface LabelPrintCommand {
  name: 'printBmpLabel';
  width?: number;   // 默认 384
  height?: number;  // 默认 280
  top?: number;     // 默认 8，单位：点（8点=1mm）
  concentration?: number; // 打印浓度 1~20，默认 18
  runOnNewThread?: boolean;
  data: LabelPrintItem[];
}

// ─── 发送命令 ─────────────────────────────────────────────────────────────────

function send(cmd: object) {
  window.PDAJsBridge.SendControlCommand(JSON.stringify(cmd));
}

// ─── 扫码 ─────────────────────────────────────────────────────────────────────

/**
 * 注册扫码监听，扫到结果后原生调用 ReceiveControlCommand({name:'onScan', data:'...'})
 * callbackFnName 是页面上处理回调的全局函数名，与 setOnBack 同理，约定传 'onScan'
 */
export function registerScanListener() {
  send({ name: 'setOnScan', data: 'onScan' });
}

// ─── 打印 ─────────────────────────────────────────────────────────────────────

/**
 * 注册打印状态监听，打印完成后原生调用 ReceiveControlCommand({name:'onPrint', data: PrintStatus})
 */
export function registerPrintListener() {
  send({ name: 'setOnPrint', data: 'onPrint' });
}

/**
 * 标签纸混合打印（不干胶/贴纸）
 * 布局使用绝对坐标，画布宽度最大 384 点
 */
export function printLabel(cmd: Omit<LabelPrintCommand, 'name'>) {
  send({ name: 'printBmpLabel', ...cmd });
}

/**
 * 检测黑标（打印完一张标签后调用，让打印机走纸到下一张起始位置）
 * 应在 onPrint 回调收到 PRINT_OK 或 PREPARE_LABEL_OK 后调用
 */
export function checkBlack() {
  send({ name: 'checkBlack' });
}

// ─── 其他 ─────────────────────────────────────────────────────────────────────

export function getVersion() {
  send({ name: 'version' });
}

export function exitApp() {
  send({ name: 'finish' });
}

/**
 * 监听物理返回键，callbackName 传空字符串取消监听
 */
export function setBackListener(callbackName: string) {
  send({ name: 'setOnBack', data: callbackName });
}
