# API 文档(当前实现)

Base URL:`http://localhost:8080`
统一错误格式:`{"error": "<原因>"}`;除 Health / Auth 外都需要 `Authorization: Bearer <JWT>`。

## Health

```http
GET /api/health
→ 200 {"status":"ok","database":"up"}
数据库连不上时返回 503。Redis 或 Kafka 不可用不会让这个接口失败。
```

## Auth

```http
POST /api/auth/register
{"email":"demo@aireshub.dev","password":"demo12345","displayName":"Demo 研究员"}
→ 201 {"accessToken":"eyJ...","user":{"id":"…","email":"demo@aireshub.dev","displayName":"Demo 研究员"}}
   409 {"error":"该邮箱已注册"}   400 参数不合法

POST /api/auth/login
{"email":"demo@aireshub.dev","password":"demo12345"}
→ 200 同上   401 {"error":"邮箱或密码错误"}
```

## Knowledge Base

```http
POST /api/knowledge-bases        {"name":"PQC 论文库","description":"后量子密码 TLS 相关论文"}
→ 201 {"id":"…","name":"…","description":"…","createdAt":"…","updatedAt":"…"}

GET  /api/knowledge-bases        → 200 [KbView…](仅自己的,按创建时间倒序)
GET  /api/knowledge-bases/{id}   → 200 KbView   404 不存在或非本人
DELETE /api/knowledge-bases/{id} → 204(级联删文档行 + 磁盘文件)
```

## Document

```http
POST /api/knowledge-bases/{kbId}/documents       # multipart/form-data
  file : 二进制(application/pdf | text/markdown | text/plain,≤20MB)
  title: 可选,默认文件名
→ 201 {"id":"…","knowledgeBaseId":"…","title":"…","filename":"…",
       "mimeType":"text/markdown","sizeBytes":239,"status":"UPLOADED","createdAt":"…"}
  # 上传后 Kafka Worker 异步处理,状态经 SSE 推送:PROCESSING → INDEXED / FAILED

GET    /api/knowledge-bases/{kbId}/documents     → 200 [DocumentView…]
DELETE /api/documents/{id}                       → 204
```

## Search(Phase 3:Hybrid Search 调试/评估入口)

```http
POST /api/search
{"knowledgeBaseId":"…","query":"混合密钥交换的性能开销是多少"}
→ 200 {
  "results":[ {"chunkId":"…","documentTitle":"…","content":"…","score":0.051} ],   // 重排后 top5
  "trace": { "dense":[…], "sparse":[…], "fused":[…], "reranked":[…] },              // 各阶段留痕
  "latencyMs": 70
}
```

每次调用写 `search_logs`(dense/sparse/fused/reranked 四阶段 + latency)。

## Chat(Phase 2/4:RAG 问答 + 引用 + 查询缓存)

```http
POST /api/knowledge-bases/{kbId}/chat
{"question":"TLS 证书签名迁移到 ML-DSA 会带来什么问题?","sessionId":null}
→ 200 {
  "sessionId":"…","answer":"…[1] …[2]","provider":"extractive|openai:*",
  "citations":[ {"n":1,"documentId":"…","documentTitle":"…","chunkId":"…","snippet":"…","score":0.06} ],
  "latencyMs":446, "fromCache":false        // 第二次同问题 → true(Redis 查询缓存)
}

GET /api/chat/sessions/{sessionId}/messages   → 200 [{"role":"user|assistant","content":"…"}]
```

## Agent(Phase 8:Agentic RAG)

```http
POST /api/knowledge-bases/{kbId}/agent
{"question":"总结一下这份笔记的部署现状"}
→ 200 {
  "runId":"…","answer":"…[1][2]",
  "steps":[ {"stepNo":1,"tool":"search_knowledge_base","resultSummary":"检索「…」命中 3 个片段",…},
            {"stepNo":2,"tool":"summarize_document",…} ],
  "latencyMs":31
}
# 工具集(≤5):search_knowledge_base / get_document / list_documents / summarize_document
# 全程留痕:agent_runs / agent_steps

GET /api/agent/runs/{runId}   → 200 运行回放
```

## Realtime(Phase 9:SSE)

```http
GET /api/knowledge-bases/{kbId}/events?token=<JWT>     # EventSource;token 走查询参数
→ text/event-stream
  event: document-status
  data: {"documentId":"…","title":"…","status":"INDEXED","errorMessage":null}
```

## 检索日志

```http
GET /api/knowledge-bases/{kbId}/search-logs?limit=20
GET /api/knowledge-bases/{kbId}/search-report
```

`search-logs` 返回最近若干次检索的问题、耗时和重排后的条数。
`search-report` 汇总最近 200 次里的空结果次数和平均耗时。

## 检索评测

标准答案和历史日志是两件事。标注记「这个问题应该命中哪篇文档」，评测会用当前索引把这些问题重新搜一遍。

```http
POST /api/knowledge-bases/{kbId}/judgments
{"query":"ML-DSA 证书有多大","documentId":"<文档 uuid>"}
→ 201

GET  /api/knowledge-bases/{kbId}/judgments
DELETE /api/knowledge-bases/{kbId}/judgments/{judgmentId}

POST /api/knowledge-bases/{kbId}/search-evaluation?k=5
→ {"k":5,"queries":1,"hits":1,"recallAtK":1.0,"mrr":1.0,"details":[...]}
```

同一个问题可以标多篇文档。Recall@K 是每题命中篇数除以应命中篇数，再取平均。MRR 是第一篇命中文档排名的倒数，没命中记 0。

## Observability(Phase 10)

```http
GET /actuator/health          → {"status":"UP",…}
GET /actuator/prometheus      → Prometheus 文本指标(含自定义 arh_chat_cache_total{result=hit|miss})
```

## 隔离性(已实测)

用户 B 携带自己的 token:
- `GET /api/knowledge-bases/{A的库}` → **404**
- `POST /api/knowledge-bases/{A的库}/documents` → **403**

## curl 快速自测

```bash
B=http://localhost:8080
TOKEN=$(curl -s -X POST $B/api/auth/login -H "Content-Type: application/json" \
  -d '{"email":"demo@aireshub.dev","password":"demo12345"}' \
  | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')
curl -s $B/api/knowledge-bases -H "Authorization: Bearer $TOKEN"
```

> 注意:Windows Git Bash 控制台发中文 JSON 会按 GBK 编码导致后端 400/乱码;
> 中文内容请写入 UTF-8 文件后用 `--data-binary @file.json` 发送(浏览器前端无此问题)。
