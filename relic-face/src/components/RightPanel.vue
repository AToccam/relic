<script setup lang="ts">
import { ref } from 'vue'
import ComingSoonModal from './ComingSoonModal.vue'

const modal = ref<string | null>(null)

const cards = [
  { title: '音频概览', icon: 'M9 19V6l12-3v13M9 19c0 1.1-.9 2-2 2s-2-.9-2-2 .9-2 2-2 2 .9 2 2zm12-3c0 1.1-.9 2-2 2s-2-.9-2-2 .9-2 2-2 2 .9 2 2z' },
  { title: '演示文稿', icon: 'M2 3h20v14H2zM8 21h8M12 17v4' },
  { title: '思维导图', icon: 'M12 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM3 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm18 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM12 18a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM12 6v4M5 12h4m6 0h4M12 16v-4' },
  { title: '报告', icon: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M16 13H8M16 17H8M10 9H8' },
  { title: '闪卡', icon: 'M20 7H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2zM12 11v2' },
  { title: '信息图', icon: 'M18 20V10M12 20V4M6 20v-6' },
]
</script>

<template>
  <aside class="right-panel">
    <div class="panel-header">
      <span class="panel-title">Studio</span>
      <button class="header-icon-btn" @click="modal = '更多选项'" title="更多">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="5" r="1" /><circle cx="12" cy="12" r="1" /><circle cx="12" cy="19" r="1" />
        </svg>
      </button>
    </div>

    <div class="panel-body">
      <p class="cards-hint">选择输出类型，Studio 将为你生成内容</p>
      <div class="cards-grid">
        <button
          v-for="card in cards"
          :key="card.title"
          class="studio-card"
          @click="modal = card.title"
        >
          <svg class="card-icon" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
            <path :d="card.icon" />
          </svg>
          <span class="card-title">{{ card.title }}</span>
        </button>
      </div>

      <div class="studio-empty">
        <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M12 20h9M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" />
        </svg>
        <p>Studio 输出将保存在此处</p>
        <span>添加来源后，点击上方卡片开始生成内容。</span>
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
.right-panel {
  flex: 1;
  min-width: 0;
  background: #f8f9fa;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
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
  padding: 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  scrollbar-width: thin;
  scrollbar-color: #cbd5e0 transparent;
}

.cards-hint {
  font-size: 12px;
  color: #a0aec0;
  line-height: 1.5;
  text-align: center;
}

.cards-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.studio-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 16px 8px;
  border-radius: 10px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  cursor: pointer;
  transition: all 0.15s;
  font-family: inherit;
}

.studio-card:hover {
  border-color: #6366f1;
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.12);
  background: #fafafe;
}

.card-icon {
  color: #6366f1;
}

.card-title {
  font-size: 12px;
  font-weight: 500;
  color: #4a5568;
}

.studio-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
  padding: 16px 4px;
  color: #cbd5e0;
  user-select: none;
}

.studio-empty p {
  font-size: 13px;
  font-weight: 500;
  color: #a0aec0;
  margin: 0;
}

.studio-empty span {
  font-size: 12px;
  color: #cbd5e0;
  line-height: 1.5;
}
</style>
