# RAG 聊天接口协议

## 1. 接口信息
- 方法: `POST`
- 路径: `/v1/chat/completions`
- Content-Type: `application/json`
- 响应格式: `text/event-stream` (SSE)

## 2. 请求正文

### 2.1 字段说明
- `conversationId`: 字符串, 可选
- `messages`: 数组, 必填
- `ragConfig`: 对象, 可选
  - `enabled`: 布尔值, 可选
  - `sourceIds`: 字符串数组, 可选

### 2.2 示例 (启用 RAG)

```json
{
  "conversationId": "conv-demo-001",
  "messages": [
    {"role": "user", "content": "请总结这两份文档的关键结论"}
  ],
  "ragConfig": {
    "enabled": true,
    "sourceIds": [
      "uploads/2026-04-12/report-a.pdf",
      "uploads/2026-04-12/report-b.docx"
    ]
  }
}
```

### 2.3 示例 (禁用 RAG / 省略)

```json
{
  "conversationId": "conv-demo-002",
  "messages": [
    {"role": "user", "content": "你好"}
  ]
}
```

## 3. SSE 响应协议

### 3.1 标准数据块 (兼容模式)

```json
{
  "id": "chatcmpl-1712900000000",
  "object": "chat.completion.chunk",
  "created": 1712900000,
  "model": "deepseek",
  "choices": [
    {
      "index": 0,
      "delta": {"content": "这是流式输出片段"},
      "finish_reason": null
    }
  ]
}
```

### 3.2 包含引用信息的首块数据 (仅限 RAG 命中时)

```json
{
  "id": "chatcmpl-1712900000000",
  "object": "chat.completion.chunk",
  "created": 1712900000,
  "model": "deepseek",
  "citations": [
    {
      "id": "1",
      "sourceId": "uploads/2026-04-12/report-a.pdf",
      "snippet": "......"
    },
    {
      "id": "2",
      "sourceId": "uploads/2026-04-12/report-b.docx",
      "snippet": "......"
    }
  ],
  "choices": [
    {
      "index": 0,
      "delta": {"content": "根据资料"},
      "finish_reason": null
    }
  ]
}
```

### 3.3 结束块

```json
{
  "id": "chatcmpl-1712900000000",
  "object": "chat.completion.chunk",
  "created": 1712900000,
  "model": "deepseek",
  "choices": [
    {
      "index": 0,
      "delta": {},
      "finish_reason": "stop"
    }
  ]
}
```

最终 SSE 事件保持不变:

```text
[DONE]
```

## 4. 降级策略
- 如果 `ragConfig.enabled != true`: 不进行检索，不返回 `citations` 字段。
- 如果 `sourceIds` 为空: 不进行检索，不返回 `citations` 字段。
- 如果检索抛出异常: 记录警告日志，继续普通对话，不返回 `citations` 字段。
- 在所有情况下，SSE 格式均保持 OpenAI 兼容性。
