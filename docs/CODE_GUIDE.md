# 代码学习指南（面试自用）

面向项目作者本人：逐文件讲清 B2/B3 两条核心链路——文档版本管理与 RAG 引用追踪，附高频面试问答。读之前建议先跑一遍 docs/demo-evidence/curl-evidence.txt 里的调用序列，对着真实响应读代码。

## 1. 项目分层总览

    controller（REST）→ service（业务/事务）→ repository（JPA/JdbcTemplate）→ PostgreSQL + pgvector
    前端只有一个 src/main/resources/static/index.html，无构建步骤，直接消费 NDJSON 流。

鉴权是手写的：AuthController 发 token，业务 Controller 用 authService.resolveUser(header) 解析，不依赖 Spring Security 过滤器链——面试可以主动指出这是演示级简化，生产应换过滤器 + 无状态 JWT。

## 2. 版本管理链路（逐文件）

### 2.1 entity/DocumentVersion.java

- 表 kb_document_version，唯一约束 uk_doc_version(document_id, version_no) 是整条链路的并发兜底。
- content_hash CHAR(64)：对归一化全文（统一换行 + trim）算 SHA-256。哈希的是规范化后的内容，避免 CRLF 与 LF 差异造成假新版本。
- storage_key 目前只是逻辑路径 kb/{docId}/v{n}，不存物理文件（见 HANDOFF Backlog）。

### 2.2 repository/DocumentVersionRepository.java

三个方法各有一个用途：

- findTopByDocumentIdOrderByVersionNoDesc：取最新版本号，用于下一次版本号 = max + 1。
- findByDocumentIdOrderByVersionNoAsc：版本列表接口直接返回。
- findByDocumentIdAndVersionNo：版本预览接口定位。

### 2.3 service/DocumentService.java（重点）

uploadDocument 的事务流程，背下来能讲 5 分钟：

    1. 归一化全文 → sha256 → splitText（返回 ChunkData：内容 + 在全文中的起始偏移）
    2. 用 knowledgeBaseId + fileName（忽略大小写）找同名文档；找不到就新建 kb_document_info
    3. 查最新版本：contentHash 相同 → 不入库，直接返回当前版本号（幂等重传）
       contentHash 不同（或首次）→ versionNo = max + 1
    4. save 版本记录，捕获 DataIntegrityViolationException 后重算 max+1 重试一次
       —— 这就是数据库唯一约束兜底并发：两个请求同时读到 max=1，都插 2，
          第二个被唯一约束拒绝，重读 max 得 2 再插 3
    5. 新切片全部挂新 versionId，旧版本切片不动（天然归档，可按版本检索）

面试话术：为什么不用 SELECT ... FOR UPDATE？因为唯一约束是数据库层面的最终真相，重试一次的代价远小于长事务锁竞争；应用层只做乐观分配。

splitText 返回 startOffset 时要小心 trim：先记 rawChunk 的起点，再在 rawChunk 里找 trim 后首字符的索引，两者相加才是切片在全文中的真实偏移。

### 2.4 entity/TextChunk.java 与检索过滤

- 新增 documentVersionId（可空：兼容旧数据）与 charOffset。
- 同一文档多版本共存时，kb_text_chunk 里会有多套切片，检索层负责默认只看最新版。
- findByDocumentIdAndDocumentVersionIdOrderByChunkIndexAsc 供版本预览拼全文。

### 2.5 service/VectorStoreService.java 的版本过滤 SQL

searchSimilarChunks(knowledgeBaseId, query, topK, documentVersionId) 两条路径：

- 传了版本号：LEFT JOIN kb_document_version 后按 tc.document_version_id = ? 精确检索该版本。
- 不传：相关子查询 dv.version_no = (SELECT MAX(version_no) ... 同文档)，只保留每个文档的最新版本切片；UNION ALL 下半段 kb_uploaded_file_chunk 用 NULL::integer 补 version_no/char_offset——UNION ALL 要求列数与类型完全对齐，这是调试时真实踩过的坑（见 B2 失败记录）。

## 3. RAG 引用链路（逐层）

### 3.1 dto/ChunkSearchResult.java 与 dto/RagSource.java

ChunkSearchResult 是检索层产物：chunkId/documentId/chunkIndex/content/distance/versionNo/charOffset。
RagSource 是 API 层产物，额外把 distance 换算成 similarityScore = 1 - distance（pgvector 的 <=> 是余弦距离，0 最相似，所以 1-distance 越大越相似）。
两层分开的意义：检索 DTO 不绑死前端契约；改字段名只动 RagSource，不动 SQL 映射。

### 3.2 service/RagService.java

ask（非流式）与 streamAsk（流式）共享同一检索与上下文构造：

    rewriteQuestionForRetrieval（有历史时用 LLM 改写成独立问题；注意小模型可能改坏，见已知限制）
    → vectorStoreService.searchSimilarChunks(..., documentVersionId)   ← 版本开关透传
    → judgeKnowledgeRelevant（LLM 判断相关性，避免闲聊硬凑引用）
    → buildRagPrompt（buildContext 已带 版本号/字符偏移/距离，引导模型标注 依据：知识片段 x）
    → buildRagSources：chunk → RagSource 一一映射

引用能追踪到文档 + 版本 + 位置的完整链条：chunkId 在 kb_text_chunk，documentVersionId 关联 kb_document_version（版本号/哈希/文件名），charOffset 定位切片在归一化全文中的位置。

### 3.3 controller/RagController.java 的 NDJSON 流式原理

返回 ResponseEntity<StreamingResponseBody>，Content-Type 是 application/x-ndjson：

- Servlet 异步写：lambda 里拿 OutputStream，每写一行 JSON 加换行就 flush，Tomcat 立即推给浏览器。
- 事件类型：sessionId / delta（增量文本）/ sources（引用数组）/ error / done。
- writeStreamEvent 里 synchronized(outputStream)：防止并发写撕行。
- 前端 fetch + ReadableStream 按行 split 解析，sources 事件到达后 renderCollapsibleSources 渲染引用卡片。

面试要点：为什么 NDJSON 而不是 SSE？SSE 需要 text/event-stream 特定格式与重连语义，NDJSON 一行一个 JSON，前端解析最简单；两者本质都是 HTTP 分块传输 + 服务端不关连接。为什么用 StreamingResponseBody 而不是 WebFlux？项目是 Spring MVC，流式只此一处，不值得引入响应式栈。

### 3.4 前端引用卡片（static/index.html）

renderCollapsibleSources：每条引用渲染 文档 ID · 版本 v{n} · 字符偏移 · 相似度 · 距离 的元数据行 + 可展开片段原文；source.versionNo 为 null（旧数据/上传文件切片）时显示 最新。FrontendSourceCardTest 用断言锁住这几个字段名，防止改字段后前端静默失效。

## 4. Embedding 与 pgvector

- EmbeddingService.VECTOR_DIM = 768 必须与列定义 vector(768) 一致；Ollama 返回维度不符直接抛异常——比悄悄截断安全。nomic-embed-text-v1.5 就是 768 维。
- EmbeddingService 用 Redis 缓存同文本向量（key = model + 文本哈希，TTL 7 天），重复构建向量省掉大部分推理。
- initVectorColumn 用 ADD COLUMN IF NOT EXISTS 保证幂等；CREATE EXTENSION IF NOT EXISTS vector 需要 pgvector 镜像（pgvector/pgvector:pg16）。
- 索引：当前数据量小走顺序扫描 + <=> 余弦距离即可；数据量大要建 HNSW（CREATE INDEX ... USING hnsw (embedding vector_cosine_ops)）——面试必考：HNSW 查询快召回稳但建索引慢占内存，IVFFlat 建得快但需定期 REINDEX。

## 5. 面试问答（7 题）

1. 问：pgvector 支持哪些索引？本项目为什么没建？
   答：IVFFlat 与 HNSW。本项目演示数据每知识库只有几个切片，顺序扫描毫秒级完成，建索引反而增加维护成本；生产数据上万后应建 HNSW + vector_cosine_ops，并把 topK 与 ef_search 一起调。

2. 问：embedding 维度不一致会发生什么？怎么防？
   答：pgvector 列是 vector(768)，插入 769 维直接报错；换 Ollama 模型会返回不同维度。项目在 EmbeddingService.parseEmbedding 里显式校验维度并抛带上下文的异常，向量列宽度用常量 VECTOR_DIM 拼进 DDL，保证两处同源。

3. 问：并发上传同一文档，版本号怎么保证不重？
   答：乐观分配 + 唯一约束兜底。两个事务都读到 max=1 各自插 version_no=2，唯一约束 uk_doc_version 让后提交者抛 DataIntegrityViolationException，catch 后重读 max 再插 3。比行锁轻，正确性由数据库保证。

4. 问：重传相同文件为什么可以幂等跳过？
   答：content_hash 是归一化全文的 SHA-256，哈希相同即内容相同，直接返回现有版本号，不重复切片、不重复向量化——既省 Ollama 推理也避免向量表膨胀。

5. 问：NDJSON 流式和 SSE 的区别？为什么选前者？
   答：两者都是服务端持续推送。SSE 有 event/id/重连语义但要求 text/event-stream；NDJSON 一行一个 JSON，fetch + ReadableStream 按行解析即可。本项目单页前端、无重连需求，NDJSON 复杂度最低。注意写端每行 flush、加锁防撕行。

6. 问：旧版本重传后，向量检索怎么保证不命中过期内容？
   答：检索 SQL 对每个文档取 MAX(version_no) 的切片（相关子查询），旧版本切片物理保留但被过滤；传 documentVersionId 时切换为精确版本过滤——支持按历史版本问答而不污染默认答案。

7. 问：similarityScore 怎么来的？为什么保留 distance？
   答：pgvector <=> 返回余弦距离（0~2），score = 1 - distance 是单调线性换算，仅用于排序展示；distance 原值保留展示，换算只在 buildRagSources 一处完成，避免多处口径不一。

## 6. 已知坑（真实踩过，面试可讲）

- @MockitoBean 默认每个测试方法后重置 mock：@BeforeAll 里的 stub 到第二个用例就失效，表现为 embedding 全是 NULL——挪到 @BeforeEach 才稳定。
- UNION ALL 两臂列数/类型必须对齐：给 kb_text_chunk 加版本列时，kb_uploaded_file_chunk 一臂要补 NULL::integer，漏补或没别名都会报 ambiguous column / 语法错。
- 幂等重传依赖 文件名 + 知识库 定位文档：不同文件名的 v1/v2 会被当成两个文档，演示文档必须同名不同目录（docs/demo-docs/v1、v2）。
- mvnw wrapper 在被 MITM 的网络下下载 Maven 会因证书主域名不匹配失败（SEC_E_WRONG_PRINCIPAL），解法是阿里云镜像 + 手动解压到 wrapper dists 目录。
- 小参数量 LLM（qwen2.5-0.5b）做问题改写与相关性判断不稳定，会话含历史时可能落入闲聊分支——这是 B3/B4 演示中如实记录的已知限制。
