const BASE = '/api'

export interface UploadFileResponse {
  filename: string
  storedName: string
  relativePath: string
  mimeType: string
  size: number
}

export interface PersistedFileResponse {
  filename: string
  relativePath: string
  mimeType: string
  size: number
  updatedAt: string
}

export async function uploadSourceFile(file: File): Promise<UploadFileResponse> {
  const form = new FormData()
  form.append('file', file)

  const response = await fetch(`${BASE}/files/upload`, {
    method: 'POST',
    body: form
  })

  if (!response.ok) {
    throw new Error(`上传失败: HTTP ${response.status}`)
  }

  return response.json()
}

export async function listSourceFiles(): Promise<PersistedFileResponse[]> {
  const response = await fetch(`${BASE}/files/list`)
  if (!response.ok) {
    throw new Error(`读取文件列表失败: HTTP ${response.status}`)
  }

  const json = await response.json()
  return Array.isArray(json.items) ? json.items : []
}

export async function deleteSourceFile(relativePath: string): Promise<boolean> {
  const response = await fetch(`${BASE}/files/delete`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ relativePath })
  })

  if (!response.ok) {
    throw new Error(`删除文件失败: HTTP ${response.status}`)
  }

  const json = await response.json()
  return !!json.ok
}
