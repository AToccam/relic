<script setup lang="ts">
import TopNavBar from '@/components/TopNavBar.vue'
import SidePanel from '@/components/SidePanel.vue'
import MessageList from '@/components/MessageList.vue'
import MessageInput from '@/components/MessageInput.vue'
</script>

<template>
  <div class="chat-layout">
    <TopNavBar />
    <div class="chat-body">
      <SidePanel />
      <main class="chat-main">
        <MessageList />
        <MessageInput />
      </main>
      <div class="right-panel-placeholder" />
    </div>
  </div>
</template>

<style scoped>
.chat-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

.chat-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #1a202c;
}

.right-panel-placeholder {
  width: 260px;
  flex-shrink: 0;
  background: #141920;
  border-left: 1px solid #2d3748;
}
</style>
