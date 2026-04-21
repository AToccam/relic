import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { deleteGeneratedFile, listGeneratedFiles } from '@/api/files'

export interface GeneratedFileItem {
  id: string
  name: string
  sizeLabel: string
  sizeBytes: number
  mimeType: string
  relativePath: string
  updatedAt: string
}

export const useStudioStore = defineStore('studio', () => {
  const files = ref<GeneratedFileItem[]>([])
  const loading = ref(false)

  const hasFiles = computed(() => files.value.length > 0)

  async function loadPersistedFiles() {
    loading.value = true
    try {
      const items = await listGeneratedFiles()
      files.value = items.map(item => ({
        id: `${item.relativePath}-${item.updatedAt}`,
        name: item.filename,
        sizeLabel: formatSize(item.size || 0),
        sizeBytes: item.size || 0,
        mimeType: item.mimeType || 'application/octet-stream',
        relativePath: item.relativePath,
        updatedAt: item.updatedAt || ''
      }))
    } catch {
      // 后端不可达时静默忽略
    } finally {
      loading.value = false
    }
  }

  async function removeFile(id: string) {
    const item = files.value.find(f => f.id === id)
    if (!item) {
      return
    }

    await deleteGeneratedFile(item.relativePath)
    files.value = files.value.filter(f => f.id !== id)
  }

  return {
    files,
    hasFiles,
    loading,
    loadPersistedFiles,
    removeFile
  }
})

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
