import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { isFetchBlocked } from "../src/fs";
import {
  contentDownloadPath,
  filterPlatforms,
  joinDownloadPath,
  lookupPlatform,
  platformReleaseYear,
  resolveDestination,
  sanitizeFilename,
  sortPlatformsByGeneration,
  type PlatformMapFile,
} from "../src/map";
import { normalizeBaseUrl, RommClient, romsQuery } from "../src/api";

const map = JSON.parse(
  readFileSync(join(dirname(fileURLToPath(import.meta.url)), "../platform-map.json"), "utf8"),
) as PlatformMapFile;

describe("platform map", () => {
  it("maps obvious mismatches into Cocoon folder names", () => {
    expect(lookupPlatform(map, "ngc")?.cocoonUniqueId).toBe("gc");
    expect(lookupPlatform(map, "3ds")?.cocoonUniqueId).toBe("n3ds");
    expect(lookupPlatform(map, "sms")?.cocoonUniqueId).toBe("mastersystem");
    expect(lookupPlatform(map, "dc")?.cocoonUniqueId).toBe("dreamcast");
    expect(lookupPlatform(map, "zxs")?.cocoonUniqueId).toBe("zxspectrum");
    expect(lookupPlatform(map, "acpc")?.cocoonUniqueId).toBe("amstradcpc");
    expect(lookupPlatform(map, "gb")?.cocoonUniqueId).toBe("gb");
  });

  it("uses Cocoon unique IDs by default", () => {
    expect(resolveDestination(map, { rommSlug: "ngc", rommFsSlug: "ngc" }).folderName).toBe("gc");
    expect(resolveDestination(map, { rommSlug: "snes" }).folderName).toBe("snes");
  });

  it("can keep RomM slugs when the library already uses them", () => {
    expect(resolveDestination(map, { rommSlug: "ngc", layout: "romm" }).folderName).toBe("ngc");
  });

  it("prefers an existing local folder in alias mode", () => {
    const dest = resolveDestination(map, {
      rommSlug: "ngc",
      layout: "alias",
      existingFolders: ["Roms", "gamecube", "snes"],
    });
    expect(dest.folderName).toBe("gamecube");
  });

  it("honors per-platform overrides", () => {
    const dest = resolveDestination(map, {
      rommSlug: "gb",
      overrides: { gb: "Game Boy" },
    });
    expect(dest.folderName).toBe("Game Boy");
  });

  it("still downloads unmapped platforms into the RomM folder name", () => {
    const dest = resolveDestination(map, { rommSlug: "playdate", rommFsSlug: "playdate" });
    expect(dest.mapped).toBe(false);
    expect(dest.folderName).toBe("playdate");
  });
});

describe("platform generation sort and search", () => {
  const sample = [
    { slug: "nes", fs_slug: "nes", name: "NES", display_name: "Nintendo Entertainment System", rom_count: 3 },
    { slug: "switch", fs_slug: "switch", name: "Switch", display_name: "Nintendo Switch", rom_count: 12 },
    { slug: "nds", fs_slug: "nds", name: "NDS", display_name: "Nintendo DS", rom_count: 8 },
    { slug: "3ds", fs_slug: "3ds", name: "3DS", display_name: "Nintendo 3DS", rom_count: 9 },
    { slug: "wiiu", fs_slug: "wiiu", name: "Wii U", display_name: "Nintendo Wii U", rom_count: 4 },
    { slug: "ps3", fs_slug: "ps3", name: "PS3", display_name: "Sony PlayStation 3", rom_count: 6 },
    { slug: "wii", fs_slug: "wii", name: "Wii", display_name: "Nintendo Wii", rom_count: 7 },
    { slug: "playdate", fs_slug: "playdate", name: "Playdate", display_name: "Playdate", rom_count: 1 },
  ];

  it("sorts consoles newest generation first", () => {
    const ordered = sortPlatformsByGeneration(map, sample).map((p) => p.slug);
    expect(ordered.slice(0, 5)).toEqual(["switch", "wiiu", "3ds", "wii", "ps3"]);
    expect(ordered.at(-1)).toBe("playdate");
  });

  it("knows original launch years", () => {
    expect(platformReleaseYear(map, { slug: "switch" })).toBe(2017);
    expect(platformReleaseYear(map, { slug: "3ds" })).toBe(2011);
    expect(platformReleaseYear(map, { slug: "nds" })).toBe(2004);
    expect(platformReleaseYear(map, { slug: "nes" })).toBe(1983);
    expect(platformReleaseYear(map, { slug: "ngc" })).toBe(2001);
  });

  it("filters platforms without treating the query as a game title", () => {
    const hits = filterPlatforms(sample, "switch");
    expect(hits.map((p) => p.slug)).toEqual(["switch"]);
    expect(filterPlatforms(sample, "nintendo").map((p) => p.slug)).toEqual([
      "nes",
      "switch",
      "nds",
      "3ds",
      "wiiu",
      "wii",
    ]);
  });
});

describe("download paths", () => {
  it("sanitizes and joins destination paths", () => {
    expect(sanitizeFilename("Mario: Lost Levels.nes")).toBe("Mario_ Lost Levels.nes");
    expect(joinDownloadPath("n64", "Super Mario 64.z64")).toBe("n64/Super Mario 64.z64");
    expect(contentDownloadPath(12, "Game (USA).zip")).toBe("/api/roms/12/content/Game%20(USA).zip");
  });
});

describe("RomM client", () => {
  it("normalizes server URLs", () => {
    expect(normalizeBaseUrl("demo.romm.app/")).toBe("http://demo.romm.app");
    expect(normalizeBaseUrl("https://romm.lan:8080/")).toBe("https://romm.lan:8080");
  });

  it("builds authenticated list and download URLs", async () => {
    const calls: string[] = [];
    const fetchImpl: typeof fetch = async (input) => {
      calls.push(String(input));
      return new Response(JSON.stringify({
        items: [{ id: 1, fs_name: "Aardvark.bin", name: "Aardvark", platform_slug: "atari2600" }],
        total: 1,
        limit: 48,
        offset: 0,
      }), { headers: { "Content-Type": "application/json" } });
    };
    const client = new RommClient({
      baseUrl: "https://romm.example",
      auth: { kind: "bearer", token: "rmm_test" },
      fetchImpl,
    });
    expect(client.authHeaders()).toEqual({ Authorization: "Bearer rmm_test" });
    expect(client.downloadUrl(1, "Aardvark.bin")).toBe("https://romm.example/api/roms/1/content/Aardvark.bin");
    const page = await client.roms({ platformId: 4, search: "zelda" });
    expect(page.items[0].name).toBe("Aardvark");
    expect(calls[0]).toContain("platform_ids=4");
    expect(calls[0]).toContain("search_term=zelda");
    expect(calls[0]).not.toContain("with_rom_id_index=");
  });

  it("omits gallery sidecars that some RomM versions 500 on", () => {
    expect(romsQuery({ platformId: 17, variant: "gallery" })).not.toContain("with_rom_id_index");
    expect(romsQuery({ platformId: 17, variant: "legacy" })).toContain("platform_id=17");
  });

  it("retries ROM lists after a 500 using a simpler query", async () => {
    const calls: string[] = [];
    const fetchImpl: typeof fetch = async (input) => {
      const url = String(input);
      calls.push(url);
      if (calls.length === 1) {
        return new Response(JSON.stringify({ detail: "Internal Server Error" }), {
          status: 500,
          headers: { "Content-Type": "application/json" },
        });
      }
      return new Response(JSON.stringify({ items: [], total: 0, limit: 48, offset: 0 }), {
        headers: { "Content-Type": "application/json" },
      });
    };
    const client = new RommClient({
      baseUrl: "https://romm.example",
      auth: { kind: "bearer", token: "rmm_test" },
      fetchImpl,
    });
    const page = await client.roms({ platformId: 17 });
    expect(page.total).toBe(0);
    expect(calls.length).toBeGreaterThan(1);
  });

  it("does not send a Bearer token to /api/token", async () => {
    const headers: string[] = [];
    const fetchImpl: typeof fetch = async (input, init) => {
      headers.push(new Headers(init?.headers).get("Authorization") || "");
      return new Response(JSON.stringify({
        access_token: "new",
        token_type: "bearer",
        expires: 3600,
        refresh_token: "r2",
      }), { headers: { "Content-Type": "application/json" } });
    };
    const client = new RommClient({
      baseUrl: "https://romm.example",
      auth: { kind: "bearer", token: "expired", refreshToken: "r1", username: "kaval", password: "secret" },
      fetchImpl,
    });
    await client.login("kaval", "secret");
    expect(headers[0]).toBe("");
    expect(client.auth.kind).toBe("bearer");
    if (client.auth.kind === "bearer") {
      expect(client.auth.token).toBe("new");
      expect(client.auth.username).toBe("kaval");
      expect(client.auth.password).toBe("secret");
    }
  });

  it("reissues an expired token then retries the original call", async () => {
    const calls: string[] = [];
    const fetchImpl: typeof fetch = async (input, init) => {
      const url = String(input);
      calls.push(`${init?.method || "GET"} ${url}`);
      if (url.includes("/api/token")) {
        return new Response(JSON.stringify({
          access_token: "fresh",
          token_type: "bearer",
          expires: 3600,
          refresh_token: "r2",
        }), { headers: { "Content-Type": "application/json" } });
      }
      const auth = new Headers(init?.headers).get("Authorization");
      if (auth === "Bearer expired") {
        return new Response(JSON.stringify({ detail: "Unauthorized" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        });
      }
      return new Response(JSON.stringify([]), { headers: { "Content-Type": "application/json" } });
    };
    const client = new RommClient({
      baseUrl: "https://romm.example",
      auth: {
        kind: "bearer",
        token: "expired",
        refreshToken: "r1",
        expiresAt: Date.now() - 1000,
        username: "kaval",
        password: "secret",
      },
      fetchImpl,
    });
    const platforms = await client.platforms();
    expect(platforms).toEqual([]);
    expect(calls.some((line) => line.includes("/api/token"))).toBe(true);
    expect(client.auth.kind === "bearer" && client.auth.token === "fresh").toBe(true);
  });
});

describe("browser download fallback", () => {
  it("detects CORS/fetch blocks", () => {
    expect(isFetchBlocked(new TypeError("Failed to fetch"))).toBe(true);
    expect(isFetchBlocked(new Error("Download failed (404)"))).toBe(false);
  });
});

describe("android bundle", () => {
  it("ships a classic-script HTML file with no ES modules", () => {
    const html = readFileSync(
      join(dirname(fileURLToPath(import.meta.url)), "../android/app/src/main/assets/www/index.html"),
      "utf8",
    );
    const raw = readFileSync(
      join(dirname(fileURLToPath(import.meta.url)), "../android/app/src/main/res/raw/store.html"),
      "utf8",
    );
    expect(html).toBe(raw);
    expect(html).not.toMatch(/type=["']module["']/);
    expect(html).not.toMatch(/index-dSIPXYpo/);
    expect(html).not.toMatch(/assets\/index-/);
    expect(html).not.toMatch(/await fetch\(["']\.\/platform-map\.json/);
    expect(html).toContain("Cocoon RomM Store");
    expect(html).toContain('name="romm-store-build"');
    expect(html).toContain("1.0.7");
    expect(html).toContain("Handheld controls");
    expect(html).toContain("<script>");
    expect(html).toContain("<style>");
    expect(html.lastIndexOf("<script>")).toBeGreaterThan(html.lastIndexOf('<div id="app">'));
  });
});
