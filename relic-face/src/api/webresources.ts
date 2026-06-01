const BASE = '/api'

export interface WebSearchResult {
  id: string
  title: string
  url: string
  snippet: string
  score: number
}

export interface ImportedResource {
  ok: boolean
  filename: string
  relativePath: string
  mimeType: string
  size: number
  sourceType: string
  indexTriggered: boolean
  originUrl?: string
  title?: string
  snippet?: string
  keyword?: string
  sourceId: string
}

export async function searchWeb(keyword: string, limit?: number): Promise<WebSearchResult[]> {
  const response = await fetch(`${BASE}/web-resources/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ keyword, limit: limit ?? 8 })
  })
  if (!response.ok) {
    throw new Error(`联网搜索失败: HTTP ${response.status}`)
  }
  const json = await response.json()
  return Array.isArray(json.items) ? json.items : []
}

export async function importWebResource(result: WebSearchResult, keyword: string): Promise<ImportedResource> {
  const response = await fetch(`${BASE}/web-resources/import`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      url: result.url,
      title: result.title,
      snippet: result.snippet,
      keyword
    })
  })
  if (!response.ok) {
    throw new Error(`导入网页失败: HTTP ${response.status}`)
  }
  return response.json()
}
