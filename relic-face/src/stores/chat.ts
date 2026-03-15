import { defineStore } from 'pinia'
import { ref } from 'vue'
import { streamChat } from '@/api/chat'
import type { Message } from '@/types'

export const useChatStore = defineStore('chat', () => {
  const messages = ref<Message[]>([])
  const isStreaming = ref(false)
  let abortController: AbortController | null = null

  function addMessage(role: 'user' | 'assistant', content: string): Message {
    const msg: Message = {
      id: `${Date.now()}-${Math.random()}`,
      role,
      content,
      streaming: false
    }
    messages.value.push(msg)
    return messages.value[messages.value.length - 1]!
  }

  async function send(userText: string) {
    if (isStreaming.value || !userText.trim()) return

    addMessage('user', userText)
    const assistantMsg = addMessage('assistant', '')
    assistantMsg.streaming = true
    isStreaming.value = true
    abortController = new AbortController()

    try {
      const payload = messages.value
        .filter(m => m.id !== assistantMsg.id)
        .map(m => ({ role: m.role, content: m.content }))

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
