import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getMode, setMode as apiSetMode, testProvider, testMulti as apiTestMulti } from '@/api/mode'
import type { Mode, ModeResponse, TestResult, MultiTestResult } from '@/types'

export const useSettingsStore = defineStore('settings', () => {
  const preferredSingleProviders = ['deepseek', 'qwen', 'kimi']

  const mode = ref<Mode>('multi')
  const providers = ref<string[]>([])
  const singleProvider = ref('deepseek')
  const singleProviderOptions = computed(() => {
    const providerSet = new Set(providers.value)
    const preferred = preferredSingleProviders.filter(p => providerSet.has(p))
    const rest = providers.value.filter(p => !preferred.includes(p))
    return [...preferred, ...rest]
  })
  const testResults = ref<Record<string, TestResult>>({})
  const multiTestResult = ref<MultiTestResult | null>(null)
  const loading = ref(false)
  const multiLoading = ref(false)
  const roleSaving = ref(false)
  const roleSaveError = ref('')

  const multiAdvisors = ref<string[]>([])
  const multiLeader = ref<string>('')

  function normalizeAdvisors(input: string[]): string[] {
    const providerSet = new Set(providers.value)
    const deduped = Array.from(new Set(input.map(x => x.trim()).filter(Boolean)))
    return deduped.filter(p => providerSet.has(p))
  }

  function applyModeData(data: ModeResponse) {
    mode.value = data.mode
    providers.value = Array.from(data.availableProviders ?? [])

    if (data.singleProvider) {
      singleProvider.value = data.singleProvider
    } else if (singleProviderOptions.value.length > 0 && !singleProviderOptions.value.includes(singleProvider.value)) {
      singleProvider.value = singleProviderOptions.value[0] ?? ''
    }

    const backendAdvisors = Array.isArray(data.multiAdvisors) ? data.multiAdvisors : []
    const normalizedAdvisors = normalizeAdvisors(backendAdvisors)
    if (normalizedAdvisors.length > 0) {
      multiAdvisors.value = normalizedAdvisors
    } else if (providers.value.length > 0 && multiAdvisors.value.length === 0) {
      multiAdvisors.value = [...providers.value]
    }

    const backendLeader = data.multiLeader?.trim() ?? ''
    if (backendLeader && providers.value.includes(backendLeader)) {
      multiLeader.value = backendLeader
    } else if (!multiLeader.value || !providers.value.includes(multiLeader.value)) {
      multiLeader.value = multiAdvisors.value[0] ?? ''
    }
  }

  async function fetchMode() {
    try {
      const data = await getMode()
      applyModeData(data)
      roleSaveError.value = ''
    } catch {
      // backend not reachable on load
    }
  }

  async function switchMode(newMode: Mode) {
    const selected = singleProvider.value
    const data = await apiSetMode({
      mode: newMode,
      singleProvider: newMode === 'single' ? selected : undefined,
      multiLeader: multiLeader.value,
      multiAdvisors: multiAdvisors.value
    })
    applyModeData(data)
  }

  async function switchSingleProvider(provider: string) {
    singleProvider.value = provider
    if (mode.value !== 'single') {
      return
    }
    const data = await apiSetMode({ mode: 'single', singleProvider: provider })
    applyModeData(data)
  }

  async function persistMultiRoles() {
    roleSaving.value = true
    roleSaveError.value = ''
    try {
      const data = await apiSetMode({
        mode: mode.value,
        multiLeader: multiLeader.value,
        multiAdvisors: multiAdvisors.value
      })
      applyModeData(data)
    } catch {
      roleSaveError.value = '角色同步失败，已自动回滚到后端状态'
      await fetchMode()
    } finally {
      roleSaving.value = false
    }
  }

  async function switchMultiLeader(leader: string) {
    if (!providers.value.includes(leader)) {
      return
    }
    multiLeader.value = leader
    await persistMultiRoles()
  }

  async function switchMultiAdvisors(advisors: string[]) {
    const normalized = normalizeAdvisors(advisors)
    if (normalized.length === 0) {
      roleSaveError.value = '至少需要保留一个 Advisor'
      return
    }

    multiAdvisors.value = normalized
    if (!multiLeader.value || !providers.value.includes(multiLeader.value)) {
      multiLeader.value = normalized[0] ?? ''
    }
    await persistMultiRoles()
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
    mode, providers, singleProvider, singleProviderOptions, testResults, multiTestResult, loading, multiLoading,
    multiAdvisors, multiLeader, roleSaving, roleSaveError,
    fetchMode, switchMode, switchSingleProvider, switchMultiLeader, switchMultiAdvisors, runTest, runMultiTest
  }
})
