<script setup lang="ts">
import { ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'

const settings = useSettingsStore()
const multiPrompt = ref('你好，请用一句话介绍你自己')
</script>

<template>
  <aside class="right-panel">
    <div class="panel-header">
      <span class="panel-title">多 AI 联测</span>
    </div>

    <div class="panel-body">
      <div class="input-group">
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
      </div>

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

      <div v-else-if="!settings.multiLoading" class="panel-empty">
        <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="3" />
          <path d="M12 2v2M12 20v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M2 12h2M20 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" />
        </svg>
        <p>并行发送同一问题给多个 AI</p>
        <span>对比不同模型的回答</span>
      </div>

      <div v-else class="panel-loading">
        <span>请求中…</span>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.right-panel {
  width: 260px;
  flex-shrink: 0;
  background: #141920;
  border-left: 1px solid #2d3748;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-header {
  height: 48px;
  display: flex;
  align-items: center;
  padding: 0 18px;
  border-bottom: 1px solid #2d3748;
  flex-shrink: 0;
}

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #a0aec0;
  letter-spacing: 0.3px;
}

.panel-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: 14px 16px;
  gap: 12px;
  scrollbar-width: thin;
  scrollbar-color: #2d3748 transparent;
}

.input-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.multi-input {
  width: 100%;
  background: #2d3748;
  border: 1px solid #4a5568;
  border-radius: 6px;
  color: #e2e8f0;
  font-size: 12px;
  padding: 7px 10px;
  outline: none;
  font-family: inherit;
  transition: border-color 0.2s;
}

.multi-input:focus {
  border-color: #3b82f6;
}

.multi-btn {
  width: 100%;
  padding: 7px 0;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid #4a5568;
  background: transparent;
  color: #a0aec0;
  transition: all 0.2s;
  font-family: inherit;
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
  border-radius: 6px;
  padding: 8px 10px;
  border: 1px solid #2d3748;
}

.advisor-name {
  font-size: 11px;
  font-weight: 600;
  color: #90cdf4;
  text-transform: capitalize;
  display: block;
  margin-bottom: 4px;
}

.advisor-reply {
  font-size: 11px;
  color: #a0aec0;
  line-height: 1.5;
  word-break: break-all;
  white-space: pre-wrap;
  max-height: 100px;
  overflow-y: auto;
  scrollbar-width: thin;
}

.panel-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #4a5568;
  text-align: center;
  user-select: none;
}

.panel-empty svg {
  opacity: 0.4;
}

.panel-empty p {
  font-size: 13px;
  color: #718096;
  margin: 0;
}

.panel-empty span {
  font-size: 12px;
  line-height: 1.5;
}

.panel-loading {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: #718096;
}
</style>
