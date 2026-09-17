export interface Heartbeat {
  SYSTEM?: { VERSION?: string };
  FILESYSTEM?: { FS_PLATFORMS?: string[] };
}

export interface RommPlatform {
  id: number;
  slug: string;
  fs_slug: string;
  name: string;
  display_name?: string;
  rom_count: number;
  url_logo?: string | null;
}

export interface SimpleRom {
  id: number;
  name: string | null;
  fs_name: string;
  fs_name_no_tags: string;
  fs_extension: string;
  fs_size_bytes: number;
  platform_id: number;
  platform_slug: string;
  platform_fs_slug: string;
  platform_display_name: string;
  summary?: string | null;
  url_cover?: string | null;
  path_cover_small?: string | null;
  path_cover_large?: string | null;
  has_multiple_files?: boolean;
  files?: Array<{ id: number; file_name: string; file_size_bytes: number }>;
}

export interface RomPage {
  items: SimpleRom[];
  total: number | null;
  limit: number;
  offset: number;
}

export interface TokenResponse {
  access_token: string;
  token_type: string;
  expires: number;
  refresh_token?: string;
  refresh_expires?: number;
}

export interface PairExchangeResponse {
  raw_token: string;
  name: string;
  scopes: string[];
}

export type AuthState =
  | { kind: "none" }
  | {
      kind: "bearer";
      token: string;
      refreshToken?: string;
      expiresAt?: number;
      username?: string;
      password?: string;
    }
  | { kind: "basic"; username: string; password: string };

export interface RommClientOptions {
  baseUrl: string;
  auth?: AuthState;
  fetchImpl?: typeof fetch;
  onAuth?: (auth: AuthState) => void;
}

function trimBase(url: string): string {
  return url.trim().replace(/\/+$/, "");
}

export function normalizeBaseUrl(url: string): string {
  const trimmed = url.trim();
  if (!trimmed) throw new Error("Server URL is required.");
  const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
  return trimBase(withScheme);
}

export function isUnauthorized(err: unknown): boolean {
  return err instanceof RommApiError && (err.status === 401 || err.status === 403);
}

function isTokenPath(path: string): boolean {
  return path.startsWith("/api/token");
}

function isPermanentBearer(auth: Extract<AuthState, { kind: "bearer" }>): boolean {
  return auth.token.startsWith("rmm_") || (!auth.expiresAt && !auth.refreshToken);
}

export function romsQuery(options: {
  platformId?: number;
  search?: string;
  limit?: number;
  offset?: number;
  variant?: "gallery" | "simple" | "legacy";
  withFiles?: boolean;
}): string {
  const params = new URLSearchParams();
  params.set("limit", String(options.limit ?? 48));
  params.set("offset", String(options.offset ?? 0));
  if (options.search) params.set("search_term", options.search);
  if (options.withFiles) params.set("with_files", "true");
  const variant = options.variant ?? "gallery";
  if (variant === "gallery") {
    params.set("order_by", "name");
    params.set("order_dir", "asc");
    if (options.platformId != null) params.append("platform_ids", String(options.platformId));
  } else if (variant === "simple") {
    params.set("order_by", "name");
    params.set("order_dir", "asc");
    if (options.platformId != null) params.append("platform_ids", String(options.platformId));
    params.set("with_char_index", "false");
    params.set("with_filter_values", "false");
  } else {
    if (options.platformId != null) params.set("platform_id", String(options.platformId));
  }
  return `/api/roms?${params.toString()}`;
}

export class RommApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
    this.name = "RommApiError";
  }
}

export class RommClient {
  baseUrl: string;
  auth: AuthState;
  private fetchImpl: typeof fetch;
  private onAuth?: (auth: AuthState) => void;
  private restoring = false;

  constructor(options: RommClientOptions) {
    this.baseUrl = normalizeBaseUrl(options.baseUrl);
    this.auth = options.auth ?? { kind: "none" };
    this.fetchImpl = options.fetchImpl ?? fetch.bind(globalThis);
    this.onAuth = options.onAuth;
  }

  setAuth(auth: AuthState) {
    this.auth = auth;
    this.onAuth?.(auth);
  }

  authHeaders(): Record<string, string> {
    if (this.auth.kind === "bearer") {
      return { Authorization: `Bearer ${this.auth.token}` };
    }
    if (this.auth.kind === "basic") {
      const raw = `${this.auth.username}:${this.auth.password}`;
      return { Authorization: `Basic ${btoa(raw)}` };
    }
    return {};
  }

  private credentials(): { username: string; password: string } | null {
    if (this.auth.kind === "basic") return { username: this.auth.username, password: this.auth.password };
    if (this.auth.kind === "bearer" && this.auth.username && this.auth.password) {
      return { username: this.auth.username, password: this.auth.password };
    }
    return null;
  }

  async restoreAuth(): Promise<boolean> {
    if (this.auth.kind === "basic") return true;
    if (this.auth.kind !== "bearer") return false;
    if (isPermanentBearer(this.auth)) return true;
    if (!this.auth.expiresAt) return true;
    if (this.auth.expiresAt > Date.now() + 30_000) return true;
    return await this.recoverAuth();
  }

  async recoverAuth(): Promise<boolean> {
    if (this.restoring) return false;
    this.restoring = true;
    try {
      if (this.auth.kind === "bearer" && this.auth.refreshToken) {
        try {
          await this.refresh();
          return true;
        } catch {
          /* try password next */
        }
      }
      const creds = this.credentials();
      if (creds) {
        try {
          await this.login(creds.username, creds.password);
          return true;
        } catch {
          this.setAuth({ kind: "basic", username: creds.username, password: creds.password });
          return true;
        }
      }
      return false;
    } finally {
      this.restoring = false;
    }
  }

  async request<T>(path: string, init: RequestInit = {}, retrying = false): Promise<T> {
    if (!isTokenPath(path) && !retrying) {
      await this.restoreAuth();
    }
    const headers = new Headers(init.headers);
    if (!isTokenPath(path)) {
      for (const [key, value] of Object.entries(this.authHeaders())) {
        if (!headers.has(key)) headers.set(key, value);
      }
    }
    const response = await this.fetchImpl(`${this.baseUrl}${path}`, {
      ...init,
      headers,
    });
    if (response.status === 401 && !isTokenPath(path) && !retrying) {
      const recovered = await this.recoverAuth();
      if (recovered) return this.request<T>(path, init, true);
    }
    if (!response.ok) {
      let detail = `${response.status} ${response.statusText}`;
      try {
        const body = await response.json();
        if (body?.detail) detail = typeof body.detail === "string" ? body.detail : JSON.stringify(body.detail);
      } catch {
        try {
          const text = await response.text();
          if (text) detail = text.slice(0, 300);
        } catch {
          /* ignore */
        }
      }
      throw new RommApiError(response.status, detail);
    }
    if (response.status === 204) return undefined as T;
    const contentType = response.headers.get("content-type") || "";
    if (contentType.includes("application/json")) return (await response.json()) as T;
    return (await response.text()) as T;
  }

  heartbeat() {
    return this.request<Heartbeat>("/api/heartbeat");
  }

  platforms() {
    return this.request<RommPlatform[]>("/api/platforms");
  }

  async login(username: string, password: string, scope = "roms.read platforms.read"): Promise<TokenResponse> {
    const body = new URLSearchParams({
      grant_type: "password",
      username,
      password,
      scope,
    });
    const token = await this.request<TokenResponse>("/api/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });
    this.setAuth({
      kind: "bearer",
      token: token.access_token,
      refreshToken: token.refresh_token,
      expiresAt: token.expires ? Date.now() + Number(token.expires) * 1000 : undefined,
      username,
      password,
    });
    return token;
  }

  async refresh(): Promise<TokenResponse | null> {
    if (this.auth.kind !== "bearer" || !this.auth.refreshToken) return null;
    const previous = this.auth;
    const body = new URLSearchParams({
      grant_type: "refresh_token",
      refresh_token: this.auth.refreshToken,
    });
    const token = await this.request<TokenResponse>("/api/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });
    this.setAuth({
      kind: "bearer",
      token: token.access_token,
      refreshToken: token.refresh_token ?? previous.refreshToken,
      expiresAt: token.expires ? Date.now() + Number(token.expires) * 1000 : undefined,
      username: previous.username,
      password: previous.password,
    });
    return token;
  }

  async exchangePairCode(code: string): Promise<string> {
    const result = await this.request<PairExchangeResponse>("/api/client-tokens/exchange", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: code.trim() }),
    });
    this.setAuth({ kind: "bearer", token: result.raw_token });
    return result.raw_token;
  }

  setClientToken(token: string) {
    this.setAuth({ kind: "bearer", token: token.trim() });
  }

  async roms(query: {
    platformId?: number;
    search?: string;
    limit?: number;
    offset?: number;
    withFiles?: boolean;
  } = {}): Promise<RomPage> {
    const variants = ["gallery", "simple", "legacy"] as const;
    let lastError: unknown;
    for (const variant of variants) {
      try {
        return await this.request<RomPage>(romsQuery({ ...query, variant }));
      } catch (err) {
        lastError = err;
        if (err instanceof RommApiError && (err.status === 500 || err.status === 422 || err.status === 400)) {
          continue;
        }
        throw err;
      }
    }
    throw lastError;
  }

  rom(id: number) {
    return this.request<SimpleRom>(`/api/roms/${id}`);
  }

  downloadUrl(romId: number, fileName: string): string {
    return `${this.baseUrl}/api/roms/${romId}/content/${encodeURIComponent(fileName)}`;
  }

  coverUrl(rom: SimpleRom): string | null {
    if (rom.url_cover) return rom.url_cover.startsWith("http") ? rom.url_cover : `${this.baseUrl}${rom.url_cover}`;
    const path = rom.path_cover_small || rom.path_cover_large;
    if (!path) return null;
    return path.startsWith("http") ? path : `${this.baseUrl}${path.startsWith("/") ? path : `/${path}`}`;
  }

  async downloadBlob(romId: number, fileName: string, onProgress?: (received: number, total: number | null) => void): Promise<Blob> {
    const headers = new Headers(this.authHeaders());
    const response = await this.fetchImpl(this.downloadUrl(romId, fileName), { headers });
    if (!response.ok) {
      throw new RommApiError(response.status, `Download failed (${response.status})`);
    }
    const total = Number(response.headers.get("content-length") || "") || null;
    if (!response.body || !onProgress) return await response.blob();

    const reader = response.body.getReader();
    const chunks: BlobPart[] = [];
    let received = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      received += value.byteLength;
      onProgress(received, total);
    }
    return new Blob(chunks);
  }
}
