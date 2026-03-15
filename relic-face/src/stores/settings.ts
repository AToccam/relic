import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getMode, setMode as apiSetMode, testProvider, testMulti as apiTestMulti } from '@/api/mode'
import type { Mode, TestResult, MultiTestResult } from '@/types'

export const useSettingsStore = defineStore('settings', () => {
  const mode = ref<Mode>('multi')
  const providers = ref<string[]>([])
  const testResults = ref<Record<string, TestResult>>({})
  const multiTestResult = ref<MultiTestResult | null>(null)
  const loading = ref(false)
  const multiLoading = ref(false)

  async function fetchMode() {
    try {
      const data = await getMode()
      mode.value = data.mode
      providers.value = Array.from(data.availableProviders ?? [])
    } catch {
      // backend not reachable on load
    }
  }

  async function switchMode(newMode: Mode) {
    const data = await apiSetMode(newMode)
    mode.value = data.mode
  }

  async function runTest(provider: string) {
    loading.value = true
    try {
      const result = await testProvider(provider)
      testResults.value[provider] = result
    } finally {
      loading.value = false
    }
  }

  async function runMultiTest(prompt: string) {
    multiLoading.value = true
    multiTestResult.value = null
    try {
      multiTestResult.value = await apiTestMulti(prompt)
    } finally {
      multiLoading.value = false
    }
  }

  return { mode, providers, testResults, multiTestResult, loading, multiLoading, fetchMode, switchMode, runTest, runMultiTest }
})
