## Relic-RAG 模块实现方案

基于 NotebookLM 的自组织 RAG + 联网搜索功能

---

## 一、功能需求分析

### NotebookLM 核心特性参考
| 特性 | 说明 |
|------|------|
| **多源文档上传** | PDF、TXT、Markdown、网页抓取 |
| **自动文档分析** | 生成概览、关键主题、FAQ |
| **智能问答** | 基于文档内容的精准问答，带引用 |
| **多模态输出** | 文本摘要、播客脚本、学习指南 |
| **引用追溯** | 每个回答都标注信息来源 |

### Relic-RAG 目标功能
1. **联网搜索**：实时搜索互联网内容
2. **自组织知识库**：自动解析、索引文档
3. **智能检索**：向量检索 + 关键词检索混合
4. **增强生成**：基于检索结果生成答案并引用来源
5. **来源追溯**：每个答案标注引用的文档片段

---

## 二、推荐技术选型

### 核心依赖
```xml
<!-- 向量数据库 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-redis-store</artifactId>
</dependency>

<!-- 或使用 Milvus/Pinecone -->
<dependency>
    <groupId>io.milvus</groupId>
    <artifactId>milvus-sdk-java</artifactId>
</dependency>

<!-- 文档解析 -->
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
</dependency>

<!-- 网页抓取 -->
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
</dependency>

<!-- Embedding 模型 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
</dependency>
```

---

## 三、文件架构设计

```
relic-rag/
├── src/main/java/com/relic/relicrag/
│   ├── RelicRagApplication.java           # 启动类
│   ├── config/                            # 配置类
│   │   ├── VectorStoreConfig.java          # 向量数据库配置
│   │   ├── EmbeddingConfig.java           # Embedding 模型配置
│   │   ├── SearchConfig.java              # 搜索配置
│   │   └── RedisConfig.java              # Redis 缓存配置
│   │
│   ├── controller/                        # 控制器层
│   │   ├── RagController.java             # RAG 主控制器
│   │   ├── DocumentController.java         # 文档管理控制器
│   │   ├── SearchController.java          # 搜索控制器
│   │   └── KnowledgeBaseController.java   # 知识库管理控制器
│   │
│   ├── service/                           # 服务层
│   │   ├── RagService.java                # RAG 核心服务
│   │   ├── DocumentService.java           # 文档处理服务
│   │   ├── SearchService.java             # 网络搜索服务
│   │   ├── EmbeddingService.java          # Embedding 生成服务
│   │   ├── RetrievalService.java          # 检索服务
│   │   ├── KnowledgeBaseService.java      # 知识库管理服务
│   │   ├── CitationService.java           # 引用归因服务
│   │   └── OrganizerService.java          # 自组织服务（文档分析）
│   │
│   ├── service/impl/                      # 服务实现
│   │   ├── RagServiceImpl.java
│   │   ├── DocumentServiceImpl.java
│   │   ├── SearchServiceImpl.java
│   │   ├── EmbeddingServiceImpl.java
│   │   ├── RetrievalServiceImpl.java
│   │   ├── KnowledgeBaseServiceImpl.java
│   │   ├── CitationServiceImpl.java
│   │   └── OrganizerServiceImpl.java
│   │
│   ├── parser/                            # 文档解析器
│   │   ├── DocumentParser.java           # 解析器接口
│   │   ├── PdfParser.java                # PDF 解析
│   │   ├── MarkdownParser.java           # Markdown 解析
│   │   ├── HtmlParser.java               # HTML 解析
│   │   ├── WebParser.java                # 网页解析
│   │   └── TikaUniversalParser.java     # Tika 通用解析
│   │
│   ├── chunker/                           # 文档分块器
│   │   ├── TextChunker.java             # 分块器接口
│   │   ├── FixedSizeChunker.java         # 固定大小分块
│   │   ├── SemanticChunker.java          # 语义分块
│   │   └── HybridChunker.java            # 混合分块
│   │
│   ├── retrieval/                         # 检索策略
│   │   ├── RetrievalStrategy.java        # 检索策略接口
│   │   ├── VectorRetrieval.java         # 向量检索
│   │   ├── KeywordRetrieval.java        # 关键词检索
│   │   ├── HybridRetrieval.java         # 混合检索
│   │   └── Reranker.java                # 重排序器
│   │
│   ├── websearch/                         # 网络搜索模块
│   │   ├── WebSearchEngine.java          # 搜索引擎接口
│   │   ├── GoogleSearchEngine.java       # Google 搜索
│   │   ├── BingSearchEngine.java        # Bing 搜索
│   │   ├── DuckDuckGoSearchEngine.java  # DuckDuckGo 搜索
│   │   ├── SearchResultParser.java       # 搜索结果解析
│   │   └── WebContentExtractor.java     # 网页内容提取
│   │
│   ├── model/                            # 数据模型
│   │   ├── entity/
│   │   │   ├── Document.java            # 文档实体
│   │   │   ├── DocumentChunk.java       # 文档分块实体
│   │   │   ├── KnowledgeBase.java       # 知识库实体
│   │   │   ├── Citation.java            # 引用实体
│   │   │   └── SearchResult.java        # 搜索结果实体
│   │   ├── dto/
│   │   │   ├── RagRequest.java          # RAG 请求
│   │   │   ├── RagResponse.java         # RAG 响应（带引用）
│   │   │   ├── DocumentUploadRequest.java
│   │   │   ├── SearchRequest.java
│   │   │   ├── KnowledgeBaseCreateRequest.java
│   │   │   └── OrganizedSummary.java    # 自组织摘要
│   │   └── vo/
│   │       ├── DocumentVO.java
│   │       ├── ChunkVO.java
│   │       └── SearchResultVO.java
│   │
│   ├── repository/                        # 数据访问层
│   │   ├── DocumentRepository.java
│   │   ├── ChunkRepository.java
│   │   ├── KnowledgeBaseRepository.java
│   │   └── VectorRepository.java        # 向量存储访问
│   │
│   ├── vectorstore/                       # 向量存储抽象
│   │   ├── VectorStore.java             # 向量存储接口
│   │   ├── RedisVectorStore.java        # Redis 实现
│   │   ├── MilvusVectorStore.java       # Milvus 实现
│   │   └── InMemoryVectorStore.java     # 内存实现（开发用）
│   │
│   ├── embedding/                         # Embedding 抽象
│   │   ├── EmbeddingModel.java          # Embedding 接口
│   │   ├── OpenAIEmbedding.java         # OpenAI Embedding
│   │   ├── DeepSeekEmbedding.java       # DeepSeek Embedding
│   │   └── LocalEmbedding.java          # 本地模型 Embedding
│   │
│   ├── client/                           # 外部客户端
│   │   ├── OpenAIClient.java
│   │   ├── DeepSeekClient.java
│   │   ├── GoogleSearchClient.java
│   │   └── VectorStoreClient.java
│   │
│   ├── integration/                       # 集成层（与 relic-core 交互）
│   │   ├── RagAiProvider.java           # 实现 AiProvider 接口
│   │   ├── RagToolExecutor.java         # RAG 工具执行器
│   │   └── RelicCoreClient.java        # 与 core 通信的客户端
│   │
│   ├── exception/                         # 异常处理
│   │   ├── RagException.java
│   │   ├── DocumentParseException.java
│   │   └── SearchException.java
│   │
│   ├── util/                             # 工具类
│   │   ├── TextCleaner.java
│   │   ├── URLValidator.java
│   │   ├── CitationFormatter.java
│   │   └── RetryUtil.java
│   │
│   └── constant/                         # 常量定义
│       ├── RagConstants.java
│       ├── EmbeddingConstants.java
│       └── SearchConstants.java
│
├── src/main/resources/
│   ├── application.yaml                   # 主配置文件
│   ├── application-dev.yaml              # 开发环境配置
│   ├── application-prod.yaml             # 生产环境配置
│   ├── prompts/
│   │   ├── rag-system.prompt             # RAG 系统提示词
│   │   ├── citation.prompt               # 引用生成提示词
│   │   └── summary.prompt                # 摘要生成提示词
│   └── static/
│       └── docs/                         # 文档模板
│
├── src/test/java/                        # 测试
│   ├── service/
│   │   ├── RagServiceTest.java
│   │   └── RetrievalServiceTest.java
│   └── integration/
│       └── RagIntegrationTest.java
│
├── docker/                              # Docker 配置
│   ├── Dockerfile
│   └── docker-compose.yml               # 包含 Redis/Milvus
│
└── doc/                                 # 文档
    ├── api.md                           # API 文档
    ├── architecture.md                   # 架构设计
    └── integration.md                   # 集成指南
```

---

## 四、核心服务设计

### 1. RagService 核心流程

```java
// RAG 服务主流程
public class RagServiceImpl implements RagService {
    
    @Override
    public RagResponse query(RagRequest request) {
        // 1. 查询扩展（同义词、重写）
        String expandedQuery = expandQuery(request.getQuery());
        
        // 2. 检索策略选择
        List<RetrievalStrategy> strategies = getStrategies(request);
        
        // 3. 多路检索（知识库 + 网络搜索）
        RetrievalResult kbResult = retrieveFromKnowledgeBase(expandedQuery);
        RetrievalResult webResult = webSearch ? searchWeb(expandedQuery) : null;
        
        // 4. 结果融合与重排序
        List<DocumentChunk> merged = mergeAndRerank(kbResult, webResult);
        
        // 5. 构建上下文
        String context = buildContext(merged);
        
        // 6. 调用 LLM 生成答案
        String answer = generateAnswer(request.getQuery(), context);
        
        // 7. 提取引用
        List<Citation> citations = extractCitations(merged);
        
        return RagResponse.builder()
            .answer(answer)
            .citations(citations)
            .sources(merged.stream().map(DocumentChunk::getSource).toList())
            .build();
    }
}
```

### 2. DocumentService 文档处理流程

```java
// 文档处理服务流程
public class DocumentServiceImpl implements DocumentService {
    
    @Override
    public Document processAndIndex(File file) {
        // 1. 解析文档内容
        String content = parser.parse(file);
        
        // 2. 清洗和预处理
        String cleaned = textCleaner.clean(content);
        
        // 3. 文档分块
        List<DocumentChunk> chunks = chunker.chunk(cleaned);
        
        // 4. 生成 Embedding
        for (DocumentChunk chunk : chunks) {
            float[] embedding = embeddingModel.embed(chunk.getContent());
            chunk.setEmbedding(embedding);
        }
        
        // 5. 存储到向量数据库
        vectorStore.insert(chunks);
        
        // 6. 元数据存储
        documentRepository.save(document);
        
        return document;
    }
    
    @Override
    public Document processAndIndexUrl(String url) {
        // 1. 抓取网页内容
        String content = webContentExtractor.extract(url);
        
        // 2. 后续流程与文件处理相同
        return processContent(url, content);
    }
}
```

### 3. SearchService 网络搜索

```java
// 网络搜索服务
public class SearchServiceImpl implements SearchService {
    
    @Override
    public List<SearchResult> search(String query) {
        // 1. 调用搜索引擎 API
        List<SearchResult> results = searchEngine.search(query);
        
        // 2. 抓取搜索结果页面内容
        for (SearchResult result : results) {
            String content = webContentExtractor.extract(result.getUrl());
            result.setContent(content);
            
            // 3. 内容分块和向量化
            List<DocumentChunk> chunks = chunker.chunk(content);
            float[] embedding = embeddingModel.embed(content);
            
            // 4. 临时存储（可选持久化到知识库）
            vectorStore.insert(chunks);
        }
        
        return results;
    }
}
```

### 4. OrganizerService 自组织功能

```java
// 类似 NotebookLM 的文档自组织
public class OrganizerServiceImpl implements OrganizerService {
    
    @Override
    public OrganizedSummary organizeKnowledgeBase(String kbId) {
        KnowledgeBase kb = knowledgeBaseService.getById(kbId);
        
        // 1. 生成文档概览
        String overview = generateOverview(kb.getDocuments());
        
        // 2. 提取关键主题
        List<String> topics = extractTopics(kb.getDocuments());
        
        // 3. 生成常见问题
        List<FAQ> faqs = generateFAQs(kb.getDocuments());
        
        // 4. 生成学习指南/播客脚本
        String guide = generateStudyGuide(kb.getDocuments());
        
        return OrganizedSummary.builder()
            .overview(overview)
            .topics(topics)
            .faqs(faqs)
            .studyGuide(guide)
            .build();
    }
}
```

---

## 五、作为微服务集成方案

### 方案架构

```
┌─────────────────────────────────────────────────────────┐
│                    Relic Gateway (18789)              │
│                 WebSocket + OpenClaw SDK               │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│                  Relic Core (8082)                    │
│         AiRouterService + ToolCallService              │
│  ┌─────────────────────────────────────────────────┐   │
│  │  AiProvider 实现:                             │   │
│  │  - DeepSeekService                            │   │
│  │  - KimiService                                │   │
│  │  - OllamaLocalService                         │   │
│  │  - RagAiProvider (新增) ◄──────────────────┐  │   │
│  └───────────────────────────────────────────────┼──┘   │
└────────────────────────────────────────────────────┼─────┘
                                                     │ HTTP/gRPC
                                                     ▼
┌─────────────────────────────────────────────────────────┐
│                Relic RAG (8083)                      │
│         RAG + 知识库 + 网络搜索                      │
│  ┌─────────────────────────────────────────────────┐   │
│  │  DocumentService  - 文档解析和索引             │   │
│  │  SearchService    - 网络搜索                   │   │
│  │  RagService       - RAG 核心逻辑               │   │
│  │  OrganizerService- 自组织分析                   │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│             向量数据库 (Redis/Milvus/Pinecone)         │
└─────────────────────────────────────────────────────────┘
```

### 集成方式

#### 方案 A：HTTP REST API 集成（推荐）

**Relic-Core 新增组件：**
```
relic-core/
├── service/
│   ├── RagAiProvider.java          # 实现 AiProvider 接口
│   └── RagClient.java             # HTTP 客户端调用 RAG 服务
└── dto/
    └── RagRequest.java
```

**RagAiProvider 实现：**
```java
@Service
public class RagAiProvider implements AiProvider {
    
    @Autowired
    private RagClient ragClient;
    
    @Value("${relic.rag.endpoint:http://localhost:8083}")
    private String ragEndpoint;
    
    @Override
    public String getName() {
        return "rag";
    }
    
    @Override
    public String ask(String prompt) {
        RagResponse response = ragClient.query(
            RagRequest.builder()
                .query(prompt)
                .useWebSearch(true)
                .topK(5)
                .build()
        );
        return response.getAnswer();
    }
    
    @Override
    public ToolCallResult askWithTools(...) {
        // RAG 模式下，直接返回检索增强的结果
        String answer = ask(prompt);
        return ToolCallResult.textOnly(answer);
    }
}
```

**路由集成：**
在 `AiRouterService.java` 中新增 RAG 路径：
```java
public enum RoutePath {
    TOOL_FIRST,  // 工具优先
    FAST,        // 快速回复
    DEEP,        // 深度分析
    RAG          // 知识检索（新增）
}
```

#### 方案 B：gRPC 高性能集成（高并发场景）

**优势：**
- 更低的通信开销
- 更高的并发性能
- 强类型接口

**需要工作：**
1. 定义 `.proto` 文件
2. 使用 gRPC 生成 Java 代码
3. relic-rag 提供 gRPC 服务
4. relic-core 通过 gRPC 客户端调用

#### 方案 C：消息队列异步集成

**适用场景：**
- 文档上传和索引（异步）
- 知识库构建任务
- 后台分析任务

**流程：**
```
用户上传文档 → relic-core → 消息队列 → relic-rag 异步处理
```

---

## 六、详细实现步骤

### 第一阶段：基础架构搭建

1. **更新 pom.xml 依赖**
   - 添加 Spring AI / LangChain4j
   - 添加 Redis / Milvus 客户端
   - 添加 Tika / Jsoup

2. **配置文件设计**
   ```yaml
   relic:
     rag:
       endpoint: http://localhost:8083
       embedding:
         provider: openai  # openai | deepseek | local
         model: text-embedding-3-small
       vector-store:
         provider: redis  # redis | milvus | pinecone
         host: localhost
         port: 6379
         index-name: relic-rag
       search:
         enable-web-search: true
         engine: duckduckgo  # google | bing | duckduckgo
         max-results: 10
       retrieval:
         top-k: 5
         score-threshold: 0.7
         chunk-size: 512
         chunk-overlap: 50
   ```

3. **创建基础目录结构**
   - 按上述文件架构创建目录
   - 创建接口和基础类

### 第二阶段：文档处理模块

1. **实现文档解析器**
   - TikaUniversalParser（通用）
   - PdfParser（PDF 专用）
   - MarkdownParser（Markdown 专用）

2. **实现文档分块器**
   - FixedSizeChunker（简单版）
   - SemanticChunker（语义分块，需要 Embedding）

3. **实现 Embedding 服务**
   - OpenAI / DeepSeek API 调用
   - 批量 Embedding 优化

### 第三阶段：向量存储与检索

1. **向量存储抽象**
   - VectorStore 接口
   - RedisVectorStore 实现

2. **检索策略**
   - 向量检索（余弦相似度）
   - 混合检索（向量 + BM25）
   - 重排序器（Cross-Encoder）

### 第四阶段：网络搜索

1. **搜索引擎集成**
   - DuckDuckGo API（免费）
   - Google Custom Search API
   - Bing Search API

2. **内容提取**
   - Jsoup 抓取网页
   - 内容清洗和去噪

### 第五阶段：RAG 核心功能

1. **RagService 实现**
   - 查询扩展
   - 多路检索
   - 结果融合
   - 上下文构建
   - 答案生成

2. **引用归因**
   - CitationFormatter
   - 来源追溯

### 第六阶段：自组织功能

1. **OrganizerService 实现**
   - 文档概览生成
   - 主题提取
   - FAQ 生成
   - 学习指南生成

### 第七阶段：微服务集成

1. **创建 RagAiProvider**
2. **集成到 AiRouterService**
3. **API 接口定义**
4. **测试联调**

---

## 七、需要做的工作清单

### 开发工作

| 优先级 | 任务 | 预估工作量 |
|--------|------|-----------|
| P0 | 文档解析和分块 | 3-5 天 |
| P0 | Embedding 服务 | 2-3 天 |
| P0 | 向量存储集成 | 3-4 天 |
| P0 | 基础检索功能 | 3-4 天 |
| P1 | 网络搜索 | 2-3 天 |
| P1 | RAG 核心流程 | 4-5 天 |
| P1 | 引用归因 | 2-3 天 |
| P2 | 自组织功能 | 5-7 天 |
| P2 | 微服务集成 | 2-3 天 |
| P2 | 性能优化 | 3-5 天 |

### 非开发工作

1. **向量数据库选型和部署**
   - Redis Stack（轻量）
   - Milvus（功能丰富）
   - Pinecone（托管服务）

2. **API 密钥获取**
   - OpenAI Embedding API
   - 搜索引擎 API

3. **测试数据准备**
   - 测试文档
   - 测试查询集

4. **前端界面**
   - 文档上传界面
   - 知识库管理界面
   - 搜索结果展示

---

## 八、配置说明

### application.yaml 示例

```yaml
server:
  port: 8083

spring:
  application:
    name: relic-rag
  data:
    redis:
      host: localhost
      port: 6379
      password:
      database: 0

relic:
  rag:
    workspace: ${user.home}/.openclaw/rag-workspace
    embedding:
      provider: openai
      model: text-embedding-3-small
      api-key: ${OPENAI_API_KEY}
    vector-store:
      provider: redis
      dimension: 1536
      index-name: relic-rag-index
    search:
      enabled: true
      engine: duckduckgo
      max-results: 5
      timeout-seconds: 30
    retrieval:
      chunk-size: 512
      chunk-overlap: 50
      top-k: 5
      score-threshold: 0.7
      use-rerank: true
    organization:
      auto-organize: true
      organize-after-upload: false
```

---

## 九、API 接口设计

```
# 文档管理
POST   /api/documents/upload              # 上传文档
GET    /api/documents/{id}                # 获取文档信息
DELETE /api/documents/{id}                # 删除文档
POST   /api/documents/url                 # 添加 URL

# 知识库管理
POST   /api/knowledge-bases              # 创建知识库
GET    /api/knowledge-bases/{id}         # 获取知识库
GET    /api/knowledge-bases/{id}/docs    # 获取知识库文档
POST   /api/knowledge-bases/{id}/organize # 自组织分析

# RAG 查询
POST   /api/rag/query                   # RAG 查询
GET    /api/rag/citations/{id}           # 获取引用详情

# 网络搜索
POST   /api/search                       # 网络搜索
GET    /api/search/status                # 搜索状态

# 健康检查
GET    /actuator/health
GET    /actuator/metrics
```

---

这个方案提供了一个完整的实现路径，你可以根据实际需求优先实现核心功能（文档处理 + 基础 RAG），然后逐步扩展到网络搜索和自组织功能。微服务集成可以通过 HTTP API 简单实现，也可以根据性能需求升级到 gRPC。