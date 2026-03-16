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
  background: #1a202c;
  color: #e2e8f0;
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
  background: #2d3748;
  border-radius: 2px;
}

::-webkit-scrollbar-thumb:hover {
  background: #4a5568;
}

::selection {
  background: rgba(59, 130, 246, 0.3);
  color: #e2e8f0;
}
</style>
