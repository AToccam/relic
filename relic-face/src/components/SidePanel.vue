<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import ComingSoonModal from './ComingSoonModal.vue'

const settings = useSettingsStore()
onMounted(() => settings.fetchMode())

const modal = ref<string | null>(null)
</script>

<template>
  <aside class="side-panel">
    <div class="panel-header">
      <span class="panel-title">来源</span>
      <button class="header-icon-btn" @click="modal = '折叠面板'" title="折叠">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="3" y="3" width="18" height="18" rx="2" />
          <path d="M9 3v18" />
        </svg>
      </button>
    </div>

    <div class="panel-body">
      <button class="add-source-btn" @click="modal = '添加来源'">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <line x1="12" y1="5" x2="12" y2="19" />
          <line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        添加来源
      </button>

      <div class="search-bar" @click="modal = '搜索来源'">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8" />
          <line x1="21" y1="21" x2="16.65" y2="16.65" />
        </svg>
        <span class="search-placeholder">在网络中搜索新来源</span>
      </div>

      <div class="source-section-title">AI 提供者</div>

      <div
        v-for="p in settings.providers"
        :key="p"
        class="source-item"
      >
        <div class="source-icon">{{ p.charAt(0).toUpperCase() }}</div>
        <div class="source-info">
          <span class="source-name">{{ p }}</span>
          <span
            v-if="settings.testResults[p]"
            :class="['source-status', settings.testResults[p].status]"
          >
            {{ settings.testResults[p].status === 'ok' ? '在线' : '异常' }}
            · {{ settings.testResults[p].costMs }}ms
          </span>
          <span v-else class="source-status unknown">未测试</span>
        </div>
      </div>

      <div v-if="settings.providers.length === 0" class="empty-hint">
        <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z" />
          <polyline points="13 2 13 9 20 9" />
        </svg>
        <p>后端未连接</p>
        <span>已保存的来源将显示在此处</span>
      </div>
    </div>
  </aside>

  <ComingSoonModal
    v-if="modal"
    :title="modal"
    @close="modal = null"
  />
</template>

<style scoped>
.side-panel {
  width: 240px;
  flex-shrink: 0;
  background: #f8f9fa;
  border-right: 1px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-header {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  border-bottom: 1px solid #e2e8f0;
  flex-shrink: 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: #1a202c;
}

.header-icon-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: #a0aec0;
  cursor: pointer;
  transition: all 0.15s;
}

.header-icon-btn:hover {
  background: #e2e8f0;
  color: #4a5568;
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  scrollbar-width: thin;
  scrollbar-color: #cbd5e0 transparent;
}

.add-source-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  padding: 8px 0;
  border-radius: 8px;
  border: 1.5px dashed #cbd5e0;
  background: transparent;
  color: #4a5568;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  font-family: inherit;
}

.add-source-btn:hover {
  border-color: #6366f1;
  color: #6366f1;
  background: rgba(99, 102, 241, 0.04);
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  color: #a0aec0;
  cursor: pointer;
  transition: border-color 0.15s;
}

.search-bar:hover {
  border-color: #6366f1;
}

.search-placeholder {
  font-size: 12px;
  color: #a0aec0;
}

.source-section-title {
  font-size: 11px;
  font-weight: 600;
  color: #a0aec0;
  text-transform: uppercase;
  letter-spacing: 0.8px;
  padding: 4px 4px 0;
}

.source-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  transition: box-shadow 0.15s;
}

.source-item:hover {
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.source-icon {
  width: 30px;
  height: 30px;
  border-radius: 6px;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.source-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.source-name {
  font-size: 13px;
  font-weight: 500;
  color: #1a202c;
  text-transform: capitalize;
}

.source-status {
  font-size: 11px;
}

.source-status.ok { color: #38a169; }
.source-status.fail { color: #e53e3e; }
.source-status.unknown { color: #a0aec0; }

.empty-hint {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  text-align: center;
  padding: 24px 0;
  user-select: none;
}

.empty-hint p {
  font-size: 13px;
  font-weight: 500;
  color: #a0aec0;
  margin: 0;
}

.empty-hint span {
  font-size: 12px;
  color: #cbd5e0;
  line-height: 1.5;
}
</style>
