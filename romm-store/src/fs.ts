export interface NativeBridge {
  pickRomRoot?: () => Promise<string | null>;
  getRomRoot?: () => Promise<string | null>;
  listFolders?: () => Promise<string[]>;
  writeFile?: (relativePath: string, bytes: ArrayBuffer) => Promise<string>;
  download?: (url: string, relativePath: string, authorization?: string) => Promise<string>;
  fileExists?: (relativePath: string) => Promise<boolean>;
}

declare global {
  interface Window {
    CocoonRomm?: NativeBridge;
  }
}

export function nativeBridge(): NativeBridge | null {
  return window.CocoonRomm ?? null;
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
