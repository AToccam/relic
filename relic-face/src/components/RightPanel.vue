<script setup lang="ts">
import { computed, ref } from 'vue'
import { useStudioStore } from '@/stores/studio'

const studio = useStudioStore()

const selectedFormat = ref('all')

interface FormatTab {
  key: string
  label: string
  extensions: string[]
  icon: string
}

const formatTabs: FormatTab[] = [
  { key: 'all',  label: '全部',    extensions: [],                                 icon: 'M9 12h6M12 9v6' },
  { key: 'md',   label: 'Markdown', extensions: ['.md'],                           icon: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M16 13H8M16 17H8M10 9H8' },
  { key: 'pdf',  label: 'PDF',      extensions: ['.pdf'],                          icon: 'M7 21h10a2 2 0 0 0 2-2V9.414a1 1 0 0 0-.293-.707l-5.414-5.414A1 1 0 0 0 12.586 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2z' },
  { key: 'docx', label: 'Word',     extensions: ['.docx', '.doc'],                 icon: 'M4 6h16v12H4zM8 12h8M12 8v8' },
  { key: 'pptx', label: 'PPT',      extensions: ['.pptx', '.ppt'],                 icon: 'M2 3h20v14H2zM8 21h8M12 17v4' },
  { key: 'xlsx', label: 'Excel',    extensions: ['.xlsx', '.xls', '.csv'],         icon: 'M3 3h18v18H3zM8 8h8M8 12h8M8 16h4' },
  { key: 'txt',  label: 'Text',     extensions: ['.txt'],                          icon: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M10 13H8M16 17H8' },
  { key: 'html', label: 'HTML',     extensions: ['.html', '.htm'],                 icon: 'M12 18l-4-4 4-4M16 6l4 4-4 4' },
  { key: 'json', label: 'JSON',     extensions: ['.json'],                         icon: 'M16 18l6-6-6-6M8 6l-6 6 6 6' },
  { key: 'img',  label: '图片',     extensions: ['.png', '.jpg', '.jpeg', '.gif', '.svg', '.webp', '.bmp'], icon: 'M4 16l4.586-4.586a2 2 0 0 1 2.828 0L16 16M14 14l1.586-1.586a2 2 0 0 1 2.828 0L22 16M2 4h20v16H2z' },
  { key: 'other',label: '其他',     extensions: [],                                 icon: 'M5 12h14M12 5v14' },
]

function getExt(filename: string): string {
  const dot = filename.lastIndexOf('.')
  if (dot < 0) return ''
  return filename.substring(dot).toLowerCase()
}

const formatCounts = computed(() => {
  const counts: Record<string, number> = {}
  for (const tab of formatTabs) {
    counts[tab.key] = 0
  }
  for (const file of studio.files) {
    const ext = getExt(file.name)
    let matched = false
    for (const tab of formatTabs) {
      if (tab.key === 'all' || tab.key === 'other') continue
      if (tab.extensions.includes(ext)) {
        counts[tab.key]!++
        matched = true
        break
      }
    }
    if (!matched) {
      counts['other']!++
    }
  }
  counts['all'] = studio.files.length
  return counts
})

const visibleTabs = computed(() => {
  return formatTabs.filter(tab => {
    if (tab.key === 'all') return true
    return formatCounts.value[tab.key]! > 0
  })
})

const filteredFiles = computed(() => {
  if (selectedFormat.value === 'all') return studio.files
  const tab = formatTabs.find(t => t.key === selectedFormat.value)
  if (!tab) return studio.files

  if (tab.key === 'other') {
    return studio.files.filter(file => {
      const ext = getExt(file.name)
      return !formatTabs.some(t => t.key !== 'all' && t.key !== 'other' && t.extensions.includes(ext))
    })
  }

  return studio.files.filter(file => {
    const ext = getExt(file.name)
    return tab.extensions.includes(ext)
  })
})

async function removeGeneratedFile(id: string) {
  const ok = window.confirm('是否确认从列表中移除该文件？')
  if (!ok) return

  try {
    await studio.removeFile(id)
  } catch (error) {
    const message = error instanceof Error ? error.message : '移除失败'
    window.alert(message)
  }
}
</script>

<template>
  <aside class="right-panel">
    <div class="panel-header">
      <span class="panel-title">Studio</span>
    </div>

    <div class="panel-body">
      <!-- Format filter tabs -->
      <div class="format-tabs">
        <button
          v-for="tab in visibleTabs"
          :key="tab.key"
          class="format-tab"
          :class="{ active: selectedFormat === tab.key }"
          @click="selectedFormat = tab.key"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path :d="tab.icon" />
          </svg>
          <span class="tab-label">{{ tab.label }}</span>
          <span class="tab-count">{{ formatCounts[tab.key] }}</span>
        </button>
      </div>

      <!-- Generated files section -->
      <div class="generated-header">AI 生成文件 · {{ filteredFiles.length }}</div>

      <div v-if="studio.loading" class="studio-loading">正在同步文件...</div>

      <template v-else-if="filteredFiles.length > 0">
        <div
          v-for="file in filteredFiles"
          :key="file.id"
          class="generated-item"
        >
          <div class="file-icon">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <polyline points="14 2 14 8 20 8" />
            </svg>
          </div>
          <div class="file-info">
            <span class="file-name" :title="file.name">{{ file.name }}</span>
            <span class="file-size">{{ file.sizeLabel }}</span>
            <span class="file-path">{{ file.relativePath }}</span>
          </div>
          <button class="remove-btn" @click="removeGeneratedFile(file.id)" title="删除">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>
      </template>

      <div v-else class="studio-empty">
        <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M12 20h9M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" />
        </svg>
        <p>暂未发现工作文档</p>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.right-panel {
  flex: 1;
  min-width: 0;
  background: #0d1626;
  border: 1px solid #1d3256;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-header {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  border-bottom: 1px solid #1d3256;
  flex-shrink: 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: #dae7f7;
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  scrollbar-width: thin;
  scrollbar-color: #234070 transparent;
}

/* Format tabs */
.format-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.format-tab {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  border-radius: 8px;
  border: 1px solid #1d3256;
  background: #111e33;
  color: #4a6285;
  cursor: pointer;
  font-family: inherit;
  font-size: 12px;
  transition: all 0.15s;
  white-space: nowrap;
}

.format-tab:hover {
  border-color: #22d3ee;
  color: #6688b0;
}

.format-tab.active {
  background: #22d3ee;
  border-color: #22d3ee;
  color: #111e33;
}

.format-tab.active .tab-count {
  background: rgba(255, 255, 255, 0.25);
  color: #111e33;
}

.tab-label {
  font-weight: 500;
}

.tab-count {
  font-size: 10px;
  font-weight: 600;
  background: #172540;
  color: #4a6a98;
  padding: 1px 5px;
  border-radius: 10px;
  min-width: 16px;
  text-align: center;
}

.generated-header {
  font-size: 12px;
  font-weight: 600;
  color: #6688b0;
}

.studio-loading {
  font-size: 12px;
  color: #4a6a98;
  padding: 6px 2px;
}

.generated-item {
  border: 1px solid #1d3256;
  border-radius: 8px;
  background: #111e33;
  padding: 8px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.file-icon {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #0e1729;
  color: #4a6285;
  flex-shrink: 0;
}

.file-info {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.file-name {
  font-size: 12px;
  font-weight: 600;
  color: #1f2937;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-size {
  font-size: 11px;
  color: #4a6285;
}

.file-path {
  font-size: 11px;
  color: #4a6a98;
  word-break: break-all;
}

.remove-btn {
  width: 20px;
  height: 20px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #4a6a98;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.15s;
}

.remove-btn:hover {
  background: #2d0d0d;
  color: #dc2626;
}

.studio-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
  padding: 16px 4px;
  color: #234070;
  user-select: none;
}

.studio-empty p {
  font-size: 13px;
  font-weight: 500;
  color: #3d5878;
  margin: 0;
}
</style>
