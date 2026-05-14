const BASE = '/api'

export type RagIndexStatus = 'NOT_INDEXED' | 'INDEXING' | 'COMPLETED' | 'FAILED'

export interface RagIndexStatusResponse {
  sourceId: string
  status: RagIndexStatus
  chunkCount?: number
  errorMessage?: string
}

export async function triggerRagIndex(sourceId: string): Promise<void> {
  const response = await fetch(`${BASE}/rag/index/manual`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sourceId })
  })
  if (!response.ok) {
    throw new Error(`触发索引失败: HTTP ${response.status}`)
  }
}

export async function getRagIndexStatus(sourceId: string): Promise<RagIndexStatusResponse> {
  const response = await fetch(`${BASE}/rag/index/status?sourceId=${encodeURIComponent(sourceId)}`)
  if (!response.ok) {
    throw new Error(`查询索引状态失败: HTTP ${response.status}`)
  }
  return response.json()
}
