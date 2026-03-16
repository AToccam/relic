<script setup lang="ts">
</script>

<template>
  <aside class="right-panel">
    <div class="panel-header">
      <span class="panel-title">多 AI 联测</span>
    </div>
    <div class="panel-empty">
      <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
        <circle cx="12" cy="12" r="3" />
        <path d="M12 2v2M12 20v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M2 12h2M20 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" />
      </svg>
      <p>并行发送同一问题给多个 AI</p>
      <span>对比不同模型的回答</span>
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

.panel-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #4a5568;
  text-align: center;
  padding: 24px;
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
</style>
