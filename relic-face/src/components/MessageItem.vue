<script setup lang="ts">
import { computed, nextTick, ref, useTemplateRef, watch, watchEffect } from 'vue'
import { unified } from 'unified'
import remarkParse from 'remark-parse'
import remarkGfm from 'remark-gfm'
import remarkRehype from 'remark-rehype'
import rehypeStringify from 'rehype-stringify'
import type { Message } from '@/types'
import ChartBlock from './ChartBlock.vue'

const markdownProcessor = unified()
  .use(remarkParse)
  .use(remarkGfm)
  .use(remarkRehype)
  .use(rehypeStringify)

const props = defineProps<{ message: Message }>()

type SegmentType = 'markdown' | 'tool' | 'status' | 'warning' | 'chart'

interface Segment {
  type: SegmentType
  text: string
  language?: string
  title?: string
}

function parseSegments(content: string): Segment[] {
  const segments: Segment[] = []
  const mdBuffer: string[] = []
  const lines = content.split('\n')
  let index = 0

  const flushMarkdown = () => {
    const text = mdBuffer.join('\n')
    if (text.trim()) segments.push({ type: 'markdown', text })
    mdBuffer.length = 0
  }

  while (index < lines.length) {
    const line = lines[index] ?? ''
    const trimmed = line.trim()
    const structuredChart = parseStructuredChartLine(trimmed)
    if (structuredChart) {
      flushMarkdown()
      segments.push(structuredChart)
      index += 1
      continue
    }

    const fence = trimmed.match(/^```([A-Za-z0-9_-]+)?\s*$/)

    if (fence) {
      const language = (fence[1] ?? '').toLowerCase()
      const codeLines: string[] = []
      index += 1

      while (index < lines.length && !lines[index]!.trim().startsWith('```')) {
        codeLines.push(lines[index]!)
        index += 1
      }

      if (index < lines.length) index += 1

      if (isRenderableChartLanguage(language)) {
        flushMarkdown()
        segments.push({ type: 'chart', language, text: codeLines.join('\n').trim() })
      } else {
        mdBuffer.push(line, ...codeLines, '```')
      }
      continue
    }

    if (isToolLine(trimmed)) {
      flushMarkdown()
      segments.push({ type: 'tool', text: trimmed })
    } else if (isStatusLine(trimmed)) {
      flushMarkdown()
      segments.push({ type: 'status', text: trimmed })
    } else if (isWarningLine(trimmed)) {
      flushMarkdown()
      segments.push({ type: 'warning', text: trimmed })
    } else {
      mdBuffer.push(line)
    }
    index += 1
  }

  flushMarkdown()
  return segments
}

function parseStructuredChartLine(text: string): Segment | null {
  const marker = 'RELIC_CHART_JSON:'
  if (!text.startsWith(marker)) return null

  try {
    const payload = JSON.parse(text.slice(marker.length)) as {
      kind?: string
      title?: string
      source?: string
    }
    if (!payload.source?.trim()) return null
    return {
      type: 'chart',
      language: payload.kind || 'mermaid',
      title: payload.title || '',
      text: payload.source.trim()
    }
  } catch {
    return null
  }
}

function isRenderableChartLanguage(language: string): boolean {
  return language === 'mermaid' || language === 'chart' || language === 'relic-chart'
}

function isToolLine(text: string): boolean {
  return text.startsWith('🔧') || text.startsWith('正在调用') || text.startsWith('Calling tool:')
}

function isStatusLine(text: string): boolean {
  return text.startsWith('✅') || text.startsWith('完成') || text.startsWith('已生成文件')
}

function isWarningLine(text: string): boolean {
  return text.startsWith('⚠️') || text.startsWith('警告')
}

function stripLinePrefix(text: string): string {
  return text.replace(/^(🔧|✅|⚠️)\s*/, '').trim()
}

const segments = computed(() => parseSegments(props.message.content))
const processSegs = computed(() =>
  segments.value.filter(segment => segment.type === 'tool' || segment.type === 'status' || segment.type === 'warning')
)
const hasProcess = computed(() => processSegs.value.length > 0)
const toolCount = computed(() => processSegs.value.filter(segment => segment.type === 'tool').length)

const processExpanded = ref(true)
watch(() => props.message.streaming, (streaming) => {
  if (!streaming) processExpanded.value = false
}, { immediate: true })

function renderMd(text: string): string {
  return String(markdownProcessor.processSync(text.replace(/\r\n?/g, '\n')))
}

const bubbleRef = useTemplateRef<HTMLElement>('bubble')

watchEffect(async () => {
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
        setTimeout(() => {
          btn.textContent = '复制'
        }, 1500)
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
        <div v-if="hasProcess" class="process-block">
          <button class="process-toggle" @click="processExpanded = !processExpanded">
            <svg
              class="toggle-arrow"
              :class="{ expanded: processExpanded }"
              width="12"
              height="12"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2.5"
            >
              <polyline points="9 18 15 12 9 6" />
            </svg>
            <span class="process-summary">
              <template v-if="message.streaming">思考中...</template>
              <template v-else-if="toolCount > 0">已调用 {{ toolCount }} 个工具</template>
              <template v-else>查看过程详情</template>
            </span>
          </button>
          <div v-if="processExpanded" class="process-body">
            <template v-for="(seg, i) in processSegs" :key="i">
              <div v-if="seg.type === 'tool'" class="seg-tool">
                <span class="seg-icon">🔧</span>
                <span class="seg-text">{{ stripLinePrefix(seg.text) }}</span>
              </div>
              <div v-else-if="seg.type === 'status'" class="seg-status">
                <span class="seg-icon">✅</span>
                <span class="seg-text">{{ stripLinePrefix(seg.text) }}</span>
              </div>
              <div v-else-if="seg.type === 'warning'" class="seg-warning">
                <span class="seg-icon">⚠️</span>
                <span class="seg-text">{{ stripLinePrefix(seg.text) }}</span>
              </div>
            </template>
          </div>
        </div>

        <template v-for="(seg, i) in segments" :key="i">
          <div v-if="seg.type === 'markdown'" class="markdown-body" v-html="renderMd(seg.text)" />
          <ChartBlock
            v-else-if="seg.type === 'chart'"
            :language="seg.language || ''"
            :title="seg.title || ''"
            :source="seg.text"
          />
        </template>

        <details v-if="message.citations && message.citations.length > 0" class="citations-block">
          <summary class="citations-summary">参考来源 ({{ message.citations.length }})</summary>
          <div class="citations-list">
            <div v-for="(c, i) in message.citations" :key="i" class="citation-item">
              <span class="citation-source">{{ c.sourceId }}</span>
              <p class="citation-snippet">{{ c.snippet }}</p>
            </div>
          </div>
        </details>
      </template>

      <template v-else>
        <template v-if="Array.isArray(message.payloadContent)">
          <div v-for="(part, i) in message.payloadContent" :key="i">
            <div v-if="part.type === 'text' && part.text" class="plain-text">{{ part.text }}</div>
            <div v-else-if="part.type === 'image_url'" class="attach-image-wrap">
              <img :src="part.image_url.url" class="attach-image" />
            </div>
            <div v-else-if="part.type === 'input_audio'" class="attach-card">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M9 18V5l12-2v13" /><circle cx="6" cy="18" r="3" /><circle cx="18" cy="16" r="3" />
              </svg>
              <span>音频附件</span>
            </div>
            <div v-else-if="part.type === 'input_file'" class="attach-card">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
              </svg>
              <span class="attach-filename">{{ part.input_file.filename }}</span>
            </div>
          </div>
        </template>
        <div v-else class="plain-text">{{ message.content }}</div>
      </template>
      <span v-if="message.streaming" class="cursor">▌</span>
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
  font-size: 11px;
  font-weight: 700;
  flex-shrink: 0;
  background: #091e2e;
  color: #22d3ee;
  border: 1.5px solid #22d3ee;
  box-shadow: 0 0 10px rgba(34, 211, 238, 0.2);
  font-family: ui-monospace, 'JetBrains Mono', 'Courier New', monospace;
  letter-spacing: 0.04em;
}

.message-item.user .avatar {
  background: #0e4d63;
  color: #7de8f8;
  border-color: #38bdf8;
  box-shadow: 0 0 10px rgba(56, 189, 248, 0.2);
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
  gap: 8px;
}

.message-item.user .bubble {
  background: #0891b2;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.message-item.assistant .bubble {
  border-bottom-left-radius: 4px;
  max-width: min(1100px, 92%);
}

.plain-text {
  white-space: pre-wrap;
}

.cursor {
  display: inline-block;
  animation: blink 0.8s step-end infinite;
  color: #22d3ee;
  margin-left: 2px;
  text-shadow: 0 0 8px rgba(34, 211, 238, 0.6);
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.process-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid rgba(8, 145, 178, 0.18);
  border-radius: 8px;
  overflow: hidden;
}

.process-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: rgba(8, 145, 178, 0.05);
  border: none;
  cursor: pointer;
  font-size: 12px;
  color: #0369a1;
  font-family: inherit;
  text-align: left;
  width: 100%;
  transition: background 0.15s;
}

.process-toggle:hover {
  background: rgba(8, 145, 178, 0.1);
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

.seg-tool,
.seg-status,
.seg-warning {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  align-self: flex-start;
}

.seg-tool {
  background: rgba(8, 145, 178, 0.08);
  border: 1px solid rgba(8, 145, 178, 0.2);
  color: #0369a1;
}

.seg-status {
  background: rgba(16, 185, 129, 0.07);
  border: 1px solid rgba(16, 185, 129, 0.2);
  color: #065f46;
}

.seg-warning {
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.25);
  color: #92400e;
}

.seg-icon {
  flex-shrink: 0;
  font-size: 13px;
}

.seg-text {
  line-height: 1.4;
}

.attach-image-wrap {
  margin-top: 6px;
}

.attach-image {
  max-width: 200px;
  max-height: 160px;
  border-radius: 6px;
  object-fit: cover;
  display: block;
  border: 1px solid rgba(255, 255, 255, 0.3);
}

.attach-card {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  padding: 5px 10px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.18);
  border: 1px solid rgba(255, 255, 255, 0.3);
  font-size: 12px;
  color: rgba(255, 255, 255, 0.9);
}

.attach-filename {
  max-width: 160px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

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

.citations-block {
  margin-top: 8px;
  border: 1px solid rgba(8, 145, 178, 0.18);
  border-radius: 8px;
  overflow: hidden;
  font-size: 12px;
}

.citations-summary {
  padding: 6px 10px;
  background: rgba(8, 145, 178, 0.05);
  color: #0369a1;
  font-weight: 500;
  cursor: pointer;
  list-style: none;
  user-select: none;
}

.citations-summary::-webkit-details-marker { display: none; }

.citations-summary::before {
  content: '▶ ';
  font-size: 10px;
}

details[open] .citations-summary::before {
  content: '▼ ';
}

.citations-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px;
}

.citation-item {
  border-left: 3px solid rgba(8, 145, 178, 0.3);
  padding-left: 8px;
}

.citation-source {
  font-size: 11px;
  font-weight: 600;
  color: #0369a1;
  display: block;
  margin-bottom: 2px;
  word-break: break-all;
}

.citation-snippet {
  font-size: 12px;
  color: #64748b;
  margin: 0;
  line-height: 1.5;
  white-space: pre-wrap;
  max-height: 80px;
  overflow-y: auto;
}

.markdown-body :deep(p) { margin: 0 0 8px; }
.markdown-body :deep(p:last-child) { margin-bottom: 0; }
.markdown-body :deep(p) { white-space: pre-wrap; }
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
  color: #0891b2;
}
.markdown-body :deep(pre code) {
  padding: 0;
  background: none;
  color: #1a202c;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) { padding-left: 20px; margin: 6px 0; }
.markdown-body :deep(li) { margin: 3px 0; }
.markdown-body :deep(h1) {
  margin: 12px 0 8px;
  font-size: 20px;
  line-height: 1.35;
  font-weight: 700;
}
.markdown-body :deep(h2) {
  margin: 10px 0 6px;
  font-size: 17px;
  line-height: 1.35;
  font-weight: 700;
}
.markdown-body :deep(h3) {
  margin: 8px 0 6px;
  font-size: 15px;
  line-height: 1.35;
  font-weight: 600;
}
.markdown-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 13px;
  background: #fff;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #cbd5e1;
  padding: 6px 8px;
  vertical-align: top;
  text-align: left;
}
.markdown-body :deep(th) {
  background: #f8fafc;
  font-weight: 600;
}
.markdown-body :deep(blockquote) {
  border-left: 3px solid #cbd5e0;
  padding-left: 10px;
  color: #718096;
  margin: 6px 0;
}
</style>
