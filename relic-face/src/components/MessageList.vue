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
      listRef.value.scrollTop = listRef.value.scrollHeight
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
  scrollbar-color: #cbd5e0 transparent;
}

.empty-hint {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #a0aec0;
  text-align: center;
  user-select: none;
}

.empty-hint p {
  font-size: 22px;
  font-weight: 600;
  color: #4a5568;
  margin: 0 0 8px;
}

.empty-hint span {
  font-size: 14px;
  color: #a0aec0;
}
</style>
