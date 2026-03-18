import type { MessageContent } from '@/types'

const BASE = '/api'

export async function streamChat(
  messages: Array<{ role: string; content: MessageContent }>,
  onChunk: (text: string) => void,
  signal?: AbortSignal
): Promise<void> {
  const response = await fetch(`${BASE}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ messages, stream: true }),
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
