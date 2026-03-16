<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useSettingsStore } from '@/stores/settings'

const settings = useSettingsStore()

const multiPrompt = ref('你好，请用一句话介绍你自己')

onMounted(() => settings.fetchMode())
</script>

<template>
  <aside class="side-panel">
    <section class="panel-section">
      <h3 class="section-title">路由模式</h3>
      <div class="mode-btns">
        <button
          :class="['mode-btn', { active: settings.mode === 'single' }]"
          @click="settings.switchMode('single')"
        >
          Single
        </button>
        <button
          :class="['mode-btn', { active: settings.mode === 'multi' }]"
          @click="settings.switchMode('multi')"
        >
          Multi
        </button>
      </div>
      <p class="mode-hint">
        {{ settings.mode === 'multi'
          ? 'Kimi + Qwen 协同 → DeepSeek 聚合'
          : 'DeepSeek 直接回答' }}
      </p>
    </section>

    <section class="panel-section">
      <h3 class="section-title">提供者</h3>
      <div
        v-for="p in settings.providers"
        :key="p"
        class="provider-row"
      >
        <span class="provider-name">{{ p }}</span>
        <div class="provider-right">
          <span
            v-if="settings.testResults[p]"
            :class="['test-status', settings.testResults[p].status]"
          >
            {{ settings.testResults[p].status === 'ok' ? '✓' : '✗' }}
            {{ settings.testResults[p].costMs }}ms
          </span>
          <button
            class="test-btn"
            @click="settings.runTest(p)"
            :disabled="settings.loading"
          >
            测试
          </button>
        </div>
      </div>
      <div v-if="settings.providers.length === 0" class="no-providers">
        后端未连接
      </div>
    </section>

    <section class="panel-section multi-test-section">
      <h3 class="section-title">多 AI 联测</h3>
      <input
        v-model="multiPrompt"
        class="multi-input"
        placeholder="输入测试问题"
      />
      <button
        class="multi-btn"
        @click="settings.runMultiTest(multiPrompt)"
        :disabled="settings.multiLoading || !multiPrompt.trim()"
      >
        {{ settings.multiLoading ? '请求中…' : '并行测试' }}
      </button>
      <div v-if="settings.multiTestResult" class="multi-results">
        <div class="multi-cost">总耗时 {{ settings.multiTestResult.costMs }}ms</div>
        <div
          v-for="(reply, name) in settings.multiTestResult.advisors"
          :key="name"
          class="multi-advisor"
        >
          <span class="advisor-name">{{ name }}</span>
          <p class="advisor-reply">{{ reply }}</p>
        </div>
      </div>
    </section>

    <div class="panel-bottom">
    </div>
  </aside>
</template>

<style scoped>
.side-panel {
  width: 240px;
  flex-shrink: 0;
  background: #141920;
  border-right: 1px solid #2d3748;
  display: flex;
  flex-direction: column;
  padding: 16px 0;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: #2d3748 transparent;
}

.panel-section {
  padding: 12px 18px;
  border-bottom: 1px solid #2d3748;
}

.section-title {
  font-size: 11px;
  font-weight: 600;
  color: #718096;
  text-transform: uppercase;
  letter-spacing: 0.8px;
  margin: 0 0 10px;
}

.mode-btns {
  display: flex;
  gap: 6px;
}

.mode-btn {
  flex: 1;
  padding: 6px 0;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid #4a5568;
  background: transparent;
  color: #718096;
  transition: all 0.2s;
}

.mode-btn.active {
  background: #3b82f6;
  border-color: #3b82f6;
  color: #fff;
}

.mode-btn:not(.active):hover {
  border-color: #718096;
  color: #a0aec0;
}

.mode-hint {
  font-size: 11px;
  color: #4a5568;
  margin: 8px 0 0;
  line-height: 1.4;
}

.provider-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
}

.provider-name {
  font-size: 13px;
  color: #a0aec0;
  text-transform: capitalize;
}

.provider-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.test-status {
  font-size: 11px;
}

.test-status.ok { color: #48bb78; }
.test-status.fail { color: #fc8181; }

.test-btn {
  padding: 3px 8px;
  font-size: 11px;
  border-radius: 4px;
  border: 1px solid #4a5568;
  background: transparent;
  color: #718096;
  cursor: pointer;
  transition: all 0.2s;
}

.test-btn:hover:not(:disabled) {
  border-color: #3b82f6;
  color: #90cdf4;
}

.test-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.no-providers {
  font-size: 12px;
  color: #4a5568;
  padding: 4px 0;
}

.panel-bottom {
  margin-top: auto;
  padding: 12px 18px 0;
}

.multi-test-section {
  flex-shrink: 0;
}

.multi-input {
  width: 100%;
  background: #2d3748;
  border: 1px solid #4a5568;
  border-radius: 5px;
  color: #e2e8f0;
  font-size: 12px;
  padding: 5px 8px;
  outline: none;
  margin-bottom: 7px;
  font-family: inherit;
}

.multi-input:focus {
  border-color: #3b82f6;
}

.multi-btn {
  width: 100%;
  padding: 6px 0;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid #4a5568;
  background: transparent;
  color: #a0aec0;
  transition: all 0.2s;
}

.multi-btn:hover:not(:disabled) {
  border-color: #3b82f6;
  color: #90cdf4;
}

.multi-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.multi-results {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.multi-cost {
  font-size: 11px;
  color: #718096;
}

.multi-advisor {
  background: #1a202c;
  border-radius: 5px;
  padding: 7px 9px;
}

.advisor-name {
  font-size: 11px;
  font-weight: 600;
  color: #90cdf4;
  text-transform: capitalize;
  display: block;
  margin-bottom: 3px;
}

.advisor-reply {
  font-size: 11px;
  color: #a0aec0;
  line-height: 1.4;
  word-break: break-all;
  white-space: pre-wrap;
  max-height: 80px;
  overflow-y: auto;
  scrollbar-width: thin;
}
</style>
