# 部署与运行

## 环境变量(配置外置,路线图 原则 4)

| 环境变量 | 默认(本机 Demo) | 说明 |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5433/ai_research_hub` | 换 Supabase 时改这里 |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `aihub` / `aihub` | |
| `JWT_SECRET` | dev-only 值 | **生产必须覆盖**(≥32 字节)。`AUTH_ISSUER=local` 时用它签发 |
| `AUTH_ISSUER` | `local` | `supabase` 时不再信任本地签发的令牌 |
| `SUPABASE_JWT_SECRET` | 空 | `AUTH_ISSUER=supabase` 时必填，至少 32 字节 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3001` | 前端来源 |
| `STORAGE_PROVIDER` / `STORAGE_ROOT` | `local` / `./data/uploads` | `supabase` 时还要 `SUPABASE_URL` 和 `SUPABASE_SERVICE_ROLE_KEY` |
| `PIPELINE_MODE` | `inline` | `kafka`=Worker 异步处理文档 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | 查询缓存(不可用自动降级) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `EMBEDDING_PROVIDER` | `local` | `openai`=OpenAI 兼容 /embeddings(需 `EMBEDDING_API_KEY`) |
| `LLM_PROVIDER` | `extractive` | `openai`=OpenAI 兼容 /chat/completions(需 `LLM_API_KEY`) |
| `LLM_BASE_URL` / `LLM_MODEL` | deepseek / deepseek-chat | 可换任何 OpenAI 兼容服务 |
| `AGENT_BRAIN` / `AGENT_MAX_STEPS` | `rule` / `6` | `llm` 需要 `LLM_API_KEY`，由模型决定工具 |
| `RERANKER_PROVIDER` | `lexical` | `api` 需要 `RERANKER_API_KEY`，调用 `/rerank` |
| `SEARCH_SPARSE` | `postgres` | `elasticsearch` 时稀疏检索改走 `ELASTICSEARCH_URL` |
| `RAG_CHUNK_SIZE` / `RAG_CHUNK_OVERLAP` / `RAG_TOP_K` | 800 / 100 / 5 | 切分与检索参数 |

前端:`NEXT_PUBLIC_API_BASE` 指定后端地址(默认 `http://localhost:8080`)。

## 一、本机运行

日常启停用项目根目录下的脚本,说明见仓库根目录 README:

```text
powershell -ExecutionPolicy Bypass -File infra\start-demo.ps1
powershell -ExecutionPolicy Bypass -File infra\stop-demo.ps1
```

默认不启动 Kafka。需要消息队列处理文档时给启动脚本加 `-WithKafka`。
构建:`mvn test package`(后端,需 JDK 21)、`npm run build`(前端)。

## 二、Docker 方式(compose 已就绪,引擎正常时可用)

```bash
cd infra
docker compose up -d                                  # core:db(pgvector)+redis+kafka+backend+frontend+nginx
docker compose --profile monitoring up -d             # + Prometheus(9090)/ Grafana(3002,admin/admin)
docker compose --profile es up es                     # + Elasticsearch(9200),见 decisions.md §17
```

- backend 容器默认 `PIPELINE_MODE=kafka`,`STORAGE_ROOT=/data/uploads`(volume 持久化)
- schema 由 db 容器的 init 脚本自动执行(`/docker-entrypoint-initdb.d/`)
- Nginx:`/api/* → backend:8080`(SSE 已关缓冲)、`/* → frontend:3000`

## 三、接入 Supabase 云端

1. Supabase 建项目 → 拿 Project URL / anon key / service role key / 连接串
2. `DATABASE_URL` 指向 Supabase(现有 schema 兼容;pgvector 云端内置)
3. Auth 切换:前端 supabase-js 登录 → JWT 给后端 → `JwtService` 改校验 Supabase secret/JWKS
4. Storage 切换:上传走 supabase-js → 后端只登记 metadata(DB 不变)
5. Realtime 切换:SSE 换 Realtime Channel(事件语义一致)

## 四、CI/CD(Phase 14)

`.github/workflows/ci.yml`:backend(mvn test package,temurin 21 + maven 缓存)、
frontend(npm ci + build,node 22 + npm 缓存)。推送到 GitHub 即生效。
