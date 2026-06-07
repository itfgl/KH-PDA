/**
 * 扫码推进页
 *
 * 扫码枪扫机器码 / NFC 贴卡 → 自动识别批次当前节点 → 操作员选择操作 → 发送
 *
 * 界面三要素：谁操作 / 哪些选项 / 一个发送按钮
 *
 * 打印触发点：batch_ready（生产注塑完成）事件提交成功后自动打印批次标签，
 * 标签随物料分别送往 IPQC 和研磨两条轨道。
 */

import React, { useEffect, useRef, useState } from 'react';
import { PrintPlugin, PrintStatus } from '../bridge/CapacitorBridge';
import { getMachineByCode, getMachineById, getBatchByNo, getProcessState, postEvent } from '../modules/api';

// ── 类型 ──────────────────────────────────────────────────────────────────────

interface Transition {
  event: string;
  label: string;
  to: string | string[];
}

interface ActiveNode {
  state_id: number;
  node_id: string;
  node_name: string;
  responsible: string;
  entered_at: string;
  valid_transitions: Transition[];
}

interface ProcessState {
  batch_no: string;
  workflow_version: number;
  is_terminal: boolean;
  active_nodes: ActiveNode[];
}

interface MachineInfo {
  id: number;
  code: string;
  name: string;
  status: 'idle' | 'running' | 'stopped';
  latest_batch_no: string | null;
  current_product_name?: string;
  current_product_type?: string;
}

// 当前选中的操作（节点 + 事件）
interface Selection {
  from_node: string;
  event: string;
  label: string;
}

type Phase = 'wait-scan' | 'loading' | 'ready' | 'submitting' | 'done' | 'error';

// ── 工具函数 ──────────────────────────────────────────────────────────────────

const NFC_PREFIX = 'kaihang://nfc/';

function normalizeCode(raw: string): string {
  let s = raw.startsWith(NFC_PREFIX) ? raw.slice(NFC_PREFIX.length) : raw;
  s = s.split('|')[0];
  s = s.replace(/^[^A-Za-z0-9]+/, '').trim();
  return s;
}

/** 批次码固定 15 位（机器码3 + 日期6 + 产品码2 + 流水4），机器码最长 4 位 */
function isBatchNo(code: string): boolean {
  return code.length >= 10;
}

// ── 主组件 ────────────────────────────────────────────────────────────────────

export default function ScanAdvancePage() {
  const [phase, setPhase]       = useState<Phase>('wait-scan');
  const [machine, setMachine]   = useState<MachineInfo | null>(null);
  const [process, setProcess]   = useState<ProcessState | null>(null);
  const [actor, setActor]       = useState('');
  const [selected, setSelected] = useState<Selection | null>(null);
  const [error, setError]       = useState('');
  const [resultMsg, setResultMsg] = useState('');
  const [printMsg, setPrintMsg]   = useState('');

  // ref 保持 handleCode 始终是最新版本，避免 useEffect 闭包过期
  const handleCodeRef    = useRef<(raw: string) => void>(() => {});
  const printListenerRef = useRef<{ remove: () => void } | null>(null);

  // ── 打印机 ────────────────────────────────────────────────────────────────────

  /** 等待特定打印状态，遇到错误状态自动 reject */
  function waitPrintStatus(wanted: PrintStatus): Promise<void> {
    const ERRORS: PrintStatus[] = [
      'NO_PAPER', 'PRINTER_CLOSED', 'SEND_DATA_FAILED', 'PRINT_FAILED',
      'BLACK_FLAG_NOT_FOUND', 'PREPARE_LABEL_NO_PAPER',
      'PREPARE_LABEL_BLACK_FLAG_NOT_FOUND', 'PREPARE_LABEL_FAILED',
      'PREPARE_LABEL_PRINTER_CLOSED', 'PREPARE_LABEL_SEND_DATA_FAILED',
    ];
    return new Promise((resolve, reject) => {
      let sub: { remove: () => void } | null = null;
      PrintPlugin.addListener('printStatus', ({ status }) => {
        if (!status) return;
        if (status === wanted)                              { sub?.remove(); resolve(); }
        else if ((ERRORS as string[]).includes(status))    { sub?.remove(); reject(new Error(status)); }
      }).then(s => { sub = s; });
    });
  }

  /** 页面挂载时连接打印机（非打印设备上静默忽略） */
  async function initPrinter() {
    try {
      printListenerRef.current = await PrintPlugin.addListener('printStatus', ({ connection }) => {
        if (connection === 'connected') setPrintMsg('打印机已就绪');
        else if (connection === 'failed') setPrintMsg('打印机连接失败，标签需手动补打');
      });
      await PrintPlugin.connect();
    } catch { /* 非 X8 设备忽略 */ }
  }

  /**
   * batch_ready 后触发：走纸 → 打印批次标签。
   * 打印失败不阻断流程，只更新 printMsg 提示操作员补打。
   */
  async function triggerPrint(batchNo: string, m: MachineInfo) {
    const printDate = new Date()
      .toLocaleDateString('zh', { year: '2-digit', month: '2-digit', day: '2-digit' })
      .replace(/\//g, '');
    try {
      setPrintMsg('打印中…');
      await PrintPlugin.prepareToPrintLabel();
      await waitPrintStatus('PREPARE_LABEL_OK');
      await PrintPlugin.printBatchLabel({
        batchNo,
        machineId:   m.code,
        productType: m.current_product_name ?? '',
        date:        printDate,
      });
      await waitPrintStatus('PRINT_OK');
      setPrintMsg('✓ 标签已打印');
    } catch (e: any) {
      setPrintMsg('打印失败：' + e.message + '（请手动补打）');
    }
  }

  // ── 扫码处理核心逻辑 ────────────────────────────────────────────────────────

  async function handleCode(raw: string) {
    const code = normalizeCode(raw);
    if (!code) return;

    setPhase('loading');
    setMachine(null);
    setProcess(null);
    setSelected(null);
    setError('');
    setResultMsg('');

    try {
      let batchNo: string;
      let m: MachineInfo;

      if (isBatchNo(code)) {
        // ── 批次码路径（扫码枪/PDA 扫批次标签）────────────────────────
        const batch = await getBatchByNo(code);
        batchNo = batch.batch_no;
        // batch 只含 machine_id，再查一次拿完整机器信息
        m = await getMachineById(batch.machine_id);
      } else {
        // ── 机器码路径（PDA 扫机器 NFC 或机器条码）────────────────────
        m = await getMachineByCode(code);
        if (!m.latest_batch_no) {
          setMachine(m);
          setError('该机器暂无进行中的批次，请先在批次录入页建批。');
          setPhase('error');
          return;
        }
        batchNo = m.latest_batch_no;
      }

      setMachine(m);

      // 查当前流程状态
      const ps: ProcessState = await getProcessState(batchNo);
      setProcess(ps);

      if (ps.is_terminal) {
        setError('当前批次已完成，无法继续推进。');
        setPhase('error');
        return;
      }

      // 3. 若所有轨道合计只有一个可触发事件，自动选中
      const all = flatTransitions(ps.active_nodes);
      if (all.length === 1) setSelected(all[0]);

      setPhase('ready');
    } catch (e: any) {
      setError(e.message ?? '查询失败');
      setPhase('error');
    }
  }

  // 保持 ref 同步
  handleCodeRef.current = handleCode;

  // ── 硬件事件订阅 ────────────────────────────────────────────────────────────

  useEffect(() => {
    const W = window as any;

    // 确保硬件模块已初始化（有 _inited 保护，重复调用无副作用）
    W.Scanner?.init(W.ScanPlugin);
    W.NFC?.init(W.KaihangNfc);

    const onScan = ({ value }: { value: string }) => handleCodeRef.current(value);
    const onNfc  = ({ code }:  { code: string })  => handleCodeRef.current(code);

    W.Events?.on('scanner:result', onScan);
    W.Events?.on('nfc:detected',   onNfc);

    // 调试辅助：浏览器中执行 window.__debugScan('M05') 可模拟扫码
    W.__debugScan = (raw: string) => handleCodeRef.current(raw);

    // 打印机：页面加载时连接，退出时清理监听
    initPrinter();

    return () => {
      W.Events?.off('scanner:result', onScan);
      W.Events?.off('nfc:detected',   onNfc);
      printListenerRef.current?.remove();
    };
  }, []);

  // ── 提交事件 ────────────────────────────────────────────────────────────────

  async function handleSubmit() {
    if (!selected || !actor.trim() || !process || !machine) return;
    setPhase('submitting');
    setPrintMsg('');
    try {
      await postEvent({
        batch_no:   process.batch_no,
        event_type: selected.event,
        actor:      actor.trim(),
        from_node:  selected.from_node,
      });
      setResultMsg(`已推进：${selected.label}`);
      setPhase('done');

      // batch_ready：流程推进后立即打印批次标签，标签随物料流转
      if (selected.event === 'batch_ready') {
        triggerPrint(process.batch_no, machine);  // 不 await，打印进度异步更新 printMsg
      }
    } catch (e: any) {
      setError(e.message ?? '提交失败');
      setPhase('error');
    }
  }

  // ── 重置（继续扫下一台）────────────────────────────────────────────────────

  function reset() {
    setPhase('wait-scan');
    setMachine(null);
    setProcess(null);
    setSelected(null);
    setError('');
    setResultMsg('');
    setPrintMsg('');
  }

  // ── 渲染 ────────────────────────────────────────────────────────────────────

  const allTransitions = process ? flatTransitions(process.active_nodes) : [];
  const multiTrack     = (process?.active_nodes.length ?? 0) > 1;
  const canSubmit      = phase === 'ready' && !!selected && !!actor.trim();

  return (
    <div style={{ padding: 20, fontFamily: 'sans-serif', maxWidth: 480, margin: '0 auto' }}>
      <h2 style={{ marginBottom: 20 }}>扫码推进</h2>

      {/* 等待扫码 */}
      {phase === 'wait-scan' && (
        <div style={styles.hint}>扫描机器码以查看当前步骤</div>
      )}

      {/* 加载中 / 提交中 */}
      {(phase === 'loading' || phase === 'submitting') && (
        <div style={{ ...styles.hint, background: '#fff3cd', color: '#856404' }}>
          {phase === 'loading' ? '查询中…' : '提交中…'}
        </div>
      )}

      {/* 错误 */}
      {phase === 'error' && (
        <>
          {machine && <MachineCard machine={machine} />}
          <div style={styles.error}>{error}</div>
          <button onClick={reset} style={styles.btnGray}>重新扫码</button>
        </>
      )}

      {/* 完成 */}
      {phase === 'done' && (
        <>
          {machine && <MachineCard machine={machine} />}
          <div style={styles.success}>✓ {resultMsg}</div>
          {printMsg && (
            <div style={{
              padding: '10px 14px', borderRadius: 10, marginBottom: 14, fontSize: 13,
              background: printMsg.startsWith('✓') ? '#e8f5e9' : '#fff3cd',
              color:      printMsg.startsWith('✓') ? '#2e7d32' : '#856404',
            }}>
              🖨 {printMsg}
            </div>
          )}
          <button onClick={reset} style={styles.btnGray}>继续扫码</button>
        </>
      )}

      {/* 就绪：操作界面 */}
      {phase === 'ready' && machine && process && (
        <>
          {/* 机器 + 批次 */}
          <MachineCard machine={machine} />

          {/* 当前步骤 */}
          <Section title="当前步骤">
            {process.active_nodes.map((n, i) => (
              <div key={n.state_id}
                style={{ paddingBottom: i < process.active_nodes.length - 1 ? 10 : 0,
                         borderBottom: i < process.active_nodes.length - 1 ? '1px solid #f0f0f0' : 'none',
                         marginBottom: i < process.active_nodes.length - 1 ? 10 : 0 }}>
                <div style={{ fontWeight: 700, fontSize: 15 }}>{n.node_name}</div>
                <div style={{ fontSize: 12, color: '#86868b', marginTop: 2 }}>负责：{n.responsible}</div>
              </div>
            ))}
          </Section>

          {/* 操作人 */}
          <Section title="操作人">
            <input
              value={actor}
              onChange={e => setActor(e.target.value)}
              placeholder="填写姓名"
              style={styles.input}
              autoComplete="off"
            />
          </Section>

          {/* 操作选项 */}
          <Section title="操作">
            {allTransitions.map(t => {
              const isSelected = selected?.event === t.event && selected?.from_node === t.from_node;
              return (
                <button
                  key={`${t.from_node}-${t.event}`}
                  onClick={() => setSelected(t)}
                  style={{
                    display: 'block', width: '100%',
                    padding: '12px 14px', marginBottom: 8,
                    background: isSelected ? '#0071e3' : '#f2f2f7',
                    color:      isSelected ? '#fff'    : '#1d1d1f',
                    border: 'none', borderRadius: 10,
                    fontSize: 14, fontWeight: isSelected ? 700 : 500,
                    textAlign: 'left', cursor: 'pointer',
                  }}
                >
                  <div>{t.label}</div>
                  {/* 多轨道时显示来源节点，帮助操作员区分 */}
                  {multiTrack && (
                    <div style={{ fontSize: 11, marginTop: 3, opacity: 0.75 }}>
                      来自：{t.node_name}
                    </div>
                  )}
                </button>
              );
            })}
          </Section>

          {/* 发送 */}
          <button
            onClick={handleSubmit}
            disabled={!canSubmit}
            style={{
              display: 'block', width: '100%', padding: 14,
              background: canSubmit ? '#0071e3' : '#c7c7cc',
              color: '#fff', border: 'none', borderRadius: 10,
              fontSize: 16, fontWeight: 700,
              cursor: canSubmit ? 'pointer' : 'not-allowed',
            }}
          >
            发送
          </button>
        </>
      )}
    </div>
  );
}

// ── 辅助函数 ──────────────────────────────────────────────────────────────────

interface FlatTransition extends Selection {
  node_name: string;
}

function flatTransitions(nodes: ActiveNode[]): FlatTransition[] {
  return nodes.flatMap(n =>
    n.valid_transitions.map(t => ({
      from_node: n.node_id,
      node_name: n.node_name,
      event:     t.event,
      label:     t.label,
    }))
  );
}

// ── 子组件 ────────────────────────────────────────────────────────────────────

const STATUS_MAP: Record<string, { label: string; color: string; bg: string }> = {
  idle:    { label: '空闲',   color: '#155724', bg: '#d4edda' },
  running: { label: '生产中', color: '#856404', bg: '#fff3cd' },
  stopped: { label: '停机',   color: '#721c24', bg: '#f8d7da' },
};

function MachineCard({ machine }: { machine: MachineInfo }) {
  const s = STATUS_MAP[machine.status] ?? { label: machine.status, color: '#333', bg: '#eee' };
  return (
    <div style={{
      background: '#fff', borderRadius: 12, padding: '12px 14px',
      marginBottom: 16, boxShadow: '0 1px 3px rgba(0,0,0,.07)',
      display: 'flex', justifyContent: 'space-between', alignItems: 'center',
    }}>
      <div>
        <div style={{ fontWeight: 700, fontSize: 16 }}>{machine.name}</div>
        <div style={{ fontSize: 12, color: '#86868b', marginTop: 2 }}>
          {machine.code}
          {machine.latest_batch_no && <span style={{ marginLeft: 8 }}>{machine.latest_batch_no}</span>}
        </div>
      </div>
      <span style={{
        padding: '4px 10px', borderRadius: 20,
        fontSize: 12, fontWeight: 600,
        color: s.color, background: s.bg,
      }}>
        {s.label}
      </span>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 16 }}>
      <div style={{ fontSize: 11, color: '#86868b', fontWeight: 600,
                    textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 6 }}>
        {title}
      </div>
      <div style={{ background: '#fff', borderRadius: 12, padding: 14,
                    boxShadow: '0 1px 3px rgba(0,0,0,.07)' }}>
        {children}
      </div>
    </div>
  );
}

// ── 样式常量 ──────────────────────────────────────────────────────────────────

const styles = {
  hint: {
    padding: '20px 16px', background: '#f2f2f7',
    borderRadius: 12, fontSize: 14, color: '#6e6e73', textAlign: 'center',
  } as React.CSSProperties,

  error: {
    padding: '12px 14px', background: '#fce8e8', color: '#c62828',
    borderRadius: 10, marginBottom: 14, fontSize: 13,
  } as React.CSSProperties,

  success: {
    padding: '14px 16px', background: '#d4edda', color: '#155724',
    borderRadius: 10, marginBottom: 14, fontSize: 15, fontWeight: 600,
  } as React.CSSProperties,

  btnGray: {
    display: 'block', width: '100%', padding: 12,
    background: '#f2f2f7', color: '#1d1d1f',
    border: 'none', borderRadius: 10, fontSize: 14, cursor: 'pointer',
  } as React.CSSProperties,

  input: {
    width: '100%', padding: '9px 12px',
    border: '1px solid #d2d2d7', borderRadius: 8,
    fontSize: 14, outline: 'none', boxSizing: 'border-box',
  } as React.CSSProperties,
};
