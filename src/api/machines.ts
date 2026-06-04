/**
 * 机器相关 API
 * 服务端地址写死，H5 打包进 APK，直接调用远程 API。
 */

const BASE_URL = 'http://115.29.178.34:2973';

export type MachineStatus = 'idle' | 'running' | 'stopped';

export interface Machine {
  id: number;
  code: string;
  name: string;
  status: MachineStatus;
  current_product_type: string | null;
  current_product_name: string | null;
  created_at: string;
  updated_at: string;
}

/** 机器详情，含最新批次号 */
export interface MachineDetail {
  id: number;
  code: string;
  name: string;
  status: MachineStatus;
  current_product_type: string | null;
  current_product_name: string | null;
  latest_batch_no: string | null;
}

export interface MachineCreate {
  code: string;
  name: string;
}

export interface MachineStatusUpdate {
  status: MachineStatus;
  current_product_type?: string;
  current_product_name?: string;
}

/** 获取全部机器列表 */
export async function fetchMachines(): Promise<Machine[]> {
  const res = await fetch(`${BASE_URL}/api/machines`);
  if (!res.ok) throw new Error(`获取机器列表失败：${res.status}`);
  return res.json();
}

/** 新增机器 */
export async function createMachine(body: MachineCreate): Promise<Machine> {
  const res = await fetch(`${BASE_URL}/api/machines`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail ?? `新增失败：${res.status}`);
  }
  return res.json();
}

/** 按 id 查机器详情（含最新批次号） */
export async function fetchMachineDetail(machineId: number): Promise<MachineDetail> {
  const res = await fetch(`${BASE_URL}/api/machines/${machineId}`);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail ?? `查询失败：${res.status}`);
  }
  return res.json();
}

/** 按机器编号查详情（含最新批次号） */
export async function fetchMachineDetailByCode(code: string): Promise<MachineDetail> {
  const res = await fetch(`${BASE_URL}/api/machines/code/${encodeURIComponent(code)}`);
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail ?? `查询失败：${res.status}`);
  }
  return res.json();
}

/** 修改机器运行状态 */
export async function updateMachineStatus(
  machineId: number,
  body: MachineStatusUpdate,
): Promise<Machine> {
  const res = await fetch(`${BASE_URL}/api/machines/${machineId}/status`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail ?? `状态更新失败：${res.status}`);
  }
  return res.json();
}
