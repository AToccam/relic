<script setup lang="ts">
import { ref } from 'vue'
import ComingSoonModal from './ComingSoonModal.vue'
import { useSourcesStore } from '@/stores/sources'

const modal = ref<string | null>(null)
const sources = useSourcesStore()
const fileInput = ref<HTMLInputElement | null>(null)
const isDragging = ref(false)

async function addFiles(fileList: FileList) {
  await sources.addFiles(fileList)
}

async function onFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  if (target.files?.length) {
    await addFiles(target.files)
  }
  if (fileInput.value) fileInput.value.value = ''
}

async function onDrop(e: DragEvent) {
  isDragging.value = false
  if (e.dataTransfer?.files.length) {
    await addFiles(e.dataTransfer.files)
  }
}

function removeFile(id: string) {
  sources.removeFile(id)
}
</script>

<template>
  <aside class="side-panel">
    <div class="panel-header">
      <span class="panel-title">来源</span>
      <button class="header-icon-btn" @click="modal = '搜索来源'" title="搜索">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8" /><line x1="21" y1="21" x2="16.65" y2="16.65" />
        </svg>
      </button>
    </div>

    <div class="panel-body">
      <!-- 拖拽上传区 -->
      <div
        :class="['drop-zone', { dragging: isDragging }]"
        @dragover.prevent="isDragging = true"
        @dragleave="isDragging = false"
        @drop.prevent="onDrop"
        @click="fileInput?.click()"
      >
        <input
          ref="fileInput"
          type="file"
          multiple
          accept=".pdf,.doc,.docx,.txt,.md,.ppt,.pptx,.xls,.xlsx,.csv,.json,.xml,.yaml,.yml,image/*,audio/*"
          style="display:none"
          @change="onFileChange"
        />
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
          <polyline points="17 8 12 3 7 8" />
          <line x1="12" y1="3" x2="12" y2="15" />
        </svg>
        <p class="drop-title">点击或拖拽上传文件</p>
        <span class="drop-hint">支持 PDF、Word、TXT、Excel 等格式</span>
      </div>

      <div v-if="sources.uploading" class="uploading">正在上传文件...</div>

      <!-- 文件列表 -->
      <template v-if="sources.files.length">
        <div class="source-section-title">已添加来源 · {{ sources.files.length }}</div>
        <div
          v-for="file in sources.files"
          :key="file.id"
          :class="['source-item', { error: !!file.uploadError }]"
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
            <span v-if="file.uploadError" class="file-error">{{ file.uploadError }}</span>
            <span v-else class="file-path">{{ file.relativePath }}</span>
          </div>
          <button class="remove-btn" @click="removeFile(file.id)" title="移除">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>
      </template>

      <!-- 空状态 -->
      <div v-else class="empty-hint">
        <p>已保存的来源将显示在此处</p>
        <span>点击上方区域添加 PDF、文本等文件</span>
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
.side-panel {
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
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  scrollbar-width: thin;
  scrollbar-color: #cbd5e0 transparent;
}

.drop-zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 20px 12px;
  border-radius: 10px;
  border: 1.5px dashed #cbd5e0;
  background: #ffffff;
  cursor: pointer;
  transition: all 0.15s;
  text-align: center;
  color: #a0aec0;
  flex-shrink: 0;
}

.drop-zone:hover,
.drop-zone.dragging {
  border-color: #6366f1;
  background: rgba(99, 102, 241, 0.04);
  color: #6366f1;
}

.drop-title {
  font-size: 13px;
  font-weight: 500;
  color: #4a5568;
  margin: 0;
}

.drop-zone:hover .drop-title,
.drop-zone.dragging .drop-title {
  color: #6366f1;
}

.drop-hint {
  font-size: 11px;
  color: #a0aec0;
  line-height: 1.4;
}

.source-section-title {
  font-size: 11px;
  font-weight: 600;
  color: #a0aec0;
  text-transform: uppercase;
  letter-spacing: 0.8px;
  padding: 4px 4px 0;
}

.source-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  transition: box-shadow 0.15s;
}

.source-item:hover {
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.source-item.error {
  border-color: #fca5a5;
}

.file-icon {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: rgba(99, 102, 241, 0.1);
  color: #6366f1;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.file-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.file-name {
  font-size: 12px;
  font-weight: 500;
  color: #1a202c;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-size {
  font-size: 11px;
  color: #a0aec0;
}

.file-path {
  font-size: 10px;
  color: #94a3b8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-error {
  font-size: 10px;
  color: #dc2626;
}

.uploading {
  font-size: 12px;
  color: #6366f1;
  text-align: center;
}

.remove-btn {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  border: none;
  background: transparent;
  color: #cbd5e0;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.15s;
}

.remove-btn:hover {
  background: #fee2e2;
  color: #ef4444;
}

.empty-hint {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  text-align: center;
  padding: 16px 0;
  user-select: none;
}

.empty-hint p {
  font-size: 13px;
  font-weight: 500;
  color: #a0aec0;
  margin: 0;
}

.empty-hint span {
  font-size: 12px;
  color: #cbd5e0;
  line-height: 1.5;
}
</style>
