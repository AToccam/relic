export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  apiContent?: ChatContent
  attachments?: LocalAttachment[]
  streaming?: boolean
}

export interface TextPart {
  type: 'text'
  text: string
}

export interface ImageUrlPart {
  type: 'image_url'
  image_url: {
    url: string
  }
}

export interface InputAudioPart {
  type: 'input_audio'
  input_audio: {
    data: string
    format: string
  }
}

export interface VideoUrlPart {
  type: 'video_url'
  video_url: {
    url: string
  }
}

export interface FileUrlPart {
  type: 'file_url'
  file_url: {
    url: string
    filename?: string
    mime_type?: string
  }
}

export type ChatContent = string | Array<TextPart | ImageUrlPart | InputAudioPart | VideoUrlPart | FileUrlPart>

export interface LocalAttachment {
  id: string
  kind: 'image' | 'audio' | 'video' | 'file'
  name: string
  mimeType: string
  dataUrl: string
}

export type Mode = 'single' | 'multi'

export interface ModeResponse {
  mode: Mode
  singleProvider?: string
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
