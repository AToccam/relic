import type { ModeResponse, TestResult, Mode, MultiTestResult } from '@/types'

const BASE = '/api'

export interface ModeUpdateRequest {
  mode?: Mode
  singleProvider?: string
  multiLeader?: string
  multiAdvisors?: string[]
}

export async function getMode(): Promise<ModeResponse> {
  const res = await fetch(`${BASE}/mode`)
  return res.json()
}

export async function setMode(payload: ModeUpdateRequest): Promise<ModeResponse> {
  const res = await fetch(`${BASE}/mode`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  return res.json()
}

export async function testProvider(provider: string): Promise<TestResult> {
  const startedAt = Date.now()
  try {
    const res = await fetch(`${BASE}/test/ai`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider, prompt: 'Hello, please introduce yourself in one sentence.' })
    })
    const data = await readJsonOrText(res)
    if (!res.ok) {
      return {
        provider,
        status: 'fail',
        costMs: Date.now() - startedAt,
        reply: formatErrorBody(data, `HTTP ${res.status}`)
      }
    }
    return data as TestResult
  } catch (e) {
    return {
      provider,
      status: 'fail',
      costMs: Date.now() - startedAt,
      reply: e instanceof Error ? e.message : 'Request failed'
    }
  }
}

export async function testMulti(prompt: string): Promise<MultiTestResult> {
  const res = await fetch(`${BASE}/test/multi`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt })
  })
  const data = await readJsonOrText(res)
  if (!res.ok) {
    throw new Error(formatErrorBody(data, `HTTP ${res.status}`))
  }
  return data as MultiTestResult
}

export async function detectTopicDrift(
  prevMsg: string,
  newMsg: string,
  anchorFiles?: string[]
): Promise<boolean> {
  const system = 'You judge topic drift. Reply only YES or NO.'
  const fileContext =
    anchorFiles && anchorFiles.length > 0
      ? `\nCurrent related files: ${anchorFiles.slice(0, 5).join(', ')}. If the new message is still related to these files, reply NO.`
      : ''
  const prompt =
    `Decide whether these two messages are clearly different topics.${fileContext}\n` +
    `Message A: ${prevMsg.slice(0, 150)}\n` +
    `Message B: ${newMsg.slice(0, 150)}\n` +
    'YES = clearly different topic; NO = same topic or related.'

  try {
    const res = await fetch('http://127.0.0.1:11434/api/generate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: 'gemma3:1b',
        system,
        prompt,
        stream: false,
        think: false,
        options: { temperature: 0.0, num_predict: 12 }
      })
    })
    const json = await res.json()
    return ((json.response as string) || '').toUpperCase().includes('YES')
  } catch {
    return false
  }
}

async function readJsonOrText(res: Response): Promise<unknown> {
  const text = await res.text()
  if (!text) {
    return null
  }
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

function formatErrorBody(data: unknown, fallback: string): string {
  if (typeof data === 'string' && data.trim()) {
    return data
  }
  if (data && typeof data === 'object') {
    const obj = data as Record<string, unknown>
    const message = obj.message ?? obj.error ?? obj.reply
    if (typeof message === 'string' && message.trim()) {
      return message
    }
    return JSON.stringify(obj)
  }
  return fallback
}
