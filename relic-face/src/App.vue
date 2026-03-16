<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <RouterView />
</template>

<style>
*, *::before, *::after {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html, body, #app {
  height: 100%;
  width: 100%;
  overflow: hidden;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
    'Microsoft YaHei', sans-serif;
  background: #f8f9fa;
  color: #1a202c;
  -webkit-font-smoothing: antialiased;
}

::-webkit-scrollbar {
  width: 4px;
  height: 4px;
}

::-webkit-scrollbar-track {
  background: transparent;
}

::-webkit-scrollbar-thumb {
  background: #cbd5e0;
  border-radius: 2px;
}

::-webkit-scrollbar-thumb:hover {
  background: #a0aec0;
}

::selection {
  background: rgba(99, 102, 241, 0.2);
  color: #1a202c;
}
</style>
