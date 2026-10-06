# 架构(当前:Phase 0/1)

```text
                    ┌────────────────────┐
                    │   用户(浏览器)      │
                    └─────────┬──────────┘
                              │ http://localhost:3001
                    ┌─────────▼──────────┐
                    │ Next.js 16 (App)   │  登录 / 注册
                    │ React 19 + TS      │  知识库列表 / 详情 / 上传
                    │ Tailwind CSS       │
                    └─────────┬──────────┘
                              │ fetch + Authorization: Bearer JWT
                              │ http://localhost:8080
                    ┌─────────▼──────────┐
                    │ Spring Boot 3.5    │
                    │  ├─ auth/          │  注册/登录/签发&校验 JWT(BCrypt)
                    │  ├─ knowledge/     │  知识库 CRUD(owner 隔离)
                    │  ├─ document/      │  上传/列表/删除(本地磁盘存储)
                    │  ├─ health/        │  /api/health
                    │  └─ common/        │  统一异常 / 当前用户
                    └─────────┬──────────┘
                              │ JDBC
                    ┌─────────▼──────────┐
                    │ PostgreSQL 18 (5433)│  users / knowledge_bases /
                    │ (项目专用实例)        │  documents / document_chunks /
                    └────────────────────┘  chat_sessions / messages
```

## 与最终目标架构的关系

路线图的最终形态在代码结构上已预留演进点(括号内为切换开关):

| 最终组件 | 当前实现 | 切换点 |
|---|---|---|
| Supabase Auth | 后端自签 JWT(HS256)+ users 表 | `JwtService` 校验换 Supabase secret/JWKS |
| Supabase Storage | 本地磁盘 `app.storage.root` | `DocumentService` 存取逻辑换 Storage SDK |
| Supabase PostgreSQL + pgvector | 本地 PG 18 + pgvector 0.8.1(5433) | 换 `DATABASE_URL` |
| Embedding API | `LocalHashEmbeddingService`(词面哈希,演示级)| `EMBEDDING_PROVIDER=openai` + Key → OpenAI 兼容 /embeddings |
| LLM | `ExtractiveLlmService`(抽取式,无 Key 兜底)| `LLM_PROVIDER=openai` + Key → /chat/completions |
| Reranker | `LexicalOverlapReranker`(词面重合)| `RerankerService` 接口换 Cross-Encoder/API |
| Kafka + Worker | `PIPELINE_MODE=kafka`(4.3.1 KRaft 单机)/ inline | 双模式已实现,Worker 含 3 次重试 |
| Supabase Realtime | SSE `/events`(token 查询参数) | 接云端后换 Realtime Channel |
| Redis 查询缓存 | 原生 Redis 5.0(E 盘)+ 静默降级 | compose 里是 redis:7-alpine |
| Elasticsearch | 未运行(PG FTS 覆盖)| SearchService 稀疏检索换 ES client;compose `--profile es` |

## 领域模块(与路线图 §25 对齐)

```text
com.aireshub
├── auth        # 用户、JWT、安全过滤器
├── knowledge   # 知识库聚合
├── document    # 文档聚合:解析/切分/摄入管线(inline|kafka)/SSE 事件
├── search      # Hybrid Search:dense+sparse→RRF→rerank,search_logs 留痕
├── chat        # RAG 问答 + Citation + Redis 查询缓存 + 会话/消息
├── agent       # Agentic RAG:AgentBrain(rule|llm)+ 4 工具 + agent_runs/steps
├── ai          # 可替换 AI 基础设施:Tokenizer/Embedding/LLM/Reranker 接口与实现
├── health      # 运维端点
└── common      # 异常体系、SecurityContext 工具
```

## 数据流(当前)

```text
上传:POST /documents → Storage 落盘 → 事务提交 → Kafka document.process
      → Worker:解析(PDFBox)→ 切分(800/100)→ Embedding(哈希768)→ pgvector+FTS 入库
      → 状态机 PROCESSING→INDEXED → SSE 推送前端

问答:question → QueryCache(Redis,sha256 归一化键)
      MISS → Hybrid:dense(pgvector HNSW)+ sparse(PG FTS bigram)→ RRF(k=60)
             → LexicalReranker → top5 → LLM(抽取式/兼容API)→ 带引用回答
             → Redis SET(TTL 1h)
      HIT  → 直接返回(fromCache=true)

Agent:brain.decide(rule)→ search_knowledge_base →(总结意图)summarize_document
        → LLM 综合上下文 → 带引用回答;agent_runs/agent_steps 全程留痕
```
