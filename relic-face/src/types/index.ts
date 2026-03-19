export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  payloadContent?: MessageContent
  streaming?: boolean
}

export interface TextPart {
  type: 'text'
  text: string
}

export interface ImagePart {
  type: 'image_url'
  image_url: {
    url: string
  }
}

export interface AudioPart {
  type: 'input_audio'
  input_audio: {
    data: string
    format: string
  }
}

export interface FilePart {
  type: 'input_file'
  input_file: {
    filename: string
    mime_type?: string
  }
}

export type MessagePart = TextPart | ImagePart | AudioPart | FilePart
export type MessageContent = string | MessagePart[]

export type Mode = 'single' | 'multi'

export interface ModeResponse {
  mode: Mode
  singleProvider?: string
  multiLeader?: string
  multiAdvisors?: string[]
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
