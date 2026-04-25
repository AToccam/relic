<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type mermaid from 'mermaid'

let chartInstanceSeq = 0

type ChartKind = 'bar' | 'line' | 'pie'

interface ChartDatum {
  label: string
  value: number
}

interface LineDot {
  x: number
  y: number
  label: string
  value: number
}

const props = defineProps<{
  language: string
  source: string
  title?: string
}>()

const width = 720
const height = 320
const plot = {
  left: 58,
  right: 28,
  top: 46,
  bottom: 48
}

const mermaidSvg = ref('')
const mermaidError = ref('')
const fullscreen = ref(false)
const copied = ref(false)
const renderInstanceId = chartInstanceSeq++
let mermaidApi: typeof mermaid | null = null

const normalizedLanguage = computed(() => props.language.toLowerCase())
const isMermaid = computed(() => normalizedLanguage.value === 'mermaid')
const parsedChart = computed(() => parseChartSpec(props.source))
const canRenderChart = computed(() => parsedChart.value.data.length > 0)

const plotWidth = computed(() => width - plot.left - plot.right)
const plotHeight = computed(() => height - plot.top - plot.bottom)
const maxValue = computed(() => Math.max(1, ...parsedChart.value.data.map(item => item.value)))
const barWidth = computed(() => {
  const count = Math.max(1, parsedChart.value.data.length)
  return Math.max(14, (plotWidth.value / count) * 0.58)
})

watch(
  () => [props.source, props.language],
  async () => {
    mermaidSvg.value = ''
    mermaidError.value = ''
    if (!isMermaid.value || !props.source.trim()) return

    await nextTick()
    try {
      const mermaid = await loadMermaid()
      const id = `mermaid-${renderInstanceId}-${hashText(props.source)}`
      const rendered = await mermaid.render(id, props.source)
      mermaidSvg.value = rendered.svg
    } catch (error) {
      mermaidError.value = error instanceof Error ? error.message : String(error)
    }
  },
  { immediate: true }
)

async function loadMermaid() {
  if (mermaidApi) return mermaidApi

  const module = await import('mermaid')
  const mermaid = module.default
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'strict',
    theme: 'base',
    themeVariables: {
      primaryColor: '#ffffff',
      primaryBorderColor: '#2563eb',
      primaryTextColor: '#1e293b',
      lineColor: '#64748b',
      fontFamily: 'Inter, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    }
  })
  mermaidApi = mermaid
  return mermaid
}

const bars = computed(() =>
  parsedChart.value.data.map((item, index) => {
    const step = plotWidth.value / Math.max(1, parsedChart.value.data.length)
    const valueHeight = (item.value / maxValue.value) * plotHeight.value
    return {
      ...item,
      x: plot.left + index * step + (step - barWidth.value) / 2,
      y: plot.top + plotHeight.value - valueHeight,
      width: barWidth.value,
      height: valueHeight,
      labelX: plot.left + index * step + step / 2
    }
  })
)

const linePoints = computed(() => {
  const data = parsedChart.value.data
  if (data.length === 1) {
    return `${plot.left + plotWidth.value / 2},${plot.top + plotHeight.value / 2}`
  }

  return data.map((item, index) => {
    const x = plot.left + (index / Math.max(1, data.length - 1)) * plotWidth.value
    const y = plot.top + plotHeight.value - (item.value / maxValue.value) * plotHeight.value
    return `${x},${y}`
  }).join(' ')
})

const lineDots = computed<LineDot[]>(() =>
  linePoints.value.split(' ').filter(Boolean).map((point, index) => {
    const coordinates = point.split(',').map(Number)
    const x = coordinates[0] ?? 0
    const y = coordinates[1] ?? 0
    return {
      x: Number.isFinite(x) ? x : 0,
      y: Number.isFinite(y) ? y : 0,
      label: parsedChart.value.data[index]?.label ?? '',
      value: parsedChart.value.data[index]?.value ?? 0
    }
  })
)

const pieSlices = computed(() => {
  const total = parsedChart.value.data.reduce((sum, item) => sum + Math.max(0, item.value), 0)
  if (total <= 0) return []

  let start = -90
  return parsedChart.value.data.map((item, index) => {
    const angle = (Math.max(0, item.value) / total) * 360
    const slice = describeArc(180, 166, 92, start, start + angle)
    start += angle
    return {
      path: slice,
      color: palette[index % palette.length],
      label: item.label,
      value: item.value
    }
  })
})

async function copySource() {
  try {
    await navigator.clipboard.writeText(props.source)
    copied.value = true
    window.setTimeout(() => {
      copied.value = false
    }, 1500)
  } catch {
    copied.value = false
  }
}

function parseChartSpec(source: string): { type: ChartKind; title: string; data: ChartDatum[] } {
  try {
    const raw = JSON.parse(source)
    const type = normalizeChartKind(raw.type)
    const data = normalizeChartData(raw.data ?? raw.values ?? raw.series)
    return {
      type,
      title: String(raw.title ?? ''),
      data
    }
  } catch {
    return { type: 'bar', title: '', data: [] }
  }
}

function normalizeChartKind(value: unknown): ChartKind {
  const text = String(value ?? '').toLowerCase()
  if (text === 'line' || text === 'pie') return text
  return 'bar'
}

function normalizeChartData(value: unknown): ChartDatum[] {
  if (!Array.isArray(value)) return []

  return value
    .map(item => {
      if (Array.isArray(item)) {
        return { label: String(item[0] ?? ''), value: Number(item[1]) }
      }
      if (item && typeof item === 'object') {
        const row = item as Record<string, unknown>
        return {
          label: String(row.label ?? row.name ?? ''),
          value: Number(row.value ?? row.y)
        }
      }
      return null
    })
    .filter((item): item is ChartDatum => !!item && item.label.trim().length > 0 && Number.isFinite(item.value))
}

function describeArc(cx: number, cy: number, r: number, startAngle: number, endAngle: number): string {
  const start = polarToCartesian(cx, cy, r, endAngle)
  const end = polarToCartesian(cx, cy, r, startAngle)
  const largeArc = endAngle - startAngle <= 180 ? '0' : '1'
  return `M ${cx} ${cy} L ${start.x} ${start.y} A ${r} ${r} 0 ${largeArc} 0 ${end.x} ${end.y} Z`
}

function polarToCartesian(cx: number, cy: number, r: number, angle: number): { x: number; y: number } {
  const radians = (angle * Math.PI) / 180
  return {
    x: cx + r * Math.cos(radians),
    y: cy + r * Math.sin(radians)
  }
}

function hashText(text: string): string {
  let hash = 0
  for (let i = 0; i < text.length; i += 1) {
    hash = Math.imul(31, hash) + text.charCodeAt(i) | 0
  }
  return Math.abs(hash).toString(36)
}

const palette = ['#2563eb', '#059669', '#d97706', '#dc2626', '#7c3aed', '#0891b2', '#be123c']
</script>

<template>
  <div class="chart-block">
    <div class="chart-toolbar">
      <span class="chart-heading">{{ title || parsedChart.title || '图表' }}</span>
      <div class="chart-actions">
        <button type="button" class="chart-action" @click="copySource">{{ copied ? '已复制' : '复制源码' }}</button>
        <button type="button" class="chart-action" @click="fullscreen = true">全屏查看</button>
      </div>
    </div>

    <div v-if="isMermaid && mermaidSvg" class="mermaid-render" v-html="mermaidSvg" />

    <svg
      v-else-if="canRenderChart && parsedChart.type !== 'pie'"
      class="chart-svg"
      :viewBox="`0 0 ${width} ${height}`"
      role="img"
      :aria-label="parsedChart.title || 'chart'"
    >
      <text v-if="parsedChart.title" x="24" y="26" class="chart-title">{{ parsedChart.title }}</text>
      <line :x1="plot.left" :y1="plot.top" :x2="plot.left" :y2="plot.top + plotHeight" class="axis" />
      <line :x1="plot.left" :y1="plot.top + plotHeight" :x2="plot.left + plotWidth" :y2="plot.top + plotHeight" class="axis" />

      <template v-if="parsedChart.type === 'bar'">
        <g v-for="(bar, index) in bars" :key="bar.label">
          <rect :x="bar.x" :y="bar.y" :width="bar.width" :height="bar.height" :fill="palette[index % palette.length]" rx="4" />
          <text :x="bar.labelX" :y="plot.top + plotHeight + 22" class="tick" text-anchor="middle">{{ bar.label }}</text>
          <text :x="bar.labelX" :y="bar.y - 8" class="value" text-anchor="middle">{{ bar.value }}</text>
        </g>
      </template>

      <template v-else>
        <polyline :points="linePoints" fill="none" stroke="#2563eb" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" />
        <g v-for="dot in lineDots" :key="dot.label">
          <circle :cx="dot.x" :cy="dot.y" r="5" fill="#fff" stroke="#2563eb" stroke-width="3" />
          <text :x="dot.x" :y="plot.top + plotHeight + 22" class="tick" text-anchor="middle">{{ dot.label }}</text>
          <text :x="dot.x" :y="dot.y - 12" class="value" text-anchor="middle">{{ dot.value }}</text>
        </g>
      </template>
    </svg>

    <svg
      v-else-if="canRenderChart && parsedChart.type === 'pie'"
      class="chart-svg"
      :viewBox="`0 0 ${width} ${height}`"
      role="img"
      :aria-label="parsedChart.title || 'pie chart'"
    >
      <text v-if="parsedChart.title" x="24" y="26" class="chart-title">{{ parsedChart.title }}</text>
      <path v-for="slice in pieSlices" :key="slice.label" :d="slice.path" :fill="slice.color" stroke="#fff" stroke-width="2" />
      <g v-for="(item, index) in parsedChart.data" :key="item.label" class="legend">
        <rect x="340" :y="80 + index * 28" width="14" height="14" :fill="palette[index % palette.length]" rx="3" />
        <text x="364" :y="92 + index * 28">{{ item.label }}: {{ item.value }}</text>
      </g>
    </svg>

    <div v-else-if="isMermaid && !mermaidSvg && !mermaidError" class="chart-loading">正在渲染图表...</div>

    <pre v-else class="chart-fallback"><code>{{ source }}</code></pre>

    <Teleport to="body">
      <div v-if="fullscreen" class="chart-modal" @click.self="fullscreen = false">
        <div class="chart-modal-panel">
          <div class="chart-modal-toolbar">
            <span class="chart-heading">{{ title || parsedChart.title || '图表' }}</span>
            <div class="chart-actions">
              <button type="button" class="chart-action" @click="copySource">{{ copied ? '已复制' : '复制源码' }}</button>
              <button type="button" class="chart-action" @click="fullscreen = false">关闭</button>
            </div>
          </div>
          <div class="chart-modal-canvas">
            <div v-if="isMermaid && mermaidSvg" class="mermaid-render modal-render" v-html="mermaidSvg" />
            <svg
              v-else-if="canRenderChart && parsedChart.type !== 'pie'"
              class="chart-svg modal-svg"
              :viewBox="`0 0 ${width} ${height}`"
              role="img"
              :aria-label="parsedChart.title || 'chart'"
            >
              <text v-if="parsedChart.title" x="24" y="26" class="chart-title">{{ parsedChart.title }}</text>
              <line :x1="plot.left" :y1="plot.top" :x2="plot.left" :y2="plot.top + plotHeight" class="axis" />
              <line :x1="plot.left" :y1="plot.top + plotHeight" :x2="plot.left + plotWidth" :y2="plot.top + plotHeight" class="axis" />
              <template v-if="parsedChart.type === 'bar'">
                <g v-for="(bar, index) in bars" :key="bar.label">
                  <rect :x="bar.x" :y="bar.y" :width="bar.width" :height="bar.height" :fill="palette[index % palette.length]" rx="4" />
                  <text :x="bar.labelX" :y="plot.top + plotHeight + 22" class="tick" text-anchor="middle">{{ bar.label }}</text>
                  <text :x="bar.labelX" :y="bar.y - 8" class="value" text-anchor="middle">{{ bar.value }}</text>
                </g>
              </template>
              <template v-else>
                <polyline :points="linePoints" fill="none" stroke="#2563eb" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" />
                <g v-for="dot in lineDots" :key="dot.label">
                  <circle :cx="dot.x" :cy="dot.y" r="5" fill="#fff" stroke="#2563eb" stroke-width="3" />
                  <text :x="dot.x" :y="plot.top + plotHeight + 22" class="tick" text-anchor="middle">{{ dot.label }}</text>
                  <text :x="dot.x" :y="dot.y - 12" class="value" text-anchor="middle">{{ dot.value }}</text>
                </g>
              </template>
            </svg>
            <svg
              v-else-if="canRenderChart && parsedChart.type === 'pie'"
              class="chart-svg modal-svg"
              :viewBox="`0 0 ${width} ${height}`"
              role="img"
              :aria-label="parsedChart.title || 'pie chart'"
            >
              <text v-if="parsedChart.title" x="24" y="26" class="chart-title">{{ parsedChart.title }}</text>
              <path v-for="slice in pieSlices" :key="slice.label" :d="slice.path" :fill="slice.color" stroke="#fff" stroke-width="2" />
              <g v-for="(item, index) in parsedChart.data" :key="item.label" class="legend">
                <rect x="340" :y="80 + index * 28" width="14" height="14" :fill="palette[index % palette.length]" rx="3" />
                <text x="364" :y="92 + index * 28">{{ item.label }}: {{ item.value }}</text>
              </g>
            </svg>
            <pre v-else class="chart-fallback"><code>{{ source }}</code></pre>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.chart-block {
  width: 100%;
  overflow-x: auto;
  border: 1px solid #dbe3ef;
  border-radius: 8px;
  background: #f8fafc;
  padding: 16px;
}

.chart-toolbar,
.chart-modal-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.chart-heading {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #334155;
  font-size: 13px;
  font-weight: 600;
}

.chart-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.chart-action {
  border: 1px solid #cbd5e1;
  background: #ffffff;
  color: #334155;
  border-radius: 6px;
  padding: 5px 10px;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.chart-action:hover {
  border-color: #94a3b8;
  background: #f1f5f9;
}

.mermaid-render {
  min-width: min(1100px, 92vw);
  min-height: 520px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.mermaid-render :deep(svg) {
  display: block;
  width: auto;
  min-width: 980px;
  max-width: none;
  min-height: 480px;
  height: auto;
  margin: 0 auto;
}

.chart-svg {
  display: block;
  width: 980px;
  min-width: 760px;
  height: auto;
}

.chart-title {
  font-size: 18px;
  font-weight: 700;
  fill: #0f172a;
}

.axis {
  stroke: #94a3b8;
  stroke-width: 1.5;
}

.tick,
.value,
.legend {
  font-size: 13px;
  fill: #334155;
}

.value {
  font-weight: 600;
}

.chart-loading {
  color: #64748b;
  font-size: 13px;
  padding: 10px 2px;
}

.chart-fallback {
  margin: 0;
  white-space: pre-wrap;
}

.chart-modal {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(15, 23, 42, 0.62);
  padding: 28px;
  display: flex;
  align-items: stretch;
  justify-content: center;
}

.chart-modal-panel {
  width: min(1400px, 96vw);
  height: min(920px, 92vh);
  background: #f8fafc;
  border-radius: 8px;
  box-shadow: 0 24px 80px rgba(15, 23, 42, 0.28);
  padding: 16px;
  display: flex;
  flex-direction: column;
}

.chart-modal-canvas {
  flex: 1;
  overflow: auto;
  border: 1px solid #dbe3ef;
  border-radius: 8px;
  background: #ffffff;
  padding: 18px;
}

.modal-render {
  min-width: 1280px;
  min-height: 760px;
}

.modal-render :deep(svg) {
  min-width: 1240px;
  min-height: 720px;
}

.modal-svg {
  width: 1240px;
}

@media (max-width: 720px) {
  .chart-toolbar,
  .chart-modal-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .chart-modal {
    padding: 10px;
  }
}
</style>
