/**
 * 批次录入页
 *
 * 流程：
 *   选择机器 → 确认产品类型 → 创建批次 → 展示批次码 + 条码 → 打印
 */
import JsBarcode from 'jsbarcode';
import React, { useEffect, useRef, useState } from 'react';
import { PrintPlugin } from '../bridge/CapacitorBridge';
import { createBatch } from '../api/batches';
import { fetchMachines, Machine } from '../api/machines';

export default function BatchEntryPage() {
  const [machines, setMachines] = useState<Machine[]>([]);
  const [selectedId, setSelectedId] = useState<number | ''>('');
  const [productType, setProductType] = useState('');
  const [productName, setProductName] = useState('');
  const [creating, setCreating] = useState(false);
  const [batchNo, setBatchNo] = useState('');
  const [error, setError] = useState('');
  const [printStatus, setPrintStatus] = useState('');

  const barcodeRef = useRef<SVGSVGElement>(null);
  const printListenerRef = useRef<{ remove: () => void } | null>(null);

  useEffect(() => {
    fetchMachines().then(setMachines).catch(() => setError('加载机器列表失败'));
    initPrinter();
    return () => { printListenerRef.current?.remove(); };
  }, []);

  // 选机器后自动填入当前产品类型
  useEffect(() => {
    const m = machines.find(m => m.id === selectedId);
    if (m) {
      setProductType(m.current_product_type ?? '');
      setProductName(m.current_product_name ?? '');
    }
  }, [selectedId, machines]);

  // 批次码生成后渲染条码
  useEffect(() => {
    if (!batchNo || !barcodeRef.current) return;
    JsBarcode(barcodeRef.current, batchNo, {
      format: 'CODE128',
      width: 2,
      height: 60,
      displayValue: true,
      fontSize: 13,
      margin: 8,
    });
  }, [batchNo]);

  // ── 打印机 ────────────────────────────────────────────────────────────────

  async function initPrinter() {
    try {
      printListenerRef.current = await PrintPlugin.addListener('printStatus', ({ status, connection }) => {
        if (connection === 'connected') setPrintStatus('打印机已就绪');
        else if (connection === 'failed') setPrintStatus('打印机连接失败');
        else if (status === 'PRINT_OK') setPrintStatus('打印完成');
        else if (status) setPrintStatus('打印机：' + status);
      });
      await PrintPlugin.connect();
    } catch { /* 非打印机设备上忽略 */ }
  }

  // ── 创建批次 ──────────────────────────────────────────────────────────────

  async function handleCreate() {
    if (!selectedId) { setError('请选择机器'); return; }
    if (!productType.trim()) { setError('请填写产品类型码'); return; }
    if (!productName.trim()) { setError('请填写产品类型名称'); return; }

    setCreating(true);
    setError('');
    setBatchNo('');

    try {
      const batch = await createBatch({
        machine_id: selectedId as number,
        product_type: productType.trim(),
        product_name: productName.trim(),
      });
      setBatchNo(batch.batch_no);
      setPrintStatus('');
    } catch (e: any) {
      setError(e.message);
    } finally {
      setCreating(false);
    }
  }

  // ── 打印 ─────────────────────────────────────────────────────────────────

  async function handlePrint(count: number) {
    if (!batchNo) return;
    const m = machines.find(m => m.id === selectedId);
    setPrintStatus('发送打印任务…');
    try {
      for (let i = 0; i < count; i++) {
        await PrintPlugin.printBatchLabel({
          batchNo,
          machineId: m?.code,
          productType: productName,
          date: new Date().toLocaleDateString('zh', { year: '2-digit', month: '2-digit', day: '2-digit' }).replace(/\//g, ''),
        });
      }
    } catch (e: any) {
      setPrintStatus('打印失败：' + e.message);
    }
  }

  // ── 渲染 ─────────────────────────────────────────────────────────────────

  const selected = machines.find(m => m.id === selectedId);
  const canCreate = !!selectedId && !!productType.trim() && !!productName.trim() && !creating;

  return (
    <div style={{ padding: 20, fontFamily: 'sans-serif', maxWidth: 480, margin: '0 auto' }}>
      <h2 style={{ marginBottom: 20 }}>批次录入</h2>

      {error && (
        <div style={{ padding: '10px 14px', background: '#fce8e8', color: '#c62828', borderRadius: 8, marginBottom: 14, fontSize: 13 }}>
          {error}
        </div>
      )}

      {/* ── 机器选择 ── */}
      <Section title="选择机器">
        <select value={selectedId} onChange={e => setSelectedId(Number(e.target.value) || '')}
          style={selectStyle}>
          <option value="">— 请选择机器 —</option>
          {machines.map(m => (
            <option key={m.id} value={m.id}>
              {m.code}  {m.name}  [{m.status === 'running' ? '生产中' : m.status === 'idle' ? '空闲' : '停机'}]
            </option>
          ))}
        </select>

        {/* 机器状态警告 */}
        {selected && selected.status !== 'running' && (
          <div style={{ marginTop: 8, padding: '8px 12px', background: '#fff3cd', color: '#856404', borderRadius: 8, fontSize: 13 }}>
            该机器当前未在生产中，可继续创建批次但请确认状态。
          </div>
        )}
      </Section>

      {/* ── 产品类型 ── */}
      <Section title="产品类型">
        <div style={{ display: 'flex', gap: 10 }}>
          <div style={{ flex: '0 0 90px' }}>
            <div style={{ fontSize: 12, color: '#86868b', marginBottom: 4 }}>类型码</div>
            <input value={productType} onChange={e => setProductType(e.target.value)}
              placeholder="01" maxLength={8} style={{ ...inputStyle, width: '100%' }} />
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 12, color: '#86868b', marginBottom: 4 }}>产品名称</div>
            <input value={productName} onChange={e => setProductName(e.target.value)}
              placeholder="电容" style={{ ...inputStyle, width: '100%' }} />
          </div>
        </div>
      </Section>

      {/* ── 创建按钮 ── */}
      <button onClick={handleCreate} disabled={!canCreate}
        style={{ display: 'block', width: '100%', padding: 14, background: canCreate ? '#0071e3' : '#c7c7cc', color: '#fff', border: 'none', borderRadius: 10, fontSize: 16, fontWeight: 600, cursor: canCreate ? 'pointer' : 'not-allowed', marginBottom: 24 }}>
        {creating ? '创建中…' : '创建批次'}
      </button>

      {/* ── 批次码 + 条码 ── */}
      {batchNo && (
        <Section title="批次码">
          <div style={{ textAlign: 'center' }}>
            <svg ref={barcodeRef} style={{ maxWidth: '100%' }} />
            <div style={{ fontSize: 11, color: '#86868b', marginTop: 4 }}>{batchNo}</div>
          </div>

          {printStatus && (
            <div style={{ marginTop: 10, padding: '8px 12px', background: '#f2f2f7', borderRadius: 8, fontSize: 13, color: '#3a3a3c', textAlign: 'center' }}>
              {printStatus}
            </div>
          )}

          <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
            {[1, 3, 5].map(n => (
              <button key={n} onClick={() => handlePrint(n)}
                style={{ flex: 1, padding: '11px 0', background: '#34c759', color: '#fff', border: 'none', borderRadius: 9, fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>
                打印 {n} 张
              </button>
            ))}
          </div>

          <button onClick={() => { setBatchNo(''); setError(''); setPrintStatus(''); }}
            style={{ display: 'block', width: '100%', marginTop: 10, padding: 11, background: '#f2f2f7', color: '#1d1d1f', border: 'none', borderRadius: 9, fontSize: 14, cursor: 'pointer' }}>
            创建下一批次
          </button>
        </Section>
      )}
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 20 }}>
      <div style={{ fontSize: 12, color: '#86868b', marginBottom: 8, fontWeight: 600, textTransform: 'uppercase', letterSpacing: 0.5 }}>
        {title}
      </div>
      <div style={{ background: '#fff', borderRadius: 12, padding: 14, boxShadow: '0 1px 3px rgba(0,0,0,.07)' }}>
        {children}
      </div>
    </div>
  );
}

const selectStyle: React.CSSProperties = {
  width: '100%', padding: '9px 12px', border: '1px solid #d2d2d7',
  borderRadius: 8, fontSize: 14, outline: 'none', background: '#fff',
};

const inputStyle: React.CSSProperties = {
  padding: '9px 12px', border: '1px solid #d2d2d7',
  borderRadius: 8, fontSize: 14, outline: 'none',
};
