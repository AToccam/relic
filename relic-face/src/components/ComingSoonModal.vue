<script setup lang="ts">
defineProps<{ title: string }>()
defineEmits<{ close: [] }>()
</script>

<template>
  <Teleport to="body">
    <div class="modal-mask" @click.self="$emit('close')">
      <div class="modal-box">
        <div class="modal-icon">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M12 2a10 10 0 1 0 0 20A10 10 0 0 0 12 2z" />
            <path d="M12 8v4l3 3" />
          </svg>
        </div>
        <h3 class="modal-title">{{ title }}</h3>
        <p class="modal-desc">该功能正在开发中，敬请期待。</p>
        <button class="modal-close" @click="$emit('close')">知道了</button>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(2px);
}

.modal-box {
  background: #ffffff;
  border-radius: 16px;
  padding: 36px 32px 28px;
  width: 320px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

.modal-icon {
  color: #6366f1;
  opacity: 0.8;
}

.modal-title {
  font-size: 17px;
  font-weight: 600;
  color: #1a202c;
  text-align: center;
  margin: 0;
}

.modal-desc {
  font-size: 14px;
  color: #718096;
  text-align: center;
  margin: 0;
  line-height: 1.6;
}

.modal-close {
  margin-top: 8px;
  padding: 8px 32px;
  border-radius: 8px;
  border: none;
  background: #6366f1;
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s;
  font-family: inherit;
}

.modal-close:hover {
  background: #4f46e5;
}
</style>
