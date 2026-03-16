import { defineStore } from 'pinia'
import { ref } from 'vue'
import { streamChat } from '@/api/chat'
import type { ChatContent, LocalAttachment, Message } from '@/types'

export const useChatStore = defineStore('chat', () => {
  const messages = ref<Message[]>([])
  const isStreaming = ref(false)
  let abortController: AbortController | null = null

  function addMessage(
    role: 'user' | 'assistant',
    content: string,
    options?: { apiContent?: ChatContent; attachments?: LocalAttachment[] }
  ): Message {
    const msg: Message = {
      id: `${Date.now()}-${Math.random()}`,
      role,
      content,
      apiContent: options?.apiContent,
      attachments: options?.attachments,
      streaming: false
    }
    messages.value.push(msg)
    return messages.value[messages.value.length - 1]!
  }

  function inferAudioFormat(mimeType: string): string {
    const lower = mimeType.toLowerCase()
    if (lower.includes('mpeg') || lower.includes('mp3')) return 'mp3'
    if (lower.includes('wav') || lower.includes('x-wav')) return 'wav'
    if (lower.includes('ogg')) return 'ogg'
    if (lower.includes('webm')) return 'webm'
    return 'mp3'
  }

  function toUserApiContent(userText: string, attachments: LocalAttachment[]): ChatContent {
    if (attachments.length === 0) {
      return userText
    }

    const parts: Exclude<ChatContent, string> = []
    if (userText.trim()) {
      parts.push({ type: 'text', text: userText.trim() })
    }

    for (const attachment of attachments) {
      if (attachment.kind === 'image') {
        parts.push({
          type: 'image_url',
          image_url: { url: attachment.dataUrl }
        })
        continue
      }

      if (attachment.kind === 'video') {
        parts.push({
          type: 'video_url',
          video_url: { url: attachment.dataUrl }
        })
        continue
      }

      if (attachment.kind === 'file') {
        parts.push({
          type: 'file_url',
          file_url: {
            url: attachment.dataUrl,
            filename: attachment.name,
            mime_type: attachment.mimeType
          }
        })
        continue
      }

      const base64 = attachment.dataUrl.includes(',')
        ? attachment.dataUrl.split(',')[1] ?? ''
        : attachment.dataUrl
      parts.push({
        type: 'input_audio',
        input_audio: {
          data: base64,
          format: inferAudioFormat(attachment.mimeType)
        }
      })
    }

    return parts
  }

  function buildUserDisplayText(userText: string, attachments: LocalAttachment[]): string {
    const lines: string[] = []
    if (userText.trim()) {
      lines.push(userText.trim())
    }

    for (const attachment of attachments) {
      if (attachment.kind === 'image') {
        lines.push(`[图片] ${attachment.name}`)
        continue
      }
      if (attachment.kind === 'audio') {
        lines.push(`[音频] ${attachment.name}`)
        continue
      }
      if (attachment.kind === 'video') {
        lines.push(`[视频] ${attachment.name}`)
        continue
      }
      lines.push(`[文件] ${attachment.name}`)
    }

    return lines.join('\n')
  }

  async function send(userText: string, attachments: LocalAttachment[] = []) {
    if (isStreaming.value) return
    if (!userText.trim() && attachments.length === 0) return

    const userApiContent = toUserApiContent(userText, attachments)
    const userDisplayText = buildUserDisplayText(userText, attachments)

    addMessage('user', userDisplayText, {
      apiContent: userApiContent,
      attachments
    })
    const assistantMsg = addMessage('assistant', '')
    assistantMsg.streaming = true
    isStreaming.value = true
    abortController = new AbortController()

    try {
      const payload = messages.value
        .filter(m => m.id !== assistantMsg.id)
        .map(m => ({ role: m.role, content: m.apiContent ?? m.content }))

      await streamChat(
        payload,
        (chunk) => { assistantMsg.content += chunk },
        abortController.signal
      )
    } catch (e: unknown) {
      if (e instanceof Error && e.name !== 'AbortError') {
        assistantMsg.content += '\n\n⚠️ 连接失败：' + e.message
      }
    } finally {
      assistantMsg.streaming = false
      isStreaming.value = false
      abortController = null
    }
  }

  function stop() {
    abortController?.abort()
  }

  function clear() {
    messages.value = []
  }

  return { messages, isStreaming, send, stop, clear }
})
