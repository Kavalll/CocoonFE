import "./styles.css";
import type { PlatformMapFile, FolderLayout } from "./map";
import {
  filterPlatforms,
  joinDownloadPath,
  platformDisplayName,
  platformReleaseYear,
  resolveDestination,
  sortPlatformsByGeneration,
} from "./map";
import { RommApiError, RommClient, type RommPlatform, type SimpleRom } from "./api";
import { loadSession, saveSession, romDownloadKey, type StoredSession } from "./storage";
import {
  listSubfolders,
  nativeBridge,
  pickDirectory,
  platformFetch,
  triggerBrowserDownload,
  triggerUrlDownload,
  isFetchBlocked,
  writeToDirectory,
} from "./fs";
import mapJson from "../platform-map.json";

const map = mapJson as PlatformMapFile;
let app: HTMLElement;

function bindApp() {
  app =
    document.querySelector<HTMLDivElement>("#app") ??
    document.body.appendChild(Object.assign(document.createElement("div"), { id: "app" }));
}

console.info("[RommStore] boot");

function notifyNativeReady() {
  try {
    const native = (window as Window & { CocoonRommNative?: { uiReady?: () => void } }).CocoonRommNative;
    native?.uiReady?.();
  } catch {
    /* browser preview has no Android bridge */
  }
}

function showBootError(reason: unknown) {
  const message = reason instanceof Error ? `${reason.name}: ${reason.message}` : String(reason);
  console.error("[RommStore] boot failed", reason);
  app.innerHTML = `<h1>RomM store failed to start</h1><p>${escapeHtml(message)}</p>`;
  notifyNativeReady();
}

let session: StoredSession = loadSession();
let client = new RommClient({
  baseUrl: session.baseUrl || "https://demo.romm.app",
  auth: session.auth,
  fetchImpl: platformFetch,
});
let romRoot: FileSystemDirectoryHandle | null = null;
let existingFolders: string[] = [];
let screen: "login" | "platforms" | "games" | "detail" | "settings" = session.auth.kind === "none" && !session.baseUrl ? "login" : "platforms";
let platforms: RommPlatform[] = [];
let currentPlatform: RommPlatform | null = null;
let roms: SimpleRom[] = [];
let romTotal = 0;
let romOffset = 0;
let platformQuery = "";
let gameQuery = "";
let keepSearchFocus = false;
let searchCaret = 0;
let selectedRom: SimpleRom | null = null;
let status = "";
let error = "";
let downloadPct = 0;
let busy = false;

const HANDLE_DB = "cocoon-romm-store";

async function openHandleDb(): Promise<IDBDatabase> {
  return await new Promise((resolve, reject) => {
    const req = indexedDB.open(HANDLE_DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore("fs");
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function persistRomRoot(handle: FileSystemDirectoryHandle | null) {
  const db = await openHandleDb();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction("fs", "readwrite");
    if (handle) tx.objectStore("fs").put(handle, "romRoot");
    else tx.objectStore("fs").delete("romRoot");
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

async function restoreRomRoot(): Promise<FileSystemDirectoryHandle | null> {
  try {
    const db = await openHandleDb();
    const handle = await new Promise<FileSystemDirectoryHandle | undefined>((resolve, reject) => {
      const tx = db.transaction("fs", "readonly");
      const req = tx.objectStore("fs").get("romRoot");
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    if (!handle) return null;
    const permission = await (handle as FileSystemDirectoryHandle & {
      queryPermission: (descriptor: { mode: string }) => Promise<string>;
      requestPermission: (descriptor: { mode: string }) => Promise<string>;
    }).queryPermission({ mode: "readwrite" });
    if (permission === "granted") return handle;
    const next = await (handle as FileSystemDirectoryHandle & {
      requestPermission: (descriptor: { mode: string }) => Promise<string>;
    }).requestPermission({ mode: "readwrite" });
    return next === "granted" ? handle : null;
  } catch {
    return null;
  }
}

function persist() {
  saveSession(session);
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function destinationFor(rom: SimpleRom) {
  return resolveDestination(map, {
    rommSlug: rom.platform_slug,
    rommFsSlug: rom.platform_fs_slug,
    layout: session.layout,
    existingFolders,
    overrides: session.folderOverrides,
  });
}

function downloaded(rom: SimpleRom): boolean {
  return Boolean(session.downloaded[romDownloadKey(rom.id, rom.fs_name)]);
}

async function refreshLocalFolders() {
  const native = nativeBridge();
  if (native?.listFolders) {
    existingFolders = await native.listFolders();
    return;
  }
  if (romRoot) existingFolders = await listSubfolders(romRoot);
}

async function connect(baseUrl: string) {
  client = new RommClient({ baseUrl, auth: session.auth, fetchImpl: platformFetch });
  await client.heartbeat();
  session.baseUrl = client.baseUrl;
  persist();
}

async function loadPlatforms() {
  busy = true;
  error = "";
  render();
  try {
    await refreshLocalFolders();
    platforms = sortPlatformsByGeneration(map, await client.platforms());
    screen = "platforms";
  } catch (err) {
    error = err instanceof Error ? err.message : String(err);
    screen = "login";
  } finally {
    busy = false;
    render();
  }
}

async function loadGames(reset = true) {
  if (!currentPlatform) return;
  if (reset) {
    roms = [];
    romOffset = 0;
  }
  busy = true;
  error = "";
  render();
  try {
    const page = await client.roms({
      platformId: currentPlatform.id,
      search: gameQuery.trim() || undefined,
      limit: 48,
      offset: romOffset,
    });
    roms = reset ? page.items : [...roms, ...page.items];
    romTotal = page.total ?? roms.length;
    romOffset = roms.length;
    screen = "games";
  } catch (err) {
    error = err instanceof Error ? err.message : String(err);
  } finally {
    busy = false;
    render();
  }
}

async function fetchRomBlob(rom: SimpleRom): Promise<Blob> {
  return await client.downloadBlob(rom.id, rom.fs_name, (received, total) => {
    downloadPct = total ? Math.round((received / total) * 100) : 50;
    status = `Downloading ${formatBytes(received)}${total ? ` / ${formatBytes(total)}` : ""}`;
    render();
  });
}

function startDirectFileDownload(rom: SimpleRom, destFolder: string, reason: string) {
  triggerUrlDownload(client.downloadUrl(rom.id, rom.fs_name), rom.fs_name);
  session.downloaded[romDownloadKey(rom.id, rom.fs_name)] = { path: rom.fs_name, at: Date.now() };
  downloadPct = 100;
  status = `Browser download started for ${rom.fs_name}. Move it into your Cocoon ${destFolder} folder. ${reason}`;
}

async function downloadRom(rom: SimpleRom) {
  busy = true;
  error = "";
  status = "Starting download…";
  downloadPct = 0;
  render();
  try {
    const dest = destinationFor(rom);
    const relative = joinDownloadPath(dest.folderName, rom.fs_name);
    const native = nativeBridge();
    if (native?.download) {
      status = "Downloading through the Android folder bridge…";
      render();
      const saved = await native.download(
        client.downloadUrl(rom.id, rom.fs_name),
        relative,
        client.authHeaders().Authorization,
      );
      session.downloaded[romDownloadKey(rom.id, rom.fs_name)] = { path: saved, at: Date.now() };
      status = `Saved to ${saved}. Rescan this platform in Cocoon if it does not appear immediately.`;
    } else if (native?.writeFile) {
      const blob = await fetchRomBlob(rom);
      const saved = await native.writeFile(relative, await blob.arrayBuffer());
      session.downloaded[romDownloadKey(rom.id, rom.fs_name)] = { path: saved, at: Date.now() };
      status = `Saved to ${saved}. Rescan this platform in Cocoon if it does not appear immediately.`;
    } else if (romRoot) {
      try {
        const blob = await fetchRomBlob(rom);
        const saved = await writeToDirectory(romRoot, relative, blob);
        session.downloaded[romDownloadKey(rom.id, rom.fs_name)] = { path: saved, at: Date.now() };
        status = `Saved to ${saved}. Rescan this platform in Cocoon if it does not appear immediately.`;
      } catch (err) {
        if (!isFetchBlocked(err)) throw err;
        startDirectFileDownload(
          rom,
          dest.folderName,
          "The RomM download URL blocked in-page fetch (CORS). Direct folder write needs the Android wrapper.",
        );
      }
    } else {
      try {
        const blob = await fetchRomBlob(rom);
        triggerBrowserDownload(blob, rom.fs_name);
        session.downloaded[romDownloadKey(rom.id, rom.fs_name)] = { path: rom.fs_name, at: Date.now() };
        status = `Downloaded ${rom.fs_name}. Move it into your Cocoon ${dest.folderName} folder, or choose a ROM root in Settings so files land there automatically.`;
      } catch (err) {
        if (!isFetchBlocked(err)) throw err;
        startDirectFileDownload(
          rom,
          dest.folderName,
          "RomM file downloads often omit CORS headers, so the file is opened directly instead.",
        );
      }
    }
    persist();
    downloadPct = 100;
  } catch (err) {
    error = err instanceof Error ? err.message : String(err);
    status = "";
  } finally {
    busy = false;
    render();
  }
}

function searchValue(): string {
  if (screen === "games" || screen === "detail") return gameQuery;
  if (screen === "platforms") return platformQuery;
  return "";
}

function searchPlaceholder(): string {
  if (screen === "games" || screen === "detail") {
    return `Search games on ${platformDisplayName(currentPlatform ?? {})}`;
  }
  return "Filter platforms";
}

function searchHint(): string {
  if (screen === "platforms") {
    return platformQuery.trim()
      ? "Filtering consoles only. Open a platform to browse its games — this text is not a game search."
      : "Filter consoles by name. Open one, then search game titles.";
  }
  if (screen === "games" || screen === "detail") {
    return gameQuery.trim()
      ? `Showing ${platformDisplayName(currentPlatform ?? {})} games matching this title.`
      : "Search game titles on this platform only.";
  }
  return "";
}

function topbar(subtitle: string) {
  const showSearch = screen === "platforms" || screen === "games" || screen === "detail";
  const query = searchValue();
  return `
    <header class="topbar">
      <div class="brand">
        <strong>Cocoon RomM Store</strong>
        <span>${subtitle}</span>
      </div>
      ${showSearch ? `
        <div class="search-wrap">
          <div class="search-row">
            <input class="search" id="search" type="search" enterkeyhint="search" placeholder="${escapeHtml(searchPlaceholder())}" value="${escapeHtml(query)}" />
            ${query ? `<button class="ghost icon" id="clear-search" type="button" aria-label="Clear search">Clear</button>` : ""}
            ${screen === "games" || screen === "detail" ? `<button class="primary icon" id="run-search" type="button">Search</button>` : ""}
          </div>
          <p class="search-hint">${escapeHtml(searchHint())}</p>
        </div>
      ` : ""}
      ${screen !== "login" ? `<button class="ghost" data-go="settings">Settings</button>` : ""}
    </header>
  `;
}

function restoreSearchFocus() {
  if (!keepSearchFocus) return;
  keepSearchFocus = false;
  const box = app.querySelector<HTMLInputElement>("#search");
  if (!box) return;
  box.focus();
  const pos = Math.min(searchCaret, box.value.length);
  box.setSelectionRange(pos, pos);
}

function openPlatform(platform: RommPlatform) {
  currentPlatform = platform;
  gameQuery = "";
  void loadGames(true);
}

function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function coverStyle(url: string | null): string {
  return url ? `style="background-image:url('${url.replaceAll("'", "%27")}')"` : "";
}

function renderLogin() {
  app.innerHTML = `
    ${topbar("Connect the RomM server you already have running.")}
    <section class="panel">
      <h1>Connect RomM</h1>
      <p class="muted">Cocoon itself is closed source, so this companion lives in the dock and writes games into the same platform folders Cocoon scans.</p>
      <div class="field">
        <label for="baseUrl">RomM URL</label>
        <input id="baseUrl" value="${escapeHtml(session.baseUrl || "https://demo.romm.app")}" placeholder="https://romm.example.com" />
      </div>
      <div class="field">
        <label for="username">Username</label>
        <input id="username" autocomplete="username" />
      </div>
      <div class="field">
        <label for="password">Password</label>
        <input id="password" type="password" autocomplete="current-password" />
      </div>
      <div class="field">
        <label for="token">Or paste a client token / 8-digit pairing code</label>
        <input id="token" placeholder="rmm_… or 12345678" />
      </div>
      <div class="row">
        <button class="primary" id="connect">Connect</button>
        <button class="ghost" id="kiosk">Browse without login</button>
      </div>
      ${error ? `<p class="error">${escapeHtml(error)}</p>` : ""}
      ${busy ? `<p class="muted">Connecting…</p>` : ""}
    </section>
  `;
  app.querySelector("#connect")?.addEventListener("click", onConnect);
  app.querySelector("#kiosk")?.addEventListener("click", onKiosk);
}

async function onKiosk() {
  const baseUrl = (app.querySelector("#baseUrl") as HTMLInputElement).value;
  busy = true;
  error = "";
  render();
  try {
    session.auth = { kind: "none" };
    persist();
    await connect(baseUrl);
    await loadPlatforms();
  } catch (err) {
    error = err instanceof Error ? err.message : String(err);
    busy = false;
    render();
  }
}

async function onConnect() {
  const baseUrl = (app.querySelector("#baseUrl") as HTMLInputElement).value;
  const username = (app.querySelector("#username") as HTMLInputElement).value.trim();
  const password = (app.querySelector("#password") as HTMLInputElement).value;
  const token = (app.querySelector("#token") as HTMLInputElement).value.trim();
  busy = true;
  error = "";
  render();
  try {
    await connect(baseUrl);
    if (token.startsWith("rmm_")) {
      client.setClientToken(token);
      session.auth = client.auth;
    } else if (/^\d{8}$/.test(token)) {
      await client.exchangePairCode(token);
      session.auth = client.auth;
    } else if (username && password) {
      await client.login(username, password);
      session.auth = client.auth;
    } else {
      session.auth = { kind: "none" };
      client.setAuth(session.auth);
    }
    persist();
    await loadPlatforms();
  } catch (err) {
    if (err instanceof RommApiError && err.status === 401 && username && password) {
      client.setAuth({ kind: "basic", username, password });
      session.auth = client.auth;
      persist();
      try {
        await loadPlatforms();
        return;
      } catch (inner) {
        error = inner instanceof Error ? inner.message : String(inner);
      }
    } else {
      error = err instanceof Error ? err.message : String(err);
    }
    busy = false;
    screen = "login";
    render();
  }
}

function mappingBadge(slug: string, fsSlug: string): string {
  const dest = resolveDestination(map, {
    rommSlug: slug,
    rommFsSlug: fsSlug,
    layout: session.layout,
    existingFolders,
    overrides: session.folderOverrides,
  });
  if (!dest.mapped) return `<span class="badge warn">unmapped · ${dest.folderName}</span>`;
  return `<span class="badge ok">${dest.cocoonName} · ${dest.folderName}</span>`;
}

function renderPlatforms() {
  const visible = filterPlatforms(platforms, platformQuery);
  app.innerHTML = `
    ${topbar(session.baseUrl || "Not connected")}
    ${error ? `<p class="error">${escapeHtml(error)}</p>` : ""}
    <div class="grid">
      ${visible.map((platform) => {
        const year = platformReleaseYear(map, platform);
        const count = `${platform.rom_count} games`;
        const meta = year ? `${year} · ${count}` : count;
        return `
        <button class="card" data-platform="${platform.id}">
          <div class="art" ${coverStyle(platform.url_logo || null)}>${mappingBadge(platform.slug, platform.fs_slug)}</div>
          <div class="meta">
            <h3>${escapeHtml(platform.display_name || platform.name)}</h3>
            <p>${escapeHtml(meta)}</p>
          </div>
        </button>
      `;
      }).join("")}
    </div>
    ${platforms.length === 0 && !busy ? `<p class="empty">No platforms came back from RomM.</p>` : ""}
    ${platforms.length > 0 && visible.length === 0 ? `<p class="empty">No platforms match “${escapeHtml(platformQuery.trim())}”. Clear the filter to see every console, newest generation first.</p>` : ""}
  `;
  bindChrome();
  app.querySelectorAll<HTMLButtonElement>("[data-platform]").forEach((button) => {
    button.addEventListener("click", () => {
      const match = platforms.find((p) => String(p.id) === button.dataset.platform) ?? null;
      if (match) openPlatform(match);
    });
  });
}

function renderGames() {
  const title = currentPlatform?.display_name || currentPlatform?.name || "Games";
  app.innerHTML = `
    ${topbar(title)}
    <div class="row" style="margin-bottom:1rem">
      <button class="ghost" data-go="platforms">All platforms</button>
      <span class="muted">${roms.length} of ${romTotal || roms.length}</span>
    </div>
    ${error ? `<p class="error">${escapeHtml(error)}</p>` : ""}
    <div class="grid">
      ${roms.map((rom) => `
        <button class="card" data-rom="${rom.id}">
          <div class="art" ${coverStyle(client.coverUrl(rom))}>
            ${downloaded(rom) ? `<span class="badge ok">on device</span>` : `<span class="badge">${formatBytes(rom.fs_size_bytes)}</span>`}
          </div>
          <div class="meta">
            <h3>${escapeHtml(rom.name || rom.fs_name_no_tags)}</h3>
            <p>${escapeHtml(rom.fs_name)}</p>
          </div>
        </button>
      `).join("")}
    </div>
    ${roms.length < romTotal ? `<div class="row" style="margin-top:1rem"><button id="more">Load more</button></div>` : ""}
    ${roms.length === 0 && !busy && gameQuery.trim() ? `<p class="empty">No ${escapeHtml(title)} games match “${escapeHtml(gameQuery.trim())}”. Clear search to see every game on this platform.</p>` : ""}
    ${roms.length === 0 && !busy && !gameQuery.trim() ? `<p class="empty">No games on this platform.</p>` : ""}
  `;
  bindChrome();
  app.querySelector("[data-go='platforms']")?.addEventListener("click", () => {
    screen = "platforms";
    render();
  });
  app.querySelector("#more")?.addEventListener("click", () => void loadGames(false));
  app.querySelectorAll<HTMLButtonElement>("[data-rom]").forEach((button) => {
    button.addEventListener("click", () => {
      selectedRom = roms.find((rom) => String(rom.id) === button.dataset.rom) ?? null;
      status = "";
      downloadPct = 0;
      screen = "detail";
      render();
    });
  });
}

function renderDetail() {
  if (!selectedRom) {
    screen = "games";
    render();
    return;
  }
  const rom = selectedRom;
  const dest = destinationFor(rom);
  app.innerHTML = `
    ${topbar(rom.name || rom.fs_name_no_tags)}
    <div class="row" style="margin-bottom:1rem">
      <button class="ghost" data-go="games">Back</button>
    </div>
    <article class="detail">
      <div class="art" ${coverStyle(client.coverUrl(rom))}></div>
      <div class="copy">
        <h2>${escapeHtml(rom.name || rom.fs_name_no_tags)}</h2>
        <p>${escapeHtml(rom.platform_display_name)} · ${escapeHtml(rom.fs_name)} · ${formatBytes(rom.fs_size_bytes)}</p>
        <p style="margin-top:.8rem">${escapeHtml(rom.summary || "No description from RomM.")}</p>
        <p style="margin-top:.8rem">Cocoon folder: <strong>${escapeHtml(dest.folderName)}</strong> ${dest.mapped ? "" : "(no platform map; using RomM slug)"}</p>
        <div class="progress"><span style="width:${downloadPct}%"></span></div>
        ${status ? `<p class="muted">${escapeHtml(status)}</p>` : ""}
        ${error ? `<p class="error">${escapeHtml(error)}</p>` : ""}
        <div class="row" style="margin-top:1rem">
          <button class="primary" id="download" ${busy ? "disabled" : ""}>${downloaded(rom) ? "Download again" : "Download to Cocoon"}</button>
        </div>
      </div>
    </article>
  `;
  bindChrome();
  app.querySelector("[data-go='games']")?.addEventListener("click", () => {
    screen = "games";
    render();
  });
  app.querySelector("#download")?.addEventListener("click", () => void downloadRom(rom));
}

function renderSettings() {
  app.innerHTML = `
    ${topbar("Settings")}
    <section class="panel">
      <div class="field">
        <label for="layout">Folder layout</label>
        <select id="layout">
          <option value="cocoon" ${session.layout === "cocoon" ? "selected" : ""}>Cocoon unique IDs (gb, snes, n64, gc, n3ds)</option>
          <option value="romm" ${session.layout === "romm" ? "selected" : ""}>RomM slugs (gb, snes, n64, ngc, 3ds)</option>
          <option value="alias" ${session.layout === "alias" ? "selected" : ""}>Match folders that already exist</option>
        </select>
      </div>
      <p class="muted">Point this app at the same ROM root Cocoon scans. Games are saved into a per-platform subfolder, then show up after a library rescan.</p>
      <div class="row">
        <button class="primary" id="pick">Choose ROM root folder</button>
        <button class="ghost" data-go="platforms">Back to library</button>
        <button class="ghost" id="logout">Log out</button>
      </div>
      <p class="muted" style="margin-top:1rem">${romRoot ? "ROM root selected in this browser." : nativeBridge()?.getRomRoot ? "Native folder access available." : "No ROM root yet — downloads will go through the browser download manager."}</p>
      ${error ? `<p class="error">${escapeHtml(error)}</p>` : ""}
    </section>
  `;
  bindChrome();
  app.querySelector("#layout")?.addEventListener("change", (event) => {
    session.layout = (event.target as HTMLSelectElement).value as FolderLayout;
    persist();
  });
  app.querySelector("#pick")?.addEventListener("click", async () => {
    const native = nativeBridge();
    if (native?.pickRomRoot) {
      const path = await native.pickRomRoot();
      status = path ? `ROM root: ${path}` : "Folder picker cancelled.";
      await refreshLocalFolders();
      render();
      return;
    }
    const handle = await pickDirectory();
    if (!handle) {
      error = "This browser cannot pick a folder. Pin the Android wrapper or use Chrome, then move files into your Cocoon ROM folders.";
      render();
      return;
    }
    romRoot = handle;
    await persistRomRoot(handle);
    await refreshLocalFolders();
    error = "";
    render();
  });
  app.querySelector("#logout")?.addEventListener("click", () => {
    session.auth = { kind: "none" };
    session.baseUrl = "";
    persist();
    screen = "login";
    render();
  });
  app.querySelector("[data-go='platforms']")?.addEventListener("click", () => {
    screen = "platforms";
    render();
  });
}

function bindChrome() {
  const searchBox = app.querySelector<HTMLInputElement>("#search");
  const rememberCaret = () => {
    if (!searchBox) return;
    searchCaret = searchBox.selectionStart ?? searchBox.value.length;
  };
  searchBox?.addEventListener("input", () => {
    rememberCaret();
    if (screen === "platforms") {
      platformQuery = searchBox.value;
      keepSearchFocus = true;
      render();
      return;
    }
    if (screen === "games" || screen === "detail") {
      gameQuery = searchBox.value;
    }
  });
  searchBox?.addEventListener("keydown", (event) => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    rememberCaret();
    if (screen === "games" || screen === "detail") {
      gameQuery = searchBox.value;
      keepSearchFocus = true;
      void loadGames(true);
      return;
    }
    if (screen === "platforms") {
      platformQuery = searchBox.value;
      const visible = filterPlatforms(platforms, platformQuery);
      if (visible.length === 1) {
        openPlatform(visible[0]);
      }
    }
  });
  app.querySelector("#clear-search")?.addEventListener("click", () => {
    if (screen === "games" || screen === "detail") {
      gameQuery = "";
      void loadGames(true);
      return;
    }
    platformQuery = "";
    keepSearchFocus = true;
    searchCaret = 0;
    render();
  });
  app.querySelector("#run-search")?.addEventListener("click", () => {
    const value = searchBox?.value ?? gameQuery;
    gameQuery = value;
    keepSearchFocus = true;
    void loadGames(true);
  });
  app.querySelector("[data-go='settings']")?.addEventListener("click", () => {
    screen = "settings";
    render();
  });
}

function render() {
  if (screen === "login") renderLogin();
  else if (screen === "settings") renderSettings();
  else if (screen === "detail") renderDetail();
  else if (screen === "games") renderGames();
  else renderPlatforms();
  restoreSearchFocus();
  notifyNativeReady();
}

async function start() {
  console.info("[RommStore] start");
  render();
  romRoot = await restoreRomRoot();
  await refreshLocalFolders();
  if (session.baseUrl) {
    try {
      await connect(session.baseUrl);
      await loadPlatforms();
    } catch {
      screen = "login";
      render();
    }
  }
}

function boot() {
  bindApp();
  window.addEventListener("error", (event) => {
    showBootError(event.error ?? event.message);
  });
  window.addEventListener("unhandledrejection", (event) => {
    showBootError(event.reason);
  });
  void start().catch(showBootError);
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", boot);
} else {
  boot();
}
