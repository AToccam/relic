const BASE = '/api'

export interface UploadFileResponse {
  filename: string
  storedName: string
  relativePath: string
  mimeType: string
  size: number
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
