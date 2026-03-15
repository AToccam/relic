<script setup lang="ts">
import { ref } from 'vue'
import { useChatStore } from '@/stores/chat'

const chat = useChatStore()
const input = ref('')

function handleSend() {
  if (chat.isStreaming || !input.value.trim()) return
  chat.send(input.value.trim())
  input.value = ''
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}
</script>

<template>
  <div class="input-area">
    <textarea
      v-model="input"
      placeholder="输入消息… (Enter 发送，Shift+Enter 换行)"
      @keydown="handleKeydown"
      rows="3"
    />
    <div class="input-actions">
      <button
        v-if="!chat.isStreaming"
        class="send-btn"
        @click="handleSend"
        :disabled="!input.trim()"
      >
        发送
      </button>
      <button v-else class="stop-btn" @click="chat.stop()">
        停止
      </button>
    </div>
  </div>
</template>

<style scoped>
.input-area {
  padding: 12px 20px 16px;
  border-top: 1px solid #2d3748;
  display: flex;
  gap: 10px;
  align-items: flex-end;
  background: #1a202c;
}

textarea {
  flex: 1;
  resize: none;
  background: #2d3748;
  color: #e2e8f0;
  border: 1px solid #4a5568;
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 14px;
  line-height: 1.5;
  font-family: inherit;
  outline: none;
  transition: border-color 0.2s;
  scrollbar-width: thin;
}

textarea:focus {
  border-color: #3b82f6;
}

textarea::placeholder {
  color: #718096;
}

.input-actions {
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}

.send-btn,
.stop-btn {
  padding: 8px 20px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  border: none;
  transition: background 0.2s, opacity 0.2s;
  white-space: nowrap;
}

.send-btn {
  background: #3b82f6;
  color: #fff;
}

.send-btn:hover:not(:disabled) {
  background: #2563eb;
}

.send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.stop-btn {
  background: #e53e3e;
  color: #fff;
}

.stop-btn:hover {
  background: #c53030;
}
</style>
