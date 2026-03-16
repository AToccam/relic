<script setup lang="ts">
import { useChatStore } from '@/stores/chat'
import { useSettingsStore } from '@/stores/settings'

const chat = useChatStore()
const settings = useSettingsStore()
</script>

<template>
  <header class="top-nav">
    <div class="nav-left">
      <span class="logo-icon">R</span>
      <div class="logo-text">
        <span class="logo-name">Relic</span>
        <span class="logo-sub">AI Gateway</span>
      </div>
    </div>

    <div class="nav-center">
      <span class="nav-title">对话</span>
    </div>

    <div class="nav-right">
      <span :class="['mode-badge', settings.mode]">
        {{ settings.mode === 'multi' ? 'Multi' : 'Single' }}
      </span>
      <button class="nav-btn" @click="chat.clear()" title="清空对话">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="3 6 5 6 21 6" />
          <path d="M19 6l-1 14H6L5 6" />
          <path d="M10 11v6M14 11v6" />
          <path d="M9 6V4h6v2" />
        </svg>
        清空对话
      </button>
    </div>
  </header>
</template>

<style scoped>
.top-nav {
  height: 52px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: #0f1419;
  border-bottom: 1px solid #2d3748;
  user-select: none;
}

.nav-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 160px;
}

.logo-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #3b82f6, #6366f1);
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
  color: #e2e8f0;
  letter-spacing: 0.3px;
}

.logo-sub {
  font-size: 10px;
  color: #4a5568;
  text-transform: uppercase;
  letter-spacing: 1px;
}

.nav-center {
  flex: 1;
  display: flex;
  justify-content: center;
}

.nav-title {
  font-size: 14px;
  font-weight: 500;
  color: #718096;
}

.nav-right {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 160px;
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
  background: rgba(99, 102, 241, 0.15);
  color: #818cf8;
  border: 1px solid rgba(99, 102, 241, 0.3);
}

.mode-badge.single {
  background: rgba(59, 130, 246, 0.15);
  color: #60a5fa;
  border: 1px solid rgba(59, 130, 246, 0.3);
}

.nav-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid #2d3748;
  background: transparent;
  color: #718096;
  transition: all 0.2s;
  font-family: inherit;
}

.nav-btn:hover {
  border-color: #e53e3e;
  color: #fc8181;
}
</style>
