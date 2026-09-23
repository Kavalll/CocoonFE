(function (root) {
  "use strict";

  var PAGE_SIZE = 60;
  var years = loadYears(root);
  var YEAR = years.PLATFORM_RELEASE_YEAR || {};
  var NAME_YEARS = years.PLATFORM_NAME_YEARS || [];

  function loadYears(target) {
    if (target && target.__SHELF_YEARS__) return target.__SHELF_YEARS__;
    if (typeof require === "function") return require("./years.js");
    return { PLATFORM_RELEASE_YEAR: {}, PLATFORM_NAME_YEARS: [] };
  }

  function loadMap(target) {
    if (target && target.__SHELF_MAP__) return target.__SHELF_MAP__;
    return null;
  }

  function norm(value) {
    return String(value == null ? "" : value).replace(/^\s+|\s+$/g, "").toLowerCase();
  }

  function uniqueFolders(values) {
    var seen = {};
    var out = [];
    for (var i = 0; i < values.length; i++) {
      var key = norm(values[i]);
      if (!key || seen[key]) continue;
      seen[key] = true;
      out.push(key);
    }
    return out;
  }

  function indexPlatformMap(map) {
    var byRommSlug = {};
    var platforms = map && map.platforms ? map.platforms : [];
    for (var i = 0; i < platforms.length; i++) {
      var platform = platforms[i];
      var slugs = platform.rommSlugs || [];
      var aliases = platform.folderAliases || [];
      var s;
      for (s = 0; s < slugs.length; s++) {
        var key = norm(slugs[s]);
        if (key && !byRommSlug[key]) byRommSlug[key] = platform;
      }
      for (s = 0; s < aliases.length; s++) {
        var aliasKey = norm(aliases[s]);
        if (aliasKey && !byRommSlug[aliasKey]) byRommSlug[aliasKey] = platform;
      }
    }
    return byRommSlug;
  }

  function lookupPlatform(map, rommSlug, rommFsSlug) {
    if (!map) return null;
    var index = indexPlatformMap(map);
    return index[norm(rommSlug)] || index[norm(rommFsSlug)] || null;
  }

  function resolveDestination(map, options) {
    options = options || {};
    var layout = options.layout || (map && map.defaultLayout) || "cocoon";
    var mapping = lookupPlatform(map, options.rommSlug, options.rommFsSlug);
    var rommFolder = norm(options.rommFsSlug) || norm(options.rommSlug) || "roms";
    var aliasValues = [];
    if (mapping) {
      aliasValues.push(mapping.cocoonUniqueId);
      aliasValues.push(mapping.cocoonShortname);
      var aliases = mapping.folderAliases || [];
      for (var i = 0; i < aliases.length; i++) aliasValues.push(aliases[i]);
    }
    aliasValues.push(options.rommSlug);
    aliasValues.push(options.rommFsSlug);
    var candidates = uniqueFolders(aliasValues);
    var overrideKey = mapping ? mapping.cocoonUniqueId : rommFolder;
    var override = options.overrides ? options.overrides[overrideKey] : "";
    var base = {
      cocoonUniqueId: mapping ? mapping.cocoonUniqueId : null,
      cocoonName: mapping ? mapping.cocoonName : null,
      mapped: !!mapping,
      candidates: candidates
    };
    if (override) {
      base.folderName = override;
      return base;
    }
    if (layout === "alias" && options.existingFolders && options.existingFolders.length) {
      var existing = options.existingFolders;
      for (var c = 0; c < candidates.length; c++) {
        for (var e = 0; e < existing.length; e++) {
          if (norm(existing[e]) === candidates[c]) {
            base.folderName = existing[e];
            return base;
          }
        }
      }
    }
    base.folderName = layout === "romm" || !mapping ? rommFolder : mapping.cocoonUniqueId;
    return base;
  }

  function sanitizeFilename(name) {
    var cleaned = String(name || "").replace(/[\\/:*?"<>|\u0000-\u001f]/g, "_").replace(/^\s+|\s+$/g, "");
    return cleaned || "rom.bin";
  }

  function joinDownloadPath(folderName, fileName) {
    var folder = String(folderName || "").replace(/\\/g, "/").replace(/^\/+|\/+$/g, "");
    return folder + "/" + sanitizeFilename(fileName);
  }

  function contentDownloadPath(romId, fileName) {
    return "/api/roms/" + romId + "/content/" + encodeURIComponent(fileName);
  }

  function absoluteUrl(baseUrl, path) {
    var origin = String(baseUrl || "").replace(/\/+$/, "");
    if (/^https?:\/\//i.test(path)) return path;
    if (path.charAt(0) === "/") return origin + path;
    return origin + "/" + path;
  }

  function normalizeBaseUrl(url) {
    var trimmed = String(url || "").replace(/^\s+|\s+$/g, "");
    if (!trimmed) throw new Error("Server URL is required.");
    if (!/^https?:\/\//i.test(trimmed)) trimmed = "http://" + trimmed;
    return trimmed.replace(/\/+$/, "");
  }

  function platformDisplayName(platform) {
    return String(platform.display_name || platform.name || platform.slug || "Unknown").replace(/^\s+|\s+$/g, "");
  }

  function platformKey(platform) {
    return String(platform.slug || platform.id || platform.name || "");
  }

  function platformMatchesQuery(platform, query) {
    var q = String(query || "").replace(/^\s+|\s+$/g, "").toLowerCase();
    if (!q) return true;
    var parts = [platform.display_name, platform.name, platform.slug, platform.fs_slug];
    var hay = [];
    for (var i = 0; i < parts.length; i++) if (parts[i]) hay.push(String(parts[i]));
    return hay.join(" ").toLowerCase().indexOf(q) !== -1;
  }

  function platformGameCount(platform) {
    if (!platform) return 0;
    var count = platform.rom_count;
    var n = Number(count);
    if (count == null || !isFinite(n) || n < 1) return 0;
    return n;
  }

  function hasGames(platform) {
    return platformGameCount(platform) >= 1;
  }

  function platformsWithGames(platforms) {
    var out = [];
    var list = platforms || [];
    for (var i = 0; i < list.length; i++) {
      if (hasGames(list[i])) out.push(list[i]);
    }
    return out;
  }

  function canOpenPlatform(platform) {
    return hasGames(platform);
  }

  function filterPlatforms(platforms, query) {
    var source = platformsWithGames(platforms);
    var out = [];
    for (var i = 0; i < source.length; i++) {
      if (platformMatchesQuery(source[i], query)) out.push(source[i]);
    }
    return out;
  }

  function platformReleaseYear(map, platform) {
    var mapping = lookupPlatform(map, platform.slug, platform.fs_slug);
    var keys = [mapping && mapping.cocoonUniqueId, mapping && mapping.cocoonShortname, platform.slug, platform.fs_slug];
    for (var i = 0; i < keys.length; i++) {
      var year = YEAR[norm(keys[i])];
      if (year) return year;
    }
    var label = (platform.display_name || "") + " " + (platform.name || "");
    for (var n = 0; n < NAME_YEARS.length; n++) {
      if (NAME_YEARS[n][0].test(label)) return NAME_YEARS[n][1];
    }
    return 0;
  }

  function sortPlatformsByGeneration(map, platforms) {
    var copy = platforms.slice();
    copy.sort(function (a, b) {
      var yearDiff = platformReleaseYear(map, b) - platformReleaseYear(map, a);
      if (yearDiff !== 0) return yearDiff;
      var an = platformDisplayName(a);
      var bn = platformDisplayName(b);
      if (an < bn) return -1;
      if (an > bn) return 1;
      return 0;
    });
    return copy;
  }

  function applyPlatformFilterModel(platforms, query, currentId) {
    var visible = filterPlatforms(platforms, query);
    var still = false;
    for (var i = 0; i < visible.length; i++) {
      if (platformKey(visible[i]) === currentId) still = true;
    }
    return {
      screen: "platforms",
      gameSearch: null,
      fetchGames: false,
      focusedId: still ? currentId : (visible.length ? platformKey(visible[0]) : null),
      visible: visible
    };
  }

  function planGameSearch(input) {
    input = input || {};
    var query = String(input.query || "").replace(/^\s+|\s+$/g, "");
    if (input.mode === "draft") {
      return { fetch: false, query: input.previous || "", kind: "draft" };
    }
    if (!query) return { fetch: true, query: "", kind: "platform" };
    return { fetch: true, query: query, kind: "search" };
  }

  function authEffect(status, refreshed) {
    if (status === 401) return refreshed ? "logout" : "refresh";
    return "stay";
  }

  function shouldRetryRoms(status) {
    return status === 500 || status === 422 || status === 400;
  }

  function romsQuery(options, variant) {
    options = options || {};
    var limit = options.limit == null ? PAGE_SIZE : options.limit;
    var offset = options.offset == null ? 0 : options.offset;
    var parts = ["limit=" + encodeURIComponent(String(limit)), "offset=" + encodeURIComponent(String(offset))];
    if (options.search) parts.push("search_term=" + encodeURIComponent(options.search));
    if (variant === "plain") {
      if (options.platformId != null) parts.push("platform_id=" + encodeURIComponent(String(options.platformId)));
    } else {
      parts.push("order_by=name");
      parts.push("order_dir=asc");
      parts.push("group_by_meta_id=false");
      if (options.platformId != null) parts.push("platform_ids=" + encodeURIComponent(String(options.platformId)));
    }
    return "/api/roms?" + parts.join("&");
  }

  function romsRequests(options) {
    return { first: romsQuery(options, "gallery"), retry: romsQuery(options, "plain") };
  }

  function formEncode(fields) {
    var keys = Object.keys(fields);
    var parts = [];
    for (var i = 0; i < keys.length; i++) {
      parts.push(encodeURIComponent(keys[i]) + "=" + encodeURIComponent(fields[keys[i]] == null ? "" : fields[keys[i]]));
    }
    return parts.join("&");
  }

  function buildTokenRequest(kind, fields) {
    fields = fields || {};
    if (kind === "pair") {
      return {
        path: "/api/client-tokens/exchange",
        method: "POST",
        contentType: "application/json",
        body: JSON.stringify({ code: String(fields.code || "").replace(/^\s+|\s+$/g, "") }),
        auth: false
      };
    }
    var body = kind === "refresh"
      ? formEncode({ grant_type: "refresh_token", refresh_token: fields.refreshToken || "" })
      : formEncode({
        grant_type: "password",
        username: fields.username || "",
        password: fields.password || "",
        scope: "roms.read platforms.read assets.read"
      });
    return {
      path: "/api/token",
      method: "POST",
      contentType: "application/x-www-form-urlencoded",
      body: body,
      auth: false
    };
  }

  function nextScreen(screen, action, returnScreen) {
    if (action !== "back") return screen;
    if (screen === "game") return "games";
    if (screen === "games") return "platforms";
    if (screen === "settings") return returnScreen || "platforms";
    return screen;
  }

  function isPairCode(code) {
    return /^\d{8}$/.test(String(code || "").replace(/^\s+|\s+$/g, ""));
  }

  function isClientToken(token) {
    return String(token || "").replace(/^\s+|\s+$/g, "").indexOf("rmm_") === 0;
  }

  function trimText(value) {
    return String(value == null ? "" : value).replace(/^\s+|\s+$/g, "");
  }

  function coverField(rom, key) {
    if (!rom) return "";
    return trimText(rom[key]);
  }

  function romCoverCandidates(rom, size) {
    var small = coverField(rom, "path_cover_small");
    var large = coverField(rom, "path_cover_large");
    var external = coverField(rom, "url_cover");
    var ordered = size === "large" ? [large, small, external] : [small, large, external];
    var seen = {};
    var out = [];
    for (var i = 0; i < ordered.length; i++) {
      if (!ordered[i] || seen[ordered[i]]) continue;
      seen[ordered[i]] = true;
      out.push(ordered[i]);
    }
    return out;
  }

  function coverUrl(baseUrl, rom) {
    var paths = romCoverCandidates(rom, "small");
    if (!paths.length) return "";
    return absoluteUrl(baseUrl, paths[0]);
  }

  function coverRequestUrls(baseUrl, rom, size) {
    var paths = romCoverCandidates(rom, size);
    var out = [];
    for (var i = 0; i < paths.length; i++) out.push(absoluteUrl(baseUrl, paths[i]));
    return out;
  }

  function hostName(url) {
    var match = String(url || "").match(/^https?:\/\/([^\/?#]+)/i);
    if (!match) return "";
    return match[1].split(":")[0].toLowerCase();
  }

  function coverNeedsAuth(baseUrl, url) {
    var baseHost = hostName(baseUrl);
    var coverHost = hostName(url);
    return !!baseHost && baseHost === coverHost;
  }

  function focusCenter(item) {
    return {
      x: item.left + item.width / 2,
      y: item.top + item.height / 2
    };
  }

  function sameFocusRow(a, b) {
    var ay = a.top + a.height / 2;
    var by = b.top + b.height / 2;
    var limit = Math.max(a.height, b.height) / 2;
    if (limit < 8) limit = 8;
    return Math.abs(ay - by) <= limit;
  }

  function nearestInDirection(current, pool, direction) {
    var origin = focusCenter(current);
    var best = null;
    var bestScore = Infinity;
    for (var i = 0; i < pool.length; i++) {
      var item = pool[i];
      if (item.id === current.id) continue;
      var point = focusCenter(item);
      var dx = point.x - origin.x;
      var dy = point.y - origin.y;
      if (direction === "up" && dy >= -4) continue;
      if (direction === "down" && dy <= 4) continue;
      if (direction === "left" && dx >= -4) continue;
      if (direction === "right" && dx <= 4) continue;
      var primary = direction === "up" || direction === "down" ? Math.abs(dy) : Math.abs(dx);
      var secondary = direction === "up" || direction === "down" ? Math.abs(dx) : Math.abs(dy);
      var score = primary * 100 + secondary;
      if (score < bestScore) {
        bestScore = score;
        best = item.id;
      }
    }
    return best;
  }

  function nextFocusTarget(items, currentId, direction) {
    var list = items || [];
    var current = null;
    var i;
    for (i = 0; i < list.length; i++) {
      if (list[i].id === currentId) current = list[i];
    }
    if (!current) return list.length ? list[0].id : null;
    var zone = current.zone || "page";
    if (direction === "down" && zone === "footer") return current.id;
    var pool = [];
    if (direction === "left" || direction === "right") {
      for (i = 0; i < list.length; i++) {
        if ((list[i].zone || "page") !== zone) continue;
        if (!sameFocusRow(current, list[i])) continue;
        pool.push(list[i]);
      }
      return nearestInDirection(current, pool, direction) || current.id;
    }
    if (direction === "up" && zone === "footer") {
      var hasGrid = false;
      for (i = 0; i < list.length; i++) if (list[i].zone === "grid") hasGrid = true;
      var want = hasGrid ? "grid" : "page";
      for (i = 0; i < list.length; i++) if ((list[i].zone || "page") === want) pool.push(list[i]);
      return nearestInDirection(current, pool, direction) || current.id;
    }
    for (i = 0; i < list.length; i++) {
      if ((list[i].zone || "page") !== zone) continue;
      pool.push(list[i]);
    }
    var inside = nearestInDirection(current, pool, direction);
    if (inside) return inside;
    if (direction !== "down") return current.id;
    var footer = [];
    for (i = 0; i < list.length; i++) if (list[i].zone === "footer") footer.push(list[i]);
    return nearestInDirection(current, footer, direction) || current.id;
  }

  function normalizeTheme(value) {
    return value === "dark" ? "dark" : "bright";
  }

  function scraperReady(creds) {
    creds = creds || {};
    var user = trimText(creds.ssUser);
    var pass = trimText(creds.ssPassword);
    var devId = trimText(creds.ssDevId);
    var devPass = trimText(creds.ssDevPassword);
    return {
      screenscraper: !!(user && pass && devId && devPass),
      steamgrid: !!trimText(creds.sgdbKey),
      partialScreenScraper: !!(user || pass || devId || devPass) && !(user && pass && devId && devPass)
    };
  }

  function screenScraperQuery(creds, extra) {
    var parts = [
      "devid=" + encodeURIComponent(trimText(creds.ssDevId)),
      "devpassword=" + encodeURIComponent(trimText(creds.ssDevPassword)),
      "softname=CocoonRommShelf",
      "output=json",
      "ssid=" + encodeURIComponent(trimText(creds.ssUser)),
      "sspassword=" + encodeURIComponent(trimText(creds.ssPassword))
    ];
    var keys = extra ? Object.keys(extra) : [];
    for (var i = 0; i < keys.length; i++) parts.push(keys[i] + "=" + encodeURIComponent(extra[keys[i]]));
    return parts.join("&");
  }

  function screenScraperInfosUrl(creds, fileName) {
    return "https://api.screenscraper.fr/api2/jeuInfos.php?" + screenScraperQuery(creds, { romnom: fileName || "" });
  }

  function screenScraperSearchUrl(creds, name) {
    return "https://api.screenscraper.fr/api2/jeuRecherche.php?" + screenScraperQuery(creds, { recherche: name || "" });
  }

  function steamGridSearchUrl(name) {
    return "https://www.steamgriddb.com/api/v2/search/autocomplete/" + encodeURIComponent(name || "");
  }

  function steamGridGridsUrl(id) {
    return "https://www.steamgriddb.com/api/v2/grids/game/" + encodeURIComponent(String(id)) + "?dimensions=600x900,342x482,660x930";
  }

  function screenScraperMedias(payload) {
    if (!payload || !payload.response) return [];
    if (payload.response.jeu && payload.response.jeu.medias) return payload.response.jeu.medias;
    var jeux = payload.response.jeux;
    if (jeux && jeux.length && jeux[0].medias) return jeux[0].medias;
    return [];
  }

  function pickScreenScraperCover(payload) {
    var medias = screenScraperMedias(payload);
    var ranks = { "box-2d": 1, "box-3d": 2, mixrbv2: 3, mixrbv1: 4 };
    var best = "";
    var bestRank = 99;
    for (var i = 0; i < medias.length; i++) {
      var type = String(medias[i].type || "").toLowerCase();
      var rank = ranks[type];
      var url = medias[i].url || "";
      if (!rank || !url) continue;
      if (rank < bestRank) {
        bestRank = rank;
        best = url;
      }
    }
    return best;
  }

  function pickSteamGridGameId(payload) {
    var data = payload && payload.data;
    if (!data || !data.length || data[0].id == null) return null;
    return data[0].id;
  }

  function pickSteamGridCover(payload) {
    var data = payload && payload.data;
    if (!data || !data.length) return "";
    return data[0].url || data[0].thumb || "";
  }

  function cocoonCoverSidecar() {
    return null;
  }

  function scrapeResult(phase, request, imageUrl, message) {
    return { phase: phase, request: request, imageUrl: imageUrl || "", message: message || "" };
  }

  function scrapeStart(creds, rom) {
    var ready = scraperReady(creds);
    var fileName = rom ? (rom.fs_name || rom.name || "") : "";
    var name = rom ? (rom.name || rom.fs_name || "") : "";
    if (ready.screenscraper && fileName) {
      return scrapeResult("ss-infos", { url: screenScraperInfosUrl(creds, fileName), bearer: "" }, "", "");
    }
    if (ready.screenscraper && name) {
      return scrapeResult("ss-search", { url: screenScraperSearchUrl(creds, name), bearer: "" }, "", "");
    }
    if (ready.steamgrid && name) {
      return scrapeResult("sg-search", { url: steamGridSearchUrl(name), bearer: trimText(creds.sgdbKey) }, "", "");
    }
    if (ready.partialScreenScraper) {
      return scrapeResult("done", null, "", "ScreenScraper needs a user, password, devid, and dev password.");
    }
    return scrapeResult("done", null, "", "Add ScreenScraper or SteamGridDB in Settings.");
  }

  function scrapeAdvance(step, payload, creds, rom) {
    var ready = scraperReady(creds);
    var name = rom ? (rom.name || rom.fs_name || "") : "";
    var phase = step && step.phase;
    if (phase === "ss-infos" || phase === "ss-search") {
      var found = pickScreenScraperCover(payload);
      if (found) return scrapeResult("done", null, found, "");
      if (phase === "ss-infos" && ready.screenscraper && name) {
        return scrapeResult("ss-search", { url: screenScraperSearchUrl(creds, name), bearer: "" }, "", "");
      }
    }
    if (phase === "sg-search") {
      var gameId = pickSteamGridGameId(payload);
      if (gameId != null) return scrapeResult("sg-grids", { url: steamGridGridsUrl(gameId), bearer: trimText(creds.sgdbKey) }, "", "");
    }
    if (phase === "sg-grids") {
      var grid = pickSteamGridCover(payload);
      if (grid) return scrapeResult("done", null, grid, "");
    }
    if ((phase === "ss-infos" || phase === "ss-search") && ready.steamgrid && name) {
      return scrapeResult("sg-search", { url: steamGridSearchUrl(name), bearer: trimText(creds.sgdbKey) }, "", "");
    }
    return scrapeResult("done", null, "", "No artwork found.");
  }

  function normalizeRomPage(payload) {
    if (!payload) return { items: [], total: 0 };
    if (Object.prototype.toString.call(payload) === "[object Array]") {
      return { items: payload, total: payload.length };
    }
    var items = payload.items || payload.roms || [];
    var total = payload.total;
    if (total == null && payload.total_count != null) total = payload.total_count;
    return { items: items, total: total == null ? null : total };
  }

  var api = {
    PAGE_SIZE: PAGE_SIZE,
    norm: norm,
    resolveDestination: resolveDestination,
    sanitizeFilename: sanitizeFilename,
    joinDownloadPath: joinDownloadPath,
    contentDownloadPath: contentDownloadPath,
    absoluteUrl: absoluteUrl,
    normalizeBaseUrl: normalizeBaseUrl,
    platformDisplayName: platformDisplayName,
    platformKey: platformKey,
    platformGameCount: platformGameCount,
    hasGames: hasGames,
    platformsWithGames: platformsWithGames,
    canOpenPlatform: canOpenPlatform,
    filterPlatforms: filterPlatforms,
    platformReleaseYear: platformReleaseYear,
    sortPlatformsByGeneration: sortPlatformsByGeneration,
    applyPlatformFilterModel: applyPlatformFilterModel,
    planGameSearch: planGameSearch,
    authEffect: authEffect,
    shouldRetryRoms: shouldRetryRoms,
    romsQuery: romsQuery,
    romsRequests: romsRequests,
    buildTokenRequest: buildTokenRequest,
    nextScreen: nextScreen,
    isPairCode: isPairCode,
    isClientToken: isClientToken,
    coverUrl: coverUrl,
    romCoverCandidates: romCoverCandidates,
    coverRequestUrls: coverRequestUrls,
    coverNeedsAuth: coverNeedsAuth,
    nextFocusTarget: nextFocusTarget,
    normalizeTheme: normalizeTheme,
    scraperReady: scraperReady,
    screenScraperInfosUrl: screenScraperInfosUrl,
    screenScraperSearchUrl: screenScraperSearchUrl,
    steamGridSearchUrl: steamGridSearchUrl,
    steamGridGridsUrl: steamGridGridsUrl,
    pickScreenScraperCover: pickScreenScraperCover,
    pickSteamGridGameId: pickSteamGridGameId,
    pickSteamGridCover: pickSteamGridCover,
    cocoonCoverSidecar: cocoonCoverSidecar,
    scrapeStart: scrapeStart,
    scrapeAdvance: scrapeAdvance,
    normalizeRomPage: normalizeRomPage,
    lookupPlatform: lookupPlatform
  };

  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.CocoonShelf = api;
  if (root && root.document) boot(root, api);

  function boot(host, shelf) {
    var doc = host.document;
    var map = loadMap(host);
    var memoryPlatforms = null;
    var coverObserver = null;
    var lastHardware = { name: "", at: 0 };
    var state = {
      screen: "connect",
      returnScreen: "platforms",
      session: null,
      layout: "cocoon",
      existingFolders: [],
      platform: null,
      platformQuery: "",
      game: null,
      gameSearch: "",
      gamesLoaded: 0,
      gamesTotal: null,
      romsById: {},
      searchOpen: false,
      pendingRescan: "",
      theme: "bright",
      scrape: null
    };

    var fallback = doc.getElementById("boot-fallback");
    if (fallback) fallback.hidden = true;
    var app = doc.getElementById("app");
    if (app) app.hidden = false;
    if (host.RommNative && host.RommNative.onScriptParsed) {
      try { host.RommNative.onScriptParsed(); } catch (err) { /* native bridge is optional in tests */ }
    }

    function native(method) {
      var bridge = host.RommNative;
      if (!bridge || typeof bridge[method] !== "function") return null;
      var args = Array.prototype.slice.call(arguments, 1);
      return bridge[method].apply(bridge, args);
    }

    function parseNative(raw) {
      if (raw == null || raw === "") return null;
      try { return JSON.parse(raw); } catch (err) { return { ok: false, logout: false, error: "Bad response" }; }
    }

    function setStatus(text) {
      var el = doc.getElementById("status");
      if (el) el.textContent = text || "";
    }

    function isVisible(el) {
      var node = el;
      while (node) {
        if (node.hidden) return false;
        node = node.parentElement;
      }
      return true;
    }

    function markFocused(el) {
      var all = doc.querySelectorAll(".is-focused");
      for (var i = 0; i < all.length; i++) all[i].classList.remove("is-focused");
      if (el) el.classList.add("is-focused");
    }

    function setFocus(el, keepTyping) {
      markFocused(el);
      if (!el) return;
      var typing = doc.activeElement && (doc.activeElement.tagName === "INPUT" || doc.activeElement.tagName === "TEXTAREA");
      if (keepTyping && typing) {
        if (el.scrollIntoView) el.scrollIntoView({ block: "nearest", inline: "nearest" });
        return;
      }
      if (el.tagName === "INPUT" || el.tagName === "TEXTAREA") el.focus();
      else if (typing && doc.activeElement && doc.activeElement.blur) doc.activeElement.blur();
      if (el.scrollIntoView) el.scrollIntoView({ block: "nearest", inline: "nearest" });
    }

    function focusables() {
      var nodes = doc.querySelectorAll("[data-focus]");
      var out = [];
      for (var i = 0; i < nodes.length; i++) if (isVisible(nodes[i])) out.push(nodes[i]);
      return out;
    }

    function moveFocus(direction) {
      var nodes = focusables();
      if (!nodes.length) return;
      var current = doc.querySelector(".is-focused");
      if (!current || !isVisible(current)) {
        setFocus(nodes[0], false);
        return;
      }
      var records = [];
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        var rect = el.getBoundingClientRect();
        var id = el.getAttribute("data-focus-id") || el.id;
        if (!id) continue;
        records.push({
          id: id,
          zone: el.getAttribute("data-zone") || "page",
          left: rect.left,
          top: rect.top,
          width: rect.width,
          height: rect.height
        });
      }
      var currentId = current.getAttribute("data-focus-id") || current.id;
      var nextId = nextFocusTarget(records, currentId, direction);
      if (!nextId || nextId === currentId) return;
      for (var j = 0; j < nodes.length; j++) {
        var nid = nodes[j].getAttribute("data-focus-id") || nodes[j].id;
        if (nid === nextId) {
          setFocus(nodes[j], false);
          return;
        }
      }
    }

    function showScreen(name) {
      var screens = ["connect", "platforms", "games", "game", "settings"];
      for (var i = 0; i < screens.length; i++) {
        var section = doc.getElementById("screen-" + screens[i]);
        if (section) section.hidden = screens[i] !== name;
      }
      state.screen = name;
      var title = doc.getElementById("title");
      if (title) title.textContent = name.charAt(0).toUpperCase() + name.slice(1);
      var subtitle = doc.getElementById("subtitle");
      if (subtitle) {
        if (name === "connect") subtitle.textContent = "Sign in once. This device keeps the session until you log out.";
        else if (name === "platforms") subtitle.textContent = "Confirm opens a console. Search only filters this list.";
        else if (name === "games") subtitle.textContent = state.platform ? platformDisplayName(state.platform) : "";
        else if (name === "game") subtitle.textContent = state.platform ? platformDisplayName(state.platform) : "";
        else subtitle.textContent = "ROM root and folder names Cocoon already scans.";
      }
    }

    function focusDefault() {
      if (state.screen === "platforms") {
        var card = doc.querySelector("#platform-grid .is-focused") || doc.querySelector("#platform-grid [data-card]:not([hidden])");
        if (card) { setFocus(card, false); return; }
      }
      if (state.screen === "games") {
        var game = doc.querySelector("#game-grid [data-card]");
        if (game) { setFocus(game, false); return; }
      }
      if (state.screen === "game") {
        setFocus(doc.getElementById("game-card"), false);
        return;
      }
      if (state.screen === "connect") {
        setFocus(doc.getElementById("btn-password"), false);
        return;
      }
      if (state.screen === "settings") {
        setFocus(doc.getElementById("btn-rom-root"), false);
        return;
      }
    }

    function paintLayout() {
      var layouts = ["cocoon", "romm", "alias"];
      for (var i = 0; i < layouts.length; i++) {
        var btn = doc.getElementById("btn-layout-" + layouts[i]);
        if (btn) btn.classList.toggle("is-selected", layouts[i] === state.layout);
      }
    }

    function applyPlatformFilter(query) {
      state.platformQuery = query;
      var grid = doc.getElementById("platform-grid");
      var cards = grid.querySelectorAll("[data-card]");
      var q = String(query || "").replace(/^\s+|\s+$/g, "").toLowerCase();
      var first = null;
      var current = grid.querySelector(".is-focused");
      var currentOk = false;
      for (var i = 0; i < cards.length; i++) {
        var blob = cards[i].getAttribute("data-blob") || "";
        var match = !q || blob.indexOf(q) !== -1;
        cards[i].hidden = !match;
        if (match && !first) first = cards[i];
        if (match && cards[i] === current) currentOk = true;
      }
      var target = currentOk ? current : first;
      for (var j = 0; j < cards.length; j++) cards[j].classList.remove("is-focused");
      if (target) {
        target.classList.add("is-focused");
        if (target.scrollIntoView) target.scrollIntoView({ block: "nearest", inline: "nearest" });
      }
    }

    function makeButton(id, title, meta) {
      var btn = doc.createElement("button");
      btn.type = "button";
      btn.className = "card";
      btn.setAttribute("data-card", "1");
      btn.setAttribute("data-focus", "1");
      btn.setAttribute("data-zone", "grid");
      btn.setAttribute("data-focus-id", "card-" + id);
      btn.setAttribute("data-id", id);
      var heading = doc.createElement("span");
      heading.className = "card-title";
      heading.textContent = title;
      var note = doc.createElement("span");
      note.className = "card-meta";
      note.textContent = meta || "";
      btn.appendChild(heading);
      btn.appendChild(note);
      return btn;
    }

    function platformCard(platform) {
      var year = platformReleaseYear(map, platform);
      var count = platform.rom_count || 0;
      var btn = makeButton(platformKey(platform), platformDisplayName(platform), (year ? String(year) : "Year unknown") + " · " + count + " games");
      btn.setAttribute("data-blob", [platform.display_name, platform.name, platform.slug, platform.fs_slug].join(" ").toLowerCase());
      btn.addEventListener("click", function () { openPlatform(platform); });
      return btn;
    }

    function renderPlatforms(list) {
      var grid = doc.getElementById("platform-grid");
      if (grid.getAttribute("data-ready") !== "1") {
        for (var i = 0; i < list.length; i++) grid.appendChild(platformCard(list[i]));
        grid.setAttribute("data-ready", "1");
      }
      applyPlatformFilter(state.platformQuery || "");
      if (!grid.querySelector(".is-focused")) focusDefault();
    }

    function watchCover(img) {
      if (!host.RommNative || !host.RommNative.fetchCover) return;
      var urls = img.getAttribute("data-cover-urls");
      if (!urls) return;
      function run() {
        host.RommNative.fetchCover(Number(img.getAttribute("data-cover-id")), urls);
      }
      if (typeof host.IntersectionObserver !== "function") {
        run();
        return;
      }
      if (!coverObserver) {
        coverObserver = new host.IntersectionObserver(function (entries) {
          for (var i = 0; i < entries.length; i++) {
            if (!entries[i].isIntersecting) continue;
            var node = entries[i].target;
            coverObserver.unobserve(node);
            var queued = node.getAttribute("data-cover-urls");
            if (queued) host.RommNative.fetchCover(Number(node.getAttribute("data-cover-id")), queued);
          }
        }, { root: null });
      }
      coverObserver.observe(img);
    }

    function paintCover(img, rom, size) {
      if (!img) return;
      img.setAttribute("data-cover-id", String(rom.id));
      if (rom.shelfCover) {
        img.src = rom.shelfCover;
        img.hidden = false;
        return;
      }
      var urls = state.session ? coverRequestUrls(state.session.baseUrl, rom, size) : [];
      if (!urls.length) {
        img.removeAttribute("src");
        img.hidden = true;
        return;
      }
      img.hidden = false;
      img.setAttribute("data-cover", urls[0]);
      img.setAttribute("data-cover-urls", JSON.stringify(urls));
      watchCover(img);
    }

    function gameCard(rom) {
      var btn = makeButton(String(rom.id), rom.name || rom.fs_name || "Game", rom.fs_name || "");
      var img = doc.createElement("img");
      img.className = "cover";
      img.alt = "";
      img.width = 72;
      img.height = 96;
      btn.insertBefore(img, btn.firstChild);
      paintCover(img, rom, "small");
      btn.addEventListener("click", function () { openGame(rom); });
      return btn;
    }

    function removeMore() {
      var existing = doc.getElementById("btn-more");
      if (existing && existing.parentNode) existing.parentNode.removeChild(existing);
    }

    function appendGames(items) {
      var grid = doc.getElementById("game-grid");
      removeMore();
      for (var i = 0; i < items.length; i++) {
        state.romsById[String(items[i].id)] = items[i];
        grid.appendChild(gameCard(items[i]));
      }
    }

    function hasMore(page) {
      if (page.total != null) return state.gamesLoaded < page.total;
      return page.items.length >= PAGE_SIZE;
    }

    function appendMore() {
      var grid = doc.getElementById("game-grid");
      var btn = makeButton("more", "More", "Next " + PAGE_SIZE + " games");
      btn.id = "btn-more";
      btn.addEventListener("click", function () {
        loadGames(state.gameSearch || "", state.gamesLoaded, false);
      });
      grid.appendChild(btn);
    }

    function loadGames(search, offset, replace) {
      if (!state.platform) return;
      setStatus(search ? "Searching…" : "");
      var raw = native("roms", Number(state.platform.id), search || "", PAGE_SIZE, offset || 0);
      var result = parseNative(raw);
      if (!result) { setStatus("Could not reach RomM."); return; }
      if (result.logout) { forceLogout(result.error); return; }
      if (!result.ok) { setStatus(result.error || "Could not load games."); return; }
      var page = normalizeRomPage(result.page);
      var grid = doc.getElementById("game-grid");
      if (replace) {
        grid.textContent = "";
        state.romsById = {};
        state.gamesLoaded = 0;
      }
      appendGames(page.items);
      state.gamesLoaded += page.items.length;
      state.gamesTotal = page.total;
      if (hasMore(page)) appendMore();
      setStatus("");
      if (replace) focusDefault();
    }

    function openPlatform(platform) {
      if (!canOpenPlatform(platform)) return;
      state.platform = platform;
      state.gameSearch = "";
      state.game = null;
      state.returnScreen = "platforms";
      var input = doc.getElementById("search-input");
      if (input) {
        input.value = "";
        input.blur();
      }
      state.searchOpen = false;
      doc.getElementById("search-layer").hidden = true;
      showScreen("games");
      loadGames("", 0, true);
    }

    function destinationFor() {
      refreshFolders();
      return resolveDestination(map, {
        rommSlug: state.platform ? state.platform.slug : "",
        rommFsSlug: state.platform ? (state.platform.fs_slug || state.platform.slug) : "",
        layout: state.layout,
        existingFolders: state.existingFolders
      });
    }

    function showCoverNote(visible) {
      var note = doc.getElementById("cover-note");
      if (!note) return;
      note.hidden = !visible;
      note.textContent = visible ? "Cover stays in this app. CocoonFE does not document a cover file Cocoon scans." : "";
    }

    function openGame(rom) {
      state.game = rom;
      state.returnScreen = "games";
      showScreen("game");
      var dest = destinationFor();
      var fileName = sanitizeFilename(rom.fs_name || rom.name || "rom.bin");
      doc.getElementById("game-title").textContent = rom.name || rom.fs_name || "Game";
      doc.getElementById("game-file").textContent = rom.fs_name || fileName;
      doc.getElementById("game-dest").textContent = dest.folderName + "/" + fileName;
      paintCover(doc.getElementById("game-cover"), rom, "large");
      showCoverNote(!!rom.shelfCover || romCoverCandidates(rom, "large").length > 0);
      var note = doc.getElementById("rescan-note");
      if (note) {
        note.hidden = true;
        note.textContent = "";
      }
      setFocus(doc.getElementById("game-card"), false);
    }

    function refreshFolders() {
      var raw = native("listFolders");
      if (!raw) { state.existingFolders = []; return; }
      try { state.existingFolders = JSON.parse(raw) || []; } catch (err) { state.existingFolders = []; }
    }

    function focusedRom() {
      if (state.screen === "game") return state.game;
      if (state.screen !== "games") return null;
      var el = doc.querySelector("#game-grid .is-focused");
      if (!el) return null;
      return state.romsById[el.getAttribute("data-id")] || null;
    }

    function startDownload(rom) {
      if (!state.session || !state.session.baseUrl) { setStatus("Sign in before downloading."); return; }
      if (!rom) { setStatus("Focus a game, then download."); return; }
      if (!state.platform) { setStatus("Open a console first."); return; }
      var dest = destinationFor();
      var fileName = sanitizeFilename(rom.fs_name || rom.name || "rom.bin");
      var url = absoluteUrl(state.session.baseUrl, contentDownloadPath(rom.id, rom.fs_name || fileName));
      var progress = doc.getElementById("download-progress");
      if (progress) progress.textContent = "Starting download…";
      state.pendingRescan = "Saved to " + dest.folderName + "/" + fileName + ". Rescan " + (dest.cocoonName || platformDisplayName(state.platform)) + " in Cocoon.";
      native("downloadRom", url, dest.folderName, fileName);
    }

    function loadPlatforms() {
      if (memoryPlatforms) { renderPlatforms(memoryPlatforms); return; }
      setStatus("Loading consoles…");
      var raw = native("platforms");
      var result = parseNative(raw);
      if (!result) { setStatus("Could not reach RomM."); return; }
      if (result.logout) { forceLogout(result.error); return; }
      if (!result.ok) { setStatus(result.error || "Could not load consoles."); return; }
      memoryPlatforms = sortPlatformsByGeneration(map, platformsWithGames(result.platforms || []));
      renderPlatforms(memoryPlatforms);
      setStatus("");
    }

    function handleAuth(raw) {
      var result = parseNative(raw);
      if (!result) { setStatus("Could not reach RomM."); return; }
      if (result.logout) { forceLogout(result.error); return; }
      if (!result.ok) { setStatus(result.error || "Sign-in failed."); return; }
      state.session = result.session;
      if (result.session && result.session.layout) state.layout = result.session.layout;
      memoryPlatforms = null;
      showScreen("platforms");
      loadPlatforms();
      focusDefault();
    }

    function signInPassword() {
      var base;
      try { base = normalizeBaseUrl(doc.getElementById("server-url").value); }
      catch (err) { setStatus(err.message); return; }
      var user = doc.getElementById("username").value || "";
      var pass = doc.getElementById("password").value || "";
      if (!user || !pass) { setStatus("Username and password are required."); return; }
      setStatus("Signing in…");
      handleAuth(native("loginPassword", base, user, pass));
    }

    function signInToken() {
      var base;
      try { base = normalizeBaseUrl(doc.getElementById("server-url").value); }
      catch (err) { setStatus(err.message); return; }
      var token = (doc.getElementById("client-token").value || "").replace(/^\s+|\s+$/g, "");
      if (!isClientToken(token)) { setStatus("Client tokens start with rmm_."); return; }
      setStatus("Checking token…");
      handleAuth(native("loginToken", base, token));
    }

    function signInPair() {
      var base;
      try { base = normalizeBaseUrl(doc.getElementById("server-url").value); }
      catch (err) { setStatus(err.message); return; }
      var code = (doc.getElementById("pair-code").value || "").replace(/^\s+|\s+$/g, "");
      if (!isPairCode(code)) { setStatus("Enter the 8-digit pairing code."); return; }
      setStatus("Pairing…");
      handleAuth(native("loginPair", base, code));
    }

    function forceLogout(message) {
      memoryPlatforms = null;
      state.session = null;
      state.platform = null;
      state.game = null;
      try { native("logout"); } catch (err) { /* already signed out */ }
      showScreen("connect");
      focusDefault();
      setStatus(message || "Sign-in expired. Connect again.");
    }

    function logout() {
      native("logout");
      memoryPlatforms = null;
      state.session = null;
      state.platform = null;
      state.game = null;
      showScreen("connect");
      focusDefault();
      setStatus("Logged out.");
    }

    function toggleSearch() {
      if (state.screen !== "platforms" && state.screen !== "games") {
        setStatus("Search filters consoles, or searches the open console.");
        return;
      }
      var layer = doc.getElementById("search-layer");
      var input = doc.getElementById("search-input");
      state.searchOpen = !state.searchOpen;
      layer.hidden = !state.searchOpen;
      if (!state.searchOpen) return;
      input.placeholder = state.screen === "platforms" ? "Filter consoles" : "Search this console, then confirm";
      if (state.screen === "games") input.value = state.gameSearch || "";
      input.focus();
    }

    function submitSearch() {
      var input = doc.getElementById("search-input");
      var query = input ? input.value : "";
      if (state.screen === "platforms") {
        applyPlatformFilter(query);
        return;
      }
      if (state.screen === "games") {
        var plan = planGameSearch({ mode: "submit", query: query, previous: state.gameSearch });
        state.gameSearch = plan.query;
        if (plan.fetch) loadGames(plan.query, 0, true);
      }
    }

    function goBack() {
      var next = nextScreen(state.screen, "back", state.returnScreen);
      if (state.screen === "connect") {
        if (host.RommNative && host.RommNative.finishApp) host.RommNative.finishApp();
        return;
      }
      if (next === state.screen) return;
      showScreen(next);
      if (next === "platforms" && state.platformQuery) {
        var input = doc.getElementById("search-input");
        state.searchOpen = true;
        doc.getElementById("search-layer").hidden = false;
        if (input) input.value = state.platformQuery;
        applyPlatformFilter(state.platformQuery);
      } else {
        focusDefault();
      }
    }

    function activate() {
      var active = doc.activeElement;
      var el = doc.querySelector(".is-focused");
      if (state.screen === "platforms" && el && el.getAttribute("data-card") && !el.hidden) {
        el.click();
        return;
      }
      if (active && active.id === "search-input") {
        submitSearch();
        return;
      }
      if (!el) return;
      if (el.id === "search-input") { submitSearch(); return; }
      el.click();
    }

    function onHardwareKey(name) {
      var now = Date.now();
      if (lastHardware.name === name && now - lastHardware.at < 32) return;
      lastHardware.name = name;
      lastHardware.at = now;
      if (name === "back") { goBack(); return; }
      if (name === "confirm") { activate(); return; }
      moveFocus(name);
    }

    function chooseLayout(layout) {
      state.layout = layout;
      paintLayout();
      native("saveSettings", layout);
      setStatus(layout === "romm" ? "New files use RomM folder names." : layout === "alias" ? "New files use a folder that already exists." : "New files use Cocoon folder names.");
    }

    function applyTheme(theme) {
      state.theme = normalizeTheme(theme);
      doc.documentElement.setAttribute("data-theme", state.theme);
      var bright = doc.getElementById("btn-theme-bright");
      var dark = doc.getElementById("btn-theme-dark");
      if (bright) bright.classList.toggle("is-selected", state.theme === "bright");
      if (dark) dark.classList.toggle("is-selected", state.theme === "dark");
    }

    function chooseTheme(theme) {
      applyTheme(theme);
      native("saveTheme", state.theme);
    }

    function fieldValue(id) {
      var el = doc.getElementById(id);
      return el ? el.value : "";
    }

    function setField(id, value) {
      var el = doc.getElementById(id);
      if (el) el.value = value || "";
    }

    function scraperFromSession(session) {
      session = session || {};
      return {
        ssUser: session.ssUser || "",
        ssPassword: session.ssPassword || "",
        ssDevId: session.ssDevId || "",
        ssDevPassword: session.ssDevPassword || "",
        sgdbKey: session.sgdbKey || ""
      };
    }

    function fillScraperForm(session) {
      var creds = scraperFromSession(session);
      setField("ss-user", creds.ssUser);
      setField("ss-password", creds.ssPassword);
      setField("ss-devid", creds.ssDevId);
      setField("ss-devpassword", creds.ssDevPassword);
      setField("sgdb-key", creds.sgdbKey);
    }

    function readScraperForm() {
      return {
        ssUser: fieldValue("ss-user"),
        ssPassword: fieldValue("ss-password"),
        ssDevId: fieldValue("ss-devid"),
        ssDevPassword: fieldValue("ss-devpassword"),
        sgdbKey: fieldValue("sgdb-key")
      };
    }

    function saveScraperForm() {
      var creds = readScraperForm();
      if (!state.session) state.session = {};
      state.session.ssUser = creds.ssUser;
      state.session.ssPassword = creds.ssPassword;
      state.session.ssDevId = creds.ssDevId;
      state.session.ssDevPassword = creds.ssDevPassword;
      state.session.sgdbKey = creds.sgdbKey;
      native("saveScraper", creds.ssUser, creds.ssPassword, creds.ssDevId, creds.ssDevPassword, creds.sgdbKey);
      setStatus("Artwork accounts saved on this device.");
    }

    function clearScraperForm() {
      fillScraperForm({});
      if (state.session) {
        state.session.ssUser = "";
        state.session.ssPassword = "";
        state.session.ssDevId = "";
        state.session.ssDevPassword = "";
        state.session.sgdbKey = "";
      }
      native("clearScraper");
      setStatus("Artwork accounts cleared.");
    }

    function startScrape(rom) {
      if (!rom) { setStatus("Open a game, then scrape artwork."); return; }
      var creds = state.session ? scraperFromSession(state.session) : readScraperForm();
      var step = scrapeStart(creds, rom);
      if (!step.request) { setStatus(step.message); return; }
      state.scrape = { seq: (state.scrape && state.scrape.seq ? state.scrape.seq : 0) + 1, rom: rom, step: step, creds: creds };
      setStatus("Scraping artwork…");
      native("fetchText", state.scrape.seq, step.request.url, step.request.bearer || "");
    }

    shelf.onHardwareKey = onHardwareKey;
    shelf.onDownloadProgress = function (text) {
      var el = doc.getElementById("download-progress");
      if (el) el.textContent = text || "";
    };
    shelf.onDownloadDone = function (message) {
      var text = state.pendingRescan || message || "Download finished.";
      var el = doc.getElementById("download-progress");
      if (el) el.textContent = text;
      var note = doc.getElementById("rescan-note");
      if (note) {
        note.textContent = text;
        note.hidden = false;
      }
    };
    shelf.onLoggedOut = function (message) { forceLogout(message); };
    shelf.onCover = function (id, dataUrl) {
      if (!dataUrl) return;
      var key = String(id);
      var rom = state.romsById[key];
      if (rom) rom.shelfCover = dataUrl;
      if (state.game && String(state.game.id) === key) state.game.shelfCover = dataUrl;
      var nodes = doc.querySelectorAll('img[data-cover-id="' + key + '"]');
      for (var i = 0; i < nodes.length; i++) {
        nodes[i].hidden = false;
        nodes[i].src = dataUrl;
      }
      if (state.screen === "game" && state.game && String(state.game.id) === key) showCoverNote(true);
    };
    shelf.onText = function (id, status, body) {
      if (!state.scrape || state.scrape.seq !== id) return;
      var payload = null;
      if (status >= 200 && status < 300 && body) {
        try { payload = JSON.parse(body); } catch (err) { payload = null; }
      }
      var next = scrapeAdvance(state.scrape.step, payload, state.scrape.creds, state.scrape.rom);
      state.scrape.step = next;
      if (next.imageUrl) {
        native("fetchCover", Number(state.scrape.rom.id), JSON.stringify([next.imageUrl]));
        setStatus("Artwork found.");
        return;
      }
      if (next.request) {
        native("fetchText", id, next.request.url, next.request.bearer || "");
        return;
      }
      setStatus(next.message || "No artwork found.");
    };
    shelf.onRomRoot = function (label) {
      var el = doc.getElementById("rom-root-label");
      if (el) el.textContent = label || "No folder chosen";
    };

    doc.getElementById("btn-password").addEventListener("click", signInPassword);
    doc.getElementById("btn-token").addEventListener("click", signInToken);
    doc.getElementById("btn-pair").addEventListener("click", signInPair);
    doc.getElementById("btn-back").addEventListener("click", goBack);
    doc.getElementById("btn-search").addEventListener("click", toggleSearch);
    doc.getElementById("btn-download").addEventListener("click", function () { startDownload(focusedRom()); });
    doc.getElementById("btn-settings").addEventListener("click", function () {
      state.returnScreen = state.screen === "settings" ? state.returnScreen : state.screen;
      refreshFolders();
      showScreen("settings");
      paintLayout();
      fillScraperForm(state.session);
      focusDefault();
    });
    doc.getElementById("btn-rom-root").addEventListener("click", function () { native("pickRomRoot"); });
    doc.getElementById("btn-layout-cocoon").addEventListener("click", function () { chooseLayout("cocoon"); });
    doc.getElementById("btn-layout-romm").addEventListener("click", function () { chooseLayout("romm"); });
    doc.getElementById("btn-layout-alias").addEventListener("click", function () { chooseLayout("alias"); });
    doc.getElementById("btn-logout").addEventListener("click", logout);
    doc.getElementById("btn-theme-bright").addEventListener("click", function () { chooseTheme("bright"); });
    doc.getElementById("btn-theme-dark").addEventListener("click", function () { chooseTheme("dark"); });
    doc.getElementById("btn-save-scraper").addEventListener("click", saveScraperForm);
    doc.getElementById("btn-clear-scraper").addEventListener("click", clearScraperForm);
    doc.getElementById("btn-scrape").addEventListener("click", function () { startScrape(state.game); });
    doc.getElementById("game-card").addEventListener("click", function () { startDownload(state.game); });
    doc.getElementById("search-input").addEventListener("input", function (event) {
      if (state.screen !== "platforms") {
        planGameSearch({ mode: "draft", query: event.target.value, previous: state.gameSearch });
        return;
      }
      applyPlatformFilter(event.target.value);
    });
    doc.addEventListener("keydown", function (event) {
      var action = "";
      if (event.key === "ArrowUp") action = "up";
      else if (event.key === "ArrowDown") action = "down";
      else if (event.key === "ArrowLeft") action = "left";
      else if (event.key === "ArrowRight") action = "right";
      else if (event.key === "Enter") action = "confirm";
      else if (event.key === "Escape") action = "back";
      else if (event.key === " " && (!event.target || event.target.tagName !== "INPUT")) action = "confirm";
      if (!action) return;
      var typing = event.target && (event.target.tagName === "INPUT" || event.target.tagName === "TEXTAREA");
      if (typing && (action === "left" || action === "right")) return;
      event.preventDefault();
      onHardwareKey(action);
    });

    var session = null;
    try {
      var rawSession = native("getSession");
      session = rawSession ? JSON.parse(rawSession) : null;
    } catch (err) { session = null; }
    state.layout = session && session.layout ? session.layout : "cocoon";
    state.session = session && session.token ? session : null;
    applyTheme(session && session.theme);
    if (session) fillScraperForm(session);
    paintLayout();
    var rootLabel = doc.getElementById("rom-root-label");
    if (rootLabel) rootLabel.textContent = session && session.romRootLabel ? session.romRootLabel : "No folder chosen";
    if (state.session) {
      showScreen("platforms");
      loadPlatforms();
      focusDefault();
    } else {
      showScreen("connect");
      focusDefault();
    }
  }
})(typeof window !== "undefined" ? window : this);
