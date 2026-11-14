# B1 任务交接 — Java AI 知识库 P0 基线验证（零密钥）

日期：2026-11-14（Asia/Shanghai）
工作区：K:\Fpj Project\B-ai-knowledge-base（github.com/89few/ai-knowledge-base，HEAD c253bcb）

## 1. Compose 服务清单（原版 compose.yaml）

| 服务 | 镜像 | 镜像大小 | 端口 | 数据卷 | 大模型风险 |
| --- | --- | --- | --- | --- | --- |
| postgres | pgvector/pgvector:pg16 | 647MB | 5432:5432 | postgres-data | 无 |
| redis | redis:7-alpine | 58.5MB | 6379:6379 | redis-data | 无 |
| ollama | ollama/ollama | 未拉取 | 11434:11434 | ollama-data | 是：nomic-embed-text 向量化，ollama pull 会下载大模型 |

大模型/推理相关服务仅 ollama 一个；OpenRouter 与 Tavily 是外部 HTTP API，不在 compose 内。本次全程未启动 ollama、未下载任何大模型、未产生任何付费调用（OpenRouter Key 仅使用占位符 sk-placeholder-no-real-key，仅供 Spring 属性解析）。

## 2. Compose 隔离方案（新增 docker-compose.override.yml）

新增文件 docker-compose.override.yml（Git 已提交）：

- postgres：容器名 akb-postgres，端口改映射 55433->5432（不占用 5432）
- redis：容器名 akb-redis，端口改映射 16379->6379（不占用 6379）
- ollama：profiles: [disabled]，默认 docker compose up -d 不会启动
- 复用原有 postgres-data / redis-data 命名卷；未改动 compose.yaml

验证：docker compose config --services 仅输出 postgres、redis。实际状态：

- akb-postgres：Up (healthy)，0.0.0.0:55433->5432
- akb-redis：Up (healthy)，0.0.0.0:16379->6379

## 3. pgvector 验证

命令：docker exec akb-postgres psql -U postgres -d ai_kb -c "CREATE EXTENSION IF NOT EXISTS vector;" -c "SELECT extname, extversion FROM pg_extension WHERE extname='vector';"
结果：CREATE EXTENSION；vector 0.8.7。exit code 0。

## 4. 后端测试基线

环境说明：mvnw.cmd 首次运行因本机网络对 repo.maven.apache.org 的 TLS 证书校验失败（curl SEC_E_WRONG_PRINCIPAL）而无法下载 Maven 3.9.16；已手动从阿里云镜像下载 apache-maven-3.9.16-bin.zip 解压至 %USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.16-bin，并创建用户级 %USERPROFILE%\.m2\settings.xml 指向 maven.aliyun.com 镜像（均在本机用户目录，未改动仓库）。JDK：Temurin 21.0.13（JAVA_HOME 手动注入）。

命令（PowerShell，仓库根目录）：

    $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'
    $env:DB_URL='jdbc:postgresql://localhost:55433/ai_kb'
    $env:REDIS_HOST='localhost'; $env:REDIS_PORT='16379'
    $env:OPENROUTER_API_KEY='sk-placeholder-no-real-key'
    .\mvnw.cmd test

真实结果摘要（完整日志：build-test-baseline.log，被 .gitignore 的 *.log 规则排除，保留在本地）：

    [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.707 s -- in org.aiknowledgebase.AiKnowledgeBaseApplicationTests
    [INFO] BUILD SUCCESS
    [INFO] Total time: 8.551 s
    EXIT=0

测试上下文成功连接隔离 PostgreSQL（Hibernate 建表 kb_* 全套，DB version 16.16）与 Redis，应用上下文加载通过。首次完整构建（含依赖下载）约 54.288s。

失败项：无。无依赖模型 API 的测试失败日志。唯一测试类 src/test/java/org/aiknowledgebase/AiKnowledgeBaseApplicationTests.java（contextLoads），未覆盖 LLM/Ollama 调用路径，零密钥下没有可隔离的失败样本。

## 5. P1 设计草案

### 5.1 文档版本管理

新增表 kb_document_version：

    id BIGSERIAL PK
    document_id BIGINT NOT NULL REFERENCES kb_document_info(id)
    version_no INT NOT NULL
    file_name VARCHAR(255) NOT NULL
    file_size BIGINT
    file_type VARCHAR(50)
    content_hash CHAR(64)          -- SHA-256 去重与变更检测
    storage_key VARCHAR(500)       -- 本地/对象存储路径
    change_note VARCHAR(500)
    created_by BIGINT
    create_time TIMESTAMP(6) NOT NULL
    UNIQUE(document_id, version_no)

kb_text_chunk 增加 document_version_id；重传同一文件时按 content_hash 判断是否生成新版本并重建向量（旧版本向量归档或按 version 检索）。上传/删除接口保持兼容，新增 GET /api/documents/{id}/versions。

### 5.2 来源引用增强

- 召回结果已含 chunk 到文档映射；在 /api/rag/ask 与 ask/stream 的 sources 事件中补齐：documentId、fileName、versionNo、chunkIndex、相似度分数、片段全文。
- 前端将回答中的引用标记 [n] 渲染为可点击角标，悬停/点击展示来源片段；LLM 提示词中为每个片段编号并要求模型标注引用编号。

### 5.3 中文化改进

- 切片优化：按中文标点（。！？；）与句子边界切分，避免 800 字符硬切打断语义；规则改造在 DocumentServiceImpl 切片逻辑处。
- UI 文案与 OCR：默认 TESSERACT_LANGUAGE=chi_sim+eng 已具备；补充前端中文提示、错误信息中文化映射表。

### 5.4 模型方案优先级

1. 首选：本地 Ollama（当前已验证隔离可关停；P1 启用时单独拉起，需 ollama pull nomic-embed-text，约 274MB，无需 API Key、无费用）。问答侧也可用本地模型（如 qwen2.5:7b）实现完全离线，但需评估本机显存。
2. OpenRouter（需 API Key，付费调用）：保持现状作为可选云端方案，默认模型 z-ai/glm-4.5-air。
3. Tavily 联网搜索（需 API Key）：可选功能，维持关闭默认。

## 6. Git 可追溯性

- 原有功能未做任何代码改动；LICENSE 保持原样（MIT）。
- 新增提交内容：docker-compose.override.yml（隔离方案）、HANDOFF.md（本文件）。
- .env 未创建、未入库；无任何真实凭据。
- 未入库但保留在本地：build-test-baseline.log（完整测试输出，因 .gitignore *.log 排除）。

---

# B2 任务交接 — P1 开发：文档版本管理 + 来源引用增强

日期：2026-11-14（Asia/Shanghai）。分支 main，基于 B1 提交 cd0ffae。

## 1. 新增/修改文件清单

新增：

- src/main/java/org/aiknowledgebase/entity/DocumentVersion.java（新实体）
- src/main/java/org/aiknowledgebase/repository/DocumentVersionRepository.java
- src/main/java/org/aiknowledgebase/docs/migrations/V2__b2_document_version.sql（DDL 参考，见 docs/migrations/）
- compose.ollama.yml（可选本地 Ollama，宿主机 11435，profiles: ollama）
- src/test/java/org/aiknowledgebase/DocumentVersionRagIntegrationTest.java（7 用例集成测试）
- src/test/java/org/aiknowledgebase/FrontendSourceCardTest.java（前端构建验证）

修改：

- entity/TextChunk.java：新增 documentVersionId、charOffset 字段
- repository/TextChunkRepository.java：新增按版本查询切片方法
- repository/DocumentInfoRepository.java：新增同名文档查找方法
- service/DocumentService.java：重写上传逻辑——content_hash 判重、版本号分配（唯一约束兜底并发重试）、切片携带版本与字符偏移；新增 listVersions/previewVersion
- service/VectorStoreService.java：searchSimilarChunks 新增 documentVersionId 参数；默认只检索每文档最新版本（MAX(version_no) 相关子查询），指定版本时按 version 精确检索；结果携带 version_no/char_offset
- service/RagService.java：ask/streamAsk 新增 documentVersionId 透传；buildRagSources 输出 versionNo/charOffset/similarityScore(=1-距离)；buildContext 提示词附带版本与偏移
- controller/DocumentController.java：新增 GET /api/documents/{id}/versions、GET /api/documents/{id}/versions/{versionNo}
- controller/RagController.java：/ask 与 /ask/stream 新增可选 documentVersionId 参数
- controller/VectorController.java：/search 新增可选 documentVersionId 参数
- dto/ChunkSearchResult.java、dto/RagSource.java、dto/DocumentUploadResponse.java：新增版本追踪字段（响应兼容，纯增量）
- src/main/resources/static/index.html：引用卡片渲染“文档 ID · 版本 · 字符偏移 · 相似度 · 距离”，可展开片段原文
- README.md：新增“二次开发（B2）”分节

## 2. 表结构摘要

kb_document_version：id PK / document_id / version_no / file_name / file_size / file_type / content_hash CHAR(64) / storage_key / change_note / created_by / create_time；UNIQUE(document_id, version_no)（uk_doc_version）。kb_text_chunk 增加 document_version_id（旧版本切片保留即归档，可按版本检索）与 char_offset。

迁移机制：项目原有结构由 Hibernate ddl-auto: update 生成（无 Flyway），B2 沿用该等价机制，不改已有迁移/结构；DDL 参考脚本 docs/migrations/V2__b2_document_version.sql，版本号顺延 V2。

## 3. 引用数据结构与追踪链路

RagSource 新结构：chunkId、documentId、chunkIndex、distance、content、versionNo、charOffset、similarityScore。

链路：上传时 DocumentService 计算 SHA-256 归一化全文哈希并写 kb_document_version，切片记录 version_id 与字符偏移 → buildEmbeddings 对各版本切片生成向量 → searchSimilarChunks 默认按 dv.version_no = MAX(version_no) 过滤（或按指定 document_version_id）→ RagService.buildRagSources 将命中切片映射为 RagSource → /api/rag/ask 响应与 /api/rag/ask/stream 的 NDJSON sources 事件携带同一结构 → 前端 renderCollapsibleSources 展示版本号/偏移/得分。文档级预览：GET /api/documents/{id}/versions/{versionNo} 返回元数据 + 该版本切片全文。

## 4. 测试清单与真实结果

命令同 B1（mvnw test，env 指向 55433/16379，占位符 Key）：

    Tests run: 1 ... -- in org.aiknowledgebase.AiKnowledgeBaseApplicationTests
    Tests run: 7, Failures: 0, Errors: 0 -- in org.aiknowledgebase.DocumentVersionRagIntegrationTest
    Tests run: 1 ... -- in org.aiknowledgebase.FrontendSourceCardTest
    Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
    BUILD SUCCESS
    EXIT=0

DocumentVersionRagIntegrationTest 覆盖（真实连接 akb-postgres:55433，embedding/LLM 为确定性 mock，零密钥零付费）：

1. 首次上传生成 v1（content_hash 64 位）
2. 同内容重传不产生新版本
3. 内容修改生成 v2
4. 默认检索只返回最新版本且带字符偏移
5. 指定版本检索只返回 v1 片段
6. ragService.ask 引用携带 versionNo=2/charOffset/similarityScore/content
7. previewVersion 返回 v2 元数据与全文

修复记录：开发过程中一次 BUILD FAILURE（@MockitoBean 每用例重置导致 @BeforeAll stub 失效、版本分支 SQL id 歧义、chat mock 返回 null 触发 kb_chat_message 非空约束），日志保留在 build-test-b2.log（被 .gitignore 排除，仅本地），修复后 9/9 绿。

## 5. 演示步骤

启动：docker compose up -d（隔离 PG/Redis）→ mvnw spring-boot:run →

    # 1) 登录拿 token（POST /api/auth/login）
    # 2) 上传 v1：POST /api/documents/upload (knowledgeBaseId, file=demo.md) → versionNo=1
    # 3) 构建向量：POST /api/vector/build?knowledgeBaseId=N
    # 4) 问答：GET /api/rag/ask/stream?knowledgeBaseId=N&question=... → sources 事件含 versionNo/charOffset/similarityScore
    # 5) 修改 demo.md 重传 → versionNo=2；重复 3)-4)，引用显示 v2 与新片段
    # 6) 版本列表/预览：GET /api/documents/{id}/versions、GET /api/documents/{id}/versions/2

截图说明：本机无浏览器截图能力，端到端行为由 DocumentVersionRagIntegrationTest 的 7 个真实数据库用例等价演示（v1→重传→v2→检索引用断言），测试输出见 build-test-b2.log；带真实 LLM 回答的浏览器截图需配置可用模型后按上述步骤录制。

## 6. Ollama 启用状态

compose.ollama.yml 已就绪（容器 akb-ollama，宿主机 11435，不占 11434）。执行时 ollama/ollama 镜像拉取持续遭遇 auth.docker.io TLS 证书劫持（x509 证书为 n.sni-347-default.ssl.fastly.net），后台重试中；未完成前，embedding 路径由确定性 mock embedding 的集成测试验证交付，不阻塞。若后续拉取成功，模型 nomic-embed-text（约 274MB）将通过 OLLAMA_BASE_URL=http://localhost:11435 接入，无需 API Key。

## 7. Backlog 登记项（未实现）

- 文档物理文件对象存储（当前 storage_key 仅记录逻辑路径，未存原始文件副本，版本预览由切片全文拼装）
- 版本对比（diff）接口与前端视图
- 按版本归档的向量清理策略（当前旧版本向量保留，随版本增长需回收机制）
- FrontendSourceCardTest 升级为 Playwright 视觉回归

## 8. 提交清单与 Git

提交（main 分支，前缀 B2:）：

    B2: document version management (entity/service/controller)
    B2: traceable RAG sources (version/offset/score) and frontend card
    B2: integration tests, optional ollama compose, docs and handoff

.env/Key 不入库；无真实凭据；LICENSE 未改动。
