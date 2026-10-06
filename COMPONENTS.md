# 组件与安装位置记录

> 生成于 2026-10-06(AI Research Hub Demo 搭建当天)。
> 原则:**尽量不占用 C 盘**,新装/新下载的东西都在 D/E 盘;已存在组件仅复用、未移动。

## 一、已存在的组件(本次复用,未改动位置)

| 组件 | 版本 | 位置 | 备注 |
|---|---|---|---|
| JDK | 21.0.2 | `C:\Program Files\Java\jdk-21` | 已存在;**注意用户默认 `JAVA_HOME` 指向 jdk-17**,构建本项目需临时覆盖(见下) |
| JDK | 17.0.10 | `C:\Program Files\Java\jdk-17` | 已存在,`JAVA_HOME` 当前指向它 |
| Maven | 3.9.9 | `E:\devtools\maven` | 已存在 |
| Node.js | 24.19.0 | `C:\Program Files\nodejs` | 已存在 |
| Docker Desktop | 29.8.1 | 系统安装 | 已存在,守护进程运行正常(后续 Phase 用) |
| PostgreSQL | 18.4 | `E:\PostgreSql`(数据 `E:\PostgreSql\data`) | 已存在,**注册为系统服务 `postgresql-x64-18`,占用 5432 端口**,超管密码未知,本次未动它 |

## 二、本次新增 / 新配置(均在 D/E 盘;Docker 卷除外)

| 项目 | 位置 | 说明 |
|---|---|---|
| **PostgreSQL(当前方案)** | Docker 容器 `arh-postgres`,镜像 `pgvector/pgvector:pg17`(PG 17.11 + pgvector 0.8.7) | **端口 5433**,数据卷 `arh_pgdata`,账号 `aihub/aihub`,库 `ai_research_hub`;`--restart unless-stopped` 随 Docker 自启;启停用 `infra\pg\start-db.cmd / stop-db.cmd`(即 docker start/stop) |
| **PostgreSQL(备用:Windows 原生集群)** | `E:\devtools\pgdata-ai-research-hub` | PG 18.4(E:\PostgreSql 二进制)+ 自编译 pgvector 0.8.1;因反复出现 Windows ASLR 487 子进程崩溃,2026-10-06 晚起**停用作后备**;数据未删 |
| **pgvector 0.8.1 源码编译产物** | `E:\devtools\pgvector-build\pkg` | 仅供上面备用集群使用(集群 conf 的 GUC 引用此目录);容器方案自带 pgvector 0.8.7,不依赖它 |
| **Redis 5.0.14.1(Windows 原生)** | `E:\devtools\redis` | tporadowski/redis 发行版;`redis-server.exe --port 6379` 启动(无持久化,Demo 用);Redis 7 生产版见 infra/docker-compose.yml |
| **Kafka 4.3.1(KRaft 单机)** | `E:\devtools\kafka`(数据 `E:\devtools\kafka\data`) | `bin/kafka-server-start.sh config/broker.properties`(Git Bash 启动);动态仲裁模式;9092/9093 |
| **Maven 本地仓库** | `E:\devtools\maven-repo` | 通过用户级 `C:\Users\Lenovo\.m2\settings.xml` 的 `<localRepository>` 指定;原 `~/.m2/repository` 保留未动 |
| Maven 镜像 | 阿里云 public(`maven.aliyun.com/repository/public`) | 写在同一 `settings.xml` 的 `<mirrors>`,仅镜像 central;国内拉 Spring 依赖快 |
| **npm 缓存** | `E:\devtools\npm-cache` | `npm config set cache "E:\devtools\npm-cache"`(写入 `~/.npmrc`);回滚:`npm config delete cache` |
| 前端项目依赖 | `E:\zcode_test\ai-research-hub\frontend\node_modules` | 随项目在 E 盘 |
| 本项目 | `E:\zcode_test\ai-research-hub` | 前端 3001 / 后端 8080 / 数据库 5433 / Redis 6379 / Kafka 9092 |

> Docker 卷(arh_pgdata)位于 Docker Desktop 的 WSL 虚拟磁盘中,不在 C 盘用户目录;
> 如需彻底迁移可后续把卷换成 E 盘绑定目录。

## 二.一、环境事件与处理记录(2026-10-06)

- **Docker Desktop 引擎故障**(下午):`docker version` 报 "Docker Desktop is unable to start",镜像拉取 500 → 当时全部改用原生 Windows 组件。**用户重启机器后已恢复**(引擎 29.8.1 正常、可拉镜像),恢复后即把数据库迁入容器(见下)。
- **WSL2 Ubuntu-24.04 起不来**(HCS ERROR_NO_SYSTEM_RESOURCES)——与 Docker 同因,**重启后已恢复**(内核 6.18.40.1)。
- **PostgreSQL 487 事件**:Windows 原生集群(5433)反复出现 "could not reserve shared memory region"(ASLR 与共享内存地址预留冲突),缩小 shared_buffers 只能暂时缓解、复发两次。
  **最终解决:数据库迁入 Docker 容器 `arh-postgres`**(pgvector/pgvector:pg17,Linux 引擎不受此问题影响),同端口同账号,后端零改动。原生集群数据保留在 `E:\devtools\pgdata-ai-research-hub` 作后备。
- 当时多个 psql "挂起"其实是**没带 PGPASSWORD 在等密码输入**(Git Bash 下请始终 `PGPASSWORD=... psql ... </dev/null`)。

## 三、端口占用(本 Demo 相关)

| 端口 | 用途 |
|---|---|
| 5432 | 系统 PostgreSQL 18 服务(原有,**不使用**) |
| 5433 | 项目 PostgreSQL(容器 arh-postgres,`infra/pg/*.cmd` 启停) |
| 6379 | 项目 Redis(原生 Windows 版,E:\devtools\redis) |
| 9092 / 9093 | Kafka broker / controller(E:\devtools\kafka,KRaft 单机) |
| 8080 | Spring Boot 后端 |
| 3001 | Next.js 前端(**3000 被 Cursor 编辑器占用**,故用 3001) |

## 四、构建/运行注意事项

1. **JDK 版本**:项目按 Java 21 编译。当前 shell 的 `JAVA_HOME` 是 jdk-17,构建与运行前需临时覆盖:
   ```bash
   export JAVA_HOME="/c/Program Files/Java/jdk-21"   # Git Bash
   # CMD: set "JAVA_HOME=C:\Program Files\Java\jdk-21"
   ```
   只对当次命令生效,未改系统环境变量。若想永久解决,可把 `JAVA_HOME` 指到 jdk-21(由用户决定)。
2. **启动顺序**(本机完整 Demo):
   ```bash
   infra\pg\start-db.cmd                        # PostgreSQL 容器(5433)
   E:\devtools\redis\redis-server.exe --port 6379   # Redis
   cd E:\devtools\kafka && bin/kafka-server-start.sh config/broker.properties   # Kafka(Git Bash)
   cd backend && PIPELINE_MODE=kafka java -jar target/ai-research-hub-backend-0.1.0.jar
   cd frontend && npm run dev -- -p 3001
   ```
   `PIPELINE_MODE` 缺省为 inline(不依赖 Kafka);kafka 模式走 Worker 异步处理。
3. **psql 使用**(容器方案直接 `docker exec arh-postgres psql -U aihub -d ai_research_hub ...`;备用集群务必 `PGPASSWORD=... </dev/null`,否则会停在密码提示看起来像"挂起")。
4. **Supabase 云端**:本 Demo 用本地 PG 模拟 Supabase 的 PostgreSQL;接入云端项目时只需改 `DATABASE_URL` 等环境变量,见 docs/decisions.md。

## 五、如何回滚本次新增配置

- Maven 仓库/镜像:删除 `C:\Users\Lenovo\.m2\settings.xml`(或删掉其中 `localRepository` / `mirrors` 段)
- npm 缓存:`npm config delete cache`
- 项目数据库(容器):`docker rm -f arh-postgres && docker volume rm arh_pgdata`;若想回到 Windows 原生集群,先 `docker stop arh-postgres` 释放 5433,再用旧 conf 启动 `E:\devtools\pgdata-ai-research-hub`(注意 487 可能需要多重启几次)
- pgvector 编译产物:删除 `E:\devtools\pgvector-build` 目录(仅备用集群在用)
- Redis:停止进程后删除 `E:\devtools\redis`;Kafka:停止进程后删除 `E:\devtools\kafka`
