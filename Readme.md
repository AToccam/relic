# 🦞 Relic 基于OpenClaw的多AI助手

## Gateway (网关层)

这里是 Relic 系统的“感官”与“前线哨站”。本模块基于开源的 OpenClaw 构建，专门负责处理与外部聊天渠道（如 WebChat、飞书等）的 WebSocket 长连接与鉴权。

**⚠️ 重要架构说明**：网关层**不包含**任何 AI 业务逻辑。它接收到的所有用户消息，都会通过 Webhook 标准协议，原封不动地转发给后端的 `relic-core` (Spring Boot 大脑) 进行处理。

---

## 🛠️ 1. 环境准备 (Prerequisites)

在启动本项目前，请确保你的电脑上已经安装了 Node.js 环境：
* **Node.js**: 推荐版本 v18.x 或更高。
* **npm**: Node.js 自带的包管理器。
* **Ollama**: 用于本地运行模型（如 qwen3.5:2b）。

> **测试安装是否成功**：在终端运行 `node -v` 和 `npm -v`，如果有版本号输出则说明环境正常。

### Ollama 安装与模型准备（Windows PowerShell）

1. 在 PowerShell 中执行以下命令安装 Ollama：
   ```powershell
   irm https://ollama.com/install.ps1 | iex
   ```
2. 安装完成后，在控制台拉起并运行 gemma3:1b：
   ```powershell
   ollama run gemma3:1b
   ```
   首次运行会自动下载模型，下载完成后即可开始对话。

---

## 📦 2. 初始化与依赖安装

为了保证团队代码仓库的轻量化，本项目**没有**将庞大的 `node_modules` 依赖文件夹上传到 Git。你需要手动拉取依赖。

1. 打开终端（或 IDEA 的 Terminal），进入当前网关目录：
   ```bash
   cd relic-gateway
   ```
2. 执行依赖安装命令（由于国内网络环境，强烈建议配置镜像或使用代理）：
   ```bash
   npm install
   ```
   *稍等片刻，npm 会根据 `package.json` 自动为你下载所有必需的模块。*   
3. 执行配置文件同步
   ```bash
   npm run setup
   ```
---

## 🔗 3. 核心配置 (连接后端大脑)

网关需要知道去哪里寻找核心大脑。我们需要配置网关，将所有的流量指针拧向本地的 Java 后端。

1. 确保你的 `relic-core` (Spring Boot) 项目已经启动，并监听在 `8082` 端口。
2. 在当前目录下，配置网关代理，确保 `webhook` 地址指向咱们的 Java 接口：
   ```bash
   # 具体配置命令或 JSON 结构请参考下一步联调指南
   ```

---

## 🚀 4. 启动网关

依赖安装完毕且后端启动后，执行以下命令唤醒网关：

```bash
npx openclaw gateway --port 18789 --verbose
```

**启动成功的标志：**
1. 终端打印出 `[gateway] listening on ws://127.0.0.1:18789`。
2. 终端打印出 `[canvas] host mounted at http://127.0.0.1:18789/__openclaw__/canvas/`。

此时，你可以直接在浏览器访问上述 Canvas 网址，在网页聊天框里输入内容，体验前后端全链路贯通的效果！



## 项目架构与代码逻辑梳理

**Relic** 是一个**多层 AI 编排系统**，协调多个大模型提供商并支持工具调用（Function Calling）。

---

### 整体架构（两个模块）

| 模块              | 技术栈                      | 作用                                                         |
| ----------------- | --------------------------- | ------------------------------------------------------------ |
| **relic-core**    | Spring Boot 3.4.3 / Java 21 | AI 核心后端，负责路由、聚合、工具调用                        |
| **relic-gateway** | Node.js + OpenClaw SDK      | 轻量网关，处理 WebSocket 客户端连接，将消息转发到后端 webhook |

Gateway 是**无状态**的，它通过 OpenClaw SDK 连接客户端，所有业务逻辑由 relic-core 处理。后端暴露 OpenAI 兼容的 `/v1/chat/completions` 接口，Gateway 通过 `http://127.0.0.1:8082/v1` 连接。

---

### relic-core 核心类职责

#### Controller 层

- WebhookController.java — 5 个端点：
  - `POST /openclaw` — 接收 OpenClaw webhook 消息（非流式）
  - `POST /v1/chat/completions` — **OpenAI 兼容流式 SSE 端点**（主要入口）
  - `GET/POST /mode` — 查看/切换路由模式（SINGLE / MULTI）
  - `POST /test/ai`、`/test/multi` — 测试端点

#### DTO 层

- OpenClawRequest.java — OpenClaw webhook 请求体映射
- ToolCallResult.java — 封装 AI 返回结果（文本内容 + 工具调用列表）

#### Service 层（AI 提供商）

- AiProvider.java — 统一接口，定义 `ask()`、`stream()`、`askWithTools()`、`streamWithTools()` 等方法
- DeepSeekService.java — **主力模型**，支持流式 + 工具调用，调用 `api.deepseek.com`
- KimiService.java — Moonshot Kimi（`moonshot-v1-8k`），仅用作 MULTI 模式的顾问
- QwenService.java — 阿里通义千问（`qwen-turbo`），仅用作 MULTI 模式的顾问
- GeminiService.java — Google Gemini，需代理，当前受限未启用
- AiRouterService.java — **核心路由调度器**，根据模式分发请求

#### Tool 层（工具调用系统）

- ToolDefinitions.java — 定义 4 个工具：`web_search`、`create_text_file`、`read_file`、`list_files`
- ToolExecutor.java — 工具执行器（DuckDuckGo 搜索、文件读写、目录列举），含路径遍历防护
- ToolCallService.java — **工具调用循环引擎**，最多 10 轮迭代，直到模型不再请求工具

#### Util 层

- MessageHelper.java — 消息清洗、滑动窗口（保留最近 8 条）、系统提示词注入、多模型聚合消息构建
- OpenAiResponseBuilder.java — 构造 OpenAI 兼容的 SSE chunk 格式

---

### 两种路由模式

```
SINGLE 模式:  用户消息 → DeepSeek 直接回答（支持工具调用循环）

MULTI 模式:   用户消息 → Kimi + Qwen 并行回答
                       → 收集顾问观点
                       → 注入聚合 system prompt
                       → DeepSeek 综合分析后最终作答
```

---

### 主请求流程（流式 SSE）

```
客户端 POST /v1/chat/completions
  → WebhookController: 清洗消息 → 滑动窗口截取
    → 开启虚拟线程 + SseEmitter(180s)
      → AiRouterService.streamAuto()
        → [MULTI] 并行收集 Kimi/Qwen 的观点（5s 心跳保活）
        → 构建聚合 system prompt
        → ToolCallService.streamWithTools()
          → DeepSeek 流式输出（每个 chunk 通过 SSE 推送）
          → 若返回 tool_calls → 执行工具 → 追加结果 → 继续循环
          → 否则结束，发送 [DONE]
```

---

### 配置要点

- 后端端口：**8082**
- 工作空间路径：`~/.openclaw/workspace`（工具读写文件的沙箱）
- API Key 通过 `application.yml` 注入各服务（当前未在配置文件中显示，应通过环境变量传入）
- Gateway 通过 setup.js 自动生成 `~/.openclaw/openclaw.json` 配置文件
