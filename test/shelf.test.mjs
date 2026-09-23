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

test("connect detects password, token, or pairing code and has one sign-in action", () => {
  assert.equal(shelf.detectSignIn("ada", "hunter2").method, "password");
  assert.equal(shelf.detectSignIn("ada", "hunter2").secret, "hunter2");
  assert.equal(shelf.detectSignIn("", "hunter2").method, "");
  assert.equal(shelf.detectSignIn("", "rmm_abc").method, "token");
  assert.equal(shelf.detectSignIn("ada", " rmm_abc ").method, "token");
  assert.equal(shelf.detectSignIn("", "12345678").method, "pair");
  assert.equal(shelf.detectSignIn("ada", "12345678").method, "pair");
  assert.equal(shelf.detectSignIn("", "1234567").method, "");
  const html = readFileSync(path.join(root, "web", "index.html"), "utf8");
  const connect = html.slice(html.indexOf('id="screen-connect"'), html.indexOf('id="screen-platforms"'));
  assert.equal((connect.match(/<button/g) || []).length, 1);
  assert.match(connect, /id="btn-sign-in"/);
  assert.equal((connect.match(/Password, an rmm_ token, or an 8-digit code\./g) || []).length, 1);
  assert.equal(connect.includes("btn-password"), false);
  assert.equal(connect.includes("btn-token"), false);
  assert.equal(connect.includes("btn-pair"), false);
  assert.equal(connect.includes("client-token"), false);
  assert.equal(connect.includes("pair-code"), false);
  const form = [
    { id: "secret", zone: "page", left: 8, top: 40, width: 200, height: 56 },
    { id: "btn-sign-in", zone: "page", left: 8, top: 110, width: 200, height: 56 },
    { id: "btn-settings", zone: "footer", left: 240, top: 48, width: 90, height: 56 },
    { id: "btn-back", zone: "footer", left: 8, top: 300, width: 90, height: 56 }
  ];
  assert.equal(shelf.nextFocusTarget(form, "secret", "right"), "secret");
  assert.equal(shelf.nextFocusTarget(form, "btn-settings", "left"), "btn-settings");
  assert.equal(shelf.nextFocusTarget(form, "btn-sign-in", "down"), "btn-back");
});

test("the next page keeps earlier cards and focuses the first new one", () => {
  const plan = shelf.nextPageFocus(["1", "2", "3"], ["4", "5", "6"]);
  assert.deepEqual(plan.keepIds, ["1", "2", "3", "4", "5", "6"]);
  assert.equal(plan.focusId, "4");
  assert.equal(plan.resetToFirst, false);
  assert.notEqual(plan.focusId, plan.keepIds[0]);
  const empty = shelf.nextPageFocus(["1"], []);
  assert.deepEqual(empty.keepIds, ["1"]);
  assert.equal(empty.focusId, null);
  assert.equal(empty.resetToFirst, false);
});

test("game details use the RomM summary and screenshot paths", () => {
  const rom = {
    summary: "  A hero wakes the wind.  ",
    description: "unused when summary exists",
    merged_screenshots: ["/assets/romm/shot-a.jpg", "https://images.example/shot-b.jpg"],
    url_screenshots: ["https://images.example/extra.jpg"]
  };
  assert.equal(shelf.romSummary(rom), "A hero wakes the wind.");
  assert.deepEqual(shelf.romScreenshotPaths(rom), ["/assets/romm/shot-a.jpg", "https://images.example/shot-b.jpg"]);
  assert.equal(shelf.romSummary({ description: " Only the fallback. " }), "Only the fallback.");
  assert.equal(shelf.romSummary({}), "");
  assert.deepEqual(shelf.romScreenshotPaths({ path_screenshots: ["shots/1.png"], url_screenshots: ["https://cdn.example/2.png"], screenshot_path: "shots/1.png" }), [
    "shots/1.png",
    "https://cdn.example/2.png"
  ]);
  assert.deepEqual(shelf.romScreenshotPaths({ summary: "No pictures" }), []);
});

test("screenshot preview is separate from the left column and confirm opens the carousel", () => {
  const items = [
    { id: "game-card", zone: "page", left: 8, top: 8, width: 220, height: 140 },
    { id: "btn-scrape", zone: "page", left: 8, top: 156, width: 220, height: 56 },
    { id: "shot-preview", zone: "preview", left: 244, top: 8, width: 180, height: 112 },
    { id: "btn-download", zone: "footer", left: 8, top: 300, width: 140, height: 56 },
    { id: "btn-settings", zone: "footer", left: 156, top: 300, width: 140, height: 56 }
  ];
  assert.equal(shelf.nextFocusTarget(items, "game-card", "right"), "shot-preview");
  assert.equal(shelf.nextFocusTarget(items, "btn-scrape", "right"), "shot-preview");
  assert.equal(shelf.nextFocusTarget(items, "shot-preview", "left"), "game-card");
  assert.equal(shelf.nextFocusTarget(items, "shot-preview", "right"), "shot-preview");
  assert.equal(shelf.nextFocusTarget(items, "game-card", "down"), "btn-scrape");
  assert.equal(shelf.nextFocusTarget(items, "btn-download", "left"), "btn-download");
  assert.equal(shelf.nextFocusTarget(items, "btn-settings", "left"), "btn-download");
  const opened = shelf.gameScreenshotNav({ focus: "preview", index: 0, count: 3, columnId: "game-card" }, "confirm");
  assert.equal(opened.focus, "carousel");
  assert.equal(opened.open, true);
  assert.equal(opened.index, 0);
  assert.equal(opened.leaveScreen, false);
  const next = shelf.gameScreenshotNav(opened, "right");
  assert.equal(next.index, 1);
  assert.equal(next.open, true);
  assert.equal(next.focus, "carousel");
  const stayed = shelf.gameScreenshotNav({ focus: "carousel", index: 2, count: 3, open: true }, "right");
  assert.equal(stayed.index, 2);
  const closed = shelf.gameScreenshotNav(next, "back");
  assert.equal(closed.focus, "preview");
  assert.equal(closed.open, false);
  assert.equal(closed.leaveScreen, false);
  assert.equal(shelf.gameScreenshotNav({ focus: "column", index: 0, count: 0 }, "right").focus, "column");
  assert.equal(shelf.backTarget({ screen: "game", carousel: true }).closeCarousel, true);
  assert.equal(shelf.backTarget({ screen: "game", carousel: true }).screen, "game");
});

test("all-consoles search is separate from the console filter", () => {
  const filtered = shelf.applyPlatformFilterModel(consoles, "zelda", "switch");
  assert.equal(filtered.fetchGames, false);
  assert.equal(filtered.screen, "platforms");
  assert.equal(filtered.gameSearch, null);
  const draft = shelf.planLibrarySearch({ mode: "draft", query: "zelda", previous: "" });
  assert.equal(draft.fetch, false);
  assert.equal(draft.kind, "draft");
  const submitted = shelf.planLibrarySearch({ mode: "submit", query: " zelda ", previous: "" });
  assert.equal(submitted.fetch, true);
  assert.equal(submitted.kind, "library");
  assert.equal(submitted.query, "zelda");
  const requests = shelf.romsRequests({ search: submitted.query, limit: 60, offset: 0 });
  assert.match(requests.first, /search_term=zelda/);
  assert.doesNotMatch(requests.first, /platform_id/);
  assert.doesNotMatch(requests.retry, /platform_id/);
  const cleared = shelf.planLibrarySearch({ mode: "submit", query: "   ", previous: "zelda" });
  assert.equal(cleared.fetch, false);
  assert.equal(cleared.restore, true);
  assert.equal(cleared.kind, "clear");
  const paths = shelf.collectionListPaths();
  assert.equal(paths.manual, "/api/collections");
  assert.equal(paths.smart, "/api/collections/smart");
  assert.equal(paths.virtual, "/api/collections/virtual?type=collection");
  const rows = shelf.normalizeCollectionRows([{ id: 3, name: "RPGs", rom_count: 4 }], "manual");
  assert.equal(rows[0].id, "3");
  assert.equal(rows[0].kind, "manual");
  assert.match(shelf.romsRequests(shelf.collectionRomsOptions(rows[0], 60, 0)).first, /collection_id=3/);
  assert.match(shelf.romsRequests(shelf.collectionRomsOptions({ id: "9", kind: "smart" }, 60, 0)).first, /smart_collection_id=9/);
  assert.match(shelf.romsRequests(shelf.collectionRomsOptions({ id: "franchise-zelda", kind: "virtual" }, 60, 0)).first, /virtual_collection_id=franchise-zelda/);
  const library = [
    { id: "library-search", zone: "chrome", left: 8, top: 4, width: 300, height: 40 },
    { id: "tab-consoles", zone: "tab", left: 8, top: 52, width: 140, height: 48 },
    { id: "tab-collections", zone: "tab", left: 156, top: 52, width: 140, height: 48 },
    { id: "c1", zone: "grid", left: 8, top: 112, width: 140, height: 64 },
    { id: "c2", zone: "grid", left: 156, top: 112, width: 140, height: 64 },
    { id: "btn-settings", zone: "footer", left: 8, top: 300, width: 120, height: 56 }
  ];
  assert.equal(shelf.nextFocusTarget(library, "tab-consoles", "right"), "tab-collections");
  assert.equal(shelf.nextFocusTarget(library, "tab-collections", "left"), "tab-consoles");
  assert.equal(shelf.nextFocusTarget(library, "c1", "right"), "c2");
  assert.equal(shelf.nextFocusTarget(library, "c2", "right"), "c2");
  assert.equal(shelf.nextFocusTarget(library, "tab-collections", "down"), "c2");
  assert.equal(shelf.nextFocusTarget(library, "tab-consoles", "up"), "library-search");
  assert.equal(shelf.nextFocusTarget(library, "c1", "up"), "tab-consoles");
  assert.equal(shelf.nextFocusTarget(library, "library-search", "down"), "tab-collections");
  assert.equal(shelf.backTarget({ screen: "games", gamesKind: "collection" }).tab, "collections");
  assert.equal(shelf.backTarget({ screen: "games", gamesKind: "collection" }).screen, "platforms");
  assert.equal(shelf.backTarget({ screen: "games", gamesKind: "library" }).screen, "platforms");
  assert.equal(shelf.backTarget({ screen: "platforms" }).finish, false);
  assert.equal(shelf.backTarget({ screen: "platforms" }).screen, "platforms");
  assert.equal(shelf.backTarget({ screen: "game" }).screen, "games");
  assert.equal(shelf.hardwareKeyAction(96), "back");
  assert.equal(shelf.hardwareKeyAction(4), "back");
  assert.equal(shelf.hardwareKeyAction(97), "confirm");
  assert.equal(shelf.hardwareKeyAction(23), "confirm");
  assert.equal(shelf.hardwareKeyAction(66), "confirm");
});

test("the footer has no Back or Search button and Download is only on the Game screen", () => {
  const html = readFileSync(path.join(root, "web", "index.html"), "utf8");
  const bar = html.slice(html.indexOf('id="bar"'), html.indexOf("</nav>"));
  assert.equal(bar.includes("btn-back"), false);
  assert.equal(bar.includes("btn-search"), false);
  assert.equal(bar.includes(">Back<"), false);
  assert.equal(bar.includes(">Search<"), false);
  assert.match(bar, /id="btn-download"/);
  assert.match(bar, /id="btn-settings"/);
  assert.equal(shelf.footerControls("game").download, true);
  assert.equal(shelf.footerControls("game").back, false);
  assert.equal(shelf.footerControls("game").search, false);
  assert.equal(shelf.footerControls("platforms").download, false);
  assert.equal(shelf.footerControls("games").download, false);
  assert.equal(shelf.footerControls("settings").download, false);
  assert.equal(shelf.footerControls("connect").download, false);
  assert.match(shelf.buildTokenRequest("password", { username: "ada", password: "secret" }).body, /collections\.read/);
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
