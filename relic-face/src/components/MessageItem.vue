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
      <div v-else class="plain-text">{{ message.content }}</div>
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
  background: #4a5568;
  color: #e2e8f0;
}

.message-item.user .avatar {
  background: #3b82f6;
}

.bubble {
  max-width: 72%;
  padding: 10px 14px;
  border-radius: 12px;
  line-height: 1.6;
  font-size: 14px;
  background: #2d3748;
  color: #e2e8f0;
  word-break: break-word;
}

.message-item.user .bubble {
  background: #1d4ed8;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.message-item.assistant .bubble {
  border-bottom-left-radius: 4px;
}

.plain-text {
  white-space: pre-wrap;
}

.cursor {
  display: inline-block;
  animation: blink 0.8s step-end infinite;
  color: #90cdf4;
  margin-left: 2px;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.markdown-body :deep(p) { margin: 0 0 8px; }
.markdown-body :deep(p:last-child) { margin-bottom: 0; }
.markdown-body :deep(pre) {
  background: #1a202c;
  border-radius: 6px;
  padding: 10px 12px;
  overflow-x: auto;
  margin: 8px 0;
  font-size: 13px;
}
.markdown-body :deep(code) {
  background: #1a202c;
  border-radius: 4px;
  padding: 1px 4px;
  font-size: 13px;
  font-family: 'Consolas', 'Monaco', monospace;
}
.markdown-body :deep(pre code) {
  padding: 0;
  background: none;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) { padding-left: 20px; margin: 6px 0; }
.markdown-body :deep(li) { margin: 3px 0; }
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) { margin: 10px 0 6px; font-weight: 600; }
.markdown-body :deep(blockquote) {
  border-left: 3px solid #4a5568;
  padding-left: 10px;
  color: #a0aec0;
  margin: 6px 0;
}
</style>
