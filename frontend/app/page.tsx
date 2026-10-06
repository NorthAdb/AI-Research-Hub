"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { getToken } from "@/lib/api";

export default function Home() {
  const router = useRouter();

  useEffect(() => {
    router.replace(getToken() ? "/knowledge-bases" : "/login");
  }, [router]);

  return (
    <main className="flex min-h-screen items-center justify-center">
      <div className="flex flex-col items-center gap-3 text-slate-400">
        <span className="h-8 w-8 animate-spin rounded-full border-2 border-slate-600 border-t-indigo-400" />
        <p className="text-sm">AI Research Hub 加载中…</p>
      </div>
    </main>
  );
}
