<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import type { Message } from '@/types'

const props = defineProps<{ message: Message }>()

type SegmentType = 'markdown' | 'tool' | 'status' | 'warning'
interface Segment { type: SegmentType; text: string }

function parseSegments(content: string): Segment[] {
  const lines = content.split('\n')
  const segments: Segment[] = []
  const mdBuf: string[] = []

  const flushMd = () => {
    const text = mdBuf.join('\n')
    if (text.trim()) segments.push({ type: 'markdown', text })
    mdBuf.length = 0
  }

  for (const line of lines) {
    const t = line.trim()
    if (t.startsWith('🔧')) {
      flushMd()
      segments.push({ type: 'tool', text: t })
    } else if (t.startsWith('🤔') || t.startsWith('✅')) {
      flushMd()
      segments.push({ type: 'status', text: t })
    } else if (t.startsWith('⚠️')) {
      flushMd()
      segments.push({ type: 'warning', text: t })
    } else {
      mdBuf.push(line)
    }
  }
  flushMd()
  return segments
}

const segments = computed(() => parseSegments(props.message.content))

function renderMd(text: string): string {
  return marked.parse(text) as string
}
</script>

<template>
  <div :class="['message-item', message.role]">
    <div class="avatar">{{ message.role === 'user' ? 'U' : 'AI' }}</div>
    <div class="bubble">
      <template v-if="message.role === 'assistant'">
        <template v-for="(seg, i) in segments" :key="i">
          <div v-if="seg.type === 'markdown'" class="markdown-body" v-html="renderMd(seg.text)" />
          <div v-else-if="seg.type === 'tool'" class="seg-tool">
            <span class="seg-icon">🔧</span>
            <span class="seg-text">{{ seg.text.slice(2).trim() }}</span>
          </div>
          <div v-else-if="seg.type === 'status'" class="seg-status">
            <span class="seg-icon">{{ seg.text[0] }}</span>
            <span class="seg-text">{{ seg.text.slice(2).trim() }}</span>
          </div>
          <div v-else-if="seg.type === 'warning'" class="seg-warning">
            <span class="seg-icon">⚠️</span>
            <span class="seg-text">{{ seg.text.slice(3).trim() }}</span>
          </div>
        </template>
      </template>
      <template v-else>
        <div class="plain-text">{{ message.content }}</div>
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
  display: flex;
  flex-direction: column;
  gap: 6px;
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

/* 工具调用行 */
.seg-tool {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 6px;
  background: rgba(99, 102, 241, 0.08);
  border: 1px solid rgba(99, 102, 241, 0.2);
  font-size: 12px;
  color: #4338ca;
  align-self: flex-start;
}

/* 状态行（🤔 / ✅） */
.seg-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 6px;
  background: rgba(16, 185, 129, 0.07);
  border: 1px solid rgba(16, 185, 129, 0.2);
  font-size: 12px;
  color: #065f46;
  align-self: flex-start;
}

/* 警告行 */
.seg-warning {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 6px;
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.25);
  font-size: 12px;
  color: #92400e;
  align-self: flex-start;
}

.seg-icon {
  flex-shrink: 0;
  font-size: 13px;
}

.seg-text {
  line-height: 1.4;
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
