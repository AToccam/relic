import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getConversationHistory, listConversations, streamChat } from '@/api/chat'
import { useSourcesStore } from '@/stores/sources'
import type { Message, MessageContent, MessagePart } from '@/types'
import type { ConversationSummary, PersistedMessage } from '@/api/chat'

export const useChatStore = defineStore('chat', () => {
  const sources = useSourcesStore()
  const messages = ref<Message[]>([])
  const conversations = ref<ConversationSummary[]>([])
  const currentConversationId = ref('')
  const loadingHistory = ref(false)
  const isStreaming = ref(false)
  let abortController: AbortController | null = null

  function addMessage(role: 'user' | 'assistant', content: string, payloadContent?: MessageContent): Message {
    const msg: Message = {
      id: `${Date.now()}-${Math.random()}`,
      role,
      content,
      payloadContent,
      streaming: false
    }
    messages.value.push(msg)
    return messages.value[messages.value.length - 1]!
  }

  async function send(userText: string) {
    if (isStreaming.value) return
    const selectedFiles = sources.selectedUsableFiles
    if (!userText.trim() && selectedFiles.length === 0) return

    const content = buildUserMessageContent(userText.trim(), selectedFiles)
    const displayText = buildUserDisplayText(userText.trim(), selectedFiles)

    addMessage('user', displayText, content)
    const assistantMsg = addMessage('assistant', '')
    assistantMsg.streaming = true
    isStreaming.value = true
    abortController = new AbortController()

    try {
      const payload = messages.value
        .filter(m => m.id !== assistantMsg.id)
        .map(m => ({ role: m.role, content: m.payloadContent ?? m.content }))

      await streamChat(
        payload,
        (chunk) => { assistantMsg.content += chunk },
        currentConversationId.value,
        abortController.signal
      )

      await refreshConversations()
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
    currentConversationId.value = buildConversationId()
    messages.value = []
    conversations.value = [
      {
        conversationId: currentConversationId.value,
        updatedAt: new Date().toISOString(),
        messageCount: 0,
        lastPreview: ''
      },
      ...conversations.value.filter(c => c.conversationId !== currentConversationId.value)
    ]
  }

  async function init() {
    loadingHistory.value = true
    try {
      await refreshConversations()
      const first = conversations.value.length > 0 ? conversations.value[0] : undefined
      if (first) {
        await selectConversation(first.conversationId)
      } else {
        clear()
      }
    } finally {
      loadingHistory.value = false
    }
  }

  async function refreshConversations() {
    conversations.value = await listConversations()
  }

  async function selectConversation(conversationId: string) {
    const id = (conversationId || '').trim()
    if (!id) return

    loadingHistory.value = true
    try {
      const history = await getConversationHistory(id)
      currentConversationId.value = id
      messages.value = history
        .filter(item => item.role === 'user' || item.role === 'assistant')
        .map(item => toUiMessage(item))
    } finally {
      loadingHistory.value = false
    }
  }

  function newConversation() {
    clear()
  }

  return {
    messages,
    conversations,
    currentConversationId,
    loadingHistory,
    isStreaming,
    send,
    stop,
    clear,
    init,
    refreshConversations,
    selectConversation,
    newConversation
  }
})

function buildConversationId(): string {
  return `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
}

function toUiMessage(item: PersistedMessage): Message {
  const payload = item.content
  return {
    id: item.id || `${Date.now()}-${Math.random()}`,
    role: item.role,
    payloadContent: payload,
    content: toDisplayContent(payload),
    streaming: false
  }
}

function toDisplayContent(content: MessageContent): string {
  if (typeof content === 'string') {
    return content
  }

  const parts = Array.isArray(content) ? content : []
  const lines: string[] = []
  for (const part of parts) {
    if (part.type === 'text') {
      lines.push(part.text)
      continue
    }
    if (part.type === 'input_file') {
      lines.push(`附件: ${part.input_file.filename}`)
      continue
    }
    if (part.type === 'image_url') {
      lines.push('附件: 图片')
      continue
    }
    if (part.type === 'input_audio') {
      lines.push('附件: 音频')
    }
  }
  return lines.join('\n').trim()
}

function buildUserDisplayText(userText: string, files: Array<{ name: string; relativePath: string }>): string {
  const lines: string[] = []
  if (userText) {
    lines.push(userText)
  }
  if (files.length > 0) {
    lines.push('')
    lines.push('附件:')
    for (const f of files) {
      lines.push(`- ${f.name}${f.relativePath ? ` (${f.relativePath})` : ''}`)
    }
  }
  return lines.join('\n').trim()
}

function buildUserMessageContent(
  userText: string,
  files: Array<{ name: string; relativePath: string; mimeType: string; dataUrl?: string }>
): MessageContent {
  if (files.length === 0) {
    return userText
  }

  const parts: MessagePart[] = []
  if (userText) {
    parts.push({ type: 'text', text: userText })
  }

  const toolFallbackFiles: string[] = []

  for (const f of files) {
    const mime = (f.mimeType || '').toLowerCase()
    if (mime.startsWith('image/') && f.dataUrl) {
      parts.push({
        type: 'image_url',
        image_url: { url: f.dataUrl }
      })
      continue
    }

    if (mime.startsWith('audio/') && f.dataUrl) {
      const parsed = parseAudioDataUrl(f.dataUrl, mime)
      if (parsed) {
        parts.push({
          type: 'input_audio',
          input_audio: parsed
        })
        continue
      }
    }

    parts.push({
      type: 'input_file',
      input_file: {
        filename: f.relativePath || f.name,
        mime_type: f.mimeType
      }
    })
    toolFallbackFiles.push(f.relativePath || f.name)
  }

  if (toolFallbackFiles.length > 0) {
    parts.push({
      type: 'text',
      text:
        `已上传文件到工作区: ${toolFallbackFiles.join(', ')}。` +
        '如果你无法直接读取这些附件，请调用 read_file 工具逐个读取后再回答。'
    })
  }

  return parts
}

function parseAudioDataUrl(dataUrl: string, fallbackMime: string): { data: string; format: string } | null {
  const marker = ';base64,'
  const idx = dataUrl.indexOf(marker)
  if (idx <= 5) {
    return null
  }

  const mime = dataUrl.slice(5, idx) || fallbackMime
  const base64 = dataUrl.slice(idx + marker.length)
  if (!base64) {
    return null
  }

  const subtype = mime.split('/')[1] || 'mp3'
  const format = subtype.split(';')[0] || 'mp3'
  return { data: base64, format }
}
