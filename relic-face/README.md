# relic-face

Relic 项目的前端界面，基于 Vue 3 + TypeScript 构建，对接 relic-core 后端提供的 OpenAI 兼容接口。

## 技术栈

- **框架**：Vue 3 + TypeScript
- **构建工具**：Vite
- **状态管理**：Pinia
- **路由**：Vue Router
- **Markdown 渲染**：marked

## 功能

- 与 relic-core 进行流式对话（SSE 实时输出，支持随时中断）
- 助手回复支持 Markdown / 代码块渲染
- 侧边栏切换路由模式：**Single**（DeepSeek 直接回答）/ **Multi**（Kimi + Qwen 协同 → DeepSeek 聚合）
- 各 AI 提供者连通性一键测试
- 清空对话历史

## 快速启动

确保 relic-core 已在 `8082` 端口运行，然后：

```bash
npm install
npm run dev
```

开发服务器启动后访问 `http://localhost:5173`。

Vite 已配置代理，前端所有 `/api/*` 请求自动转发至 `http://127.0.0.1:8082`。

## 注意事项

- **不要点击 Gemini 的测试按钮**，除非本地代理（`127.0.0.1:7897`）正在运行。Gemini SDK 会在 JVM 全局设置代理，误点后会导致 relic-core 内所有 AI 提供者网络中断，需重启 relic-core 恢复。
- 生产构建：`npm run build`，产物输出至 `dist/`。
