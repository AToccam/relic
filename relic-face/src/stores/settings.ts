import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getMode, setMode as apiSetMode, testProvider, testMulti as apiTestMulti } from '@/api/mode'
import type { Mode, TestResult, MultiTestResult } from '@/types'

export const useSettingsStore = defineStore('settings', () => {
  const preferredSingleProviders = ['deepseek', 'qwen', 'kimi']

  const mode = ref<Mode>('multi')
  const providers = ref<string[]>([])
  const singleProvider = ref('deepseek')
  const singleProviderOptions = computed(() => {
    const providerSet = new Set(providers.value)
    return preferredSingleProviders.filter(p => providerSet.has(p))
  })
  const testResults = ref<Record<string, TestResult>>({})
  const multiTestResult = ref<MultiTestResult | null>(null)
  const loading = ref(false)
  const multiLoading = ref(false)

  async function fetchMode() {
    try {
      const data = await getMode()
      mode.value = data.mode
      providers.value = Array.from(data.availableProviders ?? [])
      if (data.singleProvider) {
        singleProvider.value = data.singleProvider
      }
    } catch {
      // backend not reachable on load
    }
  }

  async function switchMode(newMode: Mode) {
    const selected = singleProvider.value
    const data = await apiSetMode(newMode, newMode === 'single' ? selected : undefined)
    mode.value = data.mode
    if (data.singleProvider) {
      singleProvider.value = data.singleProvider
    }
  }

  async function switchSingleProvider(provider: string) {
    singleProvider.value = provider
    if (mode.value !== 'single') {
      return
    }
    const data = await apiSetMode('single', provider)
    if (data.singleProvider) {
      singleProvider.value = data.singleProvider
    }
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

  return {
    mode,
    providers,
    singleProvider,
    singleProviderOptions,
    testResults,
    multiTestResult,
    loading,
    multiLoading,
    fetchMode,
    switchMode,
    switchSingleProvider,
    runTest,
    runMultiTest
  }
})
