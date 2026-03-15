import type { ModeResponse, TestResult, Mode, MultiTestResult } from '@/types'

const BASE = '/api'

export async function getMode(): Promise<ModeResponse> {
  const res = await fetch(`${BASE}/mode`)
  return res.json()
}

export async function setMode(mode: Mode): Promise<ModeResponse> {
  const res = await fetch(`${BASE}/mode`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode })
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
