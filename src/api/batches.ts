const BASE_URL = '';

export interface Batch {
  id: number;
  batch_no: string;
  machine_id: number;
  product_type: string;
  product_name: string;
  status: 'created' | 'inspecting' | 'done' | 'failed';
  created_at: string;
  updated_at: string;
}

export interface BatchCreate {
  machine_id: number;
  product_type: string;
  product_name: string;
}

export async function createBatch(body: BatchCreate): Promise<Batch> {
  const res = await fetch(`${BASE_URL}/api/batches`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.detail ?? `创建失败：${res.status}`);
  return data;
}
