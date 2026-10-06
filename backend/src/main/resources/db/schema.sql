-- =============================================================
-- AI Research Hub — Schema (PostgreSQL 17 + pgvector)
-- 对应路线图的表设计、pgvector 与全文检索。
-- 容器镜像 pgvector/pgvector:pg17 已带扩展文件,这里负责在库里启用。
-- Supabase 云端同样执行这一句即可。
-- 向量列固定 768 维,必须与 app.embedding.dims 以及所用模型一致。
-- =============================================================

create extension if not exists vector;

create table if not exists users (
    id            uuid primary key default gen_random_uuid(),
    email         varchar(255) not null unique,
    password_hash varchar(255),
    display_name  varchar(100),
    created_at    timestamptz  not null default now()
);

create table if not exists knowledge_bases (
    id          uuid primary key default gen_random_uuid(),
    owner_id    uuid         not null references users (id) on delete cascade,
    name        varchar(200) not null,
    description text,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);
create index if not exists idx_kb_owner on knowledge_bases (owner_id);

create table if not exists documents (
    id                uuid primary key default gen_random_uuid(),
    knowledge_base_id uuid           not null references knowledge_bases (id) on delete cascade,
    title             varchar(300)   not null,
    filename          varchar(500)   not null,
    storage_path      varchar(1000)  not null,
    mime_type         varchar(150),
    size_bytes        bigint,
    status            varchar(30)    not null default 'UPLOADED',
    error_message     text,
    retry_count       int            not null default 0,
    created_at        timestamptz    not null default now(),
    updated_at        timestamptz    not null default now(),
    constraint documents_status_check
        check (status in ('UPLOADED', 'PROCESSING', 'INDEXED', 'FAILED'))
);
create index if not exists idx_doc_kb on documents (knowledge_base_id);

create table if not exists document_chunks (
    id          uuid primary key default gen_random_uuid(),
    document_id uuid      not null references documents (id) on delete cascade,
    chunk_index int       not null,
    content     text      not null,
    metadata    jsonb     not null default '{}'::jsonb,
    -- Phase 2: 768 维向量(维度由 app.embedding.dims 决定,改维度需同步改列)
    embedding   vector(768),
    -- Phase 3: BM25-ish 全文检索(simple 配置 + 中文 bigram 预分词,应用侧维护)
    fts         tsvector,
    created_at  timestamptz not null default now(),
    unique (document_id, chunk_index)
);
create index if not exists idx_chunks_doc on document_chunks (document_id);
create index if not exists idx_chunks_hnsw on document_chunks using hnsw (embedding vector_cosine_ops);
create index if not exists idx_chunks_fts on document_chunks using gin (fts);

create table if not exists chat_sessions (
    id                uuid primary key default gen_random_uuid(),
    user_id           uuid        not null references users (id) on delete cascade,
    knowledge_base_id uuid        references knowledge_bases (id) on delete set null,
    title             varchar(300),
    created_at        timestamptz not null default now()
);

create table if not exists messages (
    id         uuid primary key default gen_random_uuid(),
    session_id uuid        not null references chat_sessions (id) on delete cascade,
    role       varchar(20) not null check (role in ('user', 'assistant', 'system')),
    content    text        not null,
    created_at timestamptz not null default now()
);
create index if not exists idx_messages_session on messages (session_id);

-- Phase 3: 检索过程留痕(用于评估,路线图 §13)
create table if not exists search_logs (
    id               uuid primary key default gen_random_uuid(),
    user_id          uuid        not null references users (id) on delete cascade,
    knowledge_base_id uuid       not null references knowledge_bases (id) on delete cascade,
    query            text        not null,
    dense_results    jsonb,
    sparse_results   jsonb,
    fused_results    jsonb,
    reranked_results jsonb,
    latency_ms       int,
    created_at       timestamptz not null default now()
);

-- Phase 8: Agent 运行留痕(路线图 §19)
create table if not exists agent_runs (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid        not null references users (id) on delete cascade,
    knowledge_base_id uuid  references knowledge_bases (id) on delete set null,
    input        text        not null,
    final_answer text,
    token_usage  jsonb,
    latency_ms   int,
    created_at   timestamptz not null default now()
);

create table if not exists agent_steps (
    id         uuid primary key default gen_random_uuid(),
    run_id     uuid        not null references agent_runs (id) on delete cascade,
    step_no    int         not null,
    tool       varchar(100) not null,
    args       jsonb,
    result_summary text,
    latency_ms int,
    created_at timestamptz not null default now()
);
create index if not exists idx_agent_steps_run on agent_steps (run_id);

-- 检索评测的标准答案。一次提问可以对应多篇应命中的文档。
create table if not exists search_judgments (
    id                uuid primary key default gen_random_uuid(),
    knowledge_base_id uuid        not null references knowledge_bases (id) on delete cascade,
    query             text        not null,
    document_id       uuid        not null references documents (id) on delete cascade,
    created_at        timestamptz not null default now(),
    unique (knowledge_base_id, query, document_id)
);
create index if not exists idx_judgments_kb on search_judgments (knowledge_base_id);
