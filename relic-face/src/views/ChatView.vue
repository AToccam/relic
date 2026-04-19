<script setup lang="ts">
import { ref, onMounted } from 'vue'
import TopNavBar from '@/components/TopNavBar.vue'
import SidePanel from '@/components/SidePanel.vue'
import MessageList from '@/components/MessageList.vue'
import MessageInput from '@/components/MessageInput.vue'
import RightPanel from '@/components/RightPanel.vue'
import SettingsPanel from '@/components/SettingsPanel.vue'
import { useSettingsStore } from '@/stores/settings'
import { useChatStore } from '@/stores/chat'
import { useSourcesStore } from '@/stores/sources'
import { useStudioStore } from '@/stores/studio'

const showSettings = ref(false)
const settings = useSettingsStore()
const chat = useChatStore()
const sources = useSourcesStore()
const studio = useStudioStore()

onMounted(async () => {
  await settings.fetchMode()
  await settings.fetchSkills()
  await chat.init()
  await sources.loadPersistedFiles()
  await studio.loadPersistedFiles()
})
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
