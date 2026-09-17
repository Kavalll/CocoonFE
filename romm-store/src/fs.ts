export interface NativeBridge {
  pickRomRoot?: () => Promise<string | null>;
  getRomRoot?: () => Promise<string | null>;
  listFolders?: () => Promise<string[]>;
  writeFile?: (relativePath: string, bytes: ArrayBuffer) => Promise<string>;
  download?: (url: string, relativePath: string, authorization?: string) => Promise<string>;
  httpRequest?: (args: {
    url: string;
    method?: string;
    headers?: Record<string, string>;
    body?: string;
  }) => Promise<string>;
  fileExists?: (relativePath: string) => Promise<boolean>;
  getPref?: (key: string) => Promise<string>;
  setPref?: (key: string, value: string) => Promise<string>;
  removePref?: (key: string) => Promise<string>;
}

declare global {
  interface Window {
    CocoonRomm?: NativeBridge;
  }
}

export function nativeBridge(): NativeBridge | null {
  return window.CocoonRomm ?? null;
}

export async function platformFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const native = nativeBridge();
  if (!native?.httpRequest) {
    return fetch(input, init);
  }
  const url = typeof input === "string" || input instanceof URL ? String(input) : input.url;
  const headers: Record<string, string> = {};
  const rawHeaders = init?.headers;
  if (rawHeaders instanceof Headers) {
    rawHeaders.forEach((value, key) => {
      headers[key] = value;
    });
  } else if (Array.isArray(rawHeaders)) {
    for (const [key, value] of rawHeaders) headers[key] = value;
  } else if (rawHeaders) {
    for (const [key, value] of Object.entries(rawHeaders)) {
      if (value != null) headers[key] = String(value);
    }
  }
  let body = "";
  if (typeof init?.body === "string") body = init.body;
  else if (init?.body instanceof URLSearchParams) body = init.body.toString();
  else if (init?.body) body = String(init.body);

  const raw = await native.httpRequest({
    url,
    method: init?.method || "GET",
    headers,
    body,
  });
  const parsed = JSON.parse(raw) as { status: number; body: string; contentType?: string };
  return new Response(parsed.body, {
    status: parsed.status,
    headers: { "Content-Type": parsed.contentType || "application/json" },
  });
}

export async function pickDirectory(): Promise<FileSystemDirectoryHandle | null> {
  const picker = (window as Window & {
    showDirectoryPicker?: (options?: { mode?: string }) => Promise<FileSystemDirectoryHandle>;
  }).showDirectoryPicker;
  if (!picker) return null;
  return await picker({ mode: "readwrite" });
}

export async function writeToDirectory(
  root: FileSystemDirectoryHandle,
  relativePath: string,
  data: Blob,
): Promise<string> {
  const parts = relativePath.split("/").filter(Boolean);
  const fileName = parts.pop();
  if (!fileName) throw new Error("Missing file name.");
  let dir = root;
  for (const part of parts) {
    dir = await dir.getDirectoryHandle(part, { create: true });
  }
  const handle = await dir.getFileHandle(fileName, { create: true });
  const writable = await handle.createWritable();
  await writable.write(data);
  await writable.close();
  return relativePath;
}

export async function directoryHasFolder(
  root: FileSystemDirectoryHandle,
  folderName: string,
): Promise<boolean> {
  try {
    await root.getDirectoryHandle(folderName);
    return true;
  } catch {
    return false;
  }
}

export async function listSubfolders(root: FileSystemDirectoryHandle): Promise<string[]> {
  const names: string[] = [];
  const iterable = root as FileSystemDirectoryHandle & AsyncIterable<[string, FileSystemHandle]>;
  if (typeof iterable[Symbol.asyncIterator] !== "function") return names;
  for await (const [name, handle] of iterable) {
    if (handle.kind === "directory") names.push(name);
  }
  return names;
}

export function triggerBrowserDownload(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

export function triggerUrlDownload(url: string, fileName: string) {
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.rel = "noopener";
  link.target = "_blank";
  document.body.appendChild(link);
  link.click();
  link.remove();
}

export function isFetchBlocked(err: unknown): boolean {
  if (!(err instanceof Error)) return false;
  const message = err.message.toLowerCase();
  return (
    message.includes("failed to fetch") ||
    message.includes("networkerror") ||
    message.includes("network error") ||
    message.includes("blocked by cors") ||
    message.includes("access-control-allow-origin")
  );
}
