<script setup lang="ts">
import SidePanel from '@/components/SidePanel.vue'
import MessageList from '@/components/MessageList.vue'
import MessageInput from '@/components/MessageInput.vue'
</script>

<template>
  <div class="chat-layout">
    <SidePanel />
    <main class="chat-main">
      <MessageList />
      <MessageInput />
    </main>
  </div>
</template>

<style scoped>
.chat-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #1a202c;
}
</style>
