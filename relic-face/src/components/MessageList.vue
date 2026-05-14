<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { useChatStore } from '@/stores/chat'
import MessageItem from './MessageItem.vue'

const chat = useChatStore()
const listRef = ref<HTMLElement | null>(null)

watch(
  () => chat.messages.map(m => m.content).join(''),
  async () => {
    await nextTick()
    if (listRef.value) {
      const el = listRef.value
      const isNearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 100
      if (isNearBottom) {
        el.scrollTop = el.scrollHeight
      }
    }
  }
)
</script>

<template>
  <div class="message-list" ref="listRef">
    <div v-if="chat.messages.length === 0" class="empty-hint">
      <p>Relic AI 助手</p>
      <span>发送消息开始对话</span>
    </div>
    <MessageItem
      v-for="msg in chat.messages"
      :key="msg.id"
      :message="msg"
    />
  </div>
</template>

<style scoped>
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 24px 28px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  scrollbar-width: thin;
  scrollbar-color: #1e3354 transparent;
}

.empty-hint {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  user-select: none;
  gap: 10px;
}

.empty-hint p {
  font-size: 18px;
  font-weight: 800;
  color: #22d3ee;
  margin: 0;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  font-family: ui-monospace, 'JetBrains Mono', 'Courier New', monospace;
  opacity: 0.55;
}

.empty-hint span {
  font-size: 13px;
  color: #3d5878;
  letter-spacing: 0.02em;
}
</style>
