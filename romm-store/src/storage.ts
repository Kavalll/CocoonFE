import type { AuthState } from "./api";
import type { FolderLayout } from "./map";

const KEY = "cocoon-romm-store:v1";

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

export function loadSession(): StoredSession {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return { ...defaults, folderOverrides: {}, downloaded: {} };
    const parsed = JSON.parse(raw) as StoredSession;
    return {
      ...defaults,
      ...parsed,
      folderOverrides: parsed.folderOverrides ?? {},
      downloaded: parsed.downloaded ?? {},
    };
  } catch {
    return { ...defaults, folderOverrides: {}, downloaded: {} };
  }
}

export function saveSession(session: StoredSession) {
  localStorage.setItem(KEY, JSON.stringify(session));
}

export function romDownloadKey(romId: number, fileName: string): string {
  return `${romId}:${fileName}`;
}
