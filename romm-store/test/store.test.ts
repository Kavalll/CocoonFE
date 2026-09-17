import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { isFetchBlocked } from "../src/fs";
import {
  contentDownloadPath,
  joinDownloadPath,
  lookupPlatform,
  resolveDestination,
  sanitizeFilename,
  type PlatformMapFile,
} from "../src/map";
import { normalizeBaseUrl, RommClient } from "../src/api";

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
    expect(calls[0]).toContain("with_rom_id_index=false");
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
    expect(html).toContain("<script>");
    expect(html).toContain("<style>");
    expect(html.lastIndexOf("<script>")).toBeGreaterThan(html.lastIndexOf('<div id="app">'));
  });
});
