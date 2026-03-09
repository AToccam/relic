# 🦞 Relic Gateway (网关层)

这里是 Relic 系统的“感官”与“前线哨站”。本模块基于开源的 OpenClaw 构建，专门负责处理与外部聊天渠道（如 WebChat、飞书等）的 WebSocket 长连接与鉴权。

**⚠️ 重要架构说明**：网关层**不包含**任何 AI 业务逻辑。它接收到的所有用户消息，都会通过 Webhook 标准协议，原封不动地转发给后端的 `relic-core` (Spring Boot 大脑) 进行处理。

---

## 🛠️ 1. 环境准备 (Prerequisites)

在启动本项目前，请确保你的电脑上已经安装了 Node.js 环境：
* **Node.js**: 推荐版本 v18.x 或更高。
* **npm**: Node.js 自带的包管理器。

> **测试安装是否成功**：在终端运行 `node -v` 和 `npm -v`，如果有版本号输出则说明环境正常。

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