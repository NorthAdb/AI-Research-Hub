// 后端 API 基地址:可通过 NEXT_PUBLIC_API_BASE 环境变量覆盖
const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";
const TOKEN_KEY = "arh_token";
const USER_KEY = "arh_user";

export interface UserView {
  id: string;
  email: string;
  displayName: string | null;
}

export interface AuthResponse {
  accessToken: string;
  user: UserView;
}

export interface KnowledgeBase {
  id: string;
  name: string;
  description: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface DocumentItem {
  id: string;
  knowledgeBaseId: string;
  title: string;
  filename: string;
  mimeType: string | null;
  sizeBytes: number | null;
  status: "UPLOADED" | "PROCESSING" | "INDEXED" | "FAILED";
  errorMessage?: string | null;
  createdAt: string | null;
}

export interface Citation {
  n: number;
  documentId: string;
  documentTitle: string;
  chunkId: string;
  snippet: string;
  score: number;
}

export interface ChatResponse {
  sessionId: string;
  answer: string;
  provider: string;
  citations: Citation[];
  latencyMs: number;
  fromCache: boolean;
}

export interface AgentStep {
  stepNo: number;
  tool: string;
  args: Record<string, unknown> | null;
  resultSummary: string | null;
  latencyMs: number | null;
}

export interface AgentResponse {
  runId: string;
  answer: string;
  citations: Citation[];
  steps: AgentStep[];
  latencyMs: number;
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(TOKEN_KEY);
}

export function getUser(): UserView | null {
  if (typeof window === "undefined") return null;
  const raw = localStorage.getItem(USER_KEY);
  return raw ? (JSON.parse(raw) as UserView) : null;
}

export function saveSession(token: string, user: UserView): void {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

/** 统一 fetch 封装:自动带 JWT,非 2xx 抛 ApiError;意外 401 清会话回登录页。 */
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (options.body && !(options.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const res = await fetch(`${API_BASE}${path}`, { ...options, headers });

  if (!res.ok) {
    let message = `请求失败 (${res.status})`;
    try {
      const data = (await res.json()) as { error?: string };
      if (data?.error) message = data.error;
    } catch {
      // 非 JSON 错误体,保留默认消息
    }
    if (res.status === 401 && token) {
      clearSession();
      window.location.href = "/login";
    }
    throw new ApiError(res.status, message);
  }

  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const authApi = {
  register: (body: { email: string; password: string; displayName?: string }) =>
    api<AuthResponse>("/api/auth/register", { method: "POST", body: JSON.stringify(body) }),
  login: (body: { email: string; password: string }) =>
    api<AuthResponse>("/api/auth/login", { method: "POST", body: JSON.stringify(body) }),
};

export const kbApi = {
  list: () => api<KnowledgeBase[]>("/api/knowledge-bases"),
  get: (id: string) => api<KnowledgeBase>(`/api/knowledge-bases/${id}`),
  create: (body: { name: string; description?: string }) =>
    api<KnowledgeBase>("/api/knowledge-bases", { method: "POST", body: JSON.stringify(body) }),
  remove: (id: string) => api<void>(`/api/knowledge-bases/${id}`, { method: "DELETE" }),
  listDocuments: (id: string) => api<DocumentItem[]>(`/api/knowledge-bases/${id}/documents`),
  uploadDocument: (id: string, file: File, title?: string) => {
    const form = new FormData();
    form.append("file", file);
    if (title) form.append("title", title);
    return api<DocumentItem>(`/api/knowledge-bases/${id}/documents`, {
      method: "POST",
      body: form,
    });
  },
  removeDocument: (docId: string) => api<void>(`/api/documents/${docId}`, { method: "DELETE" }),
  latestChat: (kbId: string) =>
    api<{ sessionId: string; messages: { role: string; content: string }[] } | undefined>(
      `/api/knowledge-bases/${kbId}/chat/latest`,
    ),
  chat: (kbId: string, body: { question: string; sessionId?: string }) =>
    api<ChatResponse>(`/api/knowledge-bases/${kbId}/chat`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  agent: (kbId: string, question: string) =>
    api<AgentResponse>(`/api/knowledge-bases/${kbId}/agent`, {
      method: "POST",
      body: JSON.stringify({ question }),
    }),
};

/** SSE 实时进度(EventSource 无法带 Header,token 走 query 参数)。 */
export function sseUrl(kbId: string): string {
  const token = getToken() ?? "";
  return `${API_BASE}/api/knowledge-bases/${kbId}/events?token=${encodeURIComponent(token)}`;
}

export function formatBytes(bytes: number | null): string {
  if (bytes == null) return "-";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function formatDate(iso: string | null): string {
  if (!iso) return "-";
  return new Date(iso).toLocaleString("zh-CN", { hour12: false });
}
