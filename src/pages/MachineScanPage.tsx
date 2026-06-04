/**
 * 机器 NFC 扫描页
 *
 * 流程：
 *   扫描机器上的 NFC 标签（标签内存储机器 code，如 "M05"）
 *     → 查询服务端获取机器当前状态 + 最新批次号
 *     → 展示机器信息
 *     → 可选：打印最新批次标签 / 跳转到批次录入
 *
 * NFC 读卡部分复用 NfcPage 的逻辑，通过 modeRef 防止重入。
 */

import React, { useRef, useState } from 'react';
import { fetchMachineDetailByCode, MachineDetail, MachineStatus } from '../api/machines';

// NFC 标签中存储的内容格式：kaihang://nfc/<machine_code>
const CUSTOM_SCHEME_PREFIX = 'kaihang://nfc/';

// 状态徽章样式映射
const STATUS_STYLE: Record<MachineStatus, { label: string; color: string; bg: string }> = {
  idle:    { label: '空闲',  color: '#155724', bg: '#d4edda' },
  running: { label: '生产中', color: '#856404', bg: '#fff3cd' },
  stopped: { label: '停机',  color: '#721c24', bg: '#f8d7da' },
};

export default function MachineScanPage() {
  const [statusMsg, setStatusMsg] = useState('就绪。点击按钮后贴近机器 NFC 标签。');
  const [isScanning, setIsScanning] = useState(false);
  const [machine, setMachine] = useState<MachineDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  // 防止 NFC 回调重入
  const modeRef = useRef<'idle' | 'scanning'>('idle');

  // ── NFC 触发读卡 ──────────────────────────────────────────────────────────

  const handleStartScan = () => {
    if (isScanning) return;
    modeRef.current = 'scanning';
    setIsScanning(true);
    setMachine(null);
    setError(null);
    setStatusMsg('正在等待 NFC 标签... 请将设备贴近机器');

    // TODO: 调用硬件 Bridge 启动 NFC 读卡
    // 示例（方法名待 demo 确认）：
    // PDAJsBridge.SendControlCommand(JSON.stringify({ name: 'nfcRead' }));
    //
    // 开发调试用：可在浏览器中调用 window.__debugNFCResult('kaihang://nfc/M05') 模拟
  };

  /**
   * 由根组件 ReceiveControlCommand 分发器调用，或开发调试时直接调用。
   * rawData 格式：kaihang://nfc/<machine_code>
   */
  const handleNFCResult = async (rawData: string) => {
    if (modeRef.current !== 'scanning') return;
    modeRef.current = 'idle';
    setIsScanning(false);

    if (!rawData.startsWith(CUSTOM_SCHEME_PREFIX)) {
      setError(`无法识别的 NFC 内容：${rawData}`);
      setStatusMsg('扫描失败。');
      return;
    }

    const machineCode = rawData.replace(CUSTOM_SCHEME_PREFIX, '').trim();
    setStatusMsg(`已识别机器编号：${machineCode}，正在查询...`);

    try {
      const data = await fetchMachineDetailByCode(machineCode);
      setMachine(data);
      setStatusMsg('查询成功。');
    } catch (e: any) {
      setError(e.message ?? '服务端查询失败');
      setStatusMsg('查询失败。');
    }
  };

  // 挂载到 window，供调试和根组件分发器使用
  if (typeof window !== 'undefined') {
    (window as any).__debugNFCResult = handleNFCResult;
  }

  // ── 打印最新批次标签 ───────────────────────────────────────────────────────

  const handlePrint = () => {
    if (!machine?.latest_batch_no) return;
    // TODO: 调用硬件 Bridge 打印标签
    // PDAJsBridge.SendControlCommand(JSON.stringify({
    //   name: 'print',
    //   data: machine.latest_batch_no,
    //   count: 1,
    // }));
    alert(`打印批次码：${machine.latest_batch_no}（Bridge 接口待接入）`);
  };

  // ── 渲染 ──────────────────────────────────────────────────────────────────

  const statusStyle = machine ? STATUS_STYLE[machine.status] : null;

  return (
    <div style={{ padding: '20px', fontFamily: 'sans-serif', maxWidth: '480px', margin: '0 auto' }}>
      <h2 style={{ marginBottom: '16px' }}>机器 NFC 扫描</h2>

      {/* 操作状态提示 */}
      <div style={{
        padding: '12px 16px',
        backgroundColor: isScanning ? '#fff3cd' : '#e2e3e5',
        borderRadius: '8px',
        marginBottom: '20px',
        fontSize: '14px',
        fontWeight: 500,
      }}>
        {statusMsg}
      </div>

      {/* 扫描按钮 */}
      <button
        onClick={handleStartScan}
        disabled={isScanning}
        style={{
          width: '100%',
          padding: '14px',
          backgroundColor: isScanning ? '#6c757d' : '#007bff',
          color: 'white',
          border: 'none',
          borderRadius: '8px',
          fontSize: '16px',
          fontWeight: 'bold',
          marginBottom: '24px',
          cursor: isScanning ? 'not-allowed' : 'pointer',
        }}
      >
        {isScanning ? '等待 NFC 中...' : '开始扫描机器'}
      </button>

      {/* 错误提示 */}
      {error && (
        <div style={{
          padding: '12px 16px',
          backgroundColor: '#f8d7da',
          color: '#721c24',
          borderRadius: '8px',
          marginBottom: '16px',
          fontSize: '14px',
        }}>
          {error}
        </div>
      )}

      {/* 机器信息卡片 */}
      {machine && (
        <div style={{
          border: '1px solid #dee2e6',
          borderRadius: '12px',
          padding: '20px',
          backgroundColor: '#fff',
          boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
        }}>
          {/* 机器标题行 */}
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
            <div>
              <div style={{ fontSize: '20px', fontWeight: 'bold' }}>{machine.name}</div>
              <div style={{ fontSize: '13px', color: '#6c757d', marginTop: '2px' }}>编号：{machine.code}</div>
            </div>
            {statusStyle && (
              <span style={{
                padding: '4px 12px',
                borderRadius: '20px',
                fontSize: '13px',
                fontWeight: 600,
                color: statusStyle.color,
                backgroundColor: statusStyle.bg,
              }}>
                {statusStyle.label}
              </span>
            )}
          </div>

          {/* 产品类型 */}
          <InfoRow
            label="当前产品"
            value={
              machine.current_product_name
                ? `${machine.current_product_name}（${machine.current_product_type}）`
                : '—'
            }
          />

          {/* 最新批次号 */}
          <InfoRow
            label="最新批次"
            value={machine.latest_batch_no ?? '暂无批次'}
          />

          {/* 操作按钮区 */}
          <div style={{ marginTop: '20px', display: 'flex', gap: '10px' }}>
            {machine.latest_batch_no && (
              <button
                onClick={handlePrint}
                style={{
                  flex: 1,
                  padding: '10px',
                  backgroundColor: '#28a745',
                  color: 'white',
                  border: 'none',
                  borderRadius: '6px',
                  fontSize: '14px',
                  cursor: 'pointer',
                }}
              >
                打印批次标签
              </button>
            )}
            <button
              onClick={handleStartScan}
              style={{
                flex: 1,
                padding: '10px',
                backgroundColor: '#6c757d',
                color: 'white',
                border: 'none',
                borderRadius: '6px',
                fontSize: '14px',
                cursor: 'pointer',
              }}
            >
              重新扫描
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div style={{
      display: 'flex',
      justifyContent: 'space-between',
      padding: '8px 0',
      borderBottom: '1px solid #f0f0f0',
      fontSize: '14px',
    }}>
      <span style={{ color: '#6c757d' }}>{label}</span>
      <span style={{ fontWeight: 500 }}>{value}</span>
    </div>
  );
}
