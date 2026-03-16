<script setup lang="ts">
import { ref, onMounted } from 'vue'
import TopNavBar from '@/components/TopNavBar.vue'
import SidePanel from '@/components/SidePanel.vue'
import MessageList from '@/components/MessageList.vue'
import MessageInput from '@/components/MessageInput.vue'
import RightPanel from '@/components/RightPanel.vue'
import SettingsPanel from '@/components/SettingsPanel.vue'
import { useSettingsStore } from '@/stores/settings'

const showSettings = ref(false)
const settings = useSettingsStore()

onMounted(() => settings.fetchMode())
</script>

<template>
  <div class="chat-layout">
    <TopNavBar @open-settings="showSettings = true" />
    <div class="chat-body">
      <SidePanel />
      <main class="chat-main">
        <MessageList />
        <MessageInput />
      </main>
      <RightPanel />
    </div>
    <SettingsPanel v-if="showSettings" @close="showSettings = false" />
  </div>
</template>

<style scoped>
.chat-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  background: #e8eaf0;
}

.chat-body {
  flex: 1;
  display: flex;
  overflow: hidden;
  padding: 8px;
  gap: 8px;
}

.chat-main {
  flex: 2;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #ffffff;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
}
</style>
