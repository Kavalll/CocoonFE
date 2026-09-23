import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { buildScript, escapeScript, extractScript, inlineHtml } from "../scripts/build.mjs";

const require = createRequire(import.meta.url);
const shelf = require("../src/shelf.js");
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const map = JSON.parse(readFileSync(path.join(root, "platform-map.json"), "utf8"));

const consoles = [
  { slug: "nes", fs_slug: "nes", name: "NES", display_name: "Nintendo Entertainment System", rom_count: 3 },
  { slug: "switch", fs_slug: "switch", name: "Switch", display_name: "Nintendo Switch", rom_count: 12 },
  { slug: "nds", fs_slug: "nds", name: "NDS", display_name: "Nintendo DS", rom_count: 8 },
  { slug: "3ds", fs_slug: "3ds", name: "3DS", display_name: "Nintendo 3DS", rom_count: 9 },
  { slug: "wiiu", fs_slug: "wiiu", name: "Wii U", display_name: "Nintendo Wii U", rom_count: 4 },
  { slug: "ps3", fs_slug: "ps3", name: "PS3", display_name: "Sony PlayStation 3", rom_count: 6 },
  { slug: "wii", fs_slug: "wii", name: "Wii", display_name: "Nintendo Wii", rom_count: 7 },
  { slug: "playdate", fs_slug: "playdate", name: "Playdate", display_name: "Playdate", rom_count: 1 }
];

test("folder mapping uses Cocoon ids, RomM slugs, or an existing folder", () => {
  assert.equal(shelf.resolveDestination(map, { rommSlug: "ngc" }).folderName, "gc");
  assert.equal(shelf.resolveDestination(map, { rommSlug: "3ds" }).folderName, "n3ds");
  assert.equal(shelf.resolveDestination(map, { rommSlug: "sms" }).folderName, "mastersystem");
  assert.equal(shelf.resolveDestination(map, { rommSlug: "snes" }).folderName, "snes");
  assert.equal(shelf.resolveDestination(map, { rommSlug: "ngc", layout: "romm" }).folderName, "ngc");
  assert.equal(shelf.resolveDestination(map, { rommSlug: "3ds", layout: "romm" }).folderName, "3ds");
  assert.equal(shelf.resolveDestination(map, { rommSlug: "sms", layout: "romm" }).folderName, "sms");
  const alias = shelf.resolveDestination(map, {
    rommSlug: "ngc",
    layout: "alias",
    existingFolders: ["Roms", "gamecube", "snes"]
  });
  assert.equal(alias.folderName, "gamecube");
  assert.equal(shelf.resolveDestination(map, { rommSlug: "playdate" }).folderName, "playdate");
  assert.equal(shelf.joinDownloadPath("gc", "Mario: Kart.iso"), "gc/Mario_ Kart.iso");
});

test("platforms sort newest first and a console filter is not a game search", () => {
  const ordered = shelf.sortPlatformsByGeneration(map, consoles).map((platform) => platform.slug);
  assert.deepEqual(ordered.slice(0, 5), ["switch", "wiiu", "3ds", "wii", "ps3"]);
  assert.equal(ordered[ordered.length - 1], "playdate");
  assert.equal(shelf.platformReleaseYear(map, { slug: "ngc" }), 2001);
  assert.equal(shelf.platformReleaseYear(map, { slug: "switch" }), 2017);

  const filtered = shelf.applyPlatformFilterModel(consoles, "switch", "nes");
  assert.equal(filtered.screen, "platforms");
  assert.equal(filtered.fetchGames, false);
  assert.equal(filtered.gameSearch, null);
  assert.equal(filtered.focusedId, "switch");
  assert.deepEqual(filtered.visible.map((platform) => platform.slug), ["switch"]);

  const typed = shelf.planGameSearch({ mode: "draft", query: "switch", previous: "" });
  assert.equal(typed.fetch, false);
  assert.equal(typed.kind, "draft");
  const submitted = shelf.planGameSearch({ mode: "submit", query: "zelda", previous: "" });
  assert.equal(submitted.fetch, true);
  assert.equal(submitted.kind, "search");
  assert.equal(submitted.query, "zelda");
  const cleared = shelf.planGameSearch({ mode: "submit", query: "   ", previous: "zelda" });
  assert.equal(cleared.fetch, true);
  assert.equal(cleared.kind, "platform");
  assert.equal(cleared.query, "");
});

test("platforms with no games stay out of the list, the filter, and focus", () => {
  const list = [
    { slug: "switch", fs_slug: "switch", name: "Switch", display_name: "Nintendo Switch", rom_count: 2 },
    { slug: "nes", fs_slug: "nes", name: "NES", display_name: "Nintendo Entertainment System", rom_count: 0 },
    { slug: "empty", fs_slug: "empty", name: "Empty", display_name: "Empty Console" },
    { slug: "snes", fs_slug: "snes", name: "SNES", display_name: "Super Nintendo", rom_count: "4" }
  ];
  assert.deepEqual(shelf.platformsWithGames(list).map((platform) => platform.slug), ["switch", "snes"]);
  const filtered = shelf.applyPlatformFilterModel(list, "", "nes");
  assert.deepEqual(filtered.visible.map((platform) => platform.slug), ["switch", "snes"]);
  assert.equal(filtered.focusedId, "switch");
  assert.equal(filtered.fetchGames, false);
  assert.equal(filtered.screen, "platforms");
  const namedEmpty = shelf.applyPlatformFilterModel(list, "nes", "nes");
  assert.deepEqual(namedEmpty.visible.map((platform) => platform.slug), ["snes"]);
  assert.equal(namedEmpty.focusedId, "snes");
  const onlyEmpty = shelf.applyPlatformFilterModel(list, "empty", "empty");
  assert.deepEqual(onlyEmpty.visible, []);
  assert.equal(onlyEmpty.focusedId, null);
  assert.equal(shelf.canOpenPlatform(list[1]), false);
  assert.equal(shelf.canOpenPlatform(list[2]), false);
  assert.equal(shelf.canOpenPlatform(list[0]), true);
});

test("HTTP 500 stays signed in and only a failed refresh logs out", () => {
  assert.equal(shelf.authEffect(500, false), "stay");
  assert.equal(shelf.authEffect(500, true), "stay");
  assert.equal(shelf.authEffect(422, false), "stay");
  assert.equal(shelf.authEffect(400, false), "stay");
  assert.equal(shelf.authEffect(401, false), "refresh");
  assert.equal(shelf.authEffect(401, true), "logout");
  assert.equal(shelf.shouldRetryRoms(500), true);
  assert.equal(shelf.shouldRetryRoms(422), true);
  assert.equal(shelf.shouldRetryRoms(400), true);
  assert.equal(shelf.shouldRetryRoms(401), false);
  const requests = shelf.romsRequests({ platformId: 17, search: "zelda", limit: 60, offset: 0 });
  assert.match(requests.first, /platform_ids=17/);
  assert.match(requests.first, /search_term=zelda/);
  assert.match(requests.first, /order_by=name/);
  assert.match(requests.first, /group_by_meta_id=false/);
  assert.doesNotMatch(requests.first, /with_rom_id_index/);
  assert.match(requests.retry, /platform_id=17/);
  assert.doesNotMatch(requests.retry, /group_by_meta_id/);
  assert.doesNotMatch(requests.retry, /order_by=/);
  const token = shelf.buildTokenRequest("password", { username: "kaval", password: "secret" });
  assert.equal(token.path, "/api/token");
  assert.equal(token.auth, false);
  assert.doesNotMatch(JSON.stringify(token), /Authorization/);
  assert.equal(shelf.nextScreen("platforms", "back"), "platforms");
  assert.equal(shelf.nextScreen("games", "back"), "platforms");
  assert.equal(shelf.nextScreen("game", "back"), "games");
});

test("left and right stay in the grid and the footer does not steal them", () => {
  const items = [
    { id: "a", zone: "grid", left: 8, top: 40, width: 148, height: 64 },
    { id: "b", zone: "grid", left: 164, top: 40, width: 148, height: 64 },
    { id: "c", zone: "grid", left: 8, top: 112, width: 148, height: 64 },
    { id: "d", zone: "grid", left: 164, top: 112, width: 148, height: 64 },
    { id: "back", zone: "footer", left: 4, top: 300, width: 90, height: 56 },
    { id: "search", zone: "footer", left: 98, top: 300, width: 90, height: 56 },
    { id: "download", zone: "footer", left: 192, top: 300, width: 90, height: 56 },
    { id: "settings", zone: "footer", left: 286, top: 300, width: 90, height: 56 }
  ];
  assert.equal(shelf.nextFocusTarget(items, "a", "right"), "b");
  assert.equal(shelf.nextFocusTarget(items, "b", "left"), "a");
  assert.equal(shelf.nextFocusTarget(items, "b", "right"), "b");
  assert.equal(shelf.nextFocusTarget(items, "a", "down"), "c");
  assert.equal(shelf.nextFocusTarget(items, "c", "down"), "back");
  assert.equal(shelf.nextFocusTarget(items, "d", "down"), "download");
  assert.equal(shelf.nextFocusTarget(items, "back", "left"), "back");
  assert.equal(shelf.nextFocusTarget(items, "back", "right"), "search");
  assert.equal(shelf.nextFocusTarget(items, "settings", "left"), "download");
  assert.equal(shelf.nextFocusTarget(items, "search", "up"), "c");
  const closerFooter = [
    { id: "a", zone: "grid", left: 8, top: 40, width: 100, height: 64 },
    { id: "b", zone: "grid", left: 400, top: 40, width: 100, height: 64 },
    { id: "download", zone: "footer", left: 120, top: 80, width: 90, height: 56 }
  ];
  assert.equal(shelf.nextFocusTarget(closerFooter, "a", "right"), "b");
  assert.equal(shelf.nextFocusTarget(closerFooter, "a", "down"), "download");
  assert.equal(shelf.nextFocusTarget(closerFooter, "download", "left"), "download");
  assert.equal(shelf.nextFocusTarget(closerFooter, "download", "up"), "a");
});

test("RomM covers use stored paths before a scrape, and no Cocoon sidecar is invented", () => {
  const rom = {
    path_cover_small: "/assets/romm/small.jpg",
    path_cover_large: "/assets/romm/large.jpg",
    url_cover: "https://images.example/cover.jpg"
  };
  assert.deepEqual(shelf.romCoverCandidates(rom, "small"), [
    "/assets/romm/small.jpg",
    "/assets/romm/large.jpg",
    "https://images.example/cover.jpg"
  ]);
  assert.deepEqual(shelf.romCoverCandidates({ path_cover_large: "/assets/romm/large.jpg", path_cover_small: "", url_cover: null }, "large"), [
    "/assets/romm/large.jpg"
  ]);
  assert.equal(shelf.coverUrl("http://romm.local:8080", rom), "http://romm.local:8080/assets/romm/small.jpg");
  assert.equal(shelf.coverNeedsAuth("http://romm.local:8080", "http://romm.local:8080/assets/romm/small.jpg"), true);
  assert.equal(shelf.coverNeedsAuth("http://romm.local:8080", "https://images.example/cover.jpg"), false);
  assert.equal(shelf.cocoonCoverSidecar(), null);
  assert.equal(shelf.normalizeTheme("dark"), "dark");
  assert.equal(shelf.normalizeTheme("bright"), "bright");
  assert.equal(shelf.normalizeTheme("other"), "bright");

  const creds = {
    ssUser: "ada",
    ssPassword: "pw",
    ssDevId: "dev",
    ssDevPassword: "devpw",
    sgdbKey: "secret-key"
  };
  const game = { id: 7, name: "Zelda", fs_name: "zelda.nds" };
  const start = shelf.scrapeStart(creds, game);
  assert.equal(start.phase, "ss-infos");
  assert.equal(start.request.bearer, "");
  assert.match(start.request.url, /jeuInfos\.php/);
  assert.match(start.request.url, /romnom=zelda\.nds/);
  assert.equal(start.request.url.includes("secret-key"), false);
  const found = shelf.scrapeAdvance(start, {
    response: { jeu: { medias: [{ type: "box-3D", url: "https://img.example/3d.png" }, { type: "box-2D", url: "https://img.example/box.png" }] } }
  }, creds, game);
  assert.equal(found.imageUrl, "https://img.example/box.png");
  const missed = shelf.scrapeAdvance(start, { response: { jeu: { medias: [] } } }, creds, game);
  assert.equal(missed.phase, "ss-search");
  const searchMiss = shelf.scrapeAdvance(missed, { response: { jeux: [] } }, creds, game);
  assert.equal(searchMiss.phase, "sg-search");
  assert.equal(searchMiss.request.bearer, "secret-key");
  assert.equal(searchMiss.request.url.includes("secret-key"), false);
  const grids = shelf.scrapeAdvance(searchMiss, { data: [{ id: 42, name: "Zelda" }] }, creds, game);
  assert.match(grids.request.url, /\/grids\/game\/42/);
  const image = shelf.scrapeAdvance(grids, { data: [{ url: "https://cdn.example/g.png", thumb: "https://cdn.example/t.png" }] }, creds, game);
  assert.equal(image.imageUrl, "https://cdn.example/g.png");
  assert.equal(shelf.scrapeStart({}, game).request, null);
  assert.match(shelf.scrapeStart({ ssUser: "ada" }, game).message, /devid/);
  assert.equal(shelf.scrapeStart({ sgdbKey: "secret-key" }, game).phase, "sg-search");
});

test("inlined script has no raw closing tag and parses", () => {
  const hostile = 'var token = "$&"; var markup = "</script></body></style>";';
  const broken = "<body></body>".replace("</body>", "<script>" + hostile + "</script></body>");
  assert.match(broken, /<script>var token = "<\/body>/);
  const safe = inlineHtml("<body></body>", hostile);
  const extracted = extractScript(safe);
  assert.equal(extracted.includes("</script"), false);
  assert.equal(extracted.includes("</body"), false);
  assert.equal(extracted.includes("</style"), false);
  assert.match(extracted, /\$&/);
  assert.equal(escapeScript(hostile).includes("</script"), false);
  new Function(extracted);

  const html = inlineHtml(
    readFileSync(path.join(root, "web", "index.html"), "utf8"),
    buildScript(
      readFileSync(path.join(root, "src", "shelf.js"), "utf8"),
      readFileSync(path.join(root, "src", "years.js"), "utf8"),
      JSON.stringify(map)
    )
  );
  assert.equal(html.includes('type="module"'), false);
  assert.equal(html.includes("type='module'"), false);
  assert.equal(html.includes("assets/index-"), false);
  assert.equal(html.includes("modulepreload"), false);
  const script = extractScript(html);
  assert.equal(/<\/(?:script|style|body)/i.test(script), false);
  new Function(script);
  assert.match(script, /CocoonShelf/);
  assert.match(script, /platform_ids=/);
});
