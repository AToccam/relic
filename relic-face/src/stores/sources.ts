import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { deleteSourceFile, listSourceFiles, uploadSourceFile } from '@/api/files'

export interface SourceFileItem {
  id: string
  name: string
  sizeLabel: string
  sizeBytes: number
  mimeType: string
  relativePath: string
  selected: boolean
  dataUrl?: string
  uploadError?: string
}

interface UploadResult {
  okCount: number
  errorCount: number
}

const IMAGE_MIME_PREFIX = 'image/'
const AUDIO_MIME_PREFIX = 'audio/'

export const useSourcesStore = defineStore('sources', () => {
  const files = ref<SourceFileItem[]>([])
  const uploading = ref(false)

  const usableFiles = computed(() => files.value.filter(f => !f.uploadError))
  const selectedUsableFiles = computed(() => usableFiles.value.filter(f => f.selected))
  const hasFiles = computed(() => selectedUsableFiles.value.length > 0)
  const allUsableSelected = computed(() => usableFiles.value.length > 0 && selectedUsableFiles.value.length === usableFiles.value.length)

  async function addFiles(fileList: FileList): Promise<UploadResult> {
    const incoming = Array.from(fileList)
    if (incoming.length === 0) {
      return { okCount: 0, errorCount: 0 }
    }

    uploading.value = true
    let okCount = 0
    let errorCount = 0

    try {
      for (const file of incoming) {
        try {
          const uploaded = await uploadSourceFile(file)
          const mimeType = uploaded.mimeType || file.type || 'application/octet-stream'
          const item: SourceFileItem = {
            id: `${Date.now()}-${Math.random()}`,
            name: uploaded.filename || file.name,
            sizeLabel: formatSize(uploaded.size || file.size),
            sizeBytes: uploaded.size || file.size,
            mimeType,
            relativePath: uploaded.relativePath,
            selected: true
          }

          if (isImage(mimeType) || isAudio(mimeType)) {
            item.dataUrl = await readFileAsDataUrl(file)
          }

          files.value.push(item)
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
    const items = await listSourceFiles()
    const existing = new Set(files.value.map(f => f.relativePath))

    for (const item of items) {
      if (!item.relativePath || existing.has(item.relativePath)) {
        continue
      }

      files.value.push({
        id: `${Date.now()}-${Math.random()}`,
        name: item.filename,
        sizeLabel: formatSize(item.size || 0),
        sizeBytes: item.size || 0,
        mimeType: item.mimeType || 'application/octet-stream',
        relativePath: item.relativePath,
        selected: false
      })
      existing.add(item.relativePath)
    }
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
    for (const file of files.value) {
      if (!file.uploadError) {
        file.selected = selected
      }
    }
  }

  function clearAll() {
    files.value = []
  }

  return {
    files,
    hasFiles,
    usableFiles,
    selectedUsableFiles,
    allUsableSelected,
    uploading,
    addFiles,
    removeFile,
    toggleFileSelection,
    setAllUsableSelection,
    clearAll,
    loadPersistedFiles
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

function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve((reader.result as string) || '')
    reader.onerror = () => reject(new Error('读取文件内容失败'))
    reader.readAsDataURL(file)
  })
}
