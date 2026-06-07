/**
 * NFC 机器页（统一入口）
 *
 * 扫描结果分两路：
 *   有 kaihang://nfc/<code> → 查机器状态，展示详情
 *   空白卡 / 非凯航卡       → 展示新建机器表单 → 创建 → 写卡
 *
 * NFC 使用自研 KaihangNfc 插件（com.kaihang.scanner.plugins.KaihangNfcPlugin）。
 * 插件 load() 时已自动启用 Reader Mode，无需手动 startScanning。
 * nfcEvent 在每次贴卡时触发，通过 phaseRef 区分当前操作模式。
 */
import React, { useEffect, useRef, useState } from 'react';
import { KaihangNfc, NfcEvent } from '../bridge/CapacitorBridge';
import { createMachine, fetchMachineDetailByCode, MachineDetail } from '../api/machines';

const NFC_PREFIX = 'kaihang://nfc/';

type Phase =
  | 'idle'       // 等待扫描
  | 'scanning'   // 等待贴卡（读模式）
  | 'detail'     // 展示已有机器详情
  | 'new-form'   // 填写新机器表单
  | 'writing'    // 等待贴卡（写模式）
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

  // 用 ref 持有当前阶段，避免 nfcEvent 回调中读到 stale state
  const phaseRef = useRef<Phase>('idle');
  const newCodeRef = useRef('');

  function setPhaseSync(p: Phase) {
    phaseRef.current = p;
    setPhase(p);
  }

  // 同步 newCode 到 ref，供 nfcEvent 回调读取
  useEffect(() => { newCodeRef.current = newCode; }, [newCode]);

  // ── NFC 监听（整个页面生命周期内持续监听）─────────────────────────────────��──

  useEffect(() => {
    let sub: { remove: () => void } | null = null;

    KaihangNfc.addListener('nfcEvent', handleNfcEvent).then(s => { sub = s; });

    return () => {
      sub?.remove();
      KaihangNfc.removeAllListeners();
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function handleNfcEvent(event: NfcEvent) {
    const currentPhase = phaseRef.current;

    // ── 读模式：识别机器 ────────────────────────���───────────────────────────
    if (currentPhase === 'scanning') {
      setPhaseSync('idle'); // 防止重入

      let machineCode: string | null = null;

      // 优先从 NDEF URI Record 提取
      if (event.isNdef && event.ndefMessage) {
        for (const r of event.ndefMessage) {
          if (r.tnf === 1) {
            // payload[0] 是 URI 标识符前缀字节（0x00 = 无缩写，保留完整 URI）
            const uri = new TextDecoder().decode(new Uint8Array(r.payload).slice(1));
            if (uri.startsWith(NFC_PREFIX)) {
              machineCode = uri.replace(NFC_PREFIX, '').trim();
            }
            break;
          }
        }
      }

      // MifareUltralight 卡：直接读取 mifareData（已由插件自动解析）
      if (!machineCode && event.mifareData) {
        const data = event.mifareData.trim();
        if (data.startsWith(NFC_PREFIX)) {
          machineCode = data.replace(NFC_PREFIX, '');
        } else {
          machineCode = data; // 直接存储 code 的情况
        }
      }

      if (machineCode) {
        setHint('已识别机器编号：' + machineCode + '，查询中…');
        try {
          const detail = await fetchMachineDetailByCode(machineCode);
          setMachine(detail);
          setPhaseSync('detail');
          setHint('');
        } catch (e: any) {
          setError(e.message);
          setPhaseSync('idle');
        }
      } else {
        // 空白卡或非凯航卡 → 进入新建表单
        setNewCode('');
        setNewName('');
        setPhaseSync('new-form');
        setHint('空白卡，请填写机器信息后写入');
      }
      return;
    }

    // ── 写模式：写入机器编号 ─────────────────────────────────────────────────
    if (currentPhase === 'writing') {
      setPhaseSync('idle'); // 防止重入

      const code = newCodeRef.current.trim();
      if (!code) {
        setError('机器编号为空，无法写卡');
        setPhaseSync('new-form');
        return;
      }

      try {
        // writeNdef 自动写入 kaihang://nfc/<data> URI Record + AAR Record
        await KaihangNfc.writeNdef({ data: code });
        setPhaseSync('done');
        setHint(`机器 ${code} 已写入卡片，贴卡可直接唤起应用。`);
      } catch (e: any) {
        setError('写卡失败：' + e.message);
        setPhaseSync('new-form');
      }
    }
  }

  // ── 扫描控制 ────────────────────────────────────────────────────────────────

  function startScan() {
    setPhaseSync('scanning');
    setError('');
    setMachine(null);
    setHint('扫描中… 请靠近 NFC 标签');
  }

  // ── 新建机器 + 触发写卡 ──────────────────────────────────────────────────────

  async function handleCreateAndWrite() {
    if (!newCode.trim() || !newName.trim()) { setError('编号和名称不能为空'); return; }
    setSubmitting(true);
    setError('');

    try {
      await createMachine({ code: newCode.trim(), name: newName.trim() });
      setPhaseSync('writing');
      setHint('机器已创建，请再次靠近 NFC 标签写入…');
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

      {/* 扫描中 / 写卡中：取消 */}
      {(phase === 'scanning' || phase === 'writing') && (
        <button onClick={() => { setPhaseSync('idle'); setHint('点击按钮后靠近 NFC 标签'); }}
          style={btnStyle('#6e6e73')}>
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
          <button onClick={() => { setPhaseSync('idle'); setHint('点击按钮后靠近 NFC 标签'); }}
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
        style={{ width: '100%', padding: '9px 12px', border: '1px solid #d2d2d7', borderRadius: 8, fontSize: 14, outline: 'none', boxSizing: 'border-box' }} />
    </div>
  );
}

function btnStyle(bg: string, color = '#fff'): React.CSSProperties {
  return { display: 'block', width: '100%', padding: '13px', background: bg, color, border: 'none', borderRadius: 10, fontSize: 15, fontWeight: 600, cursor: 'pointer', marginBottom: 4 };
}
