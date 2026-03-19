import type { MessageContent } from '@/types'

const BASE = '/api'

export interface ConversationSummary {
  conversationId: string
  title?: string
  updatedAt: string
  messageCount: number
  lastPreview: string
}

export interface PersistedMessage {
  id?: string
  role: 'user' | 'assistant'
  content: MessageContent
  createdAt?: string
}

export async function streamChat(
  messages: Array<{ role: string; content: MessageContent }>,
  onChunk: (text: string) => void,
  conversationId?: string,
  signal?: AbortSignal
): Promise<void> {
  const response = await fetch(`${BASE}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ messages, stream: true, conversationId }),
    signal
  })

  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  if (!response.body) throw new Error('No response body')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''

    for (const line of lines) {
      const trimmed = line.trim()
      if (!trimmed.startsWith('data:')) continue
      const data = trimmed.slice(5).trim()
      if (data === '[DONE]') return

      try {
        const json = JSON.parse(data)
        const content = json.choices?.[0]?.delta?.content
        if (content) onChunk(content)
      } catch {
        // ignore parse errors for malformed chunks
      }
    }
  }
}

export async function listConversations(): Promise<ConversationSummary[]> {
  const response = await fetch(`${BASE}/chat/conversations`)
  if (!response.ok) {
    throw new Error(`获取会话列表失败: HTTP ${response.status}`)
  }

  const json = await response.json()
  return Array.isArray(json.items) ? json.items : []
}

export async function getConversationHistory(conversationId: string): Promise<PersistedMessage[]> {
  const response = await fetch(`${BASE}/chat/history?conversationId=${encodeURIComponent(conversationId)}`)
  if (!response.ok) {
    throw new Error(`获取聊天记录失败: HTTP ${response.status}`)
  }

  const json = await response.json()
  return Array.isArray(json.messages) ? json.messages : []
}

export async function renameConversation(conversationId: string, newName: string): Promise<boolean> {
  const response = await fetch(`${BASE}/chat/conversations/rename`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId, newName })
  })
  if (!response.ok) {
    throw new Error(`重命名失败: HTTP ${response.status}`)
  }
  const json = await response.json()
  return !!json.ok
}

export async function deleteConversation(conversationId: string): Promise<boolean> {
  const response = await fetch(`${BASE}/chat/conversations?conversationId=${encodeURIComponent(conversationId)}`, {
    method: 'DELETE'
  })
  if (!response.ok) {
    throw new Error(`删除失败: HTTP ${response.status}`)
  }
  const json = await response.json()
  return !!json.ok
}
