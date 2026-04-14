import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'
import {
  deleteConversation as deleteConversationApi,
  getConversationHistory,
  listConversations,
  renameConversation as renameConversationApi,
  streamChat
} from '@/api/chat'
import { detectTopicDrift } from '@/api/mode'
import { useSourcesStore } from '@/stores/sources'
import { useStudioStore } from '@/stores/studio'
import type { Message, MessageContent, MessagePart } from '@/types'
import type { ConversationSummary, PersistedMessage } from '@/api/chat'

export const useChatStore = defineStore('chat', () => {
  const sources = useSourcesStore()
  const studio = useStudioStore()
  const messages = ref<Message[]>([])
  const conversations = ref<ConversationSummary[]>([])
  const currentConversationId = ref('')
  const loadingHistory = ref(false)
  const streamingByConversation = ref<Record<string, boolean>>({})
  const pendingConversationIds = ref<string[]>([])
  const abortControllers = new Map<string, AbortController>()
  const messageCacheByConversation = new Map<string, Message[]>()
  const driftSuggested = ref(false)
  const driftDetecting = ref(false)
  let driftCheckSeq = 0

  function toMessageArray(history: PersistedMessage[]): Message[] {
    return history
      .filter(item => item.role === 'user' || item.role === 'assistant')
      .map(item => toUiMessage(item))
  }

  function createConversationBuffer(initial?: Message[]): Message[] {
    return reactive(initial ? [...initial] : []) as Message[]
  }

  function getOrCreateConversationBuffer(conversationId: string): Message[] {
    const id = (conversationId || '').trim()
    if (!id) return []
    const existing = messageCacheByConversation.get(id)
    if (existing) {
      return existing
    }
    const created = createConversationBuffer()
    messageCacheByConversation.set(id, created)
    return created
  }

  function isConversationStreaming(conversationId: string): boolean {
    const id = (conversationId || '').trim()
    if (!id) return false
    return !!streamingByConversation.value[id]
  }

  function setConversationStreaming(conversationId: string, value: boolean) {
    const id = (conversationId || '').trim()
    if (!id) return

    const next = { ...streamingByConversation.value }
    if (value) {
      next[id] = true
      if (!pendingConversationIds.value.includes(id)) {
        pendingConversationIds.value = [...pendingConversationIds.value, id]
      }
    } else {
      delete next[id]
      pendingConversationIds.value = pendingConversationIds.value.filter(item => item !== id)
    }
    streamingByConversation.value = next
  }

  function addMessage(role: 'user' | 'assistant', content: string, payloadContent?: MessageContent): Message {
    const buffer = getOrCreateConversationBuffer(currentConversationId.value)
    const msg: Message = {
      id: `${Date.now()}-${Math.random()}`,
      role,
      content,
      payloadContent,
      streaming: false
    }
    buffer.push(msg)
    if (currentConversationId.value) {
      messages.value = buffer
    }
    return buffer[buffer.length - 1]!
  }

  async function send(userText: string) {
    const targetConversationId = currentConversationId.value || buildConversationId()
    if (!currentConversationId.value) {
      currentConversationId.value = targetConversationId
      messages.value = getOrCreateConversationBuffer(targetConversationId)
      sources.setConversation(targetConversationId)
    }

    if (isConversationStreaming(targetConversationId)) return

    const selectedFiles = sources.selectedUsableFiles
    if (!userText.trim() && selectedFiles.length === 0) return

    const content = buildUserMessageContent(userText.trim(), selectedFiles)
    const displayText = buildUserDisplayText(userText.trim(), selectedFiles)

    addMessage('user', displayText, content)
    sources.setAllUsableSelection(false)
    const assistantMsg = addMessage('assistant', '')
    assistantMsg.streaming = true
    setConversationStreaming(targetConversationId, true)
    const abortController = new AbortController()
    abortControllers.set(targetConversationId, abortController)

    try {
      const payload = messages.value
        .filter(m => m.id !== assistantMsg.id)
        .map(m => ({ role: m.role, content: m.payloadContent ?? m.content }))

      await streamChat(
        payload,
        (chunk) => { assistantMsg.content += chunk },
        targetConversationId,
        abortController.signal
      )

      await refreshConversations()
    } catch (e: unknown) {
      if (e instanceof Error && e.name !== 'AbortError') {
        assistantMsg.content += '\n\n⚠️ 连接失败：' + e.message
      }
    } finally {
      assistantMsg.streaming = false
      abortControllers.delete(targetConversationId)
      setConversationStreaming(targetConversationId, false)
      try {
        await studio.loadPersistedFiles()
      } catch {
        // 后端不可达时静默忽略
      }
    }
  }

  function stop() {
    const id = currentConversationId.value
    if (!id) return
    abortControllers.get(id)?.abort()
  }

  function clearDrift() {
    driftSuggested.value = false
    driftDetecting.value = false
    driftCheckSeq += 1
  }

  async function triggerDriftCheck(newMsg: string) {
    if (newMsg.length < 8) return
    if (driftSuggested.value || driftDetecting.value) return
    if (isConversationStreaming(currentConversationId.value)) return
    const buf = messageCacheByConversation.get(currentConversationId.value)
    if (!buf || buf.length < 4) return
    const lastUser = [...buf].reverse().find(m => m.role === 'user')
    if (!lastUser) return
    const prevText = lastUser.content
    if (!prevText || typeof prevText !== 'string') return

    const seq = ++driftCheckSeq
    driftDetecting.value = true
    try {
      const isDrift = await detectTopicDrift(prevText, newMsg)
      if (seq !== driftCheckSeq) return
      if (isDrift) driftSuggested.value = true
    } catch {
      // 检测失败静默忽略
    } finally {
      if (seq === driftCheckSeq) driftDetecting.value = false
    }
  }

  function confirmDriftNewConversation() {
    const newId = buildConversationId()
    sources.migrateSelectedFilesToConversation(newId)
    clearDrift()
    clear(newId)
  }

  function clear(id?: string) {
    clearDrift()
    currentConversationId.value = id || buildConversationId()
    const buffer = createConversationBuffer()
    messageCacheByConversation.set(currentConversationId.value, buffer)
    messages.value = buffer
    sources.setConversation(currentConversationId.value)
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
    try {
      conversations.value = await listConversations()
    } catch {
      // 后端不可达时静默忽略，保持当前会话列表不变
    }
  }

  async function selectConversation(conversationId: string) {
    const id = (conversationId || '').trim()
    if (!id) return

    clearDrift()
    loadingHistory.value = true
    try {
      if (isConversationStreaming(id)) {
        currentConversationId.value = id
        messages.value = getOrCreateConversationBuffer(id)
        sources.setConversation(id)
        return
      }

      const history = await getConversationHistory(id)
      const buffer = createConversationBuffer(toMessageArray(history))
      messageCacheByConversation.set(id, buffer)
      currentConversationId.value = id
      messages.value = buffer
      sources.setConversation(id)
    } finally {
      loadingHistory.value = false
    }
  }

  function newConversation() {
    clear()
  }

  async function renameConversation(conversationId: string, newName: string) {
    const ok = await renameConversationApi(conversationId, newName)
    if (!ok) return false
    await refreshConversations()
    return true
  }

  async function deleteConversation(conversationId: string) {
    abortControllers.get(conversationId)?.abort()
    abortControllers.delete(conversationId)
    setConversationStreaming(conversationId, false)

    const deletingCurrent = currentConversationId.value === conversationId
    const isDraftConversation = isLocalDraftConversation(conversationId)

    if (isDraftConversation) {
      messageCacheByConversation.delete(conversationId)
      conversations.value = conversations.value.filter(item => item.conversationId !== conversationId)

      if (deletingCurrent) {
        const first = conversations.value.length > 0 ? conversations.value[0] : undefined
        if (first) {
          await selectConversation(first.conversationId)
        } else {
          clear()
        }
      } else {
        messages.value = [...messages.value]
      }
      return true
    }

    messageCacheByConversation.delete(conversationId)

    const ok = await deleteConversationApi(conversationId)
    if (!ok) return false

    await refreshConversations()

    if (deletingCurrent) {
      const first = conversations.value.length > 0 ? conversations.value[0] : undefined
      if (first) {
        await selectConversation(first.conversationId)
      } else {
        clear()
      }
    } else {
      messages.value = [...messages.value]
    }
    return true
  }

  function isLocalDraftConversation(conversationId: string): boolean {
    const id = (conversationId || '').trim()
    if (!id) return false

    const summary = conversations.value.find(item => item.conversationId === id)
    if (!summary) return false

    const title = (summary.title || '').trim()
    const preview = (summary.lastPreview || '').trim()
    const messageCount = Number(summary.messageCount || 0)
    const cachedMessages = messageCacheByConversation.get(id)

    return !title && !preview && messageCount === 0 && (!cachedMessages || cachedMessages.length === 0)
  }

  return {
    messages,
    conversations,
    currentConversationId,
    loadingHistory,
    streamingByConversation,
    pendingConversationIds,
    driftSuggested,
    driftDetecting,
    isConversationStreaming,
    send,
    stop,
    clear,
    init,
    refreshConversations,
    selectConversation,
    newConversation,
    renameConversation,
    deleteConversation,
    triggerDriftCheck,
    clearDrift,
    confirmDriftNewConversation
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
