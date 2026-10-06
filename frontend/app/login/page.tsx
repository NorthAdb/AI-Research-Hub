"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { authApi, getToken, saveSession } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (getToken()) router.replace("/knowledge-bases");
  }, [router]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const res =
        mode === "login"
          ? await authApi.login({ email, password })
          : await authApi.register({ email, password, displayName: displayName || undefined });
      saveSession(res.accessToken, res.user);
      router.replace("/knowledge-bases");
    } catch (err) {
      setError(err instanceof Error ? err.message : "请求失败,请稍后再试");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-md">
        <div className="mb-8 text-center">
          <h1 className="text-3xl font-bold tracking-tight">
            AI <span className="text-indigo-400">Research</span> Hub
          </h1>
          <p className="mt-2 text-sm text-slate-400">个人 / 团队 AI 知识库 + RAG + Agent</p>
        </div>

        <div className="rounded-2xl border border-slate-800 bg-slate-900/60 p-8 shadow-xl shadow-black/30">
          <div className="mb-6 grid grid-cols-2 gap-1 rounded-lg bg-slate-800/60 p-1 text-sm font-medium">
            <button
              type="button"
              onClick={() => {
                setMode("login");
                setError(null);
              }}
              className={`rounded-md py-2 transition ${
                mode === "login" ? "bg-indigo-500 text-white" : "text-slate-400 hover:text-slate-200"
              }`}
            >
              登录
            </button>
            <button
              type="button"
              onClick={() => {
                setMode("register");
                setError(null);
              }}
              className={`rounded-md py-2 transition ${
                mode === "register" ? "bg-indigo-500 text-white" : "text-slate-400 hover:text-slate-200"
              }`}
            >
              注册
            </button>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            {mode === "register" && (
              <div>
                <label className="mb-1.5 block text-sm text-slate-300">昵称(可选)</label>
                <input
                  type="text"
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  placeholder="你的名字"
                  className="w-full rounded-lg border border-slate-700 bg-slate-800/60 px-3 py-2.5 text-sm outline-none transition placeholder:text-slate-500 focus:border-indigo-400"
                />
              </div>
            )}
            <div>
              <label className="mb-1.5 block text-sm text-slate-300">邮箱</label>
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@example.com"
                className="w-full rounded-lg border border-slate-700 bg-slate-800/60 px-3 py-2.5 text-sm outline-none transition placeholder:text-slate-500 focus:border-indigo-400"
              />
            </div>
            <div>
              <label className="mb-1.5 block text-sm text-slate-300">密码</label>
              <input
                type="password"
                required
                minLength={8}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={mode === "register" ? "至少 8 位" : "输入密码"}
                className="w-full rounded-lg border border-slate-700 bg-slate-800/60 px-3 py-2.5 text-sm outline-none transition placeholder:text-slate-500 focus:border-indigo-400"
              />
            </div>

            {error && (
              <p className="rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">
                {error}
              </p>
            )}

            <button
              type="submit"
              disabled={submitting}
              className="w-full rounded-lg bg-indigo-500 py-2.5 text-sm font-semibold text-white transition hover:bg-indigo-400 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {submitting ? "请稍候…" : mode === "login" ? "登录" : "创建账号"}
            </button>
          </form>
        </div>

        <p className="mt-6 text-center text-xs text-slate-500">
本地知识库 · 检索、问答、Agent
        </p>
      </div>
    </main>
  );
}
