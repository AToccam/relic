<script setup lang="ts">
import { ref } from 'vue'
import ComingSoonModal from './ComingSoonModal.vue'
import { useSourcesStore } from '@/stores/sources'
import { useChatStore } from '@/stores/chat'

const modal = ref<string | null>(null)
const sources = useSourcesStore()
const chat = useChatStore()
const fileInput = ref<HTMLInputElement | null>(null)
const isDragging = ref(false)
const openHistoryMenuId = ref<string | null>(null)

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

async function removeFile(id: string) {
  const ok = window.confirm('确认删除这个文件吗？将同时删除上传目录中的文件。')
  if (!ok) return

  try {
    await sources.removeFile(id)
  } catch (error) {
    const message = error instanceof Error ? error.message : '删除失败'
    window.alert(message)
  }
}

function toggleSelection(id: string) {
  sources.toggleFileSelection(id)
}

function toggleSelectAll() {
  sources.setAllUsableSelection(!sources.allUsableSelected)
}

async function openConversation(conversationId: string) {
  await chat.selectConversation(conversationId)
  openHistoryMenuId.value = null
}

function newConversation() {
  chat.newConversation()
}

function toggleHistoryMenu(conversationId: string) {
  openHistoryMenuId.value = openHistoryMenuId.value === conversationId ? null : conversationId
}

async function renameHistoryItem(conversationId: string, currentName: string) {
  const name = window.prompt('请输入新的会话名称', currentName || '')
  if (name === null) return
  const trimmed = name.trim()
  if (!trimmed) return
  await chat.renameConversation(conversationId, trimmed)
  openHistoryMenuId.value = null
}

async function deleteHistoryItem(conversationId: string) {
  const ok = window.confirm('确认删除这个会话记录吗？删除后不可恢复。')
  if (!ok) return
  try {
    const deleted = await chat.deleteConversation(conversationId)
    if (!deleted) {
      window.alert('删除会话失败，请稍后重试。')
      return
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : '删除会话失败'
    window.alert(message)
    return
  }
  openHistoryMenuId.value = null
}

function formatTime(value: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const mm = `${date.getMonth() + 1}`.padStart(2, '0')
  const dd = `${date.getDate()}`.padStart(2, '0')
  const hh = `${date.getHours()}`.padStart(2, '0')
  const min = `${date.getMinutes()}`.padStart(2, '0')
  return `${mm}-${dd} ${hh}:${min}`
}

function isPendingConversation(conversationId: string): boolean {
  return chat.pendingConversationIds.includes(conversationId)
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
      <section class="section history-section">
        <div class="source-section-title-row">
          <div class="source-section-title">聊天记录</div>
          <button class="section-action-btn" @click="newConversation">新对话</button>
        </div>

        <div v-if="chat.loadingHistory" class="uploading">正在加载聊天记录...</div>

        <template v-else-if="chat.conversations.length">
          <button
            v-for="item in chat.conversations"
            :key="item.conversationId"
            :class="['history-item', { active: item.conversationId === chat.currentConversationId }]"
            @click="openConversation(item.conversationId)"
          >
            <div class="history-row">
              <span class="history-title">{{ item.title || item.lastPreview || '新对话' }}</span>
              <button class="history-menu-btn" @click.stop="toggleHistoryMenu(item.conversationId)">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                  <circle cx="12" cy="5" r="1.5" />
                  <circle cx="12" cy="12" r="1.5" />
                  <circle cx="12" cy="19" r="1.5" />
                </svg>
              </button>
            </div>
            <span class="history-meta">
              {{ item.messageCount }} 条 · {{ formatTime(item.updatedAt) || '刚刚' }}
              <span v-if="isPendingConversation(item.conversationId)" class="history-pending">处理中</span>
            </span>
            <div v-if="openHistoryMenuId === item.conversationId" class="history-menu" @click.stop>
              <button class="history-menu-item" @click="renameHistoryItem(item.conversationId, item.title || item.lastPreview || '')">重命名</button>
              <button class="history-menu-item danger" @click="deleteHistoryItem(item.conversationId)">删除</button>
            </div>
          </button>
        </template>

        <div v-else class="empty-hint compact">
          <p>暂无聊天记录</p>
          <span>发送第一条消息后会自动保存</span>
        </div>
      </section>

      <section class="section files-section">
        <div class="source-section-title">上传文件</div>

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

        <template v-if="sources.conversationFiles.length">
          <div class="source-section-title-row">
            <div class="source-section-title">已上传文件 · {{ sources.conversationFiles.length }}</div>
            <button
              v-if="sources.usableFiles.length"
              class="section-action-btn"
              @click="toggleSelectAll"
            >
              {{ sources.allUsableSelected ? '取消全选' : '全选' }}
            </button>
          </div>
          <div v-if="sources.usableFiles.length" class="selection-tip">
            已勾选 {{ sources.selectedUsableFiles.length }}/{{ sources.usableFiles.length }}，仅勾选文件会发送给 AI。
          </div>
          <div
            v-for="file in sources.conversationFiles"
            :key="file.id"
            :class="['source-item', { error: !!file.uploadError, selected: file.selected && !file.uploadError }]"
            @click="toggleSelection(file.id)"
          >
            <label v-if="!file.uploadError" class="select-box" @click.stop>
              <input
                type="checkbox"
                :checked="file.selected"
                @change="toggleSelection(file.id)"
              />
            </label>
            <div v-else class="select-box placeholder"></div>
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
            <button class="remove-btn" @click.stop="removeFile(file.id)" title="移除">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>
        </template>

        <div v-else class="empty-hint compact">
          <p>当前会话暂无上传文件</p>
          <span>上传的文件仅在本会话中显示</span>
        </div>
      </section>
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
  overflow: hidden;
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  scrollbar-width: thin;
  scrollbar-color: #cbd5e0 transparent;
}

.section {
  min-height: 0;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #ffffff;
  display: flex;
  flex-direction: column;
  padding: 8px;
}

.history-section {
  flex: 1;
  overflow-y: auto;
}

.files-section {
  flex: 1;
  overflow-y: auto;
}

.history-item {
  position: relative;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
  cursor: pointer;
}

.history-row {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.history-menu-btn {
  width: 20px;
  height: 20px;
  border: none;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #64748b;
  background: transparent;
  cursor: pointer;
}

.history-menu-btn:hover {
  background: #dbeafe;
  color: #1d4ed8;
}

.history-menu {
  position: absolute;
  right: 8px;
  top: 30px;
  min-width: 96px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.12);
  z-index: 10;
  padding: 4px;
}

.history-menu-item {
  width: 100%;
  border: none;
  background: transparent;
  text-align: left;
  border-radius: 6px;
  padding: 6px 8px;
  font-size: 12px;
  color: #0f172a;
  cursor: pointer;
}

.history-menu-item:hover {
  background: #f1f5f9;
}

.history-menu-item.danger {
  color: #dc2626;
}

.history-menu-item.danger:hover {
  background: #fee2e2;
}

.history-item + .history-item {
  margin-top: 8px;
}

.history-item:hover {
  border-color: #6366f1;
  background: #eef2ff;
}

.history-item.active {
  border-color: #4f46e5;
  background: #e0e7ff;
}

.history-title {
  font-size: 12px;
  color: #1e293b;
  width: 100%;
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-meta {
  font-size: 10px;
  color: #64748b;
}

.history-pending {
  margin-left: 8px;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 10px;
  color: #7c3aed;
  border: 1px solid rgba(124, 58, 237, 0.28);
  background: rgba(124, 58, 237, 0.08);
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

.source-section-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-action-btn {
  border: none;
  background: transparent;
  color: #6366f1;
  font-size: 11px;
  padding: 4px;
  cursor: pointer;
}

.selection-tip {
  font-size: 11px;
  color: #64748b;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 6px 8px;
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
  cursor: pointer;
}

.source-item:hover {
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.source-item.selected {
  border-color: #6366f1;
  background: rgba(99, 102, 241, 0.06);
}

.source-item.error {
  border-color: #fca5a5;
  cursor: default;
}

.select-box {
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.select-box input {
  width: 14px;
  height: 14px;
  cursor: pointer;
}

.select-box.placeholder {
  opacity: 0.3;
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

.empty-hint.compact {
  min-height: 80px;
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
