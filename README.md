# AI Research Hub

个人或小团队用的知识库 Demo：注册登录、上传 PDF / Markdown、切成片段、做混合检索，再据此回答问题。Agent 会自己决定要不要再查一次资料。

整体学习路线在 [AI_Research_Hub_Supabase_SpringBoot_路线图.md](AI_Research_Hub_Supabase_SpringBoot_路线图.md)。本机组件装在哪，见 [COMPONENTS.md](COMPONENTS.md)。为什么这样取舍，见 [docs/decisions.md](docs/decisions.md)。

这是一套能在自己电脑上跑通的学习项目，不是已经接上 Supabase 云的生产系统。登录、文件、实时进度都按 Supabase 的边界做了本地替身，换云时改配置和少量接入代码，而不是重写业务。

## 现在能做什么

| 能力 | 现在的做法 | 你怎么用 |
|---|---|---|
| 注册 / 登录 | 后端签发 JWT，密码用 BCrypt | 打开前端，注册或用演示账号登录 |
| 知识库 | 只有创建者能看、改、删 | 登录后新建知识库 |
| 上传文档 | 文件放在后端磁盘，数据库只存路径和状态 | 知识库详情页上传 `.pdf` / `.md` / `.txt` |
| 解析与索引 | 抽文本 → 切分 → 向量 → PostgreSQL 全文 | 上传后状态从「已上传」变成「已索引」 |
| 混合检索 | 向量检索 + 全文检索，用 RRF 合并，再按词面重排 | 详情页提问，或调用 `POST /api/search` |
| 问答 | 先检索，再让模型根据片段回答，并带来源 | 详情页的问答框。同一问题一小时内走缓存 |
| Agent | 默认按规则调用工具，并把每一步记下来 | 详情页切换到 Agent 再提问 |
| 进度 | 服务器推送（SSE） | 上传后页面上的状态会自己变，不用刷新 |

还没在这台机器上跑通的部分见文末「未完成任务」。代码里的开关都写在下面的配置表里。

## 启动和停止

在项目根目录 `E:\zcode_test\ai-research-hub` 打开 PowerShell。

```powershell
# 构建后端（本机默认 JAVA_HOME 是 JDK 17，这个项目要 JDK 21）
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
cd backend
mvn -B test package
cd ..

# 启动：数据库 + Redis + 后端 + 前端。默认不启动 Kafka。
powershell -ExecutionPolicy Bypass -File infra\start-demo.ps1

# 只停前端和后端。数据库、Redis、Kafka 继续留着。
powershell -ExecutionPolicy Bypass -File infra\stop-demo.ps1

# 连 Redis、Kafka、PostgreSQL 容器一起停。库里的数据还在 Docker 卷里。
powershell -ExecutionPolicy Bypass -File infra\stop-demo.ps1 -All
```

启动成功后：

- 页面：脚本最后一行打印的地址。默认是 <http://localhost:3001>；3001 被占用时会是 <http://localhost:3002>
- 接口：<http://localhost:8080/api/health>，正常是 `{"status":"ok","database":"up"}`
- 演示账号：`demo@aireshub.dev` / `demo12345`

前端默认用 **3001**。3000 被本机的 Cursor 占着。如果 3001 也被占用，启动脚本会改到 **3002**，后端同时允许这两个地址。停止脚本不会去关 Cursor。

默认模式不需要 Kafka。上传后的解析在后端进程里异步做。只有想看「上传进消息队列、由 Worker 消费」时，才这样启动：

```powershell
powershell -ExecutionPolicy Bypass -File infra\start-demo.ps1 -WithKafka
```

这会启动本机 Kafka（`E:\devtools\kafka`，需要 Git Bash），并把 `PIPELINE_MODE` 设为 `kafka`。

日志在 `infra\.run\backend.out.log`、`backend.err.log`、`frontend.out.log`。该目录不进 Git。

如果 8080 或 3001 已经有旧进程，启动脚本会跳过对应步骤。改完后端代码后，先 `stop-demo.ps1`，再 `mvn package`，再 `start-demo.ps1`。

## 页面上怎么用

1. 打开 <http://localhost:3001>，用演示账号登录，或自己注册。
2. 新建一个知识库，点进去。
3. 上传一份 PDF 或 Markdown。等状态变成「已索引」。这一步会解析正文、切成大约 800 字一段（相邻段重叠 100 字）、写入向量和全文索引。
4. 在问答框提问。回答下面有引用，点的是检索到的原文片段。同一个问题再问一次，会标成来自缓存。
5. 换成 Agent 提问。默认会先搜索知识库；问题里带「总结 / 概括 / 摘要」时，还会读第一篇命中文档并抽出若干句。每一步都记在数据库的 `agent_runs` / `agent_steps`。

上传后如果一直停在「已上传」，看后端日志。常见原因是数据库没起来，或文件不是 PDF / Markdown / 纯文本。扫描版 PDF 抽不出文字，会变成失败并写明原因。

## 每个部件

### 前端 `frontend/`

Next.js 页面：登录、知识库列表、知识库详情（上传、问答、Agent、实时状态）。

| 项 | 值 |
|---|---|
| 作用 | 浏览器里的全部操作 |
| 开发启动 | `cd frontend` 然后 `npm run dev`（脚本里已经指定 3001） |
| 地址 | <http://localhost:3001> |
| 配置 | `NEXT_PUBLIC_API_BASE`，默认 `http://localhost:8080`。改地址后要重新执行 `npm run dev` 或 `npm run build`，因为这个值在构建时写进页面 |

Docker 镜像里的前端监听 3000，那是容器内部端口，和本机开发的 3001 不是一回事。

### 后端 `backend/`

Spring Boot 3.5，Java 21。一个进程里包含接口、文档处理和默认的检索。

| 项 | 值 |
|---|---|
| 作用 | 登录、知识库、上传、检索、问答、Agent、指标 |
| 启动 | 由 `infra\start-demo.ps1` 启动，工作目录必须是 `backend`（上传文件写在 `backend\data\uploads`） |
| 地址 | <http://localhost:8080> |
| 构建 | `$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"`，然后 `mvn -B test package` |
| 产物 | `backend\target\ai-research-hub-backend-0.1.0.jar` |

主要接口：

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/api/health` | 进程和数据库是否可用 |
| POST | `/api/auth/register`、`/api/auth/login` | 注册、登录，返回 JWT |
| GET/POST/DELETE | `/api/knowledge-bases` | 知识库 |
| POST | `/api/knowledge-bases/{id}/documents` | 上传。表单字段 `file`，可选 `title` |
| GET | `/api/knowledge-bases/{id}/events?token=` | 文档状态的服务器推送 |
| POST | `/api/search` | 混合检索，响应里带四个阶段的 trace |
| POST | `/api/knowledge-bases/{id}/chat` | 问答 |
| POST | `/api/knowledge-bases/{id}/agent` | Agent |
| GET | `/api/knowledge-bases/{id}/search-logs` | 最近的检索记录 |
| GET | `/api/knowledge-bases/{id}/search-report` | 最近 200 次的空结果数和平均耗时 |
| POST | `/api/knowledge-bases/{id}/judgments` | 标注：这个问法应该命中哪篇文档 |
| POST | `/api/knowledge-bases/{id}/search-evaluation?k=5` | 用当前索引重跑标注，得到 Recall@K 和 MRR |
| GET | `/actuator/health` | 各依赖的健康详情 |
| GET | `/actuator/prometheus` | Prometheus 指标 |

除了健康检查和登录注册，请求都要带 `Authorization: Bearer <token>`。请求示例在 [docs/api.md](docs/api.md)。

### PostgreSQL `infra/pg/`

业务数据和向量都在这里。当前是 Docker 容器 `arh-postgres`，镜像 `pgvector/pgvector:pg17`。

| 项 | 值 |
|---|---|
| 作用 | 用户、知识库、文档、切片、向量、全文、会话、检索日志、Agent 日志 |
| 地址 | `localhost:5433`（本机 5432 是另一套系统 PostgreSQL，不要连错） |
| 库 / 账号 / 密码 | `ai_research_hub` / `aihub` / `aihub` |
| 启动 | `infra\pg\start-db.cmd`。没有容器时会创建，数据卷是 `arh_pgdata` |
| 停止 | `infra\pg\stop-db.cmd`。只停容器，不删数据 |
| 表结构 | `backend/src/main/resources/db/schema.sql`。新容器第一次启动时自动执行，开头会 `create extension vector` |
| 查看 | `docker exec -it arh-postgres psql -U aihub -d ai_research_hub` |

向量列是 `vector(768)`。换嵌入模型时，模型输出的维数必须仍是 768，否则写入会失败。这是故意的：维数不一致会把索引写坏。

### Redis

缓存「同一个知识库、同一句问法」的回答，默认保留 1 小时。文档重新索引或被删除后，这个知识库的缓存会清掉。

| 项 | 值 |
|---|---|
| 作用 | 问答缓存。不存登录态 |
| 本机程序 | `E:\devtools\redis\redis-server.exe --port 6379` |
| 地址 | `localhost:6379`，无密码 |
| 没启动时 | 问答照常工作，只是每次都重新检索。`/api/health` 仍然成功 |

Docker Compose 里用的是 Redis 7。本机复用的是已经装好的 Redis 5，只用到读写和过期，够这个 Demo。

### Kafka

把「文件已经收下」和「开始解析」拆开。上传接口只发一条消息，Worker 再做解析、切分和向量化。失败会最多试 3 次，然后停在失败状态，原因写在文档上。

| 项 | 值 |
|---|---|
| 作用 | 文档处理队列。主题名 `document.process` |
| 本机程序 | `E:\devtools\kafka`，KRaft，端口 9092 |
| 什么时候需要 | 只有 `PIPELINE_MODE=kafka`（启动脚本加 `-WithKafka`） |
| 默认 | `inline`：不连接 Kafka，后端自己异步处理 |

处理失败时，已经写入一半的切片会随事务回滚，不会留在检索结果里。

### 检索、问答、Agent

默认全部可以不配 API Key 跑通。它们演示的是管道，不是生产级语义搜索。

| 部件 | 默认 | 换成外部服务 |
|---|---|---|
| 嵌入 | 本地词面哈希，768 维。能走通 pgvector，但没有语义 | `EMBEDDING_PROVIDER=openai`，并设置 `EMBEDDING_API_KEY`。默认模型 `BAAI/bge-base-zh-v1.5`（768 维，硅基流动）。不要直接用 bge-m3，它是 1024 维 |
| 大模型 | 从检索片段里抽句子，不调用外部模型 | `LLM_PROVIDER=openai`，`LLM_API_KEY`，默认 DeepSeek 的 OpenAI 兼容接口 |
| 重排 | 按问题和片段的词面重合排序 | `RERANKER_PROVIDER=api` 时调用 `/rerank`，必须配置 `RERANKER_API_KEY` |
| Agent | 规则：先搜索；问题含总结意图时再摘要 | `AGENT_BRAIN=llm` 时改为模型决定调用哪个工具，必须配置 `LLM_API_KEY` |

Agent 实际注册了 4 个工具：`search_knowledge_base`、`get_document`、`list_documents`、`summarize_document`。规则大脑会用到搜索和摘要；另外两个留给以后的模型大脑调用，接口已经按知识库所有者做了权限检查。

换嵌入模型之前，先删掉旧文档再重新上传。旧的哈希向量和新模型的向量混在一个索引里，检索结果没有意义。

### Docker、Nginx、监控、CI

给「另一台装了 Docker 的机器一键拉起」用的。本机日常开发用上面的 PowerShell 脚本，不要和 Compose 同时占 5433、6379、8080。

```powershell
cd infra
docker compose up -d
docker compose --profile monitoring up -d
docker compose --profile es up es
```

| 文件 | 作用 |
|---|---|
| `infra/docker-compose.yml` | 数据库、Redis、Kafka、后端、前端、Nginx。`monitoring` 加 Prometheus（9090）和 Grafana（3002，管理员密码 `admin`）。`es` 启动 Elasticsearch（9200）。要让检索走它，再设 `SEARCH_SPARSE=elasticsearch` |
| `infra/nginx/nginx.conf` | 80 端口。`/api/` 转到后端，其余转前端。推送接口关闭了代理缓冲 |
| `infra/monitoring/prometheus.yml` | 抓取后端的 `/actuator/prometheus` |
| `.github/workflows/ci.yml` | 推送后跑后端测试打包和前端构建 |

Compose 里的前端构建时会把接口地址写死。本机浏览器直接访问 8080 可以工作；若只通过 Nginx 的 80 端口访问，前端仍会去请求 `http://localhost:8080`，因为 `NEXT_PUBLIC_API_BASE` 是构建期变量。这是当前 Compose 的限制。

## 配置

后端读环境变量，缺省值写在 `backend/src/main/resources/application.yml`。不要把真实密钥写进这个文件。

| 变量 | 默认 | 含义 |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5433/ai_research_hub` | 数据库 |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `aihub` / `aihub` | 数据库账号 |
| `JWT_SECRET` | 仅供开发的一串字符 | `AUTH_ISSUER=local` 时签发登录令牌。生产必须换成至少 32 字节的随机值 |
| `AUTH_ISSUER` | `local` | `supabase` 时改为校验 Supabase 的 JWT，不再接受本地签发的令牌 |
| `SUPABASE_JWT_SECRET` | 空 | Supabase 项目的 JWT Secret，至少 32 字节。只在 `AUTH_ISSUER=supabase` 时使用 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3001` | 允许的前端来源，多个用逗号分隔 |
| `STORAGE_PROVIDER` | `local` | `supabase` 时原文改存 Supabase Storage |
| `STORAGE_ROOT` | `./data/uploads` | 仅 `local` 时使用，相对后端工作目录 |
| `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` | 空 | `STORAGE_PROVIDER=supabase` 时必填。service role 只放在后端 |
| `SUPABASE_STORAGE_BUCKET` | `documents` | 私有桶名称 |
| `PIPELINE_MODE` | `inline` | `inline` 或 `kafka` |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | 缓存 |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | 只有 kafka 模式会用到 |
| `KAFKA_DOCUMENT_TOPIC` | `document.process` | 文档处理主题 |
| `KAFKA_HEALTH_ENABLED` | `false` | 为 true 时，Kafka 挂了会让 `/actuator/health` 变成 DOWN |
| `EMBEDDING_PROVIDER` | `local` | `local` 或 `openai` |
| `EMBEDDING_DIMS` | `768` | 必须和数据库向量列一致 |
| `EMBEDDING_BASE_URL` | `https://api.siliconflow.cn/v1` | OpenAI 兼容嵌入服务 |
| `EMBEDDING_MODEL` | `BAAI/bge-base-zh-v1.5` | 须输出 768 维 |
| `EMBEDDING_API_KEY` | 空 | `openai` 时必填，否则后端拒绝启动 |
| `LLM_PROVIDER` | `extractive` | `extractive` 或 `openai` |
| `LLM_BASE_URL` | `https://api.deepseek.com/v1` | OpenAI 兼容对话接口 |
| `LLM_MODEL` | `deepseek-chat` | 模型名 |
| `LLM_API_KEY` | 空 | `openai` 时必填 |
| `LLM_TIMEOUT_SECONDS` | `60` | 预留的超时配置 |
| `RERANKER_PROVIDER` | `lexical` | `api` 走 `/rerank`。还要配 `RERANKER_API_KEY`、`RERANKER_BASE_URL`、`RERANKER_MODEL` |
| `RAG_CHUNK_SIZE` / `RAG_CHUNK_OVERLAP` / `RAG_TOP_K` | `800` / `100` / `5` | 切分长度、重叠、问答取回条数 |
| `SEARCH_CANDIDATE_LIMIT` / `SEARCH_RRF_K` | `30` / `60` | 每一路召回条数、RRF 的 k |
| `SEARCH_SPARSE` | `postgres` | `elasticsearch` 时稀疏检索改走 Elasticsearch |
| `ELASTICSEARCH_URL` | `http://localhost:9200` | 只有 `SEARCH_SPARSE=elasticsearch` 时会连接，连不上则后端拒绝启动 |
| `CHAT_CACHE_TTL_SECONDS` | `3600` | 问答缓存时间 |
| `AGENT_BRAIN` / `AGENT_MAX_STEPS` | `rule` / `6` | `rule` 不需要 Key。`llm` 走 OpenAI 兼容工具调用，使用 `LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL` |
| `NEXT_PUBLIC_API_BASE` | `http://localhost:8080` | 仅前端 |

PowerShell 示例（换成真实嵌入和大模型，维数仍是 768）：

```powershell
$env:EMBEDDING_PROVIDER = "openai"
$env:EMBEDDING_API_KEY = "你的密钥"
$env:LLM_PROVIDER = "openai"
$env:LLM_API_KEY = "你的密钥"
```

然后重新启动后端。已经用本地哈希索引过的文档要删掉重传。

## 目录

```text
ai-research-hub/
├── frontend/          页面
├── backend/           接口与文档处理
├── infra/
│   ├── start-demo.ps1 / stop-demo.ps1
│   ├── pg/            数据库容器启停
│   ├── nginx/         反向代理
│   ├── monitoring/    Prometheus 与 Grafana
│   └── docker-compose.yml
├── docs/              架构、表、接口、部署、决策
└── .github/workflows/ci.yml
```

## 检索评测怎么用

先标标准答案，再打分。打分会按现在的索引重新搜索，不拿以前的检索日志充数。

```powershell
$B = "http://localhost:8080"
$login = Invoke-RestMethod -Method POST -ContentType "application/json" -Body '{"email":"demo@aireshub.dev","password":"demo12345"}' "$B/api/auth/login"
$H = @{ Authorization = "Bearer $($login.accessToken)" }
$kb = (Invoke-RestMethod -Headers $H "$B/api/knowledge-bases")[0].id
# documentId 换成该库里某篇文档的 id
Invoke-RestMethod -Method POST -Headers $H -ContentType "application/json" -Body '{"query":"ML-DSA 证书有多大","documentId":"文档uuid"}' "$B/api/knowledge-bases/$kb/judgments"
Invoke-RestMethod -Method POST -Headers $H "$B/api/knowledge-bases/$kb/search-evaluation?k=5"
```

`recallAtK` 越接近 1，说明该命中的文档越经常出现在前 K 条里。`mrr` 越接近 1，说明第一篇相关文档排得越靠前。

## 未完成任务

本地 Demo 可以注册、建库、上传、检索、问答和规则 Agent。下面这些没有在这台机器上跑通。代码里留了开关，缺的是外部条件，或还没写的前端接入。

| 事项 | 现在的状态 | 要做什么 |
|---|---|---|
| 真实嵌入 | 默认是本地词面哈希，没有语义 | 设置 `EMBEDDING_PROVIDER=openai` 和 `EMBEDDING_API_KEY`。模型必须输出 768 维（默认 `BAAI/bge-base-zh-v1.5`）。换模型后删掉旧文档再上传 |
| 真实大模型问答 | 默认从片段里抽句子 | 设置 `LLM_PROVIDER=openai` 和 `LLM_API_KEY` |
| 模型决定工具的 Agent | 代码在，默认仍是规则大脑 | 设置 `LLM_API_KEY`，再把 `AGENT_BRAIN` 改成 `llm`。没有 Key 时不要改这个值，后端会拒绝启动 |
| 外部重排 | 代码在，默认是词面重合 | 设置 `RERANKER_PROVIDER=api` 和 `RERANKER_API_KEY` |
| Elasticsearch | 稀疏检索能切换，本机没有把集群拉起来 | 内存够时 `SEARCH_SPARSE=elasticsearch`，并配置 `ELASTICSEARCH_URL`。向量仍在 PostgreSQL。这台开发机空闲内存大约只剩 1GB 出头，不要在 Demo 开着时再起默认 1GB 堆的 Elasticsearch |
| Supabase 登录 | 后端能校验 Supabase 的 JWT，前端仍是自己的登录页 | 设置 `AUTH_ISSUER=supabase` 和 `SUPABASE_JWT_SECRET`。前端还没有 supabase-js |
| Supabase Storage | 后端能把原文写到 Storage REST，默认仍是本地磁盘 | 先在云上建私有桶 `documents`，再设 `STORAGE_PROVIDER=supabase`、`SUPABASE_URL`、`SUPABASE_SERVICE_ROLE_KEY` |
| Supabase 数据库、Realtime、RLS | 用的是本地 PostgreSQL 和 SSE | 把 `DATABASE_URL` 指到云上的库并执行 `schema.sql`。进度推送还是 SSE。行级权限仍在服务层按所有者过滤，没有启用 RLS |
| 嵌入对照实验 | 评测接口能算 Recall@K 和 MRR | 配上真实嵌入之后，用同一组标注再跑一遍，和本地哈希对比 |

默认混合检索由 PostgreSQL 完成。Elasticsearch 是稀疏检索的可切换实现，不是第二套业务库。
