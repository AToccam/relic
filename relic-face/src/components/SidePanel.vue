<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useSourcesStore } from '@/stores/sources'
import { useChatStore } from '@/stores/chat'
import { downloadFile } from '@/api/files'
import { searchWebResources, type WebSearchResult } from '@/api/webResources'
import type { SourceFileItem } from '@/stores/sources'

const sources = useSourcesStore()
const chat = useChatStore()
const fileInput = ref<HTMLInputElement | null>(null)
const isDragging = ref(false)
const openHistoryMenuId = ref<string | null>(null)
const showHistorySearch = ref(false)
const historySearchTerm = ref('')
const historySearchInput = ref<HTMLInputElement | null>(null)
const showAddSourceModal = ref(false)
const sourceMode = ref<'local' | 'web'>('local')
const webKeyword = ref('')
const webResults = ref<WebSearchResult[]>([])
const webSearching = ref(false)
const webImporting = ref(false)
const webSearchError = ref('')
const selectedWebUrls = ref<string[]>([])
const webImportStatus = ref<Record<string, 'idle' | 'importing' | 'done' | 'error'>>({})
const webImportErrors = ref<Record<string, string>>({})

const hasHistorySearchTerm = computed(() => historySearchTerm.value.trim().length > 0)
const selectedWebResults = computed(() =>
  webResults.value.filter(item => selectedWebUrls.value.includes(item.url))
)
const webSelectedCount = computed(() => selectedWebResults.value.length)
const filteredConversations = computed(() => {
  const keyword = historySearchTerm.value.trim().toLowerCase()
  if (!keyword) {
    return chat.conversations
  }
  return chat.conversations.filter(item => resolveHistoryTitle(item).toLowerCase().includes(keyword))
})

async function addFiles(fileList: FileList) {
  await sources.addFiles(fileList)
  showAddSourceModal.value = false
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

async function indexFile(id: string) {
  await sources.indexFile(id)
}

async function removeFile(id: string) {
  const ok = window.confirm('确认删除这个来源吗？将同时删除工作区中的文件。')
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

function openAddSourceModal() {
  sourceMode.value = 'local'
  showAddSourceModal.value = true
}

function closeAddSourceModal() {
  if (webImporting.value || sources.uploading) return
  showAddSourceModal.value = false
  isDragging.value = false
}

function selectSourceMode(mode: 'local' | 'web') {
  sourceMode.value = mode
  if (mode === 'web') {
    nextTick(() => {
      document.querySelector<HTMLInputElement>('.web-search-input')?.focus()
    })
  }
}

async function runWebSearch() {
  const keyword = webKeyword.value.trim()
  if (!keyword || webSearching.value) return

  webSearching.value = true
  webSearchError.value = ''
  webImportStatus.value = {}
  webImportErrors.value = {}
  selectedWebUrls.value = []

  try {
    webResults.value = await searchWebResources(keyword)
    if (webResults.value.length === 0) {
      webSearchError.value = '未找到可添加的网页来源'
    }
  } catch (error) {
    webResults.value = []
    webSearchError.value = error instanceof Error ? error.message : '联网搜索失败'
  } finally {
    webSearching.value = false
  }
}

function isWebResultSelected(url: string): boolean {
  return selectedWebUrls.value.includes(url)
}

function toggleWebResult(url: string) {
  if (webImporting.value) return
  if (isWebResultSelected(url)) {
    selectedWebUrls.value = selectedWebUrls.value.filter(item => item !== url)
    return
  }
  selectedWebUrls.value = [...selectedWebUrls.value, url]
}

async function addSelectedWebResources() {
  const selected = selectedWebResults.value
  const keyword = webKeyword.value.trim()
  if (selected.length === 0 || !keyword || webImporting.value) return

  webImporting.value = true
  let errorCount = 0
  for (const result of selected) {
    setWebImportStatus(result.url, 'importing')
    try {
      await sources.addWebResource(result, keyword)
      setWebImportStatus(result.url, 'done')
    } catch (error) {
      errorCount += 1
      setWebImportStatus(result.url, 'error', error instanceof Error ? error.message : '添加失败')
    }
  }
  webImporting.value = false

  if (errorCount === 0) {
    showAddSourceModal.value = false
  }
}

function setWebImportStatus(url: string, status: 'idle' | 'importing' | 'done' | 'error', error = '') {
  webImportStatus.value = { ...webImportStatus.value, [url]: status }
  if (error) {
    webImportErrors.value = { ...webImportErrors.value, [url]: error }
  }
}

function webResultHost(url: string): string {
  try {
    return new URL(url).host
  } catch {
    return url
  }
}

function webResultMeta(result: WebSearchResult): string {
  const parts = [result.siteName || webResultHost(result.url), result.datePublished]
    .map(item => item?.trim())
    .filter(Boolean)
  return parts.join(' · ')
}

function webResultStatusLabel(url: string): string {
  const status = webImportStatus.value[url]
  if (status === 'importing') return '添加中'
  if (status === 'done') return '已添加'
  if (status === 'error') return webImportErrors.value[url] || '添加失败'
  return ''
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

async function toggleHistorySearch() {
  showHistorySearch.value = !showHistorySearch.value
  if (showHistorySearch.value) {
    await nextTick()
    historySearchInput.value?.focus()
    return
  }
  historySearchTerm.value = ''
}

function clearHistorySearch() {
  historySearchTerm.value = ''
  showHistorySearch.value = false
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

async function archiveHistoryItem(conversationId: string, archived: boolean) {
  try {
    const ok = await chat.archiveConversation(conversationId, archived)
    if (!ok) {
      window.alert(archived ? '归档会话失败，请稍后重试。' : '恢复会话失败，请稍后重试。')
      return
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : (archived ? '归档会话失败' : '恢复会话失败')
    window.alert(message)
    return
  }
  openHistoryMenuId.value = null
}

async function showActiveConversations() {
  await chat.setArchiveView(false)
  openHistoryMenuId.value = null
}

async function showArchivedConversations() {
  await chat.setArchiveView(true)
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

function resolveHistoryTitle(item: { title?: string; lastPreview?: string }): string {
  const title = (item.title || '').trim()
  if (title) {
    return title
  }
  const preview = (item.lastPreview || '').trim()
  return preview || '新对话'
}

function ragStatusLabel(status: string, chunkCount?: number): string {
  if (status === 'INDEXING') return '索引中…'
  if (status === 'COMPLETED') return chunkCount ? `已索引 ${chunkCount} 块` : '已索引'
  if (status === 'FAILED') return '索引失败'
  return '未索引'
}

function sourceKindLabel(file: SourceFileItem): string {
  if (file.sourceKind === 'web') return '网页'
  if (sources.isAttachmentSource(file)) return '附件'
  return '本地'
}

function sourcePath(file: SourceFileItem): string {
  return file.originUrl || file.relativePath
}

function handleOutsideClick() {
  openHistoryMenuId.value = null
}

onMounted(() => document.addEventListener('click', handleOutsideClick))
onUnmounted(() => document.removeEventListener('click', handleOutsideClick))
</script>

<template>
  <aside class="side-panel">
    <div class="panel-header">
      <span class="panel-title">来源</span>
      <button
        :class="['header-icon-btn', { active: showHistorySearch }]"
        @click="toggleHistorySearch"
        :title="showHistorySearch ? '关闭搜索' : '搜索聊天记录'"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8" /><line x1="21" y1="21" x2="16.65" y2="16.65" />
        </svg>
      </button>
    </div>

    <div class="panel-body">
      <section class="section history-section">
        <div class="source-section-title-row">
          <div class="source-section-title">{{ chat.showingArchived ? '归档会话' : '聊天记录' }}</div>
          <button v-if="!chat.showingArchived" class="section-action-btn" @click="newConversation">新对话</button>
        </div>

        <div class="history-filter-row">
          <button
            :class="['history-filter-btn', { active: !chat.showingArchived }]"
            @click="showActiveConversations"
          >
            当前
          </button>
          <button
            :class="['history-filter-btn', { active: chat.showingArchived }]"
            @click="showArchivedConversations"
          >
            归档
          </button>
        </div>

        <div v-if="showHistorySearch" class="history-search-row">
          <input
            ref="historySearchInput"
            v-model="historySearchTerm"
            class="history-search-input"
            type="text"
            placeholder="输入标题关键词"
            @keydown.esc="clearHistorySearch"
          />
          <button v-if="hasHistorySearchTerm" class="history-search-clear" @click="historySearchTerm = ''">清空</button>
        </div>

        <div v-if="chat.loadingHistory" class="uploading">正在加载聊天记录...</div>

        <template v-else-if="filteredConversations.length">
          <button
            v-for="item in filteredConversations"
            :key="item.conversationId"
            :class="['history-item', { active: item.conversationId === chat.currentConversationId }]"
            @click="openConversation(item.conversationId)"
          >
            <div class="history-row">
              <span class="history-title">{{ resolveHistoryTitle(item) }}</span>
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
              <button class="history-menu-item" @click="renameHistoryItem(item.conversationId, resolveHistoryTitle(item))">重命名</button>
              <button class="history-menu-item danger" @click="deleteHistoryItem(item.conversationId)">删除</button>
            </div>
          </button>
        </template>

        <div v-else class="empty-hint compact">
          <p>{{ hasHistorySearchTerm ? '未找到匹配对话' : '暂无聊天记录' }}</p>
          <span>{{ hasHistorySearchTerm ? '请尝试其他关键词' : '发送第一条消息后会自动保存' }}</span>
        </div>
      </section>

      <section class="section files-section">
        <div class="source-section-title-row">
          <div class="source-section-title">当前对话来源</div>
          <button class="section-action-btn" @click="openAddSourceModal">添加来源</button>
        </div>

        <div v-if="sources.uploading" class="uploading">正在添加本地来源...</div>

        <template v-if="sources.conversationFiles.length">
          <div class="source-section-title-row">
            <div class="source-section-title">已添加来源 · {{ sources.conversationFiles.length }}</div>
            <button
              v-if="sources.usableFiles.length"
              class="section-action-btn"
              @click="toggleSelectAll"
            >
              {{ sources.allUsableSelected ? '取消全选' : '全选' }}
            </button>
          </div>
          <div v-if="sources.usableFiles.length" class="selection-tip">
            已启用 {{ sources.selectedUsableFiles.length }}/{{ sources.usableFiles.length }}，启用的文档与网页会用于当前对话知识库。
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
              <svg v-if="file.sourceKind === 'web'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10" />
                <path d="M2 12h20" />
                <path d="M12 2a15.3 15.3 0 0 1 0 20" />
                <path d="M12 2a15.3 15.3 0 0 0 0 20" />
              </svg>
              <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
              </svg>
            </div>
            <div class="file-info">
              <span class="file-name" :title="file.name">{{ file.name }}</span>
              <span class="file-size">{{ sourceKindLabel(file) }} · {{ file.sizeLabel }}</span>
              <span v-if="file.uploadError" class="file-error">{{ file.uploadError }}</span>
              <span v-else class="file-path" :title="sourcePath(file)">{{ sourcePath(file) }}</span>
              <span v-if="file.sourceKind === 'web' && file.snippet" class="file-snippet">{{ file.snippet }}</span>
              <span
                v-if="!file.uploadError && file.ragStatus"
                :class="['rag-badge', `rag-${file.ragStatus.toLowerCase()}`]"
              >
                {{ ragStatusLabel(file.ragStatus, file.ragChunkCount) }}
              </span>
            </div>
            <button
              v-if="file.relativePath && !file.uploadError && !file.ragIndexing && file.ragStatus !== 'COMPLETED'"
              class="index-btn"
              @click.stop="indexFile(file.id)"
              title="建立 RAG 索引"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <polyline points="22 12 18 12 15 21 9 3 6 12 2 12" />
              </svg>
            </button>
            <button
              v-if="file.relativePath && !file.uploadError"
              class="download-btn"
              @click.stop="downloadFile(file.relativePath)"
              title="下载"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="7 10 12 15 17 10" />
                <line x1="12" y1="15" x2="12" y2="3" />
              </svg>
            </button>
            <button class="remove-btn" @click.stop="removeFile(file.id)" title="移除">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>
        </template>

        <div v-else class="empty-hint compact">
          <p>当前会话暂无来源</p>
          <span>添加本地文件或联网搜索结果来构建知识库</span>
        </div>
      </section>
    </div>
  </aside>

  <Teleport to="body">
    <div v-if="showAddSourceModal" class="source-modal-mask" @click.self="closeAddSourceModal">
      <div class="source-modal">
        <div class="modal-header">
          <div>
            <div class="modal-title">添加来源</div>
            <div class="modal-subtitle">选择本地文件或联网搜索结果，作为当前对话知识库</div>
          </div>
          <button class="modal-close-btn" @click="closeAddSourceModal" title="关闭">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>

        <div class="source-mode-tabs">
          <button
            :class="['source-mode-btn', { active: sourceMode === 'local' }]"
            @click="selectSourceMode('local')"
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="17 8 12 3 7 8" />
              <line x1="12" y1="3" x2="12" y2="15" />
            </svg>
            <span>本地文件</span>
          </button>
          <button
            :class="['source-mode-btn', { active: sourceMode === 'web' }]"
            @click="selectSourceMode('web')"
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="11" cy="11" r="8" /><line x1="21" y1="21" x2="16.65" y2="16.65" />
            </svg>
            <span>联网搜索</span>
          </button>
        </div>

        <div v-if="sourceMode === 'local'" class="modal-body">
          <div
            :class="['drop-zone', 'modal-drop-zone', { dragging: isDragging }]"
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
            <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="17 8 12 3 7 8" />
              <line x1="12" y1="3" x2="12" y2="15" />
            </svg>
            <p class="drop-title">{{ sources.uploading ? '正在添加本地来源...' : '点击或拖拽上传文件' }}</p>
            <span class="drop-hint">文档会自动建立 RAG 索引；图片和音频保留为附件</span>
          </div>
        </div>

        <div v-else class="modal-body web-modal-body">
          <div class="web-search-row">
            <input
              v-model="webKeyword"
              class="web-search-input"
              type="text"
              placeholder="搜索主题、资料或网页关键词"
              @keydown.enter.prevent="runWebSearch"
            />
            <button class="web-search-btn" :disabled="webSearching || !webKeyword.trim()" @click="runWebSearch">
              {{ webSearching ? '搜索中...' : '搜索' }}
            </button>
          </div>

          <div v-if="webSearchError" class="web-error">{{ webSearchError }}</div>

          <div v-if="webResults.length" class="web-results-toolbar">
            <span>选择要加入知识库的网页</span>
            <button
              class="web-add-btn"
              :disabled="webImporting || webSelectedCount === 0"
              @click="addSelectedWebResources"
            >
              {{ webImporting ? '添加中...' : `添加 ${webSelectedCount} 个来源` }}
            </button>
          </div>

          <div class="web-results-list">
            <button
              v-for="result in webResults"
              :key="result.url"
              :class="['web-result-item', { selected: isWebResultSelected(result.url) }]"
              @click="toggleWebResult(result.url)"
            >
              <span class="web-result-check">
                <input
                  type="checkbox"
                  :checked="isWebResultSelected(result.url)"
                  :disabled="webImporting"
                  @click.stop
                  @change="toggleWebResult(result.url)"
                />
              </span>
              <span class="web-result-main">
                <span class="web-result-title">{{ result.title }}</span>
                <span class="web-result-url">{{ webResultMeta(result) }}</span>
                <span v-if="result.snippet" class="web-result-snippet">{{ result.snippet }}</span>
                <span
                  v-if="webResultStatusLabel(result.url)"
                  :class="['web-result-status', `status-${webImportStatus[result.url]}`]"
                >
                  {{ webResultStatusLabel(result.url) }}
                </span>
              </span>
            </button>
          </div>

          <div v-if="!webSearching && !webResults.length && !webSearchError" class="empty-hint compact">
            <p>搜索网页来源</p>
            <span>勾选结果后会抓取正文、保存为来源并自动建立索引</span>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
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

.header-icon-btn.active {
  background: #e0e7ff;
  color: #0e7490;
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
  border-color: #0891b2;
  background: #f0f9ff;
}

.history-item.active {
  border-color: #0e7490;
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
  border-color: #0891b2;
  background: rgba(8, 145, 178, 0.04);
  color: #0891b2;
}

.drop-title {
  font-size: 13px;
  font-weight: 500;
  color: #4a5568;
  margin: 0;
}

.drop-zone:hover .drop-title,
.drop-zone.dragging .drop-title {
  color: #0891b2;
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

.history-search-row {
  margin: 8px 4px 6px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.history-filter-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  margin: 8px 4px 6px;
}

.history-filter-btn {
  height: 28px;
  border: 1px solid #e2e8f0;
  border-radius: 7px;
  background: #f8fafc;
  color: #64748b;
  font-size: 12px;
  cursor: pointer;
}

.history-filter-btn:hover {
  border-color: #c7d2fe;
  color: #4338ca;
}

.history-filter-btn.active {
  border-color: #6366f1;
  background: #eef2ff;
  color: #4338ca;
}

.history-search-input {
  flex: 1;
  height: 30px;
  padding: 0 10px;
  border-radius: 8px;
  border: 1px solid #cbd5e1;
  background: #ffffff;
  color: #1e293b;
  font-size: 12px;
}

.history-search-input:focus {
  outline: none;
  border-color: #0891b2;
  box-shadow: 0 0 0 3px rgba(8, 145, 178, 0.15);
}

.history-search-clear {
  border: none;
  background: transparent;
  color: #0e7490;
  font-size: 11px;
  padding: 4px;
  cursor: pointer;
}

.history-search-clear:hover {
  color: #0369a1;
}

.section-action-btn {
  border: none;
  background: transparent;
  color: #0891b2;
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
  border-color: #0891b2;
  background: rgba(8, 145, 178, 0.06);
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
  background: rgba(8, 145, 178, 0.1);
  color: #0891b2;
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

.file-snippet {
  font-size: 10px;
  color: #64748b;
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.file-error {
  font-size: 10px;
  color: #dc2626;
}

.uploading {
  font-size: 12px;
  color: #0891b2;
  text-align: center;
}

.rag-badge {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 4px;
  border: 1px solid;
  align-self: flex-start;
  white-space: nowrap;
}

.rag-badge.rag-not_indexed {
  color: #94a3b8;
  border-color: #e2e8f0;
  background: #f8fafc;
}

.rag-badge.rag-indexing {
  color: #7c3aed;
  border-color: rgba(124, 58, 237, 0.25);
  background: rgba(124, 58, 237, 0.07);
}

.rag-badge.rag-completed {
  color: #15803d;
  border-color: rgba(21, 128, 61, 0.25);
  background: rgba(21, 128, 61, 0.07);
}

.rag-badge.rag-failed {
  color: #dc2626;
  border-color: rgba(220, 38, 38, 0.25);
  background: rgba(220, 38, 38, 0.07);
}

.index-btn,
.download-btn,
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

.index-btn:hover {
  background: rgba(124, 58, 237, 0.1);
  color: #7c3aed;
}

.download-btn:hover {
  background: #dbeafe;
  color: #2563eb;
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

.source-modal-mask {
  position: fixed;
  inset: 0;
  z-index: 850;
  background: rgba(15, 23, 42, 0.28);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.source-modal {
  width: min(720px, calc(100vw - 40px));
  max-height: min(720px, calc(100vh - 40px));
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  box-shadow: 0 20px 50px rgba(15, 23, 42, 0.18);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.modal-header {
  min-height: 58px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 1px solid #e2e8f0;
}

.modal-title {
  font-size: 15px;
  font-weight: 700;
  color: #1a202c;
}

.modal-subtitle {
  margin-top: 3px;
  font-size: 12px;
  color: #64748b;
  line-height: 1.4;
}

.modal-close-btn {
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #94a3b8;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
}

.modal-close-btn:hover {
  background: #f1f5f9;
  color: #1e293b;
}

.source-mode-tabs {
  display: flex;
  gap: 8px;
  padding: 12px 16px 0;
}

.source-mode-btn {
  flex: 1;
  height: 36px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
  color: #64748b;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.source-mode-btn:hover {
  border-color: #0891b2;
  color: #0369a1;
}

.source-mode-btn.active {
  background: #0891b2;
  border-color: #0891b2;
  color: #ffffff;
}

.modal-body {
  padding: 14px 16px 16px;
  overflow-y: auto;
  min-height: 280px;
}

.modal-drop-zone {
  min-height: 220px;
}

.web-modal-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.web-search-row {
  display: flex;
  gap: 8px;
}

.web-search-input {
  flex: 1;
  min-width: 0;
  height: 36px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  background: #ffffff;
  color: #1e293b;
  font-size: 13px;
  padding: 0 11px;
  outline: none;
  font-family: inherit;
}

.web-search-input:focus {
  border-color: #0891b2;
  box-shadow: 0 0 0 3px rgba(8, 145, 178, 0.12);
}

.web-search-btn,
.web-add-btn {
  height: 36px;
  border: none;
  border-radius: 8px;
  background: #0891b2;
  color: #ffffff;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  padding: 0 14px;
  white-space: nowrap;
  cursor: pointer;
}

.web-search-btn:hover:not(:disabled),
.web-add-btn:hover:not(:disabled) {
  background: #0e7490;
}

.web-search-btn:disabled,
.web-add-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.web-error {
  border: 1px solid #fecaca;
  background: #fef2f2;
  color: #dc2626;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
}

.web-results-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
  color: #64748b;
}

.web-results-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.web-result-item {
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 9px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  padding: 10px;
  text-align: left;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}

.web-result-item:hover {
  border-color: #0891b2;
  background: #f0f9ff;
}

.web-result-item.selected {
  border-color: #0891b2;
  background: rgba(8, 145, 178, 0.06);
}

.web-result-check {
  width: 18px;
  padding-top: 1px;
  flex-shrink: 0;
}

.web-result-check input {
  width: 14px;
  height: 14px;
  cursor: pointer;
}

.web-result-main {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.web-result-title {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
  line-height: 1.35;
}

.web-result-url {
  font-size: 11px;
  color: #0891b2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.web-result-snippet {
  font-size: 12px;
  line-height: 1.45;
  color: #64748b;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.web-result-status {
  align-self: flex-start;
  margin-top: 3px;
  font-size: 10px;
  border-radius: 4px;
  padding: 2px 6px;
  border: 1px solid;
}

.web-result-status.status-importing {
  color: #7c3aed;
  border-color: rgba(124, 58, 237, 0.25);
  background: rgba(124, 58, 237, 0.07);
}

.web-result-status.status-done {
  color: #15803d;
  border-color: rgba(21, 128, 61, 0.25);
  background: rgba(21, 128, 61, 0.07);
}

.web-result-status.status-error {
  color: #dc2626;
  border-color: rgba(220, 38, 38, 0.25);
  background: rgba(220, 38, 38, 0.07);
}
</style>
