<script setup lang="ts">
import { ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'

defineEmits<{ close: [] }>()

const settings = useSettingsStore()
const multiPrompt = ref('你好，请用一句话介绍你自己')

function onSingleProviderChange(event: Event) {
  const target = event.target
  if (target instanceof HTMLSelectElement) {
    settings.switchSingleProvider(target.value)
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="mask" @click.self="$emit('close')">
      <div class="panel">
        <div class="panel-header">
          <span class="panel-title">设置</span>
          <button class="close-btn" @click="$emit('close')">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>

        <div class="panel-body">
          <!-- 路由模式 -->
          <section class="section">
            <h3 class="section-title">路由模式</h3>
            <div class="mode-btns">
              <button
                :class="['mode-btn', { active: settings.mode === 'single' }]"
                @click="settings.switchMode('single')"
              >Single</button>
              <button
                :class="['mode-btn', { active: settings.mode === 'multi' }]"
                @click="settings.switchMode('multi')"
              >Multi</button>
            </div>
            <p class="mode-hint">
              {{ settings.mode === 'multi'
                ? '多AI协作竞争思考'
                : '单模型直接回答' }}
            </p>
          </section>

          <!-- Single 模型选择 -->
          <section v-if="settings.mode === 'single'" class="section">
            <h3 class="section-title">Single 模型选择</h3>
            <p class="section-desc">选择当前单模型模式使用的 AI 提供者</p>
            <select
              class="provider-select"
              :value="settings.singleProvider"
              @change="onSingleProviderChange"
              :disabled="settings.singleProviderOptions.length === 0"
            >
              <option v-if="settings.singleProviderOptions.length === 0" value="">暂无可选模型</option>
              <option
                v-for="provider in settings.singleProviderOptions"
                :key="provider"
                :value="provider"
              >
                {{ provider }}
              </option>
            </select>
          </section>

          <!-- Multi 角色配置 -->
          <section v-if="settings.mode === 'multi' && settings.providers.length > 0" class="section">
            <h3 class="section-title">Multi 模式角色分配</h3>
            <p class="section-desc">选择参与协同的 Advisor 模型和负责聚合的 Leader 模型</p>

            <div class="role-group">
              <div class="role-label">
                <span class="role-tag advisor-tag">Advisor</span>
                <span class="role-desc">参与初步回答（可多选）</span>
              </div>
              <div class="role-options">
                <label
                  v-for="p in settings.providers"
                  :key="'adv-' + p"
                  class="role-checkbox"
                >
                  <input
                    type="checkbox"
                    :value="p"
                    v-model="settings.multiAdvisors"
                  />
                  <span class="role-name">{{ p }}</span>
                </label>
              </div>
            </div>

            <div class="role-group">
              <div class="role-label">
                <span class="role-tag leader-tag">Leader</span>
                <span class="role-desc">负责综合聚合（单选）</span>
              </div>
              <div class="role-options">
                <label
                  v-for="p in settings.providers"
                  :key="'ldr-' + p"
                  class="role-radio"
                >
                  <input
                    type="radio"
                    :value="p"
                    v-model="settings.multiLeader"
                  />
                  <span class="role-name">{{ p }}</span>
                </label>
              </div>
            </div>

            <p class="role-tip">
              * 接口已预留，后端对接后生效
            </p>
          </section>

          <!-- 提供者测试 -->
          <section class="section">
            <h3 class="section-title">提供者连通性测试</h3>
            <div
              v-for="p in settings.providers"
              :key="p"
              class="provider-row"
            >
              <div class="provider-left">
                <div class="provider-icon">{{ p.charAt(0).toUpperCase() }}</div>
                <span class="provider-name">{{ p }}</span>
              </div>
              <div class="provider-right">
                <span
                  v-if="settings.testResults[p]"
                  :class="['test-badge', settings.testResults[p].status]"
                >
                  {{ settings.testResults[p].status === 'ok' ? '✓ 在线' : '✗ 异常' }}
                  · {{ settings.testResults[p].costMs }}ms
                </span>
                <button
                  class="test-btn"
                  @click="settings.runTest(p)"
                  :disabled="settings.loading"
                >测试</button>
              </div>
            </div>
            <div v-if="settings.providers.length === 0" class="no-providers">
              后端未连接，无可用提供者
            </div>
          </section>

          <!-- 多 AI 联测 -->
          <section class="section">
            <h3 class="section-title">多 AI 并行联测</h3>
            <p class="section-desc">向所有提供者并行发送同一问题，对比响应结果</p>
            <div class="multi-input-row">
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
                class="advisor-card"
              >
                <span class="advisor-name">{{ name }}</span>
                <p class="advisor-reply">{{ reply }}</p>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.25);
  display: flex;
  align-items: flex-start;
  justify-content: flex-end;
  z-index: 900;
  backdrop-filter: blur(1px);
}

.panel {
  width: 380px;
  height: 100%;
  background: #ffffff;
  border-left: 1px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  box-shadow: -4px 0 24px rgba(0, 0, 0, 0.08);
}

.panel-header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  border-bottom: 1px solid #e2e8f0;
  flex-shrink: 0;
}

.panel-title {
  font-size: 16px;
  font-weight: 600;
  color: #1a202c;
}

.close-btn {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  background: transparent;
  color: #718096;
  cursor: pointer;
  transition: all 0.15s;
}

.close-btn:hover {
  background: #f1f5f9;
  color: #1a202c;
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 24px;
  scrollbar-width: thin;
  scrollbar-color: #cbd5e0 transparent;
}

.section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.section-title {
  font-size: 12px;
  font-weight: 600;
  color: #718096;
  text-transform: uppercase;
  letter-spacing: 0.8px;
}

.section-desc {
  font-size: 12px;
  color: #a0aec0;
  line-height: 1.5;
  margin-top: -4px;
}

.mode-btns {
  display: flex;
  gap: 8px;
}

.mode-btn {
  flex: 1;
  padding: 8px 0;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid #e2e8f0;
  background: #f8f9fa;
  color: #718096;
  transition: all 0.15s;
  font-family: inherit;
}

.mode-btn.active {
  background: #6366f1;
  border-color: #6366f1;
  color: #fff;
}

.mode-btn:not(.active):hover {
  border-color: #6366f1;
  color: #6366f1;
}

.mode-hint {
  font-size: 12px;
  color: #a0aec0;
  line-height: 1.4;
}

.provider-select {
  width: 100%;
  height: 36px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  color: #2d3748;
  font-size: 13px;
  padding: 0 10px;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.provider-select:focus {
  border-color: #6366f1;
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.12);
}

.provider-select:disabled {
  background: #f8f9fa;
  color: #a0aec0;
}

.provider-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f8f9fa;
  border: 1px solid #e2e8f0;
}

.provider-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.provider-icon {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.provider-name {
  font-size: 13px;
  font-weight: 500;
  color: #1a202c;
  text-transform: capitalize;
}

.provider-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.test-badge {
  font-size: 11px;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 999px;
}

.test-badge.ok {
  background: rgba(56, 161, 105, 0.1);
  color: #38a169;
}

.test-badge.fail {
  background: rgba(229, 62, 62, 0.1);
  color: #e53e3e;
}

.test-btn {
  padding: 4px 12px;
  font-size: 12px;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  color: #4a5568;
  cursor: pointer;
  transition: all 0.15s;
  font-family: inherit;
}

.test-btn:hover:not(:disabled) {
  border-color: #6366f1;
  color: #6366f1;
}

.test-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.no-providers {
  font-size: 13px;
  color: #a0aec0;
  padding: 8px 0;
}

.multi-input-row {
  display: flex;
  gap: 8px;
}

.multi-input {
  flex: 1;
  background: #f8f9fa;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  color: #1a202c;
  font-size: 13px;
  padding: 8px 10px;
  outline: none;
  font-family: inherit;
  transition: border-color 0.15s;
}

.multi-input:focus {
  border-color: #6366f1;
  background: #ffffff;
}

.multi-btn {
  padding: 8px 14px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: none;
  background: #6366f1;
  color: #fff;
  transition: background 0.15s;
  font-family: inherit;
  white-space: nowrap;
}

.multi-btn:hover:not(:disabled) {
  background: #4f46e5;
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
  color: #a0aec0;
}

.advisor-card {
  background: #f8f9fa;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px 12px;
}

.advisor-name {
  font-size: 11px;
  font-weight: 600;
  color: #6366f1;
  text-transform: capitalize;
  display: block;
  margin-bottom: 4px;
}

.advisor-reply {
  font-size: 12px;
  color: #4a5568;
  line-height: 1.5;
  word-break: break-all;
  white-space: pre-wrap;
  max-height: 100px;
  overflow-y: auto;
  scrollbar-width: thin;
}

.role-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border-radius: 8px;
  background: #f8f9fa;
  border: 1px solid #e2e8f0;
}

.role-label {
  display: flex;
  align-items: center;
  gap: 8px;
}

.role-tag {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
  letter-spacing: 0.3px;
}

.advisor-tag {
  background: rgba(99, 102, 241, 0.1);
  color: #6366f1;
  border: 1px solid rgba(99, 102, 241, 0.25);
}

.leader-tag {
  background: rgba(245, 158, 11, 0.1);
  color: #d97706;
  border: 1px solid rgba(245, 158, 11, 0.25);
}

.role-desc {
  font-size: 12px;
  color: #a0aec0;
}

.role-options {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.role-checkbox,
.role-radio {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  cursor: pointer;
  transition: all 0.15s;
  user-select: none;
}

.role-checkbox:hover,
.role-radio:hover {
  border-color: #6366f1;
}

.role-checkbox input,
.role-radio input {
  accent-color: #6366f1;
  cursor: pointer;
}

.role-name {
  font-size: 12px;
  font-weight: 500;
  color: #4a5568;
  text-transform: capitalize;
}

.role-tip {
  font-size: 11px;
  color: #cbd5e0;
  margin-top: -2px;
}
</style>
