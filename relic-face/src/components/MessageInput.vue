<script setup lang="ts">
import { ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useSourcesStore } from '@/stores/sources'

const chat = useChatStore()
const sources = useSourcesStore()
const input = ref('')

function handleSend() {
  if (chat.isStreaming) return
  if (!input.value.trim() && !sources.hasFiles) return
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
    <div v-if="sources.hasFiles" class="file-tip">
      已附加 {{ sources.files.filter(f => !f.uploadError).length }} 个文件。发送后会优先按多模态读取，不支持时自动尝试工具读取。
    </div>
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
        :disabled="!input.trim() && !sources.hasFiles"
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
  border-top: 1px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: stretch;
  background: #ffffff;
}

.file-tip {
  font-size: 12px;
  color: #4f46e5;
  background: rgba(79, 70, 229, 0.08);
  border: 1px solid rgba(79, 70, 229, 0.2);
  padding: 8px 10px;
  border-radius: 8px;
}

textarea,
.input-actions {
  align-self: stretch;
}

.input-actions {
  align-items: flex-end;
}

textarea {
  flex: 1;
  resize: none;
  background: #f8f9fa;
  color: #1a202c;
  border: 1px solid #e2e8f0;
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
  border-color: #6366f1;
  background: #ffffff;
}

textarea::placeholder {
  color: #a0aec0;
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
  background: #6366f1;
  color: #fff;
}

.send-btn:hover:not(:disabled) {
  background: #4f46e5;
}

.send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.stop-btn {
  background: #ef4444;
  color: #fff;
}

.stop-btn:hover {
  background: #dc2626;
}
</style>
