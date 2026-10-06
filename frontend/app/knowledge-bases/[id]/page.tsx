"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import {
  AgentResponse,
  ApiError,
  ChatResponse,
  Citation,
  DocumentItem,
  formatBytes,
  formatDate,
  getToken,
  kbApi,
  sseUrl,
} from "@/lib/api";

const STATUS_STYLES: Record<DocumentItem["status"], string> = {
  UPLOADED: "bg-amber-500/15 text-amber-300 border-amber-500/30",
  PROCESSING: "bg-sky-500/15 text-sky-300 border-sky-500/30",
  INDEXED: "bg-emerald-500/15 text-emerald-300 border-emerald-500/30",
  FAILED: "bg-red-500/15 text-red-300 border-red-500/30",
};

const STATUS_LABELS: Record<DocumentItem["status"], string> = {
  UPLOADED: "已上传",
  PROCESSING: "处理中",
  INDEXED: "已索引",
  FAILED: "失败",
};

interface ChatTurn {
  role: "user" | "assistant";
  content: string;
  citations?: Citation[];
  meta?: string;
}

export default function KnowledgeBaseDetailPage() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const kbId = params.id;

  const [docs, setDocs] = useState<DocumentItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState("");
  const [uploading, setUploading] = useState(false);

  const [mode, setMode] = useState<"chat" | "agent">("chat");
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const sessionRef = useRef<string | undefined>(undefined);
  const [question, setQuestion] = useState("");
  const [asking, setAsking] = useState(false);
  const [agentSteps, setAgentSteps] = useState<AgentResponse | null>(null);
  const chatBoxRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!getToken()) router.replace("/login");
  }, [router]);

  const loadDocs = useCallback(async () => {
    try {
      setDocs(await kbApi.listDocuments(kbId));
    } catch (err) {
      if (!(err instanceof ApiError && err.status === 401)) {
        setError(err instanceof Error ? err.message : "加载失败");
      }
    }
  }, [kbId]);

  useEffect(() => {
    setLoading(true);
    void loadDocs().finally(() => setLoading(false));
    void kbApi.latestChat(kbId).then((history) => {
      if (!history) return;
      sessionRef.current = history.sessionId;
      setTurns(
        history.messages
          .filter((message) => message.role === "user" || message.role === "assistant")
          .map((message) => ({
            role: message.role as "user" | "assistant",
            content: message.content,
          })),
      );
    });
  }, [kbId, loadDocs]);

  // Phase 9:SSE 实时接收文档状态变化
  useEffect(() => {
    if (!getToken()) return;
    const source = new EventSource(sseUrl(kbId));
    source.addEventListener("document-status", (e) => {
      try {
        const evt = JSON.parse((e as MessageEvent).data) as {
          documentId: string;
          status: DocumentItem["status"];
          errorMessage?: string | null;
        };
        setDocs((prev) => {
          const exists = prev.some((d) => d.id === evt.documentId);
          if (!exists) {
            void loadDocs();
            return prev;
          }
          return prev.map((d) =>
            d.id === evt.documentId
              ? { ...d, status: evt.status, errorMessage: evt.errorMessage ?? null }
              : d,
          );
        });
      } catch {
        // 忽略无法解析的事件
      }
    });
    source.onerror = () => source.close();
    return () => source.close();
  }, [kbId, loadDocs]);

  useEffect(() => {
    chatBoxRef.current?.scrollTo({ top: chatBoxRef.current.scrollHeight });
  }, [turns, agentSteps]);

  async function handleUpload(e: React.FormEvent) {
    e.preventDefault();
    if (!file) {
      setError("请先选择文件(PDF / Markdown / 纯文本)");
      return;
    }
    setUploading(true);
    setError(null);
    try {
      const doc = await kbApi.uploadDocument(kbId, file, title || undefined);
      setDocs((prev) => [doc, ...prev]);
      setFile(null);
      setTitle("");
      const input = document.getElementById("file-input") as HTMLInputElement | null;
      if (input) input.value = "";
    } catch (err) {
      setError(err instanceof Error ? err.message : "上传失败");
    } finally {
      setUploading(false);
    }
  }

  async function handleDeleteDoc(doc: DocumentItem) {
    if (!window.confirm(`确定删除文档「${doc.title}」?`)) return;
    try {
      await kbApi.removeDocument(doc.id);
      setDocs((prev) => prev.filter((d) => d.id !== doc.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    }
  }

  async function handleAsk(e: React.FormEvent) {
    e.preventDefault();
    const q = question.trim();
    if (!q || asking) return;
    setQuestion("");
    setError(null);
    setAsking(true);

    if (mode === "chat") {
      setTurns((prev) => [...prev, { role: "user", content: q }]);
      try {
        const res = await kbApi.chat(kbId, { question: q, sessionId: sessionRef.current });
        sessionRef.current = res.sessionId;
        setTurns((prev) => [
          ...prev,
          {
            role: "assistant",
            content: res.answer,
            citations: res.citations,
            meta: `${res.provider} · ${res.latencyMs}ms${res.fromCache ? " · 缓存命中" : ""}`,
          },
        ]);
      } catch (err) {
        setTurns((prev) => [
          ...prev,
          { role: "assistant", content: err instanceof Error ? err.message : "请求失败" },
        ]);
      } finally {
        setAsking(false);
      }
    } else {
      setAgentSteps(null);
      try {
        const res = await kbApi.agent(kbId, q);
        setAgentSteps(res);
      } catch (err) {
        setError(err instanceof Error ? err.message : "请求失败");
      } finally {
        setAsking(false);
      }
    }
  }

  return (
    <main className="mx-auto min-h-screen w-full max-w-6xl px-4 py-10">
      <Link
        href="/knowledge-bases"
        className="mb-6 inline-block text-sm text-slate-400 transition hover:text-indigo-300"
      >
        ← 返回知识库列表
      </Link>

      {loading ? (
        <div className="flex justify-center py-24">
          <span className="h-8 w-8 animate-spin rounded-full border-2 border-slate-600 border-t-indigo-400" />
        </div>
      ) : (
        <div className="grid gap-8 lg:grid-cols-2">
          {/* 左列:文档管理 */}
          <section>
            <h1 className="mb-1 text-2xl font-bold tracking-tight">文档管理</h1>
            <p className="mb-6 text-xs text-slate-500">
              上传后经 Kafka Worker 异步处理(解析 → 切分 → Embedding → 索引),状态实时推送
            </p>

            <form
              onSubmit={handleUpload}
              className="mb-6 rounded-2xl border border-slate-800 bg-slate-900/60 p-5"
            >
              <div className="flex flex-col gap-3">
                <label className="cursor-pointer rounded-lg border border-dashed border-slate-600 bg-slate-800/40 px-3 py-2.5 text-sm text-slate-400 transition hover:border-indigo-400/60 hover:text-slate-200">
                  {file ? file.name : "点击选择文件(PDF / Markdown / TXT,≤20MB)"}
                  <input
                    id="file-input"
                    type="file"
                    accept=".pdf,.md,.markdown,.txt,application/pdf,text/markdown,text/plain"
                    className="hidden"
                    onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                  />
                </label>
                <div className="flex gap-3">
                  <input
                    value={title}
                    onChange={(e) => setTitle(e.target.value)}
                    placeholder="标题(可选)"
                    maxLength={300}
                    className="flex-1 rounded-lg border border-slate-700 bg-slate-800/60 px-3 py-2.5 text-sm outline-none transition placeholder:text-slate-500 focus:border-indigo-400"
                  />
                  <button
                    type="submit"
                    disabled={uploading}
                    className="rounded-lg bg-indigo-500 px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-indigo-400 disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    {uploading ? "上传中…" : "上传"}
                  </button>
                </div>
              </div>
            </form>

            {error && (
              <p className="mb-4 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">
                {error}
              </p>
            )}

            {docs.length === 0 ? (
              <div className="rounded-2xl border border-dashed border-slate-700 py-14 text-center text-slate-400">
                还没有文档,上传一个试试
              </div>
            ) : (
              <div className="space-y-3">
                {docs.map((doc) => (
                  <div
                    key={doc.id}
                    className="rounded-xl border border-slate-800 bg-slate-900/60 p-4"
                  >
                    <div className="flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <p className="truncate text-sm font-medium text-slate-100">{doc.title}</p>
                        <p className="mt-1 text-xs text-slate-500">
                          {doc.filename} · {formatBytes(doc.sizeBytes)} ·{" "}
                          {formatDate(doc.createdAt)}
                        </p>
                      </div>
                      <div className="flex shrink-0 items-center gap-2">
                        <span
                          className={`rounded-full border px-2.5 py-0.5 text-xs ${STATUS_STYLES[doc.status]}`}
                        >
                          {STATUS_LABELS[doc.status]}
                        </span>
                        <button
                          onClick={() => void handleDeleteDoc(doc)}
                          className="rounded-md px-2 py-1 text-xs text-slate-500 transition hover:bg-red-500/10 hover:text-red-300"
                        >
                          删除
                        </button>
                      </div>
                    </div>
                    {doc.status === "FAILED" && doc.errorMessage && (
                      <p className="mt-2 text-xs text-red-300/80">{doc.errorMessage}</p>
                    )}
                  </div>
                ))}
              </div>
            )}
          </section>

          {/* 右列:RAG 问答 / Agent */}
          <section className="flex flex-col">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-2xl font-bold tracking-tight">知识库问答</h2>
              <div className="grid grid-cols-2 gap-1 rounded-lg bg-slate-800/60 p-1 text-xs font-medium">
                <button
                  onClick={() => setMode("chat")}
                  className={`rounded-md px-3 py-1.5 transition ${
                    mode === "chat" ? "bg-indigo-500 text-white" : "text-slate-400"
                  }`}
                >
                  RAG 问答
                </button>
                <button
                  onClick={() => setMode("agent")}
                  className={`rounded-md px-3 py-1.5 transition ${
                    mode === "agent" ? "bg-indigo-500 text-white" : "text-slate-400"
                  }`}
                >
                  Agent
                </button>
              </div>
            </div>

            <div
              ref={chatBoxRef}
              className="mb-4 h-[420px] flex-1 space-y-4 overflow-y-auto rounded-2xl border border-slate-800 bg-slate-900/40 p-4"
            >
              {mode === "chat" ? (
                turns.length === 0 ? (
                  <p className="py-20 text-center text-sm text-slate-500">
                    向知识库提问,回答会附带引用来源 [n]
                  </p>
                ) : (
                  turns.map((t, i) => (
                    <div
                      key={i}
                      className={`rounded-xl px-4 py-3 text-sm ${
                        t.role === "user"
                          ? "ml-8 bg-indigo-500/20 text-indigo-100"
                          : "mr-8 border border-slate-800 bg-slate-900/80 text-slate-200"
                      }`}
                    >
                      <p className="whitespace-pre-wrap leading-relaxed">{t.content}</p>
                      {t.citations && t.citations.length > 0 && (
                        <div className="mt-3 space-y-1.5 border-t border-slate-700/60 pt-2">
                          {t.citations.map((c) => (
                            <p key={c.n} className="text-xs text-slate-400">
                              <span className="mr-1 rounded bg-slate-700/80 px-1.5 py-0.5 text-[10px] text-slate-200">
                                [{c.n}]
                              </span>
                              {c.documentTitle} · {c.snippet.slice(0, 60)}…
                            </p>
                          ))}
                        </div>
                      )}
                      {t.meta && <p className="mt-2 text-[10px] text-slate-500">{t.meta}</p>}
                    </div>
                  ))
                )
              ) : agentSteps ? (
                <div className="space-y-3 text-sm">
                  <div className="space-y-2">
                    {agentSteps.steps.map((s) => (
                      <div
                        key={s.stepNo}
                        className="rounded-lg border border-slate-800 bg-slate-900/80 px-3 py-2"
                      >
                        <p className="text-xs text-indigo-300">
                          步骤 {s.stepNo} · 工具 <span className="font-mono">{s.tool}</span>
                        </p>
                        <p className="mt-1 whitespace-pre-wrap text-xs text-slate-400">
                          {s.resultSummary}
                        </p>
                      </div>
                    ))}
                  </div>
                  <div className="rounded-xl border border-indigo-500/30 bg-indigo-500/10 px-4 py-3 text-slate-100">
                    <p className="whitespace-pre-wrap leading-relaxed">{agentSteps.answer}</p>
                    <p className="mt-2 text-[10px] text-slate-400">
                      run {agentSteps.runId.slice(0, 8)} · {agentSteps.steps.length} 步 ·{" "}
                      {agentSteps.latencyMs}ms
                    </p>
                  </div>
                </div>
              ) : (
                <p className="py-20 text-center text-sm text-slate-500">
                  Agent 模式:提问后自动 检索 → 读取 → 总结,展示每一步工具调用
                </p>
              )}
              {asking && (
                <p className="mr-8 animate-pulse rounded-xl border border-slate-800 bg-slate-900/80 px-4 py-3 text-sm text-slate-400">
                  思考中…
                </p>
              )}
            </div>

            <form onSubmit={handleAsk} className="flex gap-3">
              <input
                value={question}
                onChange={(e) => setQuestion(e.target.value)}
                placeholder={
                  mode === "chat" ? "例如:ML-KEM 混合密钥交换是什么?" : "例如:总结一下这份笔记"
                }
                maxLength={2000}
                className="flex-1 rounded-lg border border-slate-700 bg-slate-800/60 px-3 py-2.5 text-sm outline-none transition placeholder:text-slate-500 focus:border-indigo-400"
              />
              <button
                type="submit"
                disabled={asking}
                className="rounded-lg bg-indigo-500 px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-indigo-400 disabled:cursor-not-allowed disabled:opacity-60"
              >
                发送
              </button>
            </form>
          </section>
        </div>
      )}
    </main>
  );
}
