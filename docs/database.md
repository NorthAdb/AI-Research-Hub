# 数据库设计

- 实例:PostgreSQL 18.4 + **pgvector 0.8.1**(项目专用集群,E:\devtools\pgdata-ai-research-hub,端口 5433)
- 库:`ai_research_hub`,业务账号 `aihub`
- Schema 位置:`backend/src/main/resources/db/schema.sql`(幂等,`create table if not exists`)
- JPA:`ddl-auto: validate`,结构以 schema.sql 为准,不在代码里漂移

## 表(与路线图 §24 最终设计一致)

```text
users(id, email✦, password_hash, display_name, created_at)
knowledge_bases(id, owner_id→users, name, description, created_at, updated_at)
documents(id, knowledge_base_id→knowledge_bases, title, filename, storage_path,
          mime_type, size_bytes, status, error_message, retry_count, created_at, updated_at)
document_chunks(id, document_id→documents, chunk_index, content, metadata jsonb,
          embedding vector(768), fts tsvector, created_at)
chat_sessions(id, user_id→users, knowledge_base_id→knowledge_bases, title, created_at)
messages(id, session_id→chat_sessions, role, content, created_at)
search_logs(id, user_id, knowledge_base_id, query, dense/sparse/fused/reranked jsonb,
          latency_ms, created_at)                      -- Phase 3 检索留痕
agent_runs(id, user_id, knowledge_base_id, input, final_answer, token_usage jsonb,
          latency_ms, created_at)                      -- Phase 8 Agent 留痕
agent_steps(id, run_id→agent_runs, step_no, tool, args jsonb, result_summary,
          latency_ms, created_at)
```

✦ unique。`documents.status` CHECK:`UPLOADED / PROCESSING / INDEXED / FAILED`。

## 索引(路线图要求的能力)

- **HNSW**:`idx_chunks_hnsw on document_chunks using hnsw (embedding vector_cosine_ops)`
- **GIN(FTS)**:`idx_chunks_fts on document_chunks using gin (fts)`;simple 配置 + 应用侧
  bigram 预分词(中文默认分词不可用,见 decisions.md §13)
- B-tree:`idx_kb_owner`、`idx_doc_kb`、`idx_chunks_doc`、`idx_messages_session`、`idx_agent_steps_run`
- JSONB:`document_chunks.metadata`、`search_logs.*`、`agent_runs.token_usage`、`agent_steps.args`
- 外键级联:删库→文档→切片级联;删会话→消息级联
- **RLS**:接 Supabase 后为前端直连部分启用;当前隔离在服务层(已实测 404/403)
