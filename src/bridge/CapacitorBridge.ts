/**
 * Capacitor Plugin 封装（自研壳阶段使用）
 *
 * 对应原生插件：
 *   ScanPlugin      → com.kaihang.scanner.plugins.ScanPlugin
 *   PrintPlugin     → com.kaihang.scanner.plugins.PrintPlugin
 *   KaihangNfc      → com.kaihang.scanner.plugins.KaihangNfcPlugin（自研，非 @capgo/capacitor-nfc）
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
  | 'PREPARE_LABEL_FAILED'
  | 'PREPARE_LABEL_PRINTER_CLOSED'
  | 'PREPARE_LABEL_SEND_DATA_FAILED';

export interface BatchLabelOptions {
  batchNo: string;       // 15位批次码，如 M05260604030012
  machineId?: string;    // 机器编号
  productType?: string;  // 产品类型名称（展示用）
  date?: string;         // 日期 YYMMDD
}

export interface PrintPlugin {
  /** 连接打印机，App 启动后调用一次 */
  connect(): Promise<void>;
  /**
   * 走纸到第一张标签的起始位置（打印第一张前调用一次）。
   * 结果通过 printStatus 事件返回：PREPARE_LABEL_OK 表示就绪，其他值表示失败。
   */
  prepareToPrintLabel(): Promise<void>;
  /**
   * 走纸到下一张标签的起始位置（每张 PRINT_OK 后调用）。
   * 与 prepareToPrintLabel 底层相同，语义上表示"继续下一张"。
   * 结果通过 printStatus 事件返回：PREPARE_LABEL_OK 表示就绪。
   */
  checkBlack(): Promise<void>;
  /** 打印批次标签（一维码）*/
  printBatchLabel(options: BatchLabelOptions): Promise<void>;
  /** 打印机器 QR 标签（二维码，内容：machineId|productType|date）*/
  printMachineQR(options: { machineId: string; productType?: string; date?: string }): Promise<void>;
  /** 监听打印机状态和打印结果 */
  addListener(
    event: 'printStatus',
    handler: (data: { status?: PrintStatus; connection?: string; flag?: string }) => void
  ): Promise<{ remove: () => void }>;
  removeAllListeners(): Promise<void>;
}

export const PrintPlugin = registerPlugin<PrintPlugin>('PrintPlugin');

// ─── NFC Plugin ───────────────────────────────────────────────────────────────

export interface NfcRecord {
  tnf: number;
  type: number[];     // byte array
  id: number[];
  payload: number[];  // byte array，URI Record 时 payload[0] 为 URI 标识符前缀字节
}

export interface NfcEvent {
  techTypes: string[];
  isNdef: boolean;
  isMifareUltralight: boolean;
  /** NDEF 卡：自动读取的 NDEF records */
  ndefMessage?: NfcRecord[];
  /** MifareUltralight 原始卡（凯航格式 KH）：自动解析的字符串 */
  mifareData?: string;
}

export interface KaihangNfcPlugin {
  /** 启动 NFC 扫描（插件 load 时已自动启动，通常不需要手动调用） */
  startScanning(): Promise<void>;
  /** 暂停 NFC 扫描（同时清除 lastTag） */
  stopScanning(): Promise<void>;
  /**
   * 写入 NDEF 数据（需先贴卡触发 nfcEvent 后立即调用）。
   * 写入格式：URI Record（kaihang://nfc/<data>）+ AAR Record
   * 写完后 lastTag 自动清空，需重新贴卡才能再次操作。
   */
  writeNdef(options: { data: string; allowFormat?: boolean }): Promise<void>;
  /**
   * 写入 MifareUltralight 原始数据（凯航 KH 格式）。
   * 写完后 lastTag 自动清空。
   */
  writeMifareRaw(options: { data: string }): Promise<void>;
  /**
   * 读取 MifareUltralight 原始数据（解析凯航 KH 格式）。
   * 读完后 lastTag 自动清空。
   */
  readMifareRaw(): Promise<{ data: string; isKaihang: boolean; raw?: number[] }>;
  /** 清空卡片数据（NDEF erase + Mifare 页写零）。清完后 lastTag 自动清空。 */
  clearTag(): Promise<void>;
  /** 监听 NFC 贴卡事件 */
  addListener(
    event: 'nfcEvent',
    handler: (event: NfcEvent) => void
  ): Promise<{ remove: () => void }>;
  removeAllListeners(): Promise<void>;
}

export const KaihangNfc = registerPlugin<KaihangNfcPlugin>('KaihangNfc');
