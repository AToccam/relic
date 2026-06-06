const BASE = '/api'

export interface WebSearchResult {
  id: string
  title: string
  url: string
  snippet: string
  score: number
  summary?: string
  siteName?: string
  datePublished?: string
}

export interface ImportWebResourceResponse {
  filename: string
  storedName: string
  relativePath: string
  mimeType: string
  size: number
  sourceType: string
  indexTriggered?: boolean
  sourceId?: string
  originUrl?: string
  title?: string
  snippet?: string
  keyword?: string
}

export async function searchWebResources(keyword: string, limit = 8): Promise<WebSearchResult[]> {
  const response = await fetch(`${BASE}/web-resources/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ keyword, limit })
  })

  if (!response.ok) {
    throw new Error(await readErrorMessage(response, `联网搜索失败: HTTP ${response.status}`))
  }

  const json = await response.json()
  return Array.isArray(json.items) ? json.items : []
}

export async function importWebResource(
  item: Pick<WebSearchResult, 'url' | 'title' | 'snippet' | 'summary' | 'siteName' | 'datePublished'>,
  keyword: string
): Promise<ImportWebResourceResponse> {
  const response = await fetch(`${BASE}/web-resources/import`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      url: item.url,
      title: item.title,
      snippet: item.snippet,
      summary: item.summary,
      siteName: item.siteName,
      datePublished: item.datePublished,
      keyword
    })
  })

  if (!response.ok) {
    throw new Error(await readErrorMessage(response, `添加网页来源失败: HTTP ${response.status}`))
  }

  return response.json()
}

async function readErrorMessage(response: Response, fallback: string): Promise<string> {
  try {
    const json = await response.json()
    if (typeof json?.message === 'string' && json.message.trim()) {
      return json.message.trim()
    }
  } catch {
    // ignore non-json error bodies
  }
  return fallback
}
