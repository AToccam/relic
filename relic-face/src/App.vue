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
  background: #080f1e;
  color: #dae7f7;
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
  background: #1e3354;
  border-radius: 2px;
}

::-webkit-scrollbar-thumb:hover {
  background: #2a4870;
}

::selection {
  background: rgba(34, 211, 238, 0.2);
  color: #dae7f7;
}
</style>
