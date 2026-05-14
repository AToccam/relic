import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

const STORAGE_KEY = 'relic.workspace.workingDirectory'

function loadInitial(): string {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY)
    return value ? value.trim() : ''
  } catch {
    return ''
  }
}

export const useWorkspaceStore = defineStore('workspace', () => {
  const workingDirectory = ref<string>(loadInitial())

  const hasWorkingDirectory = computed(() => workingDirectory.value.trim().length > 0)

  function setWorkingDirectory(path: string) {
    const trimmed = (path || '').trim()
    workingDirectory.value = trimmed
    try {
      if (trimmed) {
        window.localStorage.setItem(STORAGE_KEY, trimmed)
      } else {
        window.localStorage.removeItem(STORAGE_KEY)
      }
    } catch {
      // 静默忽略 storage 异常（隐私模式等）
    }
  }

  function clearWorkingDirectory() {
    setWorkingDirectory('')
  }

  return {
    workingDirectory,
    hasWorkingDirectory,
    setWorkingDirectory,
    clearWorkingDirectory
  }
})
