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
  const res = await fetch(`${BASE}/test/ai`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, prompt: '你好，请用一句话介绍你自己' })
  })
  return res.json()
}

export async function testMulti(prompt: string): Promise<MultiTestResult> {
  const res = await fetch(`${BASE}/test/multi`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt })
  })
  return res.json()
}

export async function detectTopicDrift(prevMsg: string, newMsg: string): Promise<boolean> {
  const prompt =
    `你是话题判断助手，只能回答YES或NO，不要输出任何其他内容。\n` +
    `判断以下两条消息是否属于明显不同的话题领域。\n` +
    `消息A：${prevMsg.slice(0, 150)}\n` +
    `消息B：${newMsg.slice(0, 150)}\n` +
    `YES = 话题明显不同，建议新开会话；NO = 同一话题或存在关联性`
  try {
    const res = await fetch(`${BASE}/test/ai`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: 'qwen', prompt })
    })
    const json = await res.json()
    return ((json.reply as string) || '').toUpperCase().includes('YES')
  } catch {
    return false
  }
}
