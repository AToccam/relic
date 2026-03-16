<script setup lang="ts">
import TopNavBar from '@/components/TopNavBar.vue'
import SidePanel from '@/components/SidePanel.vue'
import MessageList from '@/components/MessageList.vue'
import MessageInput from '@/components/MessageInput.vue'
import RightPanel from '@/components/RightPanel.vue'
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
      <RightPanel />
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
</style>
