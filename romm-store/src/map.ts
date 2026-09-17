export type FolderLayout = "cocoon" | "romm" | "alias";

export interface PlatformMapping {
  cocoonUniqueId: string;
  cocoonName: string;
  cocoonShortname: string;
  filename: string;
  rommSlugs: string[];
  folderAliases: string[];
  downloadable: boolean;
}

export interface PlatformMapFile {
  version: number;
  defaultLayout: FolderLayout;
  platforms: PlatformMapping[];
}

export interface ResolvedDestination {
  cocoonUniqueId: string | null;
  cocoonName: string | null;
  folderName: string;
  mapped: boolean;
  candidates: string[];
}

function norm(value: string | null | undefined): string {
  return (value ?? "").trim().toLowerCase();
}

export function indexPlatformMap(map: PlatformMapFile): {
  byCocoonId: Map<string, PlatformMapping>;
  byRommSlug: Map<string, PlatformMapping>;
} {
  const byCocoonId = new Map<string, PlatformMapping>();
  const byRommSlug = new Map<string, PlatformMapping>();
  for (const platform of map.platforms) {
    byCocoonId.set(norm(platform.cocoonUniqueId), platform);
    for (const slug of platform.rommSlugs) {
      const key = norm(slug);
      if (key && !byRommSlug.has(key)) {
        byRommSlug.set(key, platform);
      }
    }
    for (const alias of platform.folderAliases) {
      const key = norm(alias);
      if (key && !byRommSlug.has(key)) {
        byRommSlug.set(key, platform);
      }
    }
  }
  return { byCocoonId, byRommSlug };
}

export function lookupPlatform(
  map: PlatformMapFile,
  rommSlug: string | null | undefined,
  rommFsSlug: string | null | undefined = rommSlug,
): PlatformMapping | null {
  const { byRommSlug } = indexPlatformMap(map);
  return (
    byRommSlug.get(norm(rommSlug)) ??
    byRommSlug.get(norm(rommFsSlug)) ??
    null
  );
}

export function uniqueFolders(values: Array<string | null | undefined>): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const value of values) {
    const key = norm(value);
    if (!key || seen.has(key)) continue;
    seen.add(key);
    out.push(key);
  }
  return out;
}

export function resolveDestination(
  map: PlatformMapFile,
  options: {
    rommSlug?: string | null;
    rommFsSlug?: string | null;
    layout?: FolderLayout;
    existingFolders?: string[];
    overrides?: Record<string, string>;
  },
): ResolvedDestination {
  const layout = options.layout ?? map.defaultLayout ?? "cocoon";
  const mapping = lookupPlatform(map, options.rommSlug, options.rommFsSlug);
  const rommFolder = norm(options.rommFsSlug) || norm(options.rommSlug) || "roms";
  const candidates = uniqueFolders([
    mapping?.cocoonUniqueId,
    mapping?.cocoonShortname,
    ...(mapping?.folderAliases ?? []),
    options.rommSlug,
    options.rommFsSlug,
  ]);

  const overrideKey = mapping?.cocoonUniqueId ?? rommFolder;
  const override = options.overrides?.[overrideKey];
  if (override) {
    return {
      cocoonUniqueId: mapping?.cocoonUniqueId ?? null,
      cocoonName: mapping?.cocoonName ?? null,
      folderName: override,
      mapped: Boolean(mapping),
      candidates,
    };
  }

  if (layout === "alias" && options.existingFolders?.length) {
    const existing = new Set(options.existingFolders.map(norm));
    const match = candidates.find((folder) => existing.has(folder));
    if (match) {
      return {
        cocoonUniqueId: mapping?.cocoonUniqueId ?? null,
        cocoonName: mapping?.cocoonName ?? null,
        folderName: match,
        mapped: Boolean(mapping),
        candidates,
      };
    }
  }

  const folderName =
    layout === "romm" || !mapping
      ? rommFolder
      : mapping.cocoonUniqueId;

  return {
    cocoonUniqueId: mapping?.cocoonUniqueId ?? null,
    cocoonName: mapping?.cocoonName ?? null,
    folderName,
    mapped: Boolean(mapping),
    candidates,
  };
}

export function sanitizeFilename(name: string): string {
  const cleaned = name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, "_").trim();
  return cleaned || "rom.bin";
}

export function joinDownloadPath(folderName: string, fileName: string): string {
  return `${folderName.replace(/\\/g, "/").replace(/^\/+|\/+$/g, "")}/${sanitizeFilename(fileName)}`;
}

export function contentDownloadPath(romId: number, fileName: string): string {
  return `/api/roms/${romId}/content/${encodeURIComponent(fileName)}`;
}

export function absoluteUrl(baseUrl: string, path: string): string {
  const origin = baseUrl.replace(/\/+$/, "");
  if (/^https?:\/\//i.test(path)) return path;
  if (path.startsWith("/")) return `${origin}${path}`;
  return `${origin}/${path}`;
}
