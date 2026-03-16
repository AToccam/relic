<script setup lang="ts">
import { ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import type { LocalAttachment } from '@/types'

const chat = useChatStore()
const input = ref('')
const attachments = ref<LocalAttachment[]>([])
const fileInput = ref<HTMLInputElement | null>(null)

function toDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

async function onSelectFiles(event: Event) {
  const target = event.target as HTMLInputElement
  const files = target.files
  if (!files || files.length === 0) return

  for (const file of Array.from(files)) {
    const isImage = file.type.startsWith('image/')
    const isAudio = file.type.startsWith('audio/')
    const isVideo = file.type.startsWith('video/')
    const isFile = !isImage && !isAudio && !isVideo

    const dataUrl = await toDataUrl(file)
    attachments.value.push({
      id: `${Date.now()}-${Math.random()}`,
      kind: isImage ? 'image' : isAudio ? 'audio' : isVideo ? 'video' : 'file',
      name: file.name,
      mimeType: file.type,
      dataUrl
    })
  }

  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

function removeAttachment(id: string) {
  attachments.value = attachments.value.filter(a => a.id !== id)
}

function handleSend() {
  if (chat.isStreaming) return
  if (!input.value.trim() && attachments.value.length === 0) return

  chat.send(input.value.trim(), [...attachments.value])
  input.value = ''
  attachments.value = []
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
    <div class="editor-area">
      <textarea
        v-model="input"
        placeholder="输入消息… (Enter 发送，Shift+Enter 换行，可添加图片/音频/视频/文件)"
        @keydown="handleKeydown"
        rows="3"
      />
      <div v-if="attachments.length" class="attachments">
        <div v-for="item in attachments" :key="item.id" class="attachment-item">
          <div class="attachment-title">
            <span>
              {{ item.kind === 'image' ? '图片' : item.kind === 'audio' ? '音频' : item.kind === 'video' ? '视频' : '文件' }} · {{ item.name }}
            </span>
            <button class="remove-btn" @click="removeAttachment(item.id)">移除</button>
          </div>
          <img v-if="item.kind === 'image'" class="preview-image" :src="item.dataUrl" alt="attachment" />
          <audio v-else-if="item.kind === 'audio'" class="preview-audio" :src="item.dataUrl" controls preload="metadata" />
          <video v-else-if="item.kind === 'video'" class="preview-video" :src="item.dataUrl" controls preload="metadata" />
          <div v-else class="preview-file">已添加文件，发送后将按多模态附件处理</div>
        </div>
      </div>
    </div>
    <div class="input-actions">
      <input
        ref="fileInput"
        type="file"
        accept="image/*,audio/*,video/*,.ppt,.pptx,.xls,.xlsx,.csv,.ods,.odp,.key,.numbers,.pages,.pdf,.doc,.docx,.txt"
        multiple
        @change="onSelectFiles"
      />
      <button
        v-if="!chat.isStreaming"
        class="send-btn"
        @click="handleSend"
        :disabled="!input.trim() && attachments.length === 0"
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

.editor-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

textarea {
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
  gap: 8px;
}

input[type='file'] {
  width: 112px;
  font-size: 12px;
  color: #a0aec0;
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

.attachments {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.attachment-item {
  border: 1px solid #4a5568;
  border-radius: 8px;
  padding: 8px;
  background: #2d3748;
}

.attachment-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: #cbd5e0;
  font-size: 12px;
  margin-bottom: 6px;
}

.remove-btn {
  border: none;
  background: transparent;
  color: #f87171;
  cursor: pointer;
  font-size: 12px;
}

.preview-image {
  max-height: 140px;
  border-radius: 6px;
  max-width: 100%;
}

.preview-audio {
  width: 100%;
  height: 32px;
}

.preview-video {
  max-height: 200px;
  width: 100%;
  border-radius: 6px;
  background: #111827;
}

.preview-file {
  color: #cbd5e0;
  font-size: 12px;
  padding: 8px;
  border: 1px dashed #4a5568;
  border-radius: 6px;
}
</style>
