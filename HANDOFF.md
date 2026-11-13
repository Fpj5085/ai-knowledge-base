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
