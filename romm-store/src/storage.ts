import type { AuthState } from "./api";
import type { FolderLayout } from "./map";
import { nativeBridge } from "./fs";

const KEY = "cocoon-romm-store:v1";
const NATIVE_KEY = "session";

export interface StoredSession {
  baseUrl: string;
  auth: AuthState;
  layout: FolderLayout;
  folderOverrides: Record<string, string>;
  downloaded: Record<string, { path: string; at: number }>;
}

const defaults: StoredSession = {
  baseUrl: "",
  auth: { kind: "none" },
  layout: "cocoon",
  folderOverrides: {},
  downloaded: {},
};

function normalize(parsed: StoredSession): StoredSession {
  return {
    ...defaults,
    ...parsed,
    auth: parsed.auth ?? { kind: "none" },
    folderOverrides: parsed.folderOverrides ?? {},
    downloaded: parsed.downloaded ?? {},
  };
}

export function loadSession(): StoredSession {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return { ...defaults, folderOverrides: {}, downloaded: {} };
    return normalize(JSON.parse(raw) as StoredSession);
  } catch {
    return { ...defaults, folderOverrides: {}, downloaded: {} };
  }
}

function hasAuth(session: StoredSession): boolean {
  return session.auth.kind !== "none" || Boolean(session.baseUrl);
}

export async function hydrateSession(): Promise<StoredSession> {
  const local = loadSession();
  const native = nativeBridge();
  if (!native?.getPref) return local;
  try {
    const raw = await native.getPref(NATIVE_KEY);
    if (!raw) return local;
    const stored = normalize(JSON.parse(raw) as StoredSession);
    if (!hasAuth(local) && hasAuth(stored)) {
      localStorage.setItem(KEY, JSON.stringify(stored));
      return stored;
    }
    return local;
  } catch {
    return local;
  }
}

export function saveSession(session: StoredSession) {
  const raw = JSON.stringify(session);
  localStorage.setItem(KEY, raw);
  const native = nativeBridge();
  if (native?.setPref) void native.setPref(NATIVE_KEY, raw);
}

export function clearSession() {
  localStorage.removeItem(KEY);
  const native = nativeBridge();
  if (native?.removePref) void native.removePref(NATIVE_KEY);
}

export function savedUsername(session: StoredSession): string {
  if (session.auth.kind === "basic") return session.auth.username;
  if (session.auth.kind === "bearer") return session.auth.username ?? "";
  return "";
}

export function savedPassword(session: StoredSession): string {
  if (session.auth.kind === "basic") return session.auth.password;
  if (session.auth.kind === "bearer") return session.auth.password ?? "";
  return "";
}

export function romDownloadKey(romId: number, fileName: string): string {
  return `${romId}:${fileName}`;
}
