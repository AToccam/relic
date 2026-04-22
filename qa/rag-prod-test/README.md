# RAG 生产验证资产

这个目录专门存放 RAG 后端生产验证所需的测试资产：

- `input/`: 测试知识库文档
- `relic-core-rag-prodtest.yml`: `relic-core` 的测试覆盖配置
- `run-rag-prod-test.ps1`: 一键执行脚本
- `output/`: 脚本运行时生成的日志、响应和测试报告（已在 `.gitignore` 中忽略）

默认验证链路：

1. 直连 SiliconFlow Embedding API 探测可用性和返回维度
2. 启动 Chroma
3. 启动 `relic-core`
4. 上传测试文档
5. 手动触发索引并轮询完成
6. 执行 RAG 命中、关闭 RAG 对照、未命中降级 三组请求
7. 生成 Markdown 测试报告

执行前需要准备：

- 可用的 `SILICONFLOW_API_KEY`
- 本机可启动 Docker
- `relic-core` 可正常启动

示例：

```powershell
Set-Location D:\Code\JAVA\relic\qa\rag-prod-test
.\run-rag-prod-test.ps1 -SiliconFlowApiKey "sk-xxx"
```
