import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { deleteSourceFile, listSourceFiles, uploadSourceFile } from '@/api/files'
import { getRagIndexStatus, listRagIndexStatuses, triggerRagIndex, type RagIndexStatus } from '@/api/rag'
import { importWebResource, type WebSearchResult } from '@/api/webResources'

export type SourceKind = 'local' | 'web'

export interface SourceFileItem {
  id: string
  name: string
  sizeLabel: string
  sizeBytes: number
  mimeType: string
  relativePath: string
  selected: boolean
  conversationId: string
  sourceKind: SourceKind
  dataUrl?: string
  uploadError?: string
  ragStatus?: RagIndexStatus
  ragChunkCount?: number
  ragIndexing?: boolean
  originUrl?: string
  snippet?: string
  keyword?: string
}

interface UploadResult {
  okCount: number
  errorCount: number
}

const IMAGE_MIME_PREFIX = 'image/'
const AUDIO_MIME_PREFIX = 'audio/'
const MAX_AUDIO_BYTES = 7.5 * 1024 * 1024
const ALLOWED_FILE_EXTENSIONS = new Set([
  '.pdf',
  '.doc',
  '.docx',
  '.txt',
  '.md',
  '.ppt',
  '.pptx',
  '.xls',
  '.xlsx',
  '.csv',
  '.json',
  '.xml',
  '.yaml',
  '.yml'
])
const ALLOWED_AUDIO_TYPES = new Set([
  'audio/mpeg',
  'audio/mp3',
  'audio/wav',
  'audio/webm',
  'audio/ogg',
  'audio/mp4',
  'audio/x-m4a',
  'audio/m4a'
])

export const useSourcesStore = defineStore('sources', () => {
  const files = ref<SourceFileItem[]>([])
  const uploading = ref(false)
  const currentConversationId = ref('')

  const conversationFiles = computed(() =>
    files.value.filter(f => f.conversationId === currentConversationId.value)
  )
  const usableFiles = computed(() => conversationFiles.value.filter(f => !f.uploadError))
  const selectedUsableFiles = computed(() => usableFiles.value.filter(f => f.selected))
  const selectedKnowledgeSources = computed(() => selectedUsableFiles.value.filter(isKnowledgeSource))
  const selectedAttachmentFiles = computed(() => selectedUsableFiles.value.filter(isAttachmentSource))
  const hasFiles = computed(() => selectedUsableFiles.value.length > 0)
  const allUsableSelected = computed(() => usableFiles.value.length > 0 && selectedUsableFiles.value.length === usableFiles.value.length)

  function setConversation(conversationId: string) {
    currentConversationId.value = conversationId
  }

  async function addFiles(fileList: FileList): Promise<UploadResult> {
    const incoming = Array.from(fileList)
    if (incoming.length === 0) {
      return { okCount: 0, errorCount: 0 }
    }

    uploading.value = true
    let okCount = 0
    let errorCount = 0
    const convId = currentConversationId.value

    try {
      for (const file of incoming) {
        try {
          const localValidationError = validateLocalFile(file)
          if (localValidationError) {
            throw new Error(localValidationError)
          }
          const uploaded = await uploadSourceFile(file)
          const mimeType = uploaded.mimeType || file.type || 'application/octet-stream'
          const item: SourceFileItem = {
            id: `${Date.now()}-${Math.random()}`,
            name: uploaded.filename || file.name,
            sizeLabel: formatSize(uploaded.size || file.size),
            sizeBytes: uploaded.size || file.size,
            mimeType,
            relativePath: uploaded.relativePath,
            selected: true,
            conversationId: convId,
            sourceKind: 'local'
          }

          if (isImage(mimeType) || isAudio(mimeType)) {
            item.dataUrl = await readFileAsDataUrl(file)
          }

          files.value.push(item)
          if (isKnowledgeSource(item)) {
            void indexFile(item.id)
          }
          okCount += 1
        } catch (error) {
          errorCount += 1
          const msg = error instanceof Error ? error.message : '未知错误'
          files.value.push({
            id: `${Date.now()}-${Math.random()}`,
            name: file.name,
            sizeLabel: formatSize(file.size),
            sizeBytes: file.size,
            mimeType: file.type || 'application/octet-stream',
            relativePath: '',
            selected: false,
            conversationId: convId,
            sourceKind: 'local',
            uploadError: msg
          })
        }
      }
    } finally {
      uploading.value = false
    }

    return { okCount, errorCount }
  }

  async function loadPersistedFiles() {
    let items
    try {
      items = await listSourceFiles()
    } catch {
      // 后端不可达时静默忽略
      return
    }
    const existing = new Set(files.value.map(f => f.relativePath))

    for (const item of items) {
      if (!item.relativePath || existing.has(item.relativePath)) {
        continue
      }

      // 历史持久化文件无法确定归属会话，标记为空串（不显示在任何会话视图中）
      files.value.push({
        id: `${Date.now()}-${Math.random()}`,
        name: item.title || item.filename,
        sizeLabel: formatSize(item.size || 0),
        sizeBytes: item.size || 0,
        mimeType: item.mimeType || 'application/octet-stream',
        relativePath: item.relativePath,
        selected: false,
        conversationId: '',
        sourceKind: item.sourceType === 'web_search' ? 'web' : 'local',
        originUrl: item.originUrl,
        snippet: item.snippet,
        keyword: item.keyword
      })
      existing.add(item.relativePath)
    }

    // 批量回填 RAG 索引状态，让历史文件也能显示已索引徽章
    try {
      const statuses = await listRagIndexStatuses()
      const statusMap = new Map(statuses.map(s => [s.sourceId, s]))
      for (const file of files.value) {
        if (!file.relativePath) continue
        const s = statusMap.get(file.relativePath)
        if (s) {
          file.ragStatus = s.status
          file.ragChunkCount = s.chunkCount
        }
      }
    } catch {
      // 后端不可达时静默忽略
    }
  }

  async function addWebResource(result: WebSearchResult, keyword: string): Promise<SourceFileItem> {
    const imported = await importWebResource(result, keyword)
    const relativePath = imported.sourceId || imported.relativePath
    const existing = files.value.find(file => file.relativePath === relativePath)
    if (existing) {
      existing.conversationId = currentConversationId.value
      existing.selected = true
      existing.sourceKind = 'web'
      existing.originUrl = imported.originUrl || result.url
      existing.snippet = imported.snippet || result.snippet
      existing.keyword = imported.keyword || keyword
      if (existing.ragStatus !== 'COMPLETED') {
        pollIndexStatus(existing)
      }
      return existing
    }

    const item: SourceFileItem = {
      id: `${Date.now()}-${Math.random()}`,
      name: imported.title || result.title || imported.filename || result.url,
      sizeLabel: formatSize(imported.size || 0),
      sizeBytes: imported.size || 0,
      mimeType: imported.mimeType || 'text/markdown',
      relativePath,
      selected: true,
      conversationId: currentConversationId.value,
      sourceKind: 'web',
      originUrl: imported.originUrl || result.url,
      snippet: imported.snippet || result.snippet,
      keyword: imported.keyword || keyword,
      ragStatus: imported.indexTriggered ? 'INDEXING' : undefined,
      ragIndexing: !!imported.indexTriggered
    }

    files.value.push(item)
    if (imported.indexTriggered) {
      pollIndexStatus(item)
    } else {
      void indexFile(item.id)
    }
    return item
  }

  async function removeFile(id: string) {
    const item = files.value.find(f => f.id === id)
    if (!item) {
      return
    }

    if (item.relativePath) {
      await deleteSourceFile(item.relativePath)
    }

    files.value = files.value.filter(f => f.id !== id)
  }

  function toggleFileSelection(id: string) {
    const item = files.value.find(f => f.id === id)
    if (!item || item.uploadError) return
    item.selected = !item.selected
  }

  function setAllUsableSelection(selected: boolean) {
    const convId = currentConversationId.value
    for (const file of files.value) {
      if (!file.uploadError && file.conversationId === convId) {
        file.selected = selected
      }
    }
  }

  function addImportedWebResource(item: { name: string; relativePath: string; mimeType: string; size: number }) {
    files.value.push({
      id: `${Date.now()}-${Math.random()}`,
      name: item.name,
      sizeLabel: formatSize(item.size),
      sizeBytes: item.size,
      mimeType: item.mimeType,
      relativePath: item.relativePath,
      selected: true,
      conversationId: currentConversationId.value,
      sourceKind: 'web'
    })
  }

  function clearAll() {
    files.value = []
  }

  async function indexFile(id: string): Promise<void> {
    const item = files.value.find(f => f.id === id)
    if (!item || !item.relativePath || item.uploadError) return

    item.ragIndexing = true
    item.ragStatus = 'INDEXING'
    try {
      await triggerRagIndex(item.relativePath)
    } catch {
      item.ragStatus = 'FAILED'
      item.ragIndexing = false
      return
    }

    pollIndexStatus(item)
  }

  function pollIndexStatus(item: SourceFileItem) {
    if (!item.relativePath || item.uploadError) return

    item.ragIndexing = true
    if (!item.ragStatus || item.ragStatus === 'NOT_INDEXED') {
      item.ragStatus = 'INDEXING'
    }

    const poll = async () => {
      try {
        const result = await getRagIndexStatus(item.relativePath)
        item.ragStatus = result.status
        item.ragChunkCount = result.chunkCount
        if (result.status === 'INDEXING') {
          window.setTimeout(poll, 2000)
        } else {
          item.ragIndexing = false
        }
      } catch {
        item.ragStatus = 'FAILED'
        item.ragIndexing = false
      }
    }
    window.setTimeout(poll, 1000)
  }

  function migrateSelectedFilesToConversation(newConversationId: string) {
    const convId = currentConversationId.value
    for (const file of files.value) {
      if (file.conversationId === convId && file.selected && !file.uploadError) {
        file.conversationId = newConversationId
      }
    }
  }

  return {
    files,
    conversationFiles,
    hasFiles,
    usableFiles,
    selectedUsableFiles,
    selectedKnowledgeSources,
    selectedAttachmentFiles,
    allUsableSelected,
    uploading,
    currentConversationId,
    setConversation,
    addFiles,
    addWebResource,
    removeFile,
    toggleFileSelection,
    setAllUsableSelection,
    clearAll,
    loadPersistedFiles,
    migrateSelectedFilesToConversation,
    indexFile,
    isKnowledgeSource,
    isAttachmentSource,
    addImportedWebResource
  }
})

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function isImage(mimeType: string): boolean {
  return mimeType.startsWith(IMAGE_MIME_PREFIX)
}

function isAudio(mimeType: string): boolean {
  return mimeType.startsWith(AUDIO_MIME_PREFIX)
}

function validateLocalFile(file: File): string | null {
  if (!file || file.size <= 0) {
    return '上传文件为空'
  }

  const mimeType = (file.type || '').toLowerCase()
  if (isImage(mimeType)) {
    return null
  }

  if (isAudio(mimeType)) {
    if (!ALLOWED_AUDIO_TYPES.has(mimeType)) {
      return '不支持的音频格式'
    }
    if (file.size > MAX_AUDIO_BYTES) {
      return '音频文件不能超过 7.5 MB'
    }
    return null
  }

  const lowerName = file.name.toLowerCase()
  const dot = lowerName.lastIndexOf('.')
  const extension = dot >= 0 ? lowerName.slice(dot) : ''
  if (ALLOWED_FILE_EXTENSIONS.has(extension)) {
    return null
  }

  return '不支持的文件格式'
}

function isKnowledgeSource(file: SourceFileItem): boolean {
  if (!file.relativePath || file.uploadError) {
    return false
  }
  const mimeType = (file.mimeType || '').toLowerCase()
  return !isImage(mimeType) && !isAudio(mimeType)
}

function isAttachmentSource(file: SourceFileItem): boolean {
  if (!file.relativePath || file.uploadError) {
    return false
  }
  const mimeType = (file.mimeType || '').toLowerCase()
  return isImage(mimeType) || isAudio(mimeType)
}

function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve((reader.result as string) || '')
    reader.onerror = () => reject(new Error('读取文件内容失败'))
    reader.readAsDataURL(file)
  })
}
