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
        <span class="logo-sub">// AI · AGENT</span>
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
  background: #0d1626;
  border-bottom: 1px solid #1d3256;
  box-shadow: 0 2px 20px rgba(34, 211, 238, 0.1), 0 1px 0 rgba(34, 211, 238, 0.08);
  user-select: none;
}

.nav-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 200px;
}

.logo-icon {
  width: 36px;
  height: 36px;
  border-radius: 7px;
  border: 1.5px solid #22d3ee;
  background: #091e2e;
  box-shadow: 0 0 14px rgba(34, 211, 238, 0.28), inset 0 0 8px rgba(34, 211, 238, 0.07);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  font-weight: 800;
  color: #22d3ee;
  font-family: ui-monospace, 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  flex-shrink: 0;
  letter-spacing: -0.02em;
}

.logo-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  gap: 1px;
}

.logo-name {
  font-size: 15px;
  font-weight: 800;
  color: #dae7f7;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.logo-sub {
  font-size: 9px;
  color: #22d3ee;
  letter-spacing: 0.1em;
  font-family: ui-monospace, 'JetBrains Mono', 'Courier New', monospace;
}

.nav-center {
  flex: 1;
  display: flex;
  justify-content: center;
}

.nav-title {
  font-size: 13px;
  font-weight: 400;
  color: #7fb3d3;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  letter-spacing: 0.02em;
}

.nav-right {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 200px;
  justify-content: flex-end;
}

.mode-badge {
  font-size: 10px;
  font-weight: 700;
  padding: 4px 10px;
  border-radius: 4px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  font-family: ui-monospace, 'JetBrains Mono', 'Courier New', monospace;
}

.mode-badge.multi {
  background: rgba(167, 139, 250, 0.1);
  color: #a78bfa;
  border: 1px solid rgba(167, 139, 250, 0.28);
}

.mode-badge.single {
  background: rgba(34, 211, 238, 0.08);
  color: #22d3ee;
  border: 1px solid rgba(34, 211, 238, 0.22);
}

.icon-btn {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  border: 1px solid #1d3256;
  background: transparent;
  color: #6fa8c8;
  cursor: pointer;
  transition: all 0.15s;
}

.icon-btn:hover {
  background: #172540;
  color: #22d3ee;
  border-color: #234070;
}
</style>

