export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  streaming?: boolean
}

export type Mode = 'single' | 'multi'

export interface ModeResponse {
  mode: Mode
  availableProviders: string[]
}

export interface TestResult {
  provider: string
  status: 'ok' | 'fail'
  costMs: number
  reply: string
}

export interface MultiTestResult {
  costMs: number
  advisors: Record<string, string>
}
