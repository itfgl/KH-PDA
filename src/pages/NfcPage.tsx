/**
 * NFC 读写页面
 *
 * ⚠️  待确认：X8 硬件 SDK demo 中未包含 NFC 相关 Bridge 方法。
 *    需向硬件厂商确认以下问题后补全实现：
 *      1. NFC 是否通过 PDAJsBridge 触发（命令名是什么）？
 *      2. NFC 读卡结果是否通过 ReceiveControlCommand 回调返回？
 *      3. NFC 写卡是否支持？参数格式是什么？
 *
 * 下方代码为占位框架，Bridge 接口确认后按实际方法名替换 TODO 部分。
 */

import React, { useState, useEffect, useRef } from 'react';

// TODO: 确认 NFC 方法名后，改为从 PDABridge 导入
// import { readNFC, writeNFC, registerNFCListener } from '../bridge/PDABridge';

const CUSTOM_SCHEME_PREFIX = 'kaihang://nfc/';

export default function NfcPage() {
  const [statusMsg, setStatusMsg] = useState<string>('就绪。请选择操作。');
  const [readData, setReadData] = useState<string>('');
  const [writeInput, setWriteInput] = useState<string>('');
  const [isProcessing, setIsProcessing] = useState<boolean>(false);

  // 用 ref 跟踪当前操作模式，防止回调重入
  const modeRef = useRef<'idle' | 'read' | 'write'>('idle');

  useEffect(() => {
    // TODO: 确认 NFC 回调方式后注册监听
    // 若通过 ReceiveControlCommand 回调，需在此处将处理函数挂载到 window
    // window.ReceiveControlCommand 已在 App 根组件统一处理，通过事件分发到此处
    return () => {
      cancelOperation();
    };
  }, []);

  const cancelOperation = () => {
    // TODO: 如有 NFC 取消监听的 Bridge 方法，在此调用
    modeRef.current = 'idle';
    setIsProcessing(false);
    setStatusMsg('操作已取消。');
  };

  // ── 读卡 ──────────────────────────────────────────────────────────────────

  const handleStartRead = () => {
    if (isProcessing) cancelOperation();
    modeRef.current = 'read';
    setIsProcessing(true);
    setReadData('');
    setStatusMsg('正在等待 NFC 卡片... 请将设备贴近标签');

    // TODO: 调用实际 NFC 读卡 Bridge 方法
    // 示例（方法名待确认）：
    // PDAJsBridge.SendControlCommand(JSON.stringify({ name: 'nfcRead' }));
  };

  /**
   * 由外部（App 根组件的 ReceiveControlCommand 分发）调用，处理 NFC 读卡结果
   * data 格式待硬件确认
   */
  const handleNFCReadResult = (rawData: string) => {
    if (modeRef.current !== 'read') return;

    const uri = rawData.startsWith(CUSTOM_SCHEME_PREFIX)
      ? rawData.replace(CUSTOM_SCHEME_PREFIX, '')
      : null;

    if (uri) {
      setReadData(uri);
      setStatusMsg('读取成功！已解析自定义数据。');
    } else {
      setStatusMsg(`读取成功，但非本应用数据：${rawData}`);
    }

    modeRef.current = 'idle';
    setIsProcessing(false);
  };

  // ── 写卡 ──────────────────────────────────────────────────────────────────

  const handleStartWrite = () => {
    if (!writeInput.trim()) {
      alert('请先输入要写入的业务数据');
      return;
    }
    if (isProcessing) cancelOperation();
    modeRef.current = 'write';
    setIsProcessing(true);
    setStatusMsg(`准备写入 [${writeInput}]... 请将设备贴近标签`);

    const payload = `${CUSTOM_SCHEME_PREFIX}${writeInput}`;

    // TODO: 调用实际 NFC 写卡 Bridge 方法
    // 示例（方法名和参数格式待确认）：
    // PDAJsBridge.SendControlCommand(JSON.stringify({ name: 'nfcWrite', data: payload }));
    console.log('待写入内容：', payload);
  };

  /**
   * 由外部调用，处理 NFC 写卡结果
   */
  const handleNFCWriteResult = (result: string) => {
    if (modeRef.current !== 'write') return;

    if (result === 'NFC_WRITE_OK' /* TODO: 替换为实际成功码 */) {
      setStatusMsg('卡片写入成功！');
      setWriteInput('');
    } else {
      setStatusMsg(`写入失败：${result}`);
    }

    modeRef.current = 'idle';
    setIsProcessing(false);
  };

  return (
    <div style={{ padding: '20px', fontFamily: 'sans-serif' }}>
      <h2>NFC 读写管理</h2>

      <div style={{
        padding: '15px',
        backgroundColor: isProcessing ? '#fff3cd' : '#e2e3e5',
        borderRadius: '8px',
        marginBottom: '20px',
        fontWeight: 'bold',
      }}>
        状态：{statusMsg}
      </div>

      {/* 读卡区 */}
      <div style={{ border: '1px solid #ccc', padding: '15px', borderRadius: '8px', marginBottom: '20px' }}>
        <h3>读取卡片</h3>
        <button
          onClick={handleStartRead}
          disabled={isProcessing}
          style={{ padding: '10px 20px', backgroundColor: '#007bff', color: 'white', border: 'none', borderRadius: '4px' }}
        >
          开始扫描
        </button>
        {readData && (
          <div style={{ marginTop: '10px', color: '#28a745' }}>
            <strong>解析到的数据：</strong> {readData}
          </div>
        )}
      </div>

      {/* 写卡区 */}
      <div style={{ border: '1px solid #ccc', padding: '15px', borderRadius: '8px' }}>
        <h3>写入卡片</h3>
        <input
          type="text"
          value={writeInput}
          onChange={(e) => setWriteInput(e.target.value)}
          placeholder="输入业务参数（如批次码 M05260604030012）"
          disabled={isProcessing}
          style={{ padding: '10px', width: '100%', marginBottom: '10px', boxSizing: 'border-box' }}
        />
        <button
          onClick={handleStartWrite}
          disabled={isProcessing}
          style={{ padding: '10px 20px', backgroundColor: '#28a745', color: 'white', border: 'none', borderRadius: '4px' }}
        >
          确认写入
        </button>
      </div>

      {isProcessing && (
        <button
          onClick={cancelOperation}
          style={{ marginTop: '20px', padding: '10px 20px', backgroundColor: '#dc3545', color: 'white', border: 'none', borderRadius: '4px', width: '100%' }}
        >
          停止当前操作
        </button>
      )}
    </div>
  );
}

// 导出回调处理函数，供根组件的 ReceiveControlCommand 分发器调用
export { };
// TODO: 通过 ref 或 context 将 handleNFCReadResult / handleNFCWriteResult 暴露给根组件
