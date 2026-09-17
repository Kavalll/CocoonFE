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

export interface PlatformSortable {
  slug?: string | null;
  fs_slug?: string | null;
  name?: string | null;
  display_name?: string | null;
}

/**
 * First commercial launch year (usually the original home-market release).
 * Keys are Cocoon uniqueIds and common RomM slugs. Unknown platforms sort last.
 */
export const PLATFORM_RELEASE_YEAR: Record<string, number> = {
  "3do": 1993,
  amstradcpc: 1984,
  acpc: 1984,
  apple2: 1977,
  appleii: 1977,
  arcadia: 1982,
  "arcadia-2001": 1982,
  arduboy: 2015,
  atari2600: 1977,
  atari5200: 1982,
  atari7800: 1986,
  atarijaguar: 1993,
  jaguar: 1993,
  atarijaguarcd: 1995,
  "atari-jaguar-cd": 1995,
  atarilynx: 1989,
  lynx: 1989,
  atarist: 1985,
  "atari-st": 1985,
  atomiswave: 2003,
  bbcmicro: 1981,
  ebook: 2007,
  cps1: 1988,
  cps2: 1993,
  cps3: 1996,
  cannonball: 2014,
  cassette: 1981,
  cavestory: 2004,
  chip8: 1977,
  colecovision: 1982,
  c64: 1982,
  amiga: 1985,
  pet: 1977,
  cpet: 1977,
  plus4: 1984,
  "c-plus-4": 1984,
  doom: 1993,
  dos: 1981,
  dreamcast: 1998,
  dc: 1998,
  elektor: 1979,
  fmtowns: 1989,
  "fm-towns": 1989,
  channelf: 1976,
  "fairchild-channel-f": 1976,
  fds: 1986,
  famicom: 1983,
  fbneo: 1999,
  flashback: 1992,
  adobeflash: 1996,
  browser: 1996,
  gameandwatch: 1980,
  "g-and-w": 1980,
  gb: 1989,
  gba: 2001,
  gbc: 1998,
  gc: 2001,
  ngc: 2001,
  gamecube: 2001,
  intellivision: 1979,
  vc4000: 1978,
  "interton-vc-4000": 1978,
  j2me: 2002,
  lowresnx: 2018,
  mame: 1978,
  arcade: 1978,
  msx: 1983,
  msx2: 1985,
  odyssey2: 1978,
  "odyssey-2": 1978,
  megaduck: 1993,
  "mega-duck-slash-cougar-boy": 1993,
  moonlight: 2014,
  pc60: 1981,
  "pc-6001": 1981,
  pc88: 1981,
  "pc-8800-series": 1981,
  pc98: 1982,
  "pc-9800-series": 1982,
  pcfx: 1994,
  "pc-fx": 1994,
  neogeo: 1990,
  neogeoaes: 1990,
  neogeomvs: 1990,
  neogeocd: 1994,
  "neo-geo-cd": 1994,
  ngp: 1998,
  "neo-geo-pocket": 1998,
  ngpc: 1999,
  "neo-geo-pocket-color": 1999,
  ngage: 2003,
  n3ds: 2011,
  "3ds": 2011,
  "new-nintendo-3ds": 2014,
  n64: 1996,
  nds: 2004,
  ndsi: 2008,
  "nintendo-dsi": 2008,
  nes: 1983,
  satellaview: 1995,
  switch: 2017,
  "switch-2": 2025,
  nswitch: 2017,
  wii: 2006,
  wiiu: 2012,
  wiiware: 2008,
  oric: 1983,
  atmos: 1984,
  pico8: 2015,
  palm: 1996,
  "palm-os": 1996,
  cdimono1: 1991,
  "philips-cd-i": 1991,
  videopac: 1983,
  "videopac-g7400": 1983,
  psp: 2004,
  pspminis: 2009,
  "psp-minis": 2009,
  pokemini: 2001,
  "pokemon-mini": 2001,
  ports: 2018,
  quake: 1996,
  quake2: 1997,
  rpgmaker: 1997,
  scummvm: 2001,
  sega32x: 1994,
  sega32: 1994,
  segacd: 1991,
  gamegear: 1990,
  genesis: 1988,
  megadrive: 1988,
  genesismsu: 1988,
  mastersystem: 1985,
  sms: 1985,
  naomi: 1998,
  pico: 1993,
  "sega-pico": 1993,
  "sg-1000": 1983,
  sg1000: 1983,
  saturn: 1994,
  x1: 1982,
  x68000: 1987,
  "sharp-x68000": 1987,
  psvita: 2011,
  psx: 1994,
  ps1: 1994,
  ps2: 2000,
  ps3: 2006,
  ps4: 2013,
  ps5: 2020,
  steam: 2003,
  supercassette: 1984,
  supergrafx: 1989,
  snes: 1990,
  snesmsu1: 1990,
  ti83: 1996,
  tic80: 2017,
  "tic-80": 2017,
  to8: 1986,
  tg16: 1987,
  "tg-cd": 1988,
  "turbografx-cd": 1988,
  uzebox: 2008,
  vic20: 1980,
  "vic-20": 1980,
  vectrex: 1982,
  videos: 2010,
  virtualboy: 1995,
  wasm4: 2021,
  "wasm-4": 2021,
  supervision: 1992,
  windows: 1995,
  win: 1995,
  win9x: 1995,
  wonderswan: 1999,
  wonderswancolor: 2000,
  "wonderswan-color": 2000,
  xbox: 2001,
  xbox360: 2005,
  xboxone: 2013,
  xboxseries: 2020,
  xcloud: 2020,
  xboxcloudgaming: 2020,
  zx81: 1981,
  zxspectrum: 1982,
  zxs: 1982,
  zeebo: 2009,
};

/** More specific names first so “Switch 2” is not treated as 2017 Switch. */
export const PLATFORM_NAME_YEARS: Array<[RegExp, number]> = [
  [/\bswitch\s*2\b/i, 2025],
  [/\bnintendo switch\b|\bswitch\b/i, 2017],
  [/\bwii\s*u\b/i, 2012],
  [/\bwiiware\b/i, 2008],
  [/\bwii\b/i, 2006],
  [/\bnew\s*nintendo\s*3ds\b/i, 2014],
  [/\b3ds\b/i, 2011],
  [/\bdsi\b/i, 2008],
  [/\bnintendo ds\b|\bnds\b/i, 2004],
  [/\bgame\s*boy\s*advance\b|\bgba\b/i, 2001],
  [/\bgame\s*boy\s*color\b|\bgbc\b/i, 1998],
  [/\bgame\s*boy\b|\bgb\b/i, 1989],
  [/\bgamecube\b|\bngc\b/i, 2001],
  [/\bnintendo 64\b|\bn64\b/i, 1996],
  [/\bsnes\b|\bsuper nintendo\b|\bsuper famicom\b/i, 1990],
  [/\bfamicom disk\b|\bfds\b/i, 1986],
  [/\bnintendo entertainment system\b|\bfamicom\b|\bnes\b/i, 1983],
  [/\bps\s*vita\b|\bplaystation vita\b|\bpsvita\b/i, 2011],
  [/\bplaystation\s*5\b|\bps5\b/i, 2020],
  [/\bplaystation\s*4\b|\bps4\b/i, 2013],
  [/\bplaystation\s*3\b|\bps3\b/i, 2006],
  [/\bplaystation\s*2\b|\bps2\b/i, 2000],
  [/\bplaystation portable\b|\bpsp\b/i, 2004],
  [/\bplaystation\b|\bpsx\b|\bps1\b/i, 1994],
  [/\bxbox\s*series\b/i, 2020],
  [/\bxbox\s*one\b/i, 2013],
  [/\bxbox\s*360\b/i, 2005],
  [/\bxbox\s*cloud\b|\bgame pass\b|\bxcloud\b/i, 2020],
  [/\bxbox\b/i, 2001],
  [/\bdreamcast\b/i, 1998],
  [/\bsega saturn\b|\bsaturn\b/i, 1994],
  [/\bsega cd\b|\bmega cd\b/i, 1991],
  [/\b32x\b/i, 1994],
  [/\bgenesis\b|\bmega drive\b/i, 1988],
  [/\bmaster system\b|\bsms\b/i, 1985],
  [/\bsg-?1000\b/i, 1983],
  [/\bgame gear\b/i, 1990],
];

export function platformDisplayName(platform: PlatformSortable): string {
  return (platform.display_name || platform.name || platform.slug || "Unknown").trim();
}

export function platformMatchesQuery(platform: PlatformSortable, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  const haystack = [
    platform.display_name,
    platform.name,
    platform.slug,
    platform.fs_slug,
  ]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();
  return haystack.includes(q);
}

export function filterPlatforms<T extends PlatformSortable>(platforms: T[], query: string): T[] {
  return platforms.filter((platform) => platformMatchesQuery(platform, query));
}

export function platformReleaseYear(map: PlatformMapFile, platform: PlatformSortable): number {
  const mapping = lookupPlatform(map, platform.slug, platform.fs_slug);
  const keys = [mapping?.cocoonUniqueId, mapping?.cocoonShortname, platform.slug, platform.fs_slug];
  for (const key of keys) {
    const year = PLATFORM_RELEASE_YEAR[norm(key)];
    if (year) return year;
  }
  const label = `${platform.display_name || ""} ${platform.name || ""}`;
  for (const [pattern, year] of PLATFORM_NAME_YEARS) {
    if (pattern.test(label)) return year;
  }
  return 0;
}

export function sortPlatformsByGeneration<T extends PlatformSortable>(
  map: PlatformMapFile,
  platforms: T[],
): T[] {
  return [...platforms].sort((a, b) => {
    const yearDiff = platformReleaseYear(map, b) - platformReleaseYear(map, a);
    if (yearDiff !== 0) return yearDiff;
    return platformDisplayName(a).localeCompare(platformDisplayName(b));
  });
}
