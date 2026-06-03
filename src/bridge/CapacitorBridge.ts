/**
 * Capacitor Plugin 封装（自研壳阶段使用）
 *
 * 对应原生插件：
 *   ScanPlugin  → com.kaihang.scanner.plugins.ScanPlugin
 *   PrintPlugin → com.kaihang.scanner.plugins.PrintPlugin
 *   NFC         → @capgo/capacitor-nfc（已在 NfcPage.tsx 中使用）
 */

import { registerPlugin } from '@capacitor/core';

// ─── 扫码 Plugin ──────────────────────────────────────────────────────────────

export interface ScanPlugin {
  /** 触发一次扫码（等效按下硬件扳机） */
  startScan(): Promise<void>;
  /** 停止扫码 */
  stopScan(): Promise<void>;
  /** 监听扫码结果，扫码助手广播触发后自动回调 */
  addListener(
    event: 'scanResult',
    handler: (data: { value: string }) => void
  ): Promise<{ remove: () => void }>;
  removeAllListeners(): Promise<void>;
}

export const ScanPlugin = registerPlugin<ScanPlugin>('ScanPlugin');

// ─── 打印 Plugin ──────────────────────────────────────────────────────────────

export type PrintStatus =
  | 'PRINT_OK'
  | 'NO_PAPER'
  | 'PRINTER_CLOSED'
  | 'SEND_DATA_FAILED'
  | 'PRINT_FAILED'
  | 'BLACK_FLAG_NOT_FOUND'
  | 'PREPARE_LABEL_OK'
  | 'PREPARE_LABEL_NO_PAPER'
  | 'PREPARE_LABEL_BLACK_FLAG_NOT_FOUND'
  | 'PREPARE_LABEL_FAILED';

export interface BatchLabelOptions {
  batchNo: string;       // 15位批次码，如 M05260604030012
  machineId?: string;    // 机器编号
  productType?: string;  // 产品类型
  date?: string;         // 日期 YYMMDD
}

export interface PrintPlugin {
  /** 连接打印机，App 启动后调用一次 */
  connect(): Promise<void>;
  /** 标签就绪：走纸到标签起始位置，printBatchLabel 前调用 */
  prepareToPrintLabel(): Promise<void>;
  /** 打印批次标签 */
  printBatchLabel(options: BatchLabelOptions): Promise<void>;
  /** 监听打印机状态和打印结果 */
  addListener(
    event: 'printStatus',
    handler: (data: { status?: PrintStatus; connection?: string; flag?: string }) => void
  ): Promise<{ remove: () => void }>;
  removeAllListeners(): Promise<void>;
}

export const PrintPlugin = registerPlugin<PrintPlugin>('PrintPlugin');
