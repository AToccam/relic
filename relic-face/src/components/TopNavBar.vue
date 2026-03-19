<script setup lang="ts">
import { useSettingsStore } from '@/stores/settings'

const settings = useSettingsStore()

defineEmits<{ openSettings: [] }>()
</script>

<template>
  <header class="top-nav">
    <div class="nav-left">
      <span class="logo-icon">R</span>
      <div class="logo-text">
        <span class="logo-name">Relic</span>
        <span class="logo-sub">AI Agent</span>
      </div>
    </div>

    <div class="nav-center">
      <span class="nav-title">信息予你无限，Relic重塑未来</span>
    </div>

    <div class="nav-right">
      <span :class="['mode-badge', settings.mode]">
        {{ settings.mode === 'multi'
          ? `Multi · ${settings.multiLeader || '…'}`
          : `Single · ${settings.singleProvider || '…'}` }}
      </span>
      <button class="icon-btn" @click="$emit('openSettings')" title="设置">
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="3" />
          <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
        </svg>
      </button>
    </div>
  </header>
</template>

<style scoped>
.top-nav {
  height: 56px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: #ffffff;
  border-bottom: 1px solid #e2e8f0;
  user-select: none;
}

.nav-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 180px;
}

.logo-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 700;
  color: #fff;
  flex-shrink: 0;
}

.logo-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.logo-name {
  font-size: 15px;
  font-weight: 700;
  color: #1a202c;
  letter-spacing: 0.3px;
}

.logo-sub {
  font-size: 10px;
  color: #a0aec0;
  letter-spacing: 1px;
}

.nav-center {
  flex: 1;
  display: flex;
  justify-content: center;
}

.nav-title {
  font-size: 15px;
  font-weight: 500;
  color: #4a5568;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nav-right {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 180px;
  justify-content: flex-end;
}

.mode-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 999px;
  letter-spacing: 0.5px;
  text-transform: uppercase;
}

.mode-badge.multi {
  background: rgba(99, 102, 241, 0.1);
  color: #6366f1;
  border: 1px solid rgba(99, 102, 241, 0.25);
}

.mode-badge.single {
  background: rgba(59, 130, 246, 0.1);
  color: #3b82f6;
  border: 1px solid rgba(59, 130, 246, 0.25);
}

.icon-btn {
  width: 34px;
  height: 34px;
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

.icon-btn:hover {
  background: #f1f5f9;
  color: #1a202c;
  border-color: #cbd5e0;
}
</style>

