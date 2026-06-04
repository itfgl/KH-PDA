/**
 * NFC 机器页（统一入口）
 *
 * 扫描结果分两路：
 *   有 kaihang://nfc/<code> → 查机器状态，展示详情
 *   空白卡 / 非凯航卡       → 展示新建机器表单 → 创建 → 写卡
 */
import { NFC } from '@capgo/capacitor-nfc';
import React, { useEffect, useRef, useState } from 'react';
import { createMachine, fetchMachineDetailByCode, MachineDetail } from '../api/machines';

const NFC_PREFIX = 'kaihang://nfc/';

type Phase =
  | 'idle'       // 等待扫描
  | 'scanning'   // 扫描中
  | 'detail'     // 展示已有机器详情
  | 'new-form'   // 填写新机器表单
  | 'writing'    // 写卡中
  | 'done';      // 写卡完成

const STATUS_MAP = {
  idle:    { label: '空闲',   bg: '#e5e5ea', color: '#6e6e73' },
  running: { label: '生产中', bg: '#fff3cd', color: '#856404' },
  stopped: { label: '停机',   bg: '#fce8e8', color: '#c62828' },
};

export default function NfcMachinePage() {
  const [phase, setPhase] = useState<Phase>('idle');
  const [hint, setHint] = useState('点击按钮后靠近 NFC 标签');
  const [error, setError] = useState('');
  const [machine, setMachine] = useState<MachineDetail | null>(null);

  // 新建机器表单
  const [newCode, setNewCode] = useState('');
  const [newName, setNewName] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // 保存 NFC 监听器引用，防止重复注册
  const listenerRef = useRef<{ remove: () => void } | null>(null);

  useEffect(() => () => { stopNfc(); }, []);

  // ── NFC 控制 ────────────────────────────────────────────────────────────────

  async function stopNfc() {
    listenerRef.current?.remove();
    listenerRef.current = null;
    await NFC.removeAllListeners();
  }

  async function startScan() {
    await stopNfc();
    setPhase('scanning');
    setError('');
    setMachine(null);
    setHint('扫描中… 请靠近 NFC 标签');

    listenerRef.current = await NFC.addListener('nfcTag', async (tag) => {
      await stopNfc();

      const records = tag.messages?.[0]?.records ?? [];
      let machineCode: string | null = null;

      for (const r of records) {
        if (r.tnf === 1 && new TextDecoder().decode(new Uint8Array(r.type)) === 'U') {
          const uri = new TextDecoder().decode(new Uint8Array(r.payload).slice(1));
          if (uri.startsWith(NFC_PREFIX)) {
            machineCode = uri.replace(NFC_PREFIX, '').trim();
          }
          break;
        }
      }

      if (machineCode) {
        // 已有数据 → 查服务端
        setHint('已识别机器编号：' + machineCode + '，查询中…');
        try {
          const detail = await fetchMachineDetailByCode(machineCode);
          setMachine(detail);
          setPhase('detail');
          setHint('');
        } catch (e: any) {
          setError(e.message);
          setPhase('idle');
        }
      } else {
        // 空白或非凯航卡 → 进入新建表单
        setNewCode('');
        setNewName('');
        setPhase('new-form');
        setHint('空白卡，请填写机器信息后写入');
      }
    });
  }

  // ── 新建机器 + 写卡 ──────────────────────────────────────────────────────────

  async function handleCreateAndWrite() {
    if (!newCode.trim() || !newName.trim()) { setError('编号和名称不能为空'); return; }
    setSubmitting(true);
    setError('');

    try {
      // 1. 创建机器
      await createMachine({ code: newCode.trim(), name: newName.trim() });

      // 2. 写卡
      setPhase('writing');
      setHint('机器已创建，请再次靠近 NFC 标签写入…');

      await stopNfc();
      listenerRef.current = await NFC.addListener('nfcTag', async () => {
        await stopNfc();
        try {
          const enc = new TextEncoder();
          const uri = NFC_PREFIX + newCode.trim();
          const payload = new Uint8Array(1 + uri.length);
          payload[0] = 0x00;
          payload.set(enc.encode(uri), 1);

          await NFC.write({
            records: [
              { tnf: 1, type: Array.from(enc.encode('U')), id: [], payload: Array.from(payload) },
              { tnf: 4, type: Array.from(enc.encode('android.com:pkg')), id: [], payload: Array.from(enc.encode('com.kaihang.scanner')) },
            ],
          });

          setPhase('done');
          setHint(`机器 ${newCode.trim()} 已写入卡片，贴卡可直接唤起应用。`);
        } catch (e: any) {
          setError('写卡失败：' + e.message);
          setPhase('new-form');
        }
      });
    } catch (e: any) {
      setError(e.message);
    } finally {
      setSubmitting(false);
    }
  }

  // ── 渲染 ────────────────────────────────────────────────────────────────────

  const s = machine ? STATUS_MAP[machine.status] : null;

  return (
    <div style={{ padding: 20, fontFamily: 'sans-serif', maxWidth: 480, margin: '0 auto' }}>
      <h2 style={{ marginBottom: 16 }}>机器 NFC</h2>

      {/* 状态提示 */}
      <div style={{
        padding: '11px 14px', borderRadius: 10, marginBottom: 20, fontSize: 14,
        background: phase === 'scanning' || phase === 'writing' ? '#fff3cd' : '#f2f2f7',
        color: '#3a3a3c',
      }}>
        {hint || '就绪'}
      </div>

      {/* 错误 */}
      {error && (
        <div style={{ padding: '10px 14px', background: '#fce8e8', color: '#c62828', borderRadius: 8, marginBottom: 16, fontSize: 13 }}>
          {error}
        </div>
      )}

      {/* 扫描按钮 */}
      {(phase === 'idle' || phase === 'detail' || phase === 'done') && (
        <button onClick={startScan} style={btnStyle('#0071e3')}>
          {phase === 'idle' ? '开始扫描' : '重新扫描'}
        </button>
      )}

      {/* 扫描中 */}
      {phase === 'scanning' && (
        <button onClick={() => { stopNfc(); setPhase('idle'); setHint('点击按钮后靠近 NFC 标签'); }} style={btnStyle('#6e6e73')}>
          取消
        </button>
      )}

      {/* ── 已有机器：详情卡片 ── */}
      {phase === 'detail' && machine && (
        <div style={{ border: '1px solid #e5e5ea', borderRadius: 12, padding: 20, marginTop: 20 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
            <div>
              <div style={{ fontSize: 18, fontWeight: 700 }}>{machine.name}</div>
              <div style={{ fontSize: 12, color: '#86868b', marginTop: 2 }}>编号：{machine.code}</div>
            </div>
            {s && (
              <span style={{ padding: '3px 12px', borderRadius: 20, fontSize: 13, fontWeight: 600, background: s.bg, color: s.color }}>
                {s.label}
              </span>
            )}
          </div>
          <Row label="当前产品" value={machine.current_product_name ? `${machine.current_product_name}（${machine.current_product_type}）` : '—'} />
          <Row label="最新批次" value={machine.latest_batch_no ?? '暂无'} />
        </div>
      )}

      {/* ── 新建机器表单 ── */}
      {(phase === 'new-form' || phase === 'writing') && (
        <div style={{ border: '1px solid #e5e5ea', borderRadius: 12, padding: 20, marginTop: 20 }}>
          <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 14 }}>新建机器并写入卡片</div>
          <Field label="机器编号（如 M01）" value={newCode}
            onChange={setNewCode} placeholder="M01" disabled={phase === 'writing'} />
          <Field label="机器名称" value={newName}
            onChange={setNewName} placeholder="1号生产线" disabled={phase === 'writing'} />
          <button
            onClick={handleCreateAndWrite}
            disabled={submitting || phase === 'writing'}
            style={{ ...btnStyle('#34c759'), marginTop: 6, opacity: submitting ? 0.6 : 1 }}
          >
            {submitting ? '创建中…' : phase === 'writing' ? '等待靠近标签…' : '确认创建并写卡'}
          </button>
          <button onClick={() => { stopNfc(); setPhase('idle'); setHint('点击按钮后靠近 NFC 标签'); }}
            style={{ ...btnStyle('#e5e5ea', '#1d1d1f'), marginTop: 8 }}>
            取消
          </button>
        </div>
      )}

      {/* ── 写卡完成 ── */}
      {phase === 'done' && (
        <div style={{ marginTop: 20, padding: 16, background: '#e8f5e9', borderRadius: 10, color: '#2e7d32', fontSize: 14 }}>
          写入成功！该卡贴近设备可直接唤起应用并识别机器。
        </div>
      )}
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 0', borderBottom: '1px solid #f2f2f7', fontSize: 14 }}>
      <span style={{ color: '#86868b' }}>{label}</span>
      <span style={{ fontWeight: 500 }}>{value}</span>
    </div>
  );
}

function Field({ label, value, onChange, placeholder, disabled }: {
  label: string; value: string; onChange: (v: string) => void;
  placeholder?: string; disabled?: boolean;
}) {
  return (
    <div style={{ marginBottom: 12 }}>
      <div style={{ fontSize: 12, color: '#86868b', marginBottom: 4 }}>{label}</div>
      <input value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}
        disabled={disabled}
        style={{ width: '100%', padding: '9px 12px', border: '1px solid #d2d2d7', borderRadius: 8, fontSize: 14, outline: 'none' }} />
    </div>
  );
}

function btnStyle(bg: string, color = '#fff'): React.CSSProperties {
  return { display: 'block', width: '100%', padding: '13px', background: bg, color, border: 'none', borderRadius: 10, fontSize: 15, fontWeight: 600, cursor: 'pointer', marginBottom: 4 };
}
