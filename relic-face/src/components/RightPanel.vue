<script setup lang="ts">
import { ref } from 'vue'
import ComingSoonModal from './ComingSoonModal.vue'
import { useStudioStore } from '@/stores/studio'

const modal = ref<string | null>(null)
const studio = useStudioStore()

async function removeGeneratedFile(id: string) {
  const ok = window.confirm('确认删除这个 AI 生成文件吗？')
  if (!ok) return

  try {
    await studio.removeFile(id)
  } catch (error) {
    const message = error instanceof Error ? error.message : '删除失败'
    window.alert(message)
  }
}

const cards = [
  { title: '音频概览', icon: 'M9 19V6l12-3v13M9 19c0 1.1-.9 2-2 2s-2-.9-2-2 .9-2 2-2 2 .9 2 2zm12-3c0 1.1-.9 2-2 2s-2-.9-2-2 .9-2 2-2 2 .9 2 2z' },
  { title: '演示文稿', icon: 'M2 3h20v14H2zM8 21h8M12 17v4' },
  { title: '思维导图', icon: 'M12 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM3 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm18 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM12 18a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM12 6v4M5 12h4m6 0h4M12 16v-4' },
  { title: '报告', icon: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M16 13H8M16 17H8M10 9H8' },
  { title: '闪卡', icon: 'M20 7H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2zM12 11v2' },
  { title: '信息图', icon: 'M18 20V10M12 20V4M6 20v-6' },
]
</script>

<template>
  <aside class="right-panel">
    <div class="panel-header">
      <span class="panel-title">Studio</span>
      <button class="header-icon-btn" @click="modal = '更多选项'" title="更多">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="5" r="1" /><circle cx="12" cy="12" r="1" /><circle cx="12" cy="19" r="1" />
        </svg>
      </button>
    </div>

    <div class="panel-body">
      <p class="cards-hint">选择输出类型，Studio 将为你生成内容</p>
      <div class="cards-grid">
        <button
          v-for="card in cards"
          :key="card.title"
          class="studio-card"
          @click="modal = card.title"
        >
          <svg class="card-icon" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
            <path :d="card.icon" />
          </svg>
          <span class="card-title">{{ card.title }}</span>
        </button>
      </div>

      <div class="generated-header">AI 生成文件 · {{ studio.files.length }}</div>

      <div v-if="studio.loading" class="studio-loading">正在同步文件...</div>

      <template v-else-if="studio.hasFiles">
        <div
          v-for="file in studio.files"
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

  <ComingSoonModal
    v-if="modal"
    :title="modal"
    @close="modal = null"
  />
</template>

<style scoped>
.right-panel {
  flex: 1;
  min-width: 0;
  background: #f8f9fa;
  border: 1px solid #e2e8f0;
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
  border-bottom: 1px solid #e2e8f0;
  flex-shrink: 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: #1a202c;
}

.header-icon-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: #a0aec0;
  cursor: pointer;
  transition: all 0.15s;
}

.header-icon-btn:hover {
  background: #e2e8f0;
  color: #4a5568;
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  scrollbar-width: thin;
  scrollbar-color: #cbd5e0 transparent;
}

.cards-hint {
  font-size: 12px;
  color: #a0aec0;
  line-height: 1.5;
  text-align: center;
}

.cards-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.studio-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 16px 8px;
  border-radius: 10px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  cursor: pointer;
  transition: all 0.15s;
  font-family: inherit;
}

.studio-card:hover {
  border-color: #6366f1;
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.12);
  background: #fafafe;
}

.generated-header {
  margin-top: 2px;
  margin-bottom: 2px;
  font-size: 12px;
  font-weight: 600;
  color: #4a5568;
}

.studio-loading {
  font-size: 12px;
  color: #94a3b8;
  padding: 6px 2px;
}

.generated-item {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
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
  background: #f8fafc;
  color: #64748b;
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
  color: #64748b;
}

.file-path {
  font-size: 11px;
  color: #94a3b8;
  word-break: break-all;
}

.remove-btn {
  width: 20px;
  height: 20px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #94a3b8;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.15s;
}

.remove-btn:hover {
  background: #fee2e2;
  color: #dc2626;
}

.card-icon {
  color: #6366f1;
}

.card-title {
  font-size: 12px;
  font-weight: 500;
  color: #4a5568;
}

.studio-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
  padding: 16px 4px;
  color: #cbd5e0;
  user-select: none;
}

.studio-empty p {
  font-size: 13px;
  font-weight: 500;
  color: #a0aec0;
  margin: 0;
}

.studio-empty span {
  font-size: 12px;
  color: #cbd5e0;
  line-height: 1.5;
}
</style>
