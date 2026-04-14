<script setup lang="ts">
import { computed, ref, watch, useTemplateRef, watchEffect, nextTick } from 'vue'
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

const processSegs = computed(() =>
  segments.value.filter(s => s.type === 'tool' || s.type === 'status' || s.type === 'warning')
)
const hasProcess = computed(() => processSegs.value.length > 0)
const toolCount = computed(() => processSegs.value.filter(s => s.type === 'tool').length)

// 流式时展开，完成后自动折叠
const processExpanded = ref(true)
watch(() => props.message.streaming, (streaming) => {
  if (!streaming) processExpanded.value = false
}, { immediate: true })

function renderMd(text: string): string {
  return marked.parse(text) as string
}

const bubbleRef = useTemplateRef<HTMLElement>('bubble')

watchEffect(async () => {
  // 依赖 message.content 变化（流式更新）
  // eslint-disable-next-line @typescript-eslint/no-unused-expressions
  props.message.content
  await nextTick()
  const el = bubbleRef.value
  if (!el) return
  el.querySelectorAll('pre').forEach((pre) => {
    if (pre.querySelector('.copy-btn')) return
    const btn = document.createElement('button')
    btn.className = 'copy-btn'
    btn.textContent = '复制'
    btn.addEventListener('click', () => {
      const code = pre.querySelector('code')?.innerText ?? pre.innerText
      navigator.clipboard.writeText(code).then(() => {
        btn.textContent = '已复制'
        setTimeout(() => { btn.textContent = '复制' }, 1500)
      })
    })
    pre.style.position = 'relative'
    pre.appendChild(btn)
  })
})
</script>

<template>
  <div :class="['message-item', message.role]">
    <div class="avatar">{{ message.role === 'user' ? 'U' : 'AI' }}</div>
    <div class="bubble" ref="bubble">
      <template v-if="message.role === 'assistant'">
        <!-- 工具调用过程折叠块 -->
        <div v-if="hasProcess" class="process-block">
          <button class="process-toggle" @click="processExpanded = !processExpanded">
            <svg
              class="toggle-arrow"
              :class="{ expanded: processExpanded }"
              width="12" height="12" viewBox="0 0 24 24"
              fill="none" stroke="currentColor" stroke-width="2.5"
            >
              <polyline points="9 18 15 12 9 6" />
            </svg>
            <span class="process-summary">
              <template v-if="message.streaming">思考中…</template>
              <template v-else>已调用 {{ toolCount }} 个工具</template>
            </span>
          </button>
          <div v-if="processExpanded" class="process-body">
            <template v-for="(seg, i) in processSegs" :key="i">
              <div v-if="seg.type === 'tool'" class="seg-tool">
                <span class="seg-icon">🔧</span>
                <span class="seg-text">{{ seg.text.slice(2).trim() }}</span>
              </div>
              <div v-else-if="seg.type === 'status'" class="seg-status">
                <span class="seg-icon">{{ [...seg.text][0] }}</span>
                <span class="seg-text">{{ seg.text.slice(2).trim() }}</span>
              </div>
              <div v-else-if="seg.type === 'warning'" class="seg-warning">
                <span class="seg-icon">⚠️</span>
                <span class="seg-text">{{ seg.text.slice(3).trim() }}</span>
              </div>
            </template>
          </div>
        </div>
        <!-- 正文 Markdown -->
        <template v-for="(seg, i) in segments" :key="i">
          <div v-if="seg.type === 'markdown'" class="markdown-body" v-html="renderMd(seg.text)" />
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

/* 工具调用过程折叠块 */
.process-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid rgba(99, 102, 241, 0.18);
  border-radius: 8px;
  overflow: hidden;
}

.process-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: rgba(99, 102, 241, 0.05);
  border: none;
  cursor: pointer;
  font-size: 12px;
  color: #4338ca;
  font-family: inherit;
  text-align: left;
  width: 100%;
  transition: background 0.15s;
}

.process-toggle:hover {
  background: rgba(99, 102, 241, 0.1);
}

.toggle-arrow {
  flex-shrink: 0;
  transition: transform 0.2s;
  transform: rotate(0deg);
}

.toggle-arrow.expanded {
  transform: rotate(90deg);
}

.process-summary {
  font-weight: 500;
}

.process-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px 10px 8px;
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

/* 代码块复制按钮 */
:deep(.copy-btn) {
  position: absolute;
  top: 6px;
  right: 8px;
  padding: 2px 8px;
  font-size: 11px;
  font-family: inherit;
  border-radius: 4px;
  border: 1px solid #94a3b8;
  background: #ffffff;
  color: #475569;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s;
}

:deep(pre:hover .copy-btn) {
  opacity: 1;
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
