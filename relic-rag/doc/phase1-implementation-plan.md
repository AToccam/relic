# Relic-RAG 第一版实现计划

## 概述

本文档描述 Relic-RAG 模块第一版的详细实现计划，目标是支持用户上传 MD 和 TXT 文件，并基于这些文件进行 RAG 查询。

**版本范围：** Phase 1 - 基础文档上传与查询

**支持格式：** Markdown (.md) 和纯文本 (.txt)

---

## 技术选型

### 依赖项

```xml
<!-- 向量存储 - 使用 Spring AI Redis Vector Store -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-redis-store-spring-boot-starter</artifactId>
    <version>1.0.0-M5</version>
</dependency>

<!-- Embedding - 使用 OpenAI 或 DeepSeek -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
    <version>1.0.0-M5</version>
</dependency>

<!-- 或者使用 DeepSeek -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-deepseek-spring-boot-starter</artifactId>
    <version>1.0.0-M5</version>
</dependency>

<!-- 文件上传 -->
<dependency>
    <groupId>commons-io</groupId>
    <artifactId>commons-io</artifactId>
    <version>2.17.0</version>
</dependency>

<!-- Markdown 解析 -->
<dependency>
    <groupId>com.vladsch.flexmark</groupId>
    <artifactId>flexmark-all</artifactId>
    <version>0.64.8</version>
</dependency>

<!-- 可选：本地 Embedding 模型（Jina） -->
<dependency>
    <groupId>ai.djl</groupId>
    <artifactId>api</artifactId>
    <version>0.30.0</version>
</dependency>
```

---

## 文件架构（第一版）

```
relic-rag/
├── src/main/java/com/relic/relicrag/
│   ├── RelicRagApplication.java
│   │
│   ├── config/
│   │   └── RagConfig.java                      # RAG 配置类
│   │
│   ├── controller/
│   │   ├── DocumentController.java               # 文档上传/管理
│   │   └── RagController.java                   # RAG 查询
│   │
│   ├── service/
│   │   ├── DocumentService.java                  # 文档处理接口
│   │   ├── EmbeddingService.java                 # Embedding 生成接口
│   │   ├── VectorStoreService.java               # 向量存储接口
│   │   ├── RagService.java                      # RAG 核心接口
│   │   └── ChunkService.java                    # 文本分块接口
│   │
│   ├── service/impl/
│   │   ├── DocumentServiceImpl.java
│   │   ├── OpenAIEmbeddingServiceImpl.java        # 或 DeepSeekEmbeddingServiceImpl
│   │   ├── RedisVectorStoreServiceImpl.java
│   │   ├── RagServiceImpl.java
│   │   └── SimpleChunkServiceImpl.java
│   │
│   ├── parser/
│   │   ├── DocumentParser.java                 # 解析器接口
│   │   ├── MarkdownParser.java                 # MD 解析器
│   │   └── TextParser.java                     # TXT 解析器
│   │
│   ├── chunker/
│   │   ├── TextChunker.java                    # 分块器接口
│   │   └── FixedSizeChunker.java               # 固定大小分块
│   │
│   ├── model/
│   │   ├── entity/
│   │   │   └── Document.java                   # 文档实体
│   │   └── dto/
│   │       ├── DocumentUploadResponse.java
│   │       ├── RagRequest.java
│   │       └── RagResponse.java
│   │
│   ├── exception/
│   │   └── RagException.java
│   │
│   └── util/
│       ├── FileUtil.java
│       └── TextCleaner.java
│
├── src/main/resources/
│   ├── application.yaml                        # 配置文件
│   └── prompts/
│       └── rag-system.prompt                    # RAG 系统提示词
│
└── doc/
    └── phase1-implementation-plan.md           # 本文档
```

---

## 分阶段实现计划

---

## 第一阶段：项目基础搭建（预计 1 天）

### 目标
- 更新 Maven 依赖
- 创建基础目录结构
- 配置文件编写

### 任务清单

#### 1.1 更新 pom.xml
```xml
<dependencies>
    <!-- Spring Boot Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Spring AI -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
        <version>1.0.0-M5</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-redis-store-spring-boot-starter</artifactId>
        <version>1.0.0-M5</version>
    </dependency>

    <!-- 文件处理 -->
    <dependency>
        <groupId>commons-io</groupId>
        <artifactId>commons-io</artifactId>
        <version>2.17.0</version>
    </dependency>
    <dependency>
        <groupId>com.vladsch.flexmark</groupId>
        <artifactId>flexmark-all</artifactId>
        <version>0.64.8</version>
    </dependency>

    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

#### 1.2 创建目录结构
创建以下包结构：
- `config/`
- `controller/`
- `service/`
- `service/impl/`
- `parser/`
- `chunker/`
- `model/entity/`
- `model/dto/`
- `exception/`
- `util/`

#### 1.3 编写 application.yaml
```yaml
server:
  port: 8083

spring:
  application:
    name: relic-rag
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
  data:
    redis:
      host: localhost
      port: 6379

# Spring AI 配置
spring.ai:
  openai:
    api-key: ${OPENAI_API_KEY:your-api-key}
    embedding:
      options:
        model: text-embedding-3-small
    chat:
      options:
        model: gpt-4o-mini
  vectorstore:
    redis:
      index-name: relic-rag-index
      prefix: doc:

relic:
  rag:
    workspace: ${user.home}/.openclaw/rag-workspace
    chunk:
      size: 512
      overlap: 50
    retrieval:
      top-k: 5
      score-threshold: 0.7
```

---

## 第二阶段：数据模型设计（预计 0.5 天）

### 目标
- 定义核心实体类
- 定义 DTO 类

### 任务清单

#### 2.1 创建实体类

**model/entity/Document.java**
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {
    private String id;                  // 文档 ID
    private String filename;             // 原始文件名
    private String fileType;             // 文件类型 (md/txt)
    private long fileSize;              // 文件大小
    private String content;              // 完整内容（不存储大文件）
    private int chunkCount;             // 分块数量
    private LocalDateTime createdAt;     // 创建时间
    private LocalDateTime updatedAt;     // 更新时间
    private String status;              // 处理状态 (processing/indexed/failed)
}
```

#### 2.2 创建 DTO 类

**model/dto/RagRequest.java**
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagRequest {
    private String query;               // 查询问题
    private Integer topK;              // 返回的相关文档数量
    private Double scoreThreshold;     // 相似度阈值
}
```

**model/dto/RagResponse.java**
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagResponse {
    private String answer;             // 生成的答案
    private List<Citation> citations;   // 引用来源
    private int totalChunks;          // 检索到的总块数

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Citation {
        private String documentId;     // 文档 ID
        private String filename;       // 文件名
        private String content;        // 引用的内容片段
        private Double score;         // 相似度分数
    }
}
```

**model/dto/DocumentUploadResponse.java**
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadResponse {
    private String documentId;
    private String filename;
    private String status;
    private int chunkCount;
    private String message;
}
```

---

## 第三阶段：文档解析模块（预计 1 天）

### 目标
- 实现文档解析器接口
- 实现 Markdown 解析器
- 实现纯文本解析器

### 任务清单

#### 3.1 定义解析器接口

**parser/DocumentParser.java**
```java
public interface DocumentParser {
    /**
     * 判断是否支持该文件类型
     */
    boolean supports(String filename);

    /**
     * 解析文件内容
     */
    String parse(InputStream inputStream, String filename) throws IOException;

    /**
     * 提取元数据
     */
    Map<String, Object> extractMetadata(InputStream inputStream, String filename) throws IOException;
}
```

#### 3.2 实现 Markdown 解析器

**parser/MarkdownParser.java**
```java
@Component
public class MarkdownParser implements DocumentParser {

    @Override
    public boolean supports(String filename) {
        return filename.toLowerCase().endsWith(".md");
    }

    @Override
    public String parse(InputStream inputStream, String filename) throws IOException {
        // 使用 commons-io 读取文件
        String content = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        // 使用 Flexmark 进行 Markdown 清洗（可选）
        return cleanMarkdown(content);
    }

    @Override
    public Map<String, Object> extractMetadata(InputStream inputStream, String filename) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "markdown");
        metadata.put("filename", filename);
        // 可以提取 Markdown frontmatter
        return metadata;
    }

    private String cleanMarkdown(String markdown) {
        // 去除过多的空行
        return markdown.replaceAll("\n{3,}", "\n\n").trim();
    }
}
```

#### 3.3 实现纯文本解析器

**parser/TextParser.java**
```java
@Component
public class TextParser implements DocumentParser {

    @Override
    public boolean supports(String filename) {
        return filename.toLowerCase().endsWith(".txt");
    }

    @Override
    public String parse(InputStream inputStream, String filename) throws IOException {
        return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
    }

    @Override
    public Map<String, Object> extractMetadata(InputStream inputStream, String filename) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "text");
        metadata.put("filename", filename);
        return metadata;
    }
}
```

#### 3.4 创建解析器工厂

**parser/DocumentParserFactory.java**
```java
@Component
public class DocumentParserFactory {

    private final List<DocumentParser> parsers;

    public DocumentParserFactory(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    public DocumentParser getParser(String filename) {
        return parsers.stream()
                .filter(p -> p.supports(filename))
                .findFirst()
                .orElseThrow(() -> new RagException("不支持的文件类型: " + filename));
    }
}
```

---

## 第四阶段：文本分块模块（预计 1 天）

### 目标
- 实现文本分块接口
- 实现固定大小分块器
- 集成分块服务

### 任务清单

#### 4.1 定义分块器接口

**chunker/TextChunker.java**
```java
public interface TextChunker {
    /**
     * 将文本分块
     */
    List<Chunk> chunk(String text);

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Chunk {
        private String content;        // 分块内容
        private int startIndex;       // 在原文中的起始位置
        private int endIndex;         // 在原文中的结束位置
        private Map<String, Object> metadata; // 元数据
    }
}
```

#### 4.2 实现固定大小分块器

**chunker/FixedSizeChunker.java**
```java
@Component
public class FixedSizeChunker implements TextChunker {

    @Value("${relic.rag.chunk.size:512}")
    private int chunkSize;

    @Value("${relic.rag.chunk.overlap:50}")
    private int overlap;

    @Override
    public List<Chunk> chunk(String text) {
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        int chunkIndex = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String chunkContent = text.substring(start, end);

            // 如果不是最后一个块，尝试在句子边界分割
            if (end < text.length()) {
                int lastPeriod = chunkContent.lastIndexOf(".");
                int lastNewline = chunkContent.lastIndexOf("\n");
                int boundary = Math.max(lastPeriod, lastNewline);

                if (boundary > chunkSize / 2) {
                    end = start + boundary + 1;
                    chunkContent = text.substring(start, end);
                }
            }

            Chunk chunk = Chunk.builder()
                    .content(chunkContent.trim())
                    .startIndex(start)
                    .endIndex(end)
                    .metadata(Map.of("chunkIndex", chunkIndex++))
                    .build();

            chunks.add(chunk);
            start = end - overlap;
        }

        return chunks;
    }
}
```

#### 4.3 创建分块服务

**service/ChunkService.java**
```java
public interface ChunkService {
    List<Chunk> chunk(String text, String documentId, String filename);
}
```

**service/impl/SimpleChunkServiceImpl.java**
```java
@Service
public class SimpleChunkServiceImpl implements ChunkService {

    private final TextChunker chunker;

    public SimpleChunkServiceImpl(TextChunker chunker) {
        this.chunker = chunker;
    }

    @Override
    public List<Chunk> chunk(String text, String documentId, String filename) {
        List<TextChunker.Chunk> rawChunks = chunker.chunk(text);

        return rawChunks.stream()
                .map(c -> Chunk.builder()
                        .id(UUID.randomUUID().toString())
                        .documentId(documentId)
                        .filename(filename)
                        .content(c.getContent())
                        .chunkIndex((Integer) c.getMetadata().get("chunkIndex"))
                        .build())
                .collect(Collectors.toList());
    }
}
```

---

## 第五阶段：Embedding 服务（预计 1 天）

### 目标
- 配置 Spring AI Embedding
- 创建 Embedding 服务接口和实现

### 任务清单

#### 5.1 创建 Embedding 服务接口

**service/EmbeddingService.java**
```java
public interface EmbeddingService {
    /**
     * 生成单个文本的 embedding
     */
    float[] embed(String text);

    /**
     * 批量生成 embedding
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * 获取 embedding 向量维度
     */
    int getDimension();
}
```

#### 5.2 实现 OpenAI Embedding 服务

**service/impl/OpenAIEmbeddingServiceImpl.java**
```java
@Service
public class OpenAIEmbeddingServiceImpl implements EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public OpenAIEmbeddingServiceImpl(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public float[] embed(String text) {
        EmbeddingResponse response = embeddingModel.embedForResponse(List.of(text));
        return List.copyOf(response.getResults()).get(0).getOutput();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        // 分批处理，避免超过 API 限制
        int batchSize = 100;
        List<float[]> embeddings = new ArrayList<>();

        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);
            EmbeddingResponse response = embeddingModel.embedForResponse(batch);
            embeddings.addAll(response.getResults().stream()
                    .map(Embedding::getOutput)
                    .collect(Collectors.toList()));
        }

        return embeddings;
    }

    @Override
    public int getDimension() {
        // text-embedding-3-small 的维度是 1536
        return 1536;
    }
}
```

#### 5.3 创建配置类

**config/RagConfig.java**
```java
@Configuration
public class RagConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        return new OpenAiEmbeddingModel(
            System.getenv("OPENAI_API_KEY"),
            OpenAiEmbeddingOptions.builder()
                    .withModel("text-embedding-3-small")
                    .build()
        );
    }

    @Bean
    public ChatModel chatModel() {
        return new OpenAiChatModel(
            System.getenv("OPENAI_API_KEY"),
            OpenAiChatOptions.builder()
                    .withModel("gpt-4o-mini")
                    .build()
        );
    }
}
```

---

## 第六阶段：向量存储服务（预计 1 天）

### 目标
- 配置 Redis Vector Store
- 创建向量存储服务

### 任务清单

#### 6.1 创建向量存储服务接口

**service/VectorStoreService.java**
```java
public interface VectorStoreService {
    /**
     * 存储文档分块
     */
    void addDocuments(List<Chunk> chunks);

    /**
     * 搜索相似文档
     */
    List<SearchResult> search(String query, int topK, double scoreThreshold);

    /**
     * 删除文档的所有分块
     */
    void deleteDocument(String documentId);

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchResult {
        private String id;
        private String documentId;
        private String filename;
        private String content;
        private double score;
        private Map<String, Object> metadata;
    }
}
```

#### 6.2 实现 Redis 向量存储服务

**service/impl/RedisVectorStoreServiceImpl.java**
```java
@Service
public class RedisVectorStoreServiceImpl implements VectorStoreService {

    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;

    public RedisVectorStoreServiceImpl(VectorStore vectorStore, EmbeddingService embeddingService) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
    }

    @Override
    public void addDocuments(List<Chunk> chunks) {
        List<Document> documents = chunks.stream()
                .map(chunk -> Document.builder()
                        .id(chunk.getId())
                        .text(chunk.getContent())
                        .metadata(buildMetadata(chunk))
                        .build())
                .collect(Collectors.toList());

        vectorStore.add(documents);
    }

    @Override
    public List<SearchResult> search(String query, int topK, double scoreThreshold) {
        float[] queryEmbedding = embeddingService.embed(query);

        List<Document> results = vectorStore.similaritySearch(
            SearchRequest.query(query)
                    .withTopK(topK)
                    .withSimilarityThreshold(scoreThreshold)
        );

        return results.stream()
                .map(this::toSearchResult)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteDocument(String documentId) {
        vectorStore.delete(List.of(documentId));
    }

    private Map<String, Object> buildMetadata(Chunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", chunk.getDocumentId());
        metadata.put("filename", chunk.getFilename());
        metadata.put("chunkIndex", chunk.getChunkIndex());
        return metadata;
    }

    private SearchResult toSearchResult(Document doc) {
        return SearchResult.builder()
                .id(doc.getId())
                .documentId((String) doc.getMetadata().get("documentId"))
                .filename((String) doc.getMetadata().get("filename"))
                .content(doc.getText())
                .score(doc.getScore())
                .metadata(doc.getMetadata())
                .build();
    }
}
```

---

## 第七阶段：文档处理服务（预计 1 天）

### 目标
- 实现文档上传处理
- 集成解析、分块、Embedding、存储流程

### 任务清单

#### 7.1 创建文档服务接口

**service/DocumentService.java**
```java
public interface DocumentService {
    /**
     * 处理上传的文件
     */
    DocumentUploadResponse processFile(MultipartFile file);

    /**
     * 获取文档信息
     */
    Document getDocument(String documentId);

    /**
     * 删除文档
     */
    void deleteDocument(String documentId);

    /**
     * 列出所有文档
     */
    List<Document> listDocuments();
}
```

#### 7.2 实现文档服务

**service/impl/DocumentServiceImpl.java**
```java
@Service
public class DocumentServiceImpl implements DocumentService {

    private final DocumentParserFactory parserFactory;
    private final ChunkService chunkService;
    private final VectorStoreService vectorStoreService;
    private final String workspacePath;

    public DocumentServiceImpl(
            DocumentParserFactory parserFactory,
            ChunkService chunkService,
            VectorStoreService vectorStoreService,
            @Value("${relic.rag.workspace}") String workspacePath) {
        this.parserFactory = parserFactory;
        this.chunkService = chunkService;
        this.vectorStoreService = vectorStoreService;
        this.workspacePath = workspacePath;
    }

    @Override
    public DocumentUploadResponse processFile(MultipartFile file) {
        String filename = file.getOriginalFilename();
        String documentId = UUID.randomUUID().toString();

        try {
            // 1. 解析文档
            DocumentParser parser = parserFactory.getParser(filename);
            String content = parser.parse(file.getInputStream(), filename);

            // 2. 分块
            List<Chunk> chunks = chunkService.chunk(content, documentId, filename);

            // 3. 存储到向量数据库
            vectorStoreService.addDocuments(chunks);

            // 4. 创建文档记录
            Document document = Document.builder()
                    .id(documentId)
                    .filename(filename)
                    .fileType(getFileType(filename))
                    .fileSize(file.getSize())
                    .chunkCount(chunks.size())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .status("indexed")
                    .build();

            return DocumentUploadResponse.builder()
                    .documentId(documentId)
                    .filename(filename)
                    .status("success")
                    .chunkCount(chunks.size())
                    .message("文档处理成功")
                    .build();

        } catch (Exception e) {
            throw new RagException("文档处理失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Document getDocument(String documentId) {
        // 这里可以从数据库获取，第一版简化处理
        return null;
    }

    @Override
    public void deleteDocument(String documentId) {
        vectorStoreService.deleteDocument(documentId);
    }

    @Override
    public List<Document> listDocuments() {
        // 第一版简化处理
        return new ArrayList<>();
    }

    private String getFileType(String filename) {
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }
}
```

---

## 第八阶段：RAG 核心服务（预计 1.5 天）

### 目标
- 实现 RAG 查询流程
- 集成检索和生成

### 任务清单

#### 8.1 创建 RAG 服务接口

**service/RagService.java**
```java
public interface RagService {
    /**
     * RAG 查询
     */
    RagResponse query(RagRequest request);
}
```

#### 8.2 实现 RAG 服务

**service/impl/RagServiceImpl.java**
```java
@Service
public class RagServiceImpl implements RagService {

    private final VectorStoreService vectorStoreService;
    private final ChatModel chatModel;
    private final String systemPrompt;

    public RagServiceImpl(
            VectorStoreService vectorStoreService,
            ChatModel chatModel,
            @Value("classpath:prompts/rag-system.prompt") Resource systemPromptResource) {
        this.vectorStoreService = vectorStoreService;
        this.chatModel = chatModel;
        try {
            this.systemPrompt = new String(systemPromptResource.getContentAsByteArray());
        } catch (IOException e) {
            this.systemPrompt = "你是一个智能助手，请基于提供的上下文回答问题。";
        }
    }

    @Override
    public RagResponse query(RagRequest request) {
        int topK = request.getTopK() != null ? request.getTopK() : 5;
        double threshold = request.getScoreThreshold() != null ? request.getScoreThreshold() : 0.7;

        // 1. 检索相关文档
        List<VectorStoreService.SearchResult> searchResults =
                vectorStoreService.search(request.getQuery(), topK, threshold);

        if (searchResults.isEmpty()) {
            return RagResponse.builder()
                    .answer("未找到相关文档，请尝试其他问题或上传更多文档。")
                    .citations(new ArrayList<>())
                    .totalChunks(0)
                    .build();
        }

        // 2. 构建上下文
        String context = buildContext(searchResults);

        // 3. 生成答案
        String answer = generateAnswer(request.getQuery(), context);

        // 4. 构建引用
        List<RagResponse.Citation> citations = buildCitations(searchResults);

        return RagResponse.builder()
                .answer(answer)
                .citations(citations)
                .totalChunks(searchResults.size())
                .build();
    }

    private String buildContext(List<VectorStoreService.SearchResult> searchResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("上下文信息：\n\n");

        for (int i = 0; i < searchResults.size(); i++) {
            VectorStoreService.SearchResult result = searchResults.get(i);
            sb.append("[来源").append(i + 1).append("] ")
              .append("文件: ").append(result.getFilename())
              .append("\n")
              .append(result.getContent())
              .append("\n\n");
        }

        return sb.toString();
    }

    private String generateAnswer(String query, String context) {
        String prompt = String.format("""
                %s

                问题：%s

                请基于以上上下文回答问题。如果上下文中没有相关信息，请明确说明。
                """, systemPrompt, query);

        return chatModel.call(prompt);
    }

    private List<RagResponse.Citation> buildCitations(
            List<VectorStoreService.SearchResult> searchResults) {
        return searchResults.stream()
                .map(r -> RagResponse.Citation.builder()
                        .documentId(r.getDocumentId())
                        .filename(r.getFilename())
                        .content(r.getContent())
                        .score(r.getScore())
                        .build())
                .collect(Collectors.toList());
    }
}
```

#### 8.3 创建系统提示词文件

**src/main/resources/prompts/rag-system.prompt**
```
你是一个智能文档问答助手。你的职责是：
1. 仔细阅读提供的上下文信息
2. 基于上下文准确回答用户的问题
3. 如果上下文中没有相关信息，明确告知用户
4. 在回答中引用相关的上下文来源
5. 回答要简洁、准确、有条理

注意事项：
- 不要编造上下文中没有的信息
- 如果信息不足，可以指出需要更多信息
- 可以引用多个相关来源
- 保持客观中立的语气
```

---

## 第九阶段：控制器层（预计 0.5 天）

### 目标
- 实现文档上传接口
- 实现 RAG 查询接口
- 异常处理

### 任务清单

#### 9.1 创建文档控制器

**controller/DocumentController.java**
```java
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/upload")
    public ResponseEntity<DocumentUploadResponse> uploadFile(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            throw new RagException("文件不能为空");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".md") && !filename.endsWith(".txt"))) {
            throw new RagException("只支持 .md 和 .txt 文件");
        }

        DocumentUploadResponse response = documentService.processFile(file);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable String documentId) {
        documentService.deleteDocument(documentId);
        return ResponseEntity.ok().build();
    }
}
```

#### 9.2 创建 RAG 控制器

**controller/RagController.java**
```java
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/query")
    public ResponseEntity<RagResponse> query(@RequestBody RagRequest request) {
        if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            throw new RagException("查询不能为空");
        }

        RagResponse response = ragService.query(request);
        return ResponseEntity.ok(response);
    }
}
```

#### 9.3 全局异常处理

**exception/GlobalExceptionHandler.java**
```java
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RagException.class)
    public ResponseEntity<Map<String, Object>> handleRagException(RagException e) {
        return ResponseEntity.badRequest()
                .body(Map.of(
                        "error", e.getMessage(),
                        "timestamp", LocalDateTime.now()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        return ResponseEntity.internalServerError()
                .body(Map.of(
                        "error", "服务器错误: " + e.getMessage(),
                        "timestamp", LocalDateTime.now()
                ));
    }
}
```

---

## 第十阶段：测试与联调（预计 1 天）

### 目标
- 单元测试
- 集成测试
- 与 relic-core 联调

### 任务清单

#### 10.1 编写测试类

**test/DocumentServiceTest.java**
```java
@SpringBootTest
public class DocumentServiceTest {

    @Autowired
    private DocumentService documentService;

    @Test
    public void testUploadMarkdownFile() {
        // 测试 MD 文件上传
    }

    @Test
    public void testUploadTextFile() {
        // 测试 TXT 文件上传
    }
}
```

#### 10.2 编写集成测试

**test/RagIntegrationTest.java**
```java
@SpringBootTest
public class RagIntegrationTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private RagService ragService;

    @Test
    public void testFullWorkflow() {
        // 1. 上传文档
        // 2. 查询
        // 3. 验证结果
    }
}
```

#### 10.3 与 relic-core 集成

创建 RagAiProvider 接入到现有系统（详细设计见文档第七节）。

---

## 测试数据准备

### 示例 Markdown 文档

```
# Spring Boot 简介

Spring Boot 是一个快速开发框架，基于 Spring 框架。

## 核心特性

1. 自动配置
2. 内嵌服务器
3. 生产级监控

## 依赖管理

使用 Maven 或 Gradle 管理依赖。

Maven:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```
```

### 测试查询用例

| 查询 | 期望结果 |
|------|----------|
| Spring Boot 是什么？ | 基于 Spring 的快速开发框架 |
| Spring Boot 有哪些核心特性？ | 自动配置、内嵌服务器、生产级监控 |
| 如何引入 Spring Boot 依赖？ | Maven 或 Gradle 配置 |

---

## API 接口文档

### 1. 上传文档

```
POST /api/documents/upload
Content-Type: multipart/form-data

参数：
- file: 文件对象

响应：
{
  "documentId": "uuid",
  "filename": "test.md",
  "status": "success",
  "chunkCount": 5,
  "message": "文档处理成功"
}
```

### 2. 删除文档

```
DELETE /api/documents/{documentId}

响应：204 No Content
```

### 3. RAG 查询

```
POST /api/rag/query
Content-Type: application/json

请求体：
{
  "query": "Spring Boot 是什么？",
  "topK": 5,
  "scoreThreshold": 0.7
}

响应：
{
  "answer": "Spring Boot 是一个快速开发框架...",
  "citations": [
    {
      "documentId": "uuid",
      "filename": "spring-boot.md",
      "content": "Spring Boot 是一个快速开发框架...",
      "score": 0.89
    }
  ],
  "totalChunks": 3
}
```

---

## 部署要求

### 本地开发环境

1. **Redis** - 向量存储
   ```bash
   docker run -d -p 6379:6379 redis/redis-stack-server:latest
   ```

2. **OpenAI API Key**
   ```bash
   export OPENAI_API_KEY=your-api-key
   ```

3. **启动应用**
   ```bash
   mvn spring-boot:run
   ```

### 生产环境

1. 使用 Redis Cluster 保证高可用
2. 配置 API Key 管理方案
3. 添加监控和日志收集

---

## 后续扩展方向

### Phase 2 - 功能增强
- 支持更多文件格式（PDF、DOCX）
- 支持批量上传
- 文档元数据管理
- 查询历史记录

### Phase 3 - 高级功能
- 混合检索（向量 + 关键词）
- 重排序器
- 联网搜索集成
- 多知识库支持

### Phase 4 - 企业级功能
- 用户认证和权限管理
- 文档版本控制
- 协作功能
- API 限流和监控

---

## 附录：完整文件清单

### Java 文件（共 20 个）

```
config/
  - RagConfig.java

controller/
  - DocumentController.java
  - RagController.java

service/
  - DocumentService.java
  - EmbeddingService.java
  - VectorStoreService.java
  - RagService.java
  - ChunkService.java

service/impl/
  - DocumentServiceImpl.java
  - OpenAIEmbeddingServiceImpl.java
  - RedisVectorStoreServiceImpl.java
  - RagServiceImpl.java
  - SimpleChunkServiceImpl.java

parser/
  - DocumentParser.java
  - DocumentParserFactory.java
  - MarkdownParser.java
  - TextParser.java

chunker/
  - TextChunker.java
  - FixedSizeChunker.java

model/entity/
  - Document.java

model/dto/
  - RagRequest.java
  - RagResponse.java
  - DocumentUploadResponse.java

exception/
  - RagException.java
  - GlobalExceptionHandler.java
```

### 配置文件（共 3 个）

```
src/main/resources/
  - application.yaml
  - prompts/rag-system.prompt
```

---

## 总结

本计划涵盖了 Relic-RAG 第一版的完整实现路径，预计总开发时间为 **9 天**：

| 阶段 | 任务 | 预计时间 |
|------|------|----------|
| 第一阶段 | 项目基础搭建 | 1 天 |
| 第二阶段 | 数据模型设计 | 0.5 天 |
| 第三阶段 | 文档解析模块 | 1 天 |
| 第四阶段 | 文本分块模块 | 1 天 |
| 第五阶段 | Embedding 服务 | 1 天 |
| 第六阶段 | 向量存储服务 | 1 天 |
| 第七阶段 | 文档处理服务 | 1 天 |
| 第八阶段 | RAG 核心服务 | 1.5 天 |
| 第九阶段 | 控制器层 | 0.5 天 |
| 第十阶段 | 测试与联调 | 1 天 |

**总计：9 天**

完成后将具备以下能力：
1. 上传 MD 和 TXT 文件
2. 自动解析、分块、向量化
3. 基于文档内容进行智能问答
4. 提供来源引用

为后续功能扩展奠定坚实基础。
