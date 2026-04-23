# RAG 前端对接详细文档

本文面向前端开发，说明当前 `relic-core` 中 RAG 能力的实际对接方式。文档以当前后端实现为准，不以历史设计稿或旧文档为准。

适用范围：

- 文件上传到工作区
- 手动触发 RAG 索引
- 轮询索引状态
- 发起带 `ragConfig` 的聊天请求
- 解析 SSE 流式响应
- 读取 `citations`

## 1. 总体链路

前端接入 RAG 的标准链路如下：

1. 用户上传文件到 `/files/upload`
2. 取上传响应里的 `relativePath` 作为后续 `sourceId`
3. 调用 `/rag/index/manual` 触发索引
4. 轮询 `/rag/index/status?sourceId=...`，直到状态变为 `COMPLETED`
5. 调用 `/v1/chat/completions`
6. 在请求体里传入 `ragConfig.enabled=true` 和 `ragConfig.sourceIds=[sourceId1, sourceId2, ...]`
7. 从 SSE 首个 chunk 里读取 `citations`
8. 持续拼接后续 chunk 的 `choices[0].delta.content`

如果前端只做普通聊天，不走知识库增强，则：

- 可以不传 `ragConfig`
- 或传 `ragConfig.enabled=false`

## 2. 基础约定

默认后端端口：

- `http://127.0.0.1:8082`

本期 RAG 对前端最重要的字段有两个：

- `sourceId`
- `citations`

其中：

- `sourceId` 不是前端自己拼的路径
- `sourceId` 必须直接使用 `/files/upload` 返回的 `relativePath`
- `citations` 是后端在流式响应里附加的扩展字段，不是 OpenAI 官方标准字段

## 3. 接口一览

RAG 主流程需要的接口：

- `POST /files/upload`
- `POST /rag/index/manual`
- `GET /rag/index/status`
- `POST /v1/chat/completions`

前端面板常用但非必须的辅助接口：

- `GET /files/list`
- `GET /files/download`
- `DELETE /files?relativePath=...`
- `POST /files/delete`

调试接口：

- `GET /mode`
- `POST /mode`

## 4. 文件上传

接口：

```http
POST /files/upload
Content-Type: multipart/form-data
```

表单字段：

- `file`: 必填，上传文件本体

返回示例：

```json
{
  "filename": "rag-demo-a.txt",
  "storedName": "rag-demo-a.txt",
  "relativePath": "uploads/2026-04-23/rag-demo-a.txt",
  "mimeType": "text/plain",
  "size": 1234,
  "indexTriggered": false
}
```

字段说明：

- `filename`: 原始文件名
- `storedName`: 后端实际落盘后的文件名
- `relativePath`: 后续索引和 RAG 查询必须使用的 `sourceId`
- `mimeType`: 浏览器/服务端识别出的 MIME
- `size`: 文件大小，单位字节
- `indexTriggered`: 是否上传后自动触发索引

前端注意：

- 当前不要假设上传即自动建索引
- 当前默认应走“上传成功后再手动触发索引”的流程
- 必须持久化保存 `relativePath`

## 5. 手动触发索引

接口：

```http
POST /rag/index/manual
Content-Type: application/json
```

请求体：

```json
{
  "sourceId": "uploads/2026-04-23/rag-demo-a.txt"
}
```

返回示例：

```json
{
  "ok": true,
  "trigger": "manual",
  "sourceId": "uploads/2026-04-23/rag-demo-a.txt",
  "status": "INDEXING"
}
```

前端建议：

- 上传成功后立即触发一次手动索引
- 如果用户一次上传多个文件，逐个触发即可
- 前端应进入“索引中”状态，而不是立即允许发起带 RAG 的查询

## 6. 查询索引状态

接口：

```http
GET /rag/index/status?sourceId=uploads/2026-04-23/rag-demo-a.txt
```

返回示例：

```json
{
  "sourceId": "uploads/2026-04-23/rag-demo-a.txt",
  "status": "COMPLETED",
  "message": "索引完成，chunk 数: 1",
  "chunkCount": 1,
  "updatedAt": "2026-04-23T00:01:23.456Z"
}
```

状态枚举：

- `NOT_INDEXED`: 尚未建立索引
- `INDEXING`: 正在索引
- `COMPLETED`: 索引完成
- `FAILED`: 索引失败

前端建议：

- 轮询间隔建议 `1500ms ~ 3000ms`
- 单文件超时建议 `60s ~ 180s`
- 只有 `COMPLETED` 时才允许作为 RAG 数据源使用
- `FAILED` 时要把 `message` 直接展示给用户或记录到控制台

## 7. 发起 RAG 聊天

接口：

```http
POST /v1/chat/completions
Content-Type: application/json
Accept: text/event-stream
```

请求体最小示例：

```json
{
  "conversationId": "rag-demo-001",
  "messages": [
    {
      "role": "user",
      "content": "请根据资料总结 RAG 的触发条件和 citations 的返回方式。"
    }
  ],
  "ragConfig": {
    "enabled": true,
    "sourceIds": [
      "uploads/2026-04-23/rag-demo-a.txt",
      "uploads/2026-04-23/rag-demo-b.txt"
    ]
  }
}
```

### 7.1 请求体字段说明

顶层字段：

- `conversationId`: 可选但建议传，前后端用于归档聊天记录
- `messages`: 必填，OpenAI 风格消息数组
- `ragConfig`: 可选，控制是否启用 RAG
- `toolsEnabled`: 可选，是否允许模型额外调用工具

`ragConfig` 字段：

- `enabled`: 是否启用 RAG
- `sourceIds`: 本轮允许检索的资料范围

### 7.2 `toolsEnabled` 的建议用法

如果你们前端是正常产品聊天页：

- 可以不传 `toolsEnabled`
- 或传 `true`

如果你们前端要做“纯 RAG 对照”、“知识库命中验证”、“排查工具干扰”：

- 建议显式传 `toolsEnabled: false`

示例：

```json
{
  "conversationId": "rag-debug-001",
  "toolsEnabled": false,
  "messages": [
    {
      "role": "user",
      "content": "请根据资料说明 RAG 的触发条件。"
    }
  ],
  "ragConfig": {
    "enabled": true,
    "sourceIds": [
      "uploads/2026-04-23/rag-demo-a.txt"
    ]
  }
}
```

### 7.3 什么时候会真正触发 RAG

当前后端实际逻辑下，只有同时满足以下条件，才会进入 RAG 检索增强：

1. `ragConfig.enabled === true`
2. `ragConfig.sourceIds` 非空
3. 对话中存在可提取的用户问题
4. 对应 `sourceId` 的索引已经建立完成

否则就会退化成普通聊天。

## 8. SSE 响应格式

返回是 `text/event-stream`，每个事件形如：

```text
data:{"id":"chatcmpl-xxx","object":"chat.completion.chunk","created":1776872429,"model":"deepseek","citations":[{"id":"1","sourceId":"uploads/2026-04-23/rag-demo-a.txt","snippet":"..."}],"choices":[{"finish_reason":null,"delta":{"content":""},"index":0}]}

data:{"id":"chatcmpl-xxx","object":"chat.completion.chunk","created":1776872429,"model":"deepseek","choices":[{"finish_reason":null,"delta":{"content":"根据"},"index":0}]}

data:{"id":"chatcmpl-xxx","object":"chat.completion.chunk","created":1776872429,"model":"deepseek","choices":[{"finish_reason":"stop","delta":{},"index":0}]}

data:[DONE]
```

### 8.1 前端真正要用的字段

每个 chunk 关注：

- `choices[0].delta.content`
- `choices[0].finish_reason`
- `citations`

其中：

- 正文内容通过连续拼接 `delta.content` 得到
- `citations` 只会在有命中的情况下出现
- `citations` 通常出现在首个业务 chunk
- 收到 `data:[DONE]` 说明流结束

### 8.2 `citations` 字段结构

示例：

```json
[
  {
    "id": "1",
    "sourceId": "uploads/2026-04-23/rag-demo-a.txt",
    "snippet": "Relic 的 RAG 后端链路包含文件上传、手动索引、检索增强和 SSE 引用返回。"
  },
  {
    "id": "2",
    "sourceId": "uploads/2026-04-23/rag-demo-b.txt",
    "snippet": "当 ragConfig.enabled=true 且 sourceIds 非空时，后端会先检索相关片段，再把参考资料注入 system prompt。"
  }
]
```

字段说明：

- `id`: 引用序号
- `sourceId`: 命中的资料路径
- `snippet`: 可直接展示给用户的片段摘要

## 9. 前端推荐实现

由于这是 `POST + SSE`，浏览器里不要用 `EventSource`，建议直接用 `fetch + ReadableStream`。

### 9.1 TypeScript 上传并索引

```ts
export async function uploadFile(file: File) {
  const formData = new FormData();
  formData.append("file", file);

  const resp = await fetch("/files/upload", {
    method: "POST",
    body: formData,
  });

  if (!resp.ok) {
    throw new Error(`上传失败: ${resp.status}`);
  }

  return resp.json() as Promise<{
    filename: string;
    storedName: string;
    relativePath: string;
    mimeType: string;
    size: number;
    indexTriggered: boolean;
  }>;
}

export async function triggerManualIndex(sourceId: string) {
  const resp = await fetch("/rag/index/manual", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sourceId }),
  });

  if (!resp.ok) {
    throw new Error(`触发索引失败: ${resp.status}`);
  }

  return resp.json();
}

export async function waitIndexCompleted(sourceId: string, timeoutMs = 180000) {
  const startedAt = Date.now();

  while (Date.now() - startedAt < timeoutMs) {
    const resp = await fetch(`/rag/index/status?sourceId=${encodeURIComponent(sourceId)}`);
    if (!resp.ok) {
      throw new Error(`查询索引状态失败: ${resp.status}`);
    }

    const data = await resp.json();
    if (data.status === "COMPLETED") return data;
    if (data.status === "FAILED") {
      throw new Error(data.message || "索引失败");
    }

    await new Promise((resolve) => setTimeout(resolve, 2000));
  }

  throw new Error("索引超时");
}
```

### 9.2 TypeScript 发送 RAG 聊天并解析 SSE

```ts
type Citation = {
  id: string;
  sourceId: string;
  snippet: string;
};

type StreamHandlers = {
  onCitations?: (citations: Citation[]) => void;
  onText?: (textChunk: string) => void;
  onDone?: () => void;
};

export async function streamRagChat(
  payload: {
    conversationId?: string;
    toolsEnabled?: boolean;
    messages: Array<{ role: string; content: string }>;
    ragConfig?: {
      enabled: boolean;
      sourceIds: string[];
    };
  },
  handlers: StreamHandlers = {}
) {
  const resp = await fetch("/v1/chat/completions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    },
    body: JSON.stringify(payload),
  });

  if (!resp.ok || !resp.body) {
    throw new Error(`聊天请求失败: ${resp.status}`);
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let citationsDelivered = false;

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const segments = buffer.split(/(?=data:)/g);
    buffer = segments.pop() ?? "";

    for (const raw of segments) {
      const line = raw.trim();
      if (!line.startsWith("data:")) continue;

      const payloadText = line.slice(5).trim();
      if (!payloadText) continue;

      if (payloadText === "[DONE]") {
        handlers.onDone?.();
        continue;
      }

      let chunk: any;
      try {
        chunk = JSON.parse(payloadText);
      } catch {
        continue;
      }

      if (!citationsDelivered && Array.isArray(chunk.citations) && chunk.citations.length > 0) {
        citationsDelivered = true;
        handlers.onCitations?.(chunk.citations);
      }

      const text = chunk?.choices?.[0]?.delta?.content ?? "";
      if (text) {
        handlers.onText?.(text);
      }
    }
  }
}
```

## 10. 推荐的前端页面状态机

上传区建议至少有以下状态：

- `idle`
- `uploading`
- `uploaded`
- `indexing`
- `indexed`
- `index_failed`

聊天区建议至少有以下状态：

- `idle`
- `streaming`
- `done`
- `error`

引用区建议至少有以下状态：

- `hidden`
- `empty`
- `loaded`

推荐交互：

1. 用户上传文件后立即展示文件条目
2. 文件条目旁边显示“索引中”
3. 索引完成后显示“可用于问答”
4. 发问后先开始拼正文
5. 一旦首个 chunk 带回 `citations`，立即展示引用卡片

## 11. 常见坑

### 11.1 不要自己拼 `sourceId`

错误做法：

- 用文件名当 `sourceId`
- 用本地绝对路径当 `sourceId`
- 用上传前的原始文件名当 `sourceId`

正确做法：

- 直接使用 `/files/upload` 返回的 `relativePath`

### 11.2 不要在未完成索引时发起 RAG 查询

如果文件刚上传完就立刻发 `/v1/chat/completions`，很可能出现：

- 没命中
- 命中为空
- 引用为空

正确做法：

- 先等 `/rag/index/status` 返回 `COMPLETED`

### 11.3 `POST + SSE` 不要用 `EventSource`

因为当前聊天接口是：

- `POST /v1/chat/completions`

而 `EventSource` 只适合简单 `GET` 场景，所以这里应使用：

- `fetch`
- `ReadableStream`
- `TextDecoder`

### 11.4 `citations` 不是每个 chunk 都有

前端不要假设每个 chunk 都带 `citations`，通常只需：

- 首次出现时缓存下来
- 后面不重复覆盖

### 11.5 关闭 RAG 和关闭 tools 不是一回事

`ragConfig.enabled=false` 表示：

- 不走知识库检索

`toolsEnabled=false` 表示：

- 不允许模型调用额外工具

如果前端要做“纯 RAG 对照实验”，建议两个都明确控制。

## 12. 推荐请求模板

### 12.1 正常业务问答

```json
{
  "conversationId": "biz-chat-001",
  "messages": [
    {
      "role": "user",
      "content": "请根据资料总结接入方式。"
    }
  ],
  "ragConfig": {
    "enabled": true,
    "sourceIds": [
      "uploads/2026-04-23/rag-demo-a.txt",
      "uploads/2026-04-23/rag-demo-b.txt"
    ]
  }
}
```

### 12.2 纯 RAG 验证

```json
{
  "conversationId": "rag-verify-001",
  "toolsEnabled": false,
  "messages": [
    {
      "role": "user",
      "content": "请根据资料说明 citations 的返回方式。"
    }
  ],
  "ragConfig": {
    "enabled": true,
    "sourceIds": [
      "uploads/2026-04-23/rag-demo-a.txt",
      "uploads/2026-04-23/rag-demo-b.txt"
    ]
  }
}
```

### 12.3 普通聊天

```json
{
  "conversationId": "plain-chat-001",
  "messages": [
    {
      "role": "user",
      "content": "请简单介绍一下你自己。"
    }
  ]
}
```

## 13. 本期已验证结论

本期后端生产验证已覆盖以下场景：

- RAG 命中时返回 `citations`
- 关闭 RAG 时不返回 `citations`
- 使用无关 `sourceId` 时不返回 `citations`

验证使用的是：

- Chroma `v2`
- SiliconFlow Embedding
- `Qwen/Qwen3-Embedding-8B`

因此，前端可以按本文档中的接法直接对接当前后端。

## 14. 前端对接清单

上线前请逐项确认：

- 上传后已保存 `relativePath`
- 触发过手动索引
- 已轮询到 `COMPLETED`
- 聊天请求已传 `ragConfig.enabled`
- 聊天请求已传正确的 `sourceIds`
- SSE 解析使用 `fetch + stream`
- 正文是拼接 `delta.content`
- `citations` 已从首个有效 chunk 中提取
- 错误态和超时态已做 UI 提示

如果后续后端再扩展字段，请以前端兼容追加字段的方式接入，不要写死只接受固定 JSON 结构。
