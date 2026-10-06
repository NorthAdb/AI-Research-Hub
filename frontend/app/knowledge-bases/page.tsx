"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  ApiError,
  clearSession,
  formatDate,
  getToken,
  getUser,
  kbApi,
  type KnowledgeBase,
} from "@/lib/api";

export default function KnowledgeBasesPage() {
  const router = useRouter();
  const [user, setUser] = useState<{ displayName: string | null; email: string } | null>(null);
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    if (!getToken()) {
      router.replace("/login");
      return;
    }
    setUser(getUser());
  }, [router]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setKbs(await kbApi.list());
    } catch (err) {
      if (!(err instanceof ApiError && err.status === 401)) {
        setError(err instanceof Error ? err.message : "加载失败");
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    setCreating(true);
    setError(null);
    try {
      const kb = await kbApi.create({ name, description: description || undefined });
      setKbs((prev) => [kb, ...prev]);
      setName("");
      setDescription("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
    } finally {
      setCreating(false);
    }
  }

  async function handleDelete(kb: KnowledgeBase) {
    if (!window.confirm(`确定删除知识库「${kb.name}」?\n其中所有文档都会一并删除。`)) return;
    try {
      await kbApi.remove(kb.id);
      setKbs((prev) => prev.filter((k) => k.id !== kb.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    }
  }

  function handleLogout() {
    clearSession();
    router.replace("/login");
  }

  return (
    <main className="mx-auto min-h-screen w-full max-w-5xl px-4 py-10">
      <header className="mb-10 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">
            AI <span className="text-indigo-400">Research</span> Hub
          </h1>
          <p className="mt-1 text-sm text-slate-400">我的知识库</p>
        </div>
        <div className="flex items-center gap-4 text-sm">
          <span className="text-slate-300">{user?.displayName ?? user?.email ?? "…"}</span>
          <button
            onClick={handleLogout}
            className="rounded-lg border border-slate-700 px-3 py-1.5 text-slate-300 transition hover:border-slate-500 hover:text-white"
          >
            退出登录
          </button>
        </div>
      </header>

      <form
        onSubmit={handleCreate}
        className="mb-8 rounded-2xl border border-slate-800 bg-slate-900/60 p-6"
      >
        <h2 className="mb-4 text-sm font-semibold text-slate-300">新建知识库</h2>
        <div className="flex flex-col gap-3 sm:flex-row">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="名称,例如:PQC 论文库"
            required
            maxLength={200}
            className="sm:w-64 flex-1 rounded-lg border border-slate-700 bg-slate-800/60 px-3 py-2.5 text-sm outline-none transition placeholder:text-slate-500 focus:border-indigo-400"
          />
          <input
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="简介(可选)"
            maxLength={2000}
            className="flex-1 rounded-lg border border-slate-700 bg-slate-800/60 px-3 py-2.5 text-sm outline-none transition placeholder:text-slate-500 focus:border-indigo-400"
          />
          <button
            type="submit"
            disabled={creating}
            className="rounded-lg bg-indigo-500 px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-indigo-400 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {creating ? "创建中…" : "创建"}
          </button>
        </div>
      </form>

      {error && (
        <p className="mb-6 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">
          {error}
        </p>
      )}

      {loading ? (
        <div className="flex justify-center py-20">
          <span className="h-8 w-8 animate-spin rounded-full border-2 border-slate-600 border-t-indigo-400" />
        </div>
      ) : kbs.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-slate-700 py-20 text-center text-slate-400">
          还没有知识库,先创建一个吧 👆
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {kbs.map((kb) => (
            <div
              key={kb.id}
              className="group relative flex flex-col rounded-2xl border border-slate-800 bg-slate-900/60 p-5 transition hover:border-indigo-500/50"
            >
              <Link href={`/knowledge-bases/${kb.id}`} className="flex-1">
                <h3 className="font-semibold text-slate-100 group-hover:text-indigo-300">
                  {kb.name}
                </h3>
                <p className="mt-2 line-clamp-2 min-h-10 text-sm text-slate-400">
                  {kb.description || "暂无简介"}
                </p>
                <p className="mt-4 text-xs text-slate-500">创建于 {formatDate(kb.createdAt)}</p>
              </Link>
              <button
                onClick={() => void handleDelete(kb)}
                className="absolute top-4 right-4 rounded-md px-2 py-1 text-xs text-slate-500 opacity-0 transition hover:bg-red-500/10 hover:text-red-300 group-hover:opacity-100"
              >
                删除
              </button>
            </div>
          ))}
        </div>
      )}
    </main>
  );
}
