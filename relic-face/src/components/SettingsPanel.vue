<script setup lang="ts">
import { computed, ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import type { SkillInfo } from '@/types'

defineEmits<{ close: [] }>()

const settings = useSettingsStore()
const multiPrompt = ref('你好，请用一句话介绍你自己')
const skillImportSource = ref('')
const showBundledSkills = ref(false)
const visibleSkills = computed(() => {
  if (showBundledSkills.value) {
    return settings.skills
  }
  return settings.skills.filter(skill => !skill.bundled)
})

function onSingleProviderChange(event: Event) {
  const target = event.target
  if (target instanceof HTMLSelectElement) {
    settings.switchSingleProvider(target.value)
  }
}

function onAdvisorToggle(provider: string, event: Event) {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) {
    return
  }

  const selected = new Set(settings.multiAdvisors)
  if (target.checked) {
    selected.add(provider)
  } else {
    selected.delete(provider)
  }
  settings.switchMultiAdvisors(Array.from(selected))
}

function onLeaderChange(provider: string) {
  settings.switchMultiLeader(provider)
}

function onSkillToggle(skillName: string, event: Event) {
  const target = event.target
  if (!(target instanceof HTMLInputElement)) {
    return
  }
  settings.toggleSkill(skillName, target.checked)
}

async function onImportSkill() {
  const source = skillImportSource.value.trim()
  if (!source) {
    return
  }
  await settings.importSkill(source)
  if (!settings.skillImportError) {
    skillImportSource.value = ''
  }
}

function skillMissingSummary(skill: SkillInfo): string {
  const missing: string[] = []
  if (skill.missing?.bins?.length) {
    missing.push(`缺少命令: ${skill.missing.bins.slice(0, 3).join(', ')}`)
  }
  if (skill.missing?.env?.length) {
    missing.push(`缺少环境变量: ${skill.missing.env.slice(0, 3).join(', ')}`)
  }
  if (skill.missing?.config?.length) {
    missing.push(`缺少配置: ${skill.missing.config.slice(0, 2).join(', ')}`)
  }
  if (skill.missing?.os?.length) {
    missing.push(`系统限制: ${skill.missing.os.join(', ')}`)
  }
  if (skill.blockedByAllowlist) {
    missing.push('被 allowlist 限制')
  }
  return missing.length ? missing.join(' | ') : '当前环境未满足要求'
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

            <div class="current-leader-row">
              <span class="current-leader-label">当前后端 Leader</span>
              <span class="current-leader-value">{{ settings.multiLeader || '—' }}</span>
            </div>

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
                    :checked="settings.multiAdvisors.includes(p)"
                    :disabled="settings.roleSaving"
                    @change="onAdvisorToggle(p, $event)"
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
                    name="multi-leader"
                    :checked="settings.multiLeader === p"
                    :disabled="settings.roleSaving"
                    @change="onLeaderChange(p)"
                  />
                  <span class="role-name">{{ p }}</span>
                </label>
              </div>
            </div>

            <p
              v-if="settings.roleSaving || settings.roleSaveError"
              class="role-tip"
              :class="{ error: !!settings.roleSaveError }"
            >
              {{ settings.roleSaving
                ? '正在同步到后端...'
                : settings.roleSaveError }}
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

          <!-- Skills 管理 -->
          <section class="section">
            <h3 class="section-title">Skills</h3>
            <p class="section-desc">勾选控制启用状态；支持粘贴 ClawHub/GitHub 链接或 ClawHub slug 导入。内置 Skills 默认通常是启用状态。</p>

            <label class="bundled-switch">
              <input v-model="showBundledSkills" type="checkbox" />
              <span>显示内置 Skills（数量较多）</span>
            </label>

            <div class="skill-import-row">
              <input
                v-model="skillImportSource"
                class="skill-import-input"
                placeholder="例如: https://clawhub.ai/skills/xxx 或 https://github.com/owner/repo"
              />
              <button
                class="skill-import-btn"
                @click="onImportSkill"
                :disabled="settings.skillImporting || !skillImportSource.trim()"
              >
                {{ settings.skillImporting ? '导入中…' : '导入' }}
              </button>
            </div>

            <p v-if="settings.skillImportMessage" class="skill-tip ok">{{ settings.skillImportMessage }}</p>
            <p v-if="settings.skillImportError || settings.skillsError" class="skill-tip error">
              {{ settings.skillImportError || settings.skillsError }}
            </p>
            <p v-if="settings.skillsWorkspaceDir" class="skill-tip">
              安装目录: {{ settings.skillsWorkspaceDir }}/skills
            </p>

            <div v-if="settings.skillsLoading" class="skill-empty">正在加载 Skills...</div>
            <div v-else-if="settings.skills.length === 0" class="skill-empty">暂无可用 Skills</div>
            <div v-else-if="visibleSkills.length === 0" class="skill-empty">当前仅显示导入/安装的 Skills；可开启“显示内置 Skills”查看全部。</div>

            <div v-else class="skills-list">
              <label
                v-for="skill in visibleSkills"
                :key="skill.name"
                class="skill-row"
              >
                <div class="skill-row-left">
                  <input
                    type="checkbox"
                    :checked="!skill.disabled"
                    :disabled="settings.skillBusyKey === skill.name || settings.skillImporting"
                    @change="onSkillToggle(skill.name, $event)"
                  />
                  <div class="skill-meta">
                    <div class="skill-name-line">
                      <span class="skill-name">{{ skill.emoji ? `${skill.emoji} ${skill.name}` : skill.name }}</span>
                      <span class="skill-source">{{ skill.source }}</span>
                    </div>
                    <p class="skill-desc">{{ skill.description }}</p>
                    <p v-if="!skill.eligible" class="skill-missing">{{ skillMissingSummary(skill) }}</p>
                  </div>
                </div>
                <span class="skill-state" :class="{ eligible: skill.eligible, blocked: skill.blockedByAllowlist }">
                  {{ skill.eligible ? '可用' : '待满足条件' }}
                </span>
              </label>
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

.role-tip.error {
  color: #e53e3e;
}

.current-leader-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(245, 158, 11, 0.06);
  border: 1px solid rgba(245, 158, 11, 0.2);
}

.current-leader-label {
  font-size: 12px;
  color: #92400e;
  font-weight: 500;
  flex-shrink: 0;
}

.current-leader-value {
  font-size: 12px;
  font-weight: 700;
  color: #d97706;
  text-transform: capitalize;
}

.skill-import-row {
  display: flex;
  gap: 8px;
}

.skill-import-input {
  flex: 1;
  background: #f8f9fa;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  color: #1a202c;
  font-size: 12px;
  padding: 8px 10px;
  outline: none;
  font-family: inherit;
  transition: border-color 0.15s;
}

.skill-import-input:focus {
  border-color: #6366f1;
  background: #ffffff;
}

.skill-import-btn {
  padding: 8px 14px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  border: none;
  background: #6366f1;
  color: #fff;
  transition: background 0.15s;
  font-family: inherit;
  white-space: nowrap;
}

.skill-import-btn:hover:not(:disabled) {
  background: #4f46e5;
}

.skill-import-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.skill-tip {
  font-size: 11px;
  color: #64748b;
}

.skill-tip.ok {
  color: #15803d;
}

.skill-tip.error {
  color: #dc2626;
}

.skill-empty {
  font-size: 12px;
  color: #94a3b8;
  padding: 6px 0;
}

.bundled-switch {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #475569;
  user-select: none;
}

.bundled-switch input {
  accent-color: #6366f1;
}

.skills-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 280px;
  overflow-y: auto;
  padding-right: 2px;
}

.skill-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px;
}

.skill-row-left {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex: 1;
}

.skill-row-left input {
  margin-top: 2px;
  accent-color: #6366f1;
}

.skill-meta {
  flex: 1;
  min-width: 0;
}

.skill-name-line {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.skill-name {
  font-size: 12px;
  font-weight: 700;
  color: #0f172a;
}

.skill-source {
  font-size: 10px;
  color: #475569;
  background: #e2e8f0;
  border-radius: 999px;
  padding: 1px 7px;
}

.skill-desc {
  font-size: 11px;
  color: #475569;
  line-height: 1.5;
  margin: 0;
}

.skill-missing {
  font-size: 11px;
  color: #b45309;
  margin: 6px 0 0;
  line-height: 1.4;
}

.skill-state {
  font-size: 10px;
  color: #b45309;
  background: #fef3c7;
  border: 1px solid #fde68a;
  border-radius: 999px;
  padding: 2px 7px;
  white-space: nowrap;
}

.skill-state.eligible {
  color: #166534;
  background: #dcfce7;
  border-color: #86efac;
}

.skill-state.blocked {
  color: #991b1b;
  background: #fee2e2;
  border-color: #fecaca;
}
</style>
