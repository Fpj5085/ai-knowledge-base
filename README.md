# AI Knowledge Base

一个基于 Spring Boot 的开源 RAG（检索增强生成）知识库应用。它支持上传文档、文本切片、向量检索、连续对话、流式回答、联网搜索以及图片 OCR，并提供可直接使用的 Web 界面。

## 功能

- 用户注册、登录、退出与 Token 鉴权
- 创建和管理知识库
- 上传 PDF、DOCX、TXT 与图片等文件
- PDFBox、Apache POI、Tesseract OCR 文本提取
- Ollama `nomic-embed-text` 本地向量化
- PostgreSQL + pgvector 语义检索
- OpenRouter 大模型问答
- NDJSON 流式输出与多轮会话历史
- Tavily 联网搜索（可选）
- Redis 请求限流
- 单页 Web UI，无需额外前端构建

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.5、Spring MVC |
| 数据访问 | Spring Data JPA、Hibernate |
| 数据库 | PostgreSQL 16、pgvector |
| 缓存/限流 | Redis 7 |
| 大模型 | OpenRouter（默认 `z-ai/glm-4.5-air`） |
| Embedding | Ollama、`nomic-embed-text` |
| 文档解析 | PDFBox、Apache POI、Tess4J |
| 前端 | 原生 HTML、CSS、JavaScript |
| 构建 | Maven Wrapper |

## 工作流程

```text
文档上传
  → 文本提取 / OCR
  → 文本切片
  → Ollama 生成向量
  → PostgreSQL/pgvector 保存

用户提问
  → 向量召回相关片段
  → 组合知识片段与对话历史
  → OpenRouter 生成回答
  → NDJSON 流式返回前端
```

## 环境要求

- JDK 17 或更高版本
- Docker Desktop（推荐用于 PostgreSQL、Redis 和 Ollama）
- OpenRouter API Key
- Tesseract OCR（仅图片 OCR 需要）
- Tavily API Key（仅联网搜索需要）

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/89few/ai-knowledge-base.git
cd ai-knowledge-base
```

### 2. 启动基础服务

```bash
docker compose up -d
docker exec ai-kb-ollama ollama pull nomic-embed-text
```

Compose 会启动：

- PostgreSQL/pgvector：`localhost:5432`
- Redis：`localhost:6379`
- Ollama：`localhost:11434`

### 3. 配置环境变量

复制 `.env.example`，并至少设置 `OPENROUTER_API_KEY`。注意：Spring Boot 不会自动读取 `.env` 文件；它主要作为配置清单使用，请把变量导入当前终端。

PowerShell：

```powershell
$env:OPENROUTER_API_KEY="你的 OpenRouter Key"
$env:TAVILY_API_KEY="你的 Tavily Key" # 可选
```

Bash：

```bash
export OPENROUTER_API_KEY="你的 OpenRouter Key"
export TAVILY_API_KEY="你的 Tavily Key" # 可选
```

### 4. 启动应用

Windows：

```powershell
.\mvnw.cmd spring-boot:run
```

macOS / Linux：

```bash
./mvnw spring-boot:run
```

打开 <http://localhost:8080>。

## VS Code 运行

建议安装：

- Extension Pack for Java
- Spring Boot Extension Pack
- Lombok Annotations Support for VS Code

用 VS Code 打开项目根目录，等待 Maven 导入完成，然后运行 `AiKnowledgeBaseApplication.java`，或直接使用上述 Maven 命令。

## 配置项

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `OPENROUTER_API_KEY` | 无 | OpenRouter Key，必填 |
| `OPENROUTER_MODEL` | `z-ai/glm-4.5-air` | 大模型 slug |
| `TAVILY_API_KEY` | 空 | Tavily 联网搜索 Key |
| `DB_URL` | `jdbc:postgresql://localhost:5432/ai_kb` | 数据库地址 |
| `DB_USERNAME` | `postgres` | 数据库用户名 |
| `DB_PASSWORD` | `123456` | 数据库密码 |
| `REDIS_HOST` | `localhost` | Redis 地址 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama 地址 |
| `OLLAMA_EMBEDDING_MODEL` | `nomic-embed-text` | 向量模型 |
| `TESSERACT_DATA_PATH` | Windows 默认安装路径 | tessdata 路径 |
| `TESSERACT_LANGUAGE` | `chi_sim+eng` | OCR 语言 |

生产环境请务必修改数据库密码，不要使用默认值。

## 主要 API

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录 |
| GET | `/api/auth/me` | 当前用户 |
| POST | `/api/kbs` | 创建知识库 |
| GET | `/api/kbs` | 知识库列表 |
| POST | `/api/documents/upload` | 上传知识库文档 |
| GET | `/api/documents` | 文档列表 |
| POST | `/api/vector/build` | 构建向量 |
| GET | `/api/vector/search` | 向量搜索 |
| GET | `/api/rag/ask` | 普通问答 |
| GET | `/api/rag/ask/stream` | NDJSON 流式问答 |
| GET | `/api/sessions` | 会话列表 |
| GET | `/api/web-search/search` | 联网搜索 |

需要登录的请求使用：

```http
Authorization: Bearer <token>
```

流式接口返回每行一个 JSON 对象，事件类型依次可能为 `sessionId`、`delta`、`sources`、`error` 和 `done`。

## 测试

确保 PostgreSQL 和 Redis 已运行：

```bash
./mvnw test
```

Windows：

```powershell
.\mvnw.cmd test
```

## 常见问题

### OpenRouter 返回模型 404

免费模型 slug 可能下线。设置一个当前可用的模型：

```powershell
$env:OPENROUTER_MODEL="z-ai/glm-4.5-air"
```

### 流式调用出现 `RST_STREAM: Protocol error`

项目的流式 OpenRouter 客户端已固定使用 HTTP/1.1，以规避部分上游节点重置 HTTP/2 SSE 流的问题。请确认使用的是最新代码并重启应用。

### 无法连接 PostgreSQL 或 Redis

```bash
docker compose ps
docker compose logs postgres
docker compose logs redis
```

### Ollama 没有 Embedding 模型

```bash
docker exec ai-kb-ollama ollama pull nomic-embed-text
```

### OCR 无法识别中文

确认 `TESSERACT_DATA_PATH` 下存在 `chi_sim.traineddata` 和 `eng.traineddata`。

## 安全说明

- 不要把 API Key、Token、数据库生产密码提交到 Git。
- `.env`、日志和本地 `apikey.txt` 已被 `.gitignore` 排除。
- Web UI 当前把登录 Token 保存在浏览器 `localStorage`，部署到公网前应启用 HTTPS，并评估改用安全 Cookie。
- 默认配置面向本地开发；公网部署前应增加 CORS、CSRF、访问控制、审计与反向代理限流。

## 项目结构

```text
src/main/java/org/aiknowledgebase
├── config       # Spring MVC 配置
├── controller   # REST API
├── dto          # 请求和响应对象
├── entity       # JPA 实体
├── repository   # 数据访问
└── service      # RAG、LLM、向量、文档、鉴权等服务

src/main/resources
├── application.yml
└── static/index.html
```

## 贡献

欢迎提交 Issue 和 Pull Request：

1. Fork 本仓库。
2. 创建功能分支：`git switch -c feature/your-feature`。
3. 提交修改并补充测试。
4. 推送分支并创建 Pull Request。

提交前请运行 `./mvnw test`，不要提交任何真实凭据或用户数据。

## License

本项目采用 [MIT License](LICENSE)。

## 二次开发（B2：文档版本管理 + 来源引用增强）

在原项目基础上新增（不影响原有功能与接口兼容性）：

- **文档版本管理**：新增 kb_document_version 表（version_no、content_hash 等），kb_text_chunk 增加 document_version_id 与 char_offset。同一知识库内重传同名文档：内容相同（SHA-256 一致）不重复入库；内容变化自动生成新版本并重建切片，旧版本切片按版本归档、可按版本检索。并发上传由数据库唯一约束 uk_doc_version(document_id, version_no) 兜底。
- **版本 API**：GET /api/documents/{id}/versions（版本列表）、GET /api/documents/{id}/versions/{versionNo}（版本预览，含全文）；/api/vector/search 与 /api/rag/ask(/stream) 均新增可选 documentVersionId 参数——不传默认只检索每个文档的最新版本，传入则检索指定版本。
- **来源引用增强**：RAG 引用（RagSource）新增 versionNo（文档版本号）、charOffset（片段在归一化全文中的起始字符偏移）、similarityScore（1 - 向量距离）；流式 NDJSON 的 sources 事件沿用同一结构。前端引用卡片展示“文档 ID · 版本 · 字符偏移 · 相似度”并可展开片段原文。
- **可选本地 Ollama**：compose.ollama.yml 独立文件，宿主机端口 11435，通过 docker compose -f compose.yaml -f compose.ollama.yml --profile ollama up -d ollama 启动；默认隔离 compose 不受影响。
- **测试**：DocumentVersionRagIntegrationTest（7 个用例：v1 建档 / 同内容不重复建档 / 变更生成 v2 / 默认检索仅最新版 / 指定版本检索 / RAG 引用可追踪 / 版本预览）与 FrontendSourceCardTest（前端卡片构建验证），embedding 与 LLM 使用确定性 mock，零密钥、零付费调用。

迁移方式说明：沿用项目既有的 Hibernate ddl-auto: update 等价迁移机制生成新表/新列，未引入 Flyway，也没有修改既有结构；完整 DDL 参见 docs/migrations/V2__b2_document_version.sql。

## 一键部署（B2/B3 验证过）

前置：Docker Desktop 运行中，JDK 21+（宿主机跑测试用）。首次构建镜像约 2-5 分钟（Maven 依赖走阿里云镜像）。

    docker compose up -d --build

启动后自动完成：

- akb-postgres（pgvector/pgvector:pg16，宿主机 55433）自动 CREATE EXTENSION vector 并由 Hibernate 建表迁移
- akb-redis（redis:7-alpine，宿主机 16379）
- akb-app（Spring Boot，宿主机 8082 → 容器 8080），启动即建表，前端单页直接可访问 http://localhost:8082

环境变量（全部可选，密钥走环境变量不入 Git）：

    $env:OPENROUTER_API_KEY = "你的Key"        # 可选；不设则使用占位符
    $env:OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"   # 可指向本地 Ollama 的 http://localhost:11434/v1（OpenAI 兼容）
    $env:OPENROUTER_MODEL = "qwen2.5-chat"     # 本地演示用 Ollama 模型名

本地 Ollama（可选，零付费演示路径）：宿主机安装 Ollama 后导入本地 GGUF（如 nomic-embed-text、qwen2.5-0.5b），应用容器通过 host.docker.internal:11434 访问，无需 API Key。compose.ollama.yml 提供容器化 Ollama 备选（宿主机 11435）。

停止与持久化：docker compose down 保留命名卷（postgres-data/redis-data），重新 up 后文档、版本、会话历史全部保留。

演示材料：docs/demo-docs/（自制合成文档 v1/v2/PDF/DOCX）与 docs/demo-evidence/（curl 证据、持久化证据、界面截图）。

## 二次开发说明（B5：引用卡片修复 + 演示视频）

本节区分原项目功能与二次开发贡献，供 review 与学习：

- **原项目（上游 89few/ai-knowledge-base）**：文档上传/切片、Ollama 向量化、pgvector 检索、OpenRouter 问答、NDJSON 流式、单页前端。
- **B2（本仓库新增）**：文档版本管理（kb_document_version、content_hash 幂等重传、并发唯一约束兜底）、可追溯引用（versionNo/charOffset/similarityScore）、版本过滤检索、版本 API、前端引用卡片。
- **B3（本仓库新增）**：容器化部署（Dockerfile + akb-app 服务）、LLM base-url 参数化（可指向本地 Ollama OpenAI 兼容端点）、演示文档与证据链、可选 Ollama compose。
- **B5（本仓库新增）**：
  - **引用卡片修复**：RagService 判别提示词收紧（历史会话/多轮会话不影响 YES 判断），并在"命中切片但判别弱相关"的兜底分支无条件返回 sources——修复"含历史会话时实时引用卡片不渲染"；前端 sources 事件本就无条件渲染，由 FrontendSourceCardTest 新增用例锁定（sources 事件处理不得附加条件）。
  - **演示视频**：docs/demo/demo-recording.mp4（480 秒完整流程，1280x720/15fps，6.8MB，ffmpeg 压缩自 gdigrab 实时录屏）。
- 详细提交历史见 git log（B1: ~ B5: 前缀），每步验证证据在 docs/demo-evidence/ 与 HANDOFF.md。
