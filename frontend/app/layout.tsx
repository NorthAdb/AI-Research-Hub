import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "AI Research Hub",
  description: "个人/团队 AI 知识库 + RAG + Agent(Demo)",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN" className="h-full antialiased">
      <body className="min-h-full bg-slate-950 text-slate-100">{children}</body>
    </html>
  );
}
