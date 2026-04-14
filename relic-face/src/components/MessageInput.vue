<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useSourcesStore } from '@/stores/sources'

const chat = useChatStore()
const sources = useSourcesStore()
const input = ref('')

let driftTimer: ReturnType<typeof setTimeout> | null = null

watch(input, (val) => {
  if (driftTimer) { clearTimeout(driftTimer); driftTimer = null }
  if (!val.trim() || val.trim().length < 8) return
  driftTimer = setTimeout(() => {
    chat.triggerDriftCheck(val.trim())
  }, 800)
})

onUnmounted(() => {
  if (driftTimer) clearTimeout(driftTimer)
})

function handleSend() {
  if (driftTimer) { clearTimeout(driftTimer); driftTimer = null }
  chat.clearDrift()
  if (chat.isConversationStreaming(chat.currentConversationId)) return
  if (!input.value.trim() && !sources.hasFiles) return
  chat.send(input.value.trim())
  input.value = ''
}

function onDriftNewConv() {
  if (driftTimer) { clearTimeout(driftTimer); driftTimer = null }
  chat.confirmDriftNewConversation()
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function removeChip(id: string) {
  sources.toggleFileSelection(id)
}

function isImage(mimeType: string) {
  return mimeType.startsWith('image/')
}
</script>

<template>
  <div class="input-area">
    <div v-if="sources.selectedUsableFiles.length" class="chip-row">
      <div
        v-for="file in sources.selectedUsableFiles"
        :key="file.id"
        class="file-chip"
        :title="file.relativePath"
      >
        <img v-if="isImage(file.mimeType) && file.dataUrl" :src="file.dataUrl" class="chip-thumb" />
        <svg v-else width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="chip-icon">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
          <polyline points="14 2 14 8 20 8" />
        </svg>
        <span class="chip-name">{{ file.name }}</span>
        <span class="chip-size">{{ file.sizeLabel }}</span>
        <button class="chip-remove" @click="removeChip(file.id)" title="取消附带">
          <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>
      </div>
      <span v-if="sources.usableFiles.length > sources.selectedUsableFiles.length" class="chip-unselected-hint">
        还有 {{ sources.usableFiles.length - sources.selectedUsableFiles.length }} 个文件未勾选
      </span>
    </div>
    <div v-if="chat.driftSuggested" class="drift-banner">
      <span class="drift-text">检测到话题可能已发生转变，建议开启新对话</span>
      <div class="drift-actions">
        <button class="drift-btn-new" @click="onDriftNewConv">开启新对话</button>
        <button class="drift-btn-keep" @click="chat.clearDrift()">继续当前对话</button>
      </div>
      <button class="drift-close" @click="chat.clearDrift()">×</button>
    </div>
    <textarea
      v-model="input"
      placeholder="输入消息… (Enter 发送，Shift+Enter 换行)"
      @keydown="handleKeydown"
      rows="3"
    />
    <div class="input-actions">
      <button
        v-if="!chat.isConversationStreaming(chat.currentConversationId)"
        class="send-btn"
        @click="handleSend"
        :disabled="!input.trim() && sources.selectedUsableFiles.length === 0"
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

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.file-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 8px 4px 6px;
  border-radius: 6px;
  background: rgba(99, 102, 241, 0.08);
  border: 1px solid rgba(99, 102, 241, 0.22);
  font-size: 12px;
  color: #4338ca;
  max-width: 200px;
}

.chip-thumb {
  width: 20px;
  height: 20px;
  border-radius: 3px;
  object-fit: cover;
  flex-shrink: 0;
}

.chip-icon {
  color: #6366f1;
  flex-shrink: 0;
}

.chip-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
  flex: 1;
}

.chip-size {
  font-size: 10px;
  color: #818cf8;
  white-space: nowrap;
  flex-shrink: 0;
}

.chip-remove {
  width: 16px;
  height: 16px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #818cf8;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  padding: 0;
  transition: all 0.12s;
}

.chip-remove:hover {
  background: rgba(239, 68, 68, 0.12);
  color: #ef4444;
}

.chip-unselected-hint {
  font-size: 11px;
  color: #a0aec0;
  white-space: nowrap;
}

.drift-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.3);
  font-size: 13px;
  color: #92400e;
}

.drift-text {
  flex: 1;
  min-width: 0;
  line-height: 1.4;
}

.drift-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.drift-btn-new,
.drift-btn-keep {
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  border: 1px solid;
  white-space: nowrap;
  transition: background 0.15s;
}

.drift-btn-new {
  background: rgba(245, 158, 11, 0.12);
  border-color: rgba(245, 158, 11, 0.4);
  color: #92400e;
  font-weight: 500;
}

.drift-btn-new:hover {
  background: rgba(245, 158, 11, 0.22);
}

.drift-btn-keep {
  background: transparent;
  border-color: rgba(146, 64, 14, 0.2);
  color: #b45309;
}

.drift-btn-keep:hover {
  background: rgba(245, 158, 11, 0.06);
}

.drift-close {
  width: 20px;
  height: 20px;
  border: none;
  background: transparent;
  color: #b45309;
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border-radius: 4px;
  transition: background 0.12s;
}

.drift-close:hover {
  background: rgba(245, 158, 11, 0.15);
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
