# B3 演示证据索引

- curl-evidence.txt：注册/登录/建库/上传 v1 与 v2/版本列表/版本预览/向量构建/流式问答（含 sources 引用结构与 versionNo/charOffset/similarityScore）完整真实输出
- persistence-evidence.txt：compose down/up 后 kb_document_version 数据与 API 复查记录
- screenshots/01-login-page.png：前端登录页
- screenshots/02-main-page-after-restart.png：重启后主界面（历史会话仍在）
- screenshots/04-qa-stream-ollama-missing-model.png：embedding 模型缺失阶段的真实报错（保留不隐瞒）
- screenshots/05-rag-answer-with-citation-restored.png：带“依据：知识片段 1”的 RAG 回答（198 摄氏度，来自历史会话恢复）
