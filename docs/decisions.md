# 技术决策记录(decisions)

按路线图 §28 的要求,记录"为什么"。

## 1. 为什么用 Supabase(路线图选型)?

- 一个项目同时要 PostgreSQL + Auth + Storage + Realtime,Supabase 把这些通用后端能力
  打包好,RLS/行级权限开箱即用,个人/小团队几乎零运维;
- 底层就是 PostgreSQL,不是黑盒:复杂查询、pgvector、FTS 都能直接用;
- 免费档足够 Demo 与学习。

## 2. 为什么 Demo 阶段用"本地 PG + 后端自签 JWT"替代 Supabase?

- 注册 Supabase 云项目需要账号与人工操作(邮箱验证、拿 API Key),本次自动化环境不具备;
- 路线图允许"本地跑通"("一个开发者可以在本地和 Supabase 云环境中跑通完整 Demo");
- 替代实现刻意**模仿了 Supabase 的边界**:前端拿 `access_token`(JWT)→ 后端校验识别
  user_id → 按用户隔离数据。因此切换到云端时,前端换 supabase-js 登录、后端换 JWT 校验
  密钥、`DATABASE_URL` 换连接串,业务代码几乎不动(见 deployment.md 第四节)。

## 3. 为什么数据隔离放在服务层而不是 RLS?

RLS 需要"每请求设置会话变量"或"Supabase JWT 直连 PG"。当前所有流量经 Spring Boot、
单一 DB 账号连接,owner_id 过滤放在 Repository/Service 是最直接、可测的(已实测 404/403)。
RLS 属于"接 Supabase 后前端直连场景"的配套,届时在云端补策略。

## 4. 为什么 Spring Boot 用 3.5 + Java 21?

- Java 21(本机已有),LTS;Spring Boot 3.5 稳定线,JPA/Security 生态成熟;
- 没上 Spring Boot 4:Demo 优先稳定,3.5 → 4 的升级路径清晰,不值得在第一阶段冒险。

## 5. 为什么文件存本地磁盘而不是塞数据库?

路线图 Phase 4 明确:不要把 PDF 二进制直接存 PostgreSQL。DB 只存
`filename / storage_path / mime_type / size_bytes / status`。本地目录
`app.storage.root` 模拟 Supabase Storage,切换时只动 `DocumentService` 的读写实现。

## 6. Redis / Kafka / Elasticsearch 各自解决什么

这一节早期写的是"先不要加"。后面的阶段已经加了 Redis 和 Kafka,结论以这里为准:

- Redis:同一问题的重复问答不必再跑检索和 LLM。它挂了只是不缓存,问答仍然可用。
- Kafka:上传请求只负责把文件收下,解析和向量化放到 Worker,避免接口被大文件拖住。默认 `PIPELINE_MODE=inline` 时不需要它。
- Elasticsearch:复杂全文、高亮、聚合才需要它。当前稀疏检索用的是 PostgreSQL 全文检索,compose 里的 `es` profile 还没有接到 SearchService。没有它,现有 Hybrid Search 仍然成立。

## 7. 为什么前端跑在 3001?

本机 3000 端口被 Cursor 编辑器进程长期占用。`npm run dev -- -p 3001`,
后端 CORS 白名单同步配置 `http://localhost:3001`(`CORS_ALLOWED_ORIGINS` 可改)。

## 8. 为什么 Schema 用 SQL 文件而不是 JPA 自动建表?

- `ddl-auto: update` 会让结构漂移、不可审查;`validate` + schema.sql 让数据库结构
  可版本化、可重放,也为 Phase 2 手工加 pgvector 列/HNSW 索引留了口子;
- 与 Supabase 的使用方式一致(云端也是在 SQL Editor 里跑迁移)。

## 9. 为什么 JWT 放 Authorization 头、无状态会话?

与 Supabase Auth 的行为对齐(返回 access_token,前端持有,后端无状态校验),
未来多实例/水平扩展不需要粘性会话。Demo 阶段 HS256 对称密钥即可,上云后可换 JWKS 验签。

## 10. 遗留与已知的坑

- Windows Git Bash 控制台发中文 JSON 是 GBK 编码 → 后端会 400(已映射为友好错误);
  中文请走 UTF-8 文件或浏览器(已记入 api.md);
- 本机 `JAVA_HOME` 指向 JDK 17,构建本项目需临时指向 JDK 21(见 COMPONENTS.md);
- Hibernate `@CreationTimestamp` 在 6.6 中部分生成值延迟到 flush,create 响应曾出现
  `createdAt: null`,已改为服务层显式赋值(实体注解保留兜底)。

## 11.(Phase 2)为什么 pgvector 用 MSVC 本地编译而不是 Docker?

Docker Desktop 引擎在本机故障("unable to start",镜像拉取 500),WSL2 也起不来
(HCS ERROR_NO_SYSTEM_RESOURCES,疑似系统级虚拟化/注入问题)。本机有 MSVC Community
(D 盘)+ PG 自带头文件(E 盘),编译 pgvector 0.8.1 是最干净的全本地路径。
由于 `E:\PostgreSql` 目录受管理员保护,用 PG18 新增的 `extension_control_path` +
`dynamic_library_path` GUC 加载(注意:GUC 路径要写**父目录**,服务端自动追加 `/extension`)。
Supabase 云端自带 pgvector,无需这一切。

## 12.(Phase 2)为什么默认 Embedding 是"词面哈希向量"?

没有可用的 Embedding API Key,而自训模型超出 Demo 范围。`LocalHashEmbeddingService`
(CJK bigram + 特征哈希,768 维,L2 归一化)零依赖、确定性、能真实驱动 pgvector/HNSW
链路;但它只有词面相似度、没有语义。诚实定位:**演示检索管道机制,不冒充语义检索**。
`EmbeddingService` 接口已预留 `app.embedding.provider=openai`(OpenAI 兼容 /embeddings),
配置 Key 即切换,业务代码零改动。

## 13.(Phase 3)为什么 FTS 用 simple 配置 + bigram 预分词?

PostgreSQL 默认分词器不切中文。Demo 采用应用侧 bigram 预分词(与 Embedding/Reranker
共用同一分词器)写入 `fts tsvector`(GIN 索引),查询侧用 OR-tsquery。
生产中文场景应换 zhparser/pg_jieba 或 Elasticsearch(Phase 10)。

## 14.(Phase 4)为什么 Redis 用 Windows 原生 5.0 而不是 Docker?

同决策 11:Docker 不可用。tporadowski/redis 5.0.14(E:\devtools\redis)只用到
GET/SET/EXPIRE,Redis 5 完全满足;生产/compose 提供的是 redis:7-alpine。
缓存逻辑带静默降级:Redis 宕机只损失缓存,不影响问答。

## 15.(Phase 5)为什么 Kafka 用 4.3.1 且经 Git Bash 运行?

Docker 不可用;Kafka 4.0 起官方删除了 Windows 脚本,但 4.3.1 的 Unix 脚本在 Git Bash
下可正常运行(KRaft 动态仲裁单机,E:\devtools\kafka)。镜像源只剩 4.x,而
archive.apache.org 的 3.9(有 Windows 脚本)下载慢,实测 4.3.1 直接可用。
上传→document.process 事件→Worker 消费→状态机 UPLOADED→PROCESSING→INDEXED(失败
重试 3 次、retry_count 落库)已验证;`PIPELINE_MODE=inline|kafka` 双模式可切换。

## 16.(Phase 9)为什么用 SSE 替代 Supabase Realtime?

Supabase Realtime 需要云端项目。本地等价物:SSE(`/api/knowledge-bases/{id}/events`,
EventSource + token 查询参数),文档状态变化实时推送到前端,事件语义与 Realtime 一致;
接云端后可平移换成 Realtime Channel。注意 Nginx 代理 SSE 需 `proxy_buffering off`(已配置)。

## 17.(Phase 10)为什么 Elasticsearch 只提供配置而没有实际运行?

当时没有把检索接到 Elasticsearch：单机 ES 8 默认要大约 1GB 堆，而这台机器给 Demo 留出的空闲内存经常只剩 1GB 出头。混合检索的第一版由 PostgreSQL 全文加 pgvector 完成。

现在的换法是环境变量，不是改代码：`SEARCH_SPARSE=elasticsearch` 且 `ELASTICSEARCH_URL` 指向集群。稀疏检索改走 Elasticsearch，向量仍在 PostgreSQL。中文使用和 PostgreSQL 相同的 bigram，所以不装 IK 也能和原来的全文结果对照。默认值仍是 `postgres`，不启动 Elasticsearch 时 Demo 照常启动。

## 18.(Phase 10)为什么 /actuator/** 放行匿名访问?

Prometheus 抓取不带业务凭据。Demo 阶段以简单换可观测;生产应在网关/网络层隔离
actuator 或加独立认证。

## 20.(环境)为什么数据库最终迁入 Docker 容器?

Windows 原生集群(5433)当天两次复发 ASLR 487 子进程崩溃(见 §19),缩小 shared_buffers
只能碰运气。用户重启机器后 Docker 引擎恢复,遂将数据库迁入 `pgvector/pgvector:pg17`
容器(同端口 5433、同账号 aihub、同库,后端零改动;数据卷 arh_pgdata;--restart
unless-stopped 随 Docker 自启)。Linux 引擎不受 Windows ASLR 问题影响,彻底根治。
原生集群数据保留在 E 盘作后备。迁移后已重验:pgvector 0.8.7、Kafka Worker 索引、
Hybrid 检索、RAG 引用问答、Agent 两步循环全部正常。附带的收获:容器镜像的 pgvector
(0.8.7)比本地编译的 0.8.1 更新,且 Schema 加载前的 `create extension vector` 已写进
compose 的 init 流程。

## 19. 排障记录(2026-10-06)

- **PG 487 事件**:19:57-20:13,PG 子进程"could not reserve shared memory region"
  (Windows ASLR 与共享内存地址预留冲突),重启+缩小 shared_buffers 至 16MB 后恢复;
  若复发,继续缩小 shared_buffers 重启即可。当时误判为"系统级故障",实际多数
  psql "挂起"是没带 PGPASSWORD 在等密码输入——Git Bash 下请始终
  `PGPASSWORD=... psql ... </dev/null`。
- **上传后文档停在 UPLOADED**:上传事务未提交时触发 @Async 管线,Worker 读不到未提交
  行 → 改为事务提交后触发(TransactionSynchronization.afterCommit)。
- **Kafka 路径报 Executing an update/delete query**:@Modifying 需要 @Transactional;
  Worker 直接调 process(),而事务注解原本只在 processAsync 上 → 给 process() 补上
  @Transactional(inline 路径事务传播 REQUIRED,行为不变)。
