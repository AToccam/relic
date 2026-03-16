<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import type { Message } from '@/types'

const props = defineProps<{ message: Message }>()

const html = computed(() =>
  marked.parse(props.message.content) as string
)
</script>

<template>
  <div :class="['message-item', message.role]">
    <div class="avatar">{{ message.role === 'user' ? 'U' : 'AI' }}</div>
    <div class="bubble">
      <div v-if="message.role === 'assistant'" class="markdown-body" v-html="html" />
      <template v-else>
        <div class="plain-text">{{ message.content }}</div>
        <div v-if="message.attachments?.length" class="attachment-preview-list">
          <div v-for="item in message.attachments" :key="item.id" class="attachment-preview-item">
            <img v-if="item.kind === 'image'" class="inline-image" :src="item.dataUrl" alt="user image" />
            <audio v-else-if="item.kind === 'audio'" class="inline-audio" :src="item.dataUrl" controls preload="metadata" />
            <video v-else-if="item.kind === 'video'" class="inline-video" :src="item.dataUrl" controls preload="metadata" />
            <div v-else class="inline-file">文件: {{ item.name }}</div>
          </div>
        </div>
      </template>
      <span v-if="message.streaming" class="cursor">▋</span>
    </div>
  </div>
</template>

<style scoped>
.message-item {
  display: flex;
  gap: 12px;
  padding: 8px 0;
  align-items: flex-start;
}

.message-item.user {
  flex-direction: row-reverse;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
  background: #e2e8f0;
  color: #4a5568;
}

.message-item.user .avatar {
  background: #6366f1;
  color: #fff;
}

.bubble {
  max-width: 72%;
  padding: 10px 14px;
  border-radius: 12px;
  line-height: 1.6;
  font-size: 14px;
  background: #f1f5f9;
  color: #1a202c;
  word-break: break-word;
}

.message-item.user .bubble {
  background: #6366f1;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.message-item.assistant .bubble {
  border-bottom-left-radius: 4px;
}

.plain-text {
  white-space: pre-wrap;
}

.attachment-preview-list {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.attachment-preview-item {
  background: rgba(0, 0, 0, 0.05);
  border-radius: 8px;
  padding: 6px;
}

.inline-image {
  max-width: 100%;
  max-height: 220px;
  border-radius: 6px;
  display: block;
}

.inline-audio {
  width: 100%;
  height: 32px;
}

.inline-video {
  width: 100%;
  max-height: 240px;
  border-radius: 6px;
  background: #e2e8f0;
}

.inline-file {
  border: 1px dashed #cbd5e0;
  border-radius: 6px;
  padding: 8px;
  color: #718096;
  font-size: 12px;
}

.cursor {
  display: inline-block;
  animation: blink 0.8s step-end infinite;
  color: #6366f1;
  margin-left: 2px;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.markdown-body :deep(p) { margin: 0 0 8px; }
.markdown-body :deep(p:last-child) { margin-bottom: 0; }
.markdown-body :deep(pre) {
  background: #e2e8f0;
  border-radius: 6px;
  padding: 10px 12px;
  overflow-x: auto;
  margin: 8px 0;
  font-size: 13px;
}
.markdown-body :deep(code) {
  background: #e2e8f0;
  border-radius: 4px;
  padding: 1px 4px;
  font-size: 13px;
  font-family: 'Consolas', 'Monaco', monospace;
  color: #6366f1;
}
.markdown-body :deep(pre code) {
  padding: 0;
  background: none;
  color: #1a202c;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) { padding-left: 20px; margin: 6px 0; }
.markdown-body :deep(li) { margin: 3px 0; }
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) { margin: 10px 0 6px; font-weight: 600; }
.markdown-body :deep(blockquote) {
  border-left: 3px solid #cbd5e0;
  padding-left: 10px;
  color: #718096;
  margin: 6px 0;
}
</style>
