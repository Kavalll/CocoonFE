(function (root) {
  "use strict";

  var PAGE_SIZE = 200;
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
    if (options.collectionId != null && String(options.collectionId) !== "") {
      parts.push("collection_id=" + encodeURIComponent(String(options.collectionId)));
    }
    if (options.smartCollectionId != null && String(options.smartCollectionId) !== "") {
      parts.push("smart_collection_id=" + encodeURIComponent(String(options.smartCollectionId)));
    }
    if (options.virtualCollectionId != null && String(options.virtualCollectionId) !== "") {
      parts.push("virtual_collection_id=" + encodeURIComponent(String(options.virtualCollectionId)));
    }
    if (variant === "plain") {
      if (options.platformId != null && String(options.platformId) !== "") parts.push("platform_id=" + encodeURIComponent(String(options.platformId)));
    } else {
      parts.push("order_by=name");
      parts.push("order_dir=asc");
      parts.push("group_by_meta_id=false");
      if (options.platformId != null && String(options.platformId) !== "") parts.push("platform_ids=" + encodeURIComponent(String(options.platformId)));
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
        scope: "roms.read platforms.read assets.read collections.read"
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

  function backTarget(view) {
    view = view || {};
    var screen = view.screen || "platforms";
    if (screen === "game" && view.carousel) return { screen: "game", closeCarousel: true, finish: false };
    if (screen === "game") return { screen: "games", closeCarousel: false, finish: false };
    if (screen === "games" && view.gamesKind === "collection") {
      return { screen: "platforms", tab: "collections", closeCarousel: false, finish: false };
    }
    if (screen === "games") return { screen: "platforms", tab: "consoles", closeCarousel: false, finish: false };
    if (screen === "settings") return { screen: view.returnScreen || "platforms", closeCarousel: false, finish: false };
    if (screen === "connect") return { screen: "connect", closeCarousel: false, finish: true };
    return { screen: "platforms", closeCarousel: false, finish: false };
  }

  function planLibrarySearch(input) {
    input = input || {};
    var query = trimText(input.query);
    if (input.mode === "draft") return { fetch: false, restore: false, query: input.previous || "", kind: "draft" };
    if (!query) return { fetch: false, restore: true, query: "", kind: "clear" };
    return { fetch: true, restore: false, query: query, kind: "library" };
  }

  function footerControls(screen) {
    return {
      back: false,
      search: false,
      download: screen === "game",
      settings: true
    };
  }

  function hardwareKeyAction(keyCode) {
    if (keyCode === 23 || keyCode === 66 || keyCode === 160 || keyCode === 62 || keyCode === 96 || keyCode === 108) return "confirm";
    if (keyCode === 4 || keyCode === 97 || keyCode === 111) return "back";
    if (keyCode === 19) return "up";
    if (keyCode === 20) return "down";
    if (keyCode === 21) return "left";
    if (keyCode === 22) return "right";
    return "";
  }

  function listPlace(ids, focusedId, scrollTop) {
    var list = [];
    var source = ids || [];
    var i;
    for (i = 0; i < source.length; i++) list.push(String(source[i]));
    var focus = focusedId == null || focusedId === "" ? "" : String(focusedId);
    var index = -1;
    for (i = 0; i < list.length; i++) if (list[i] === focus) index = i;
    return {
      ids: list,
      index: index,
      focusedId: index >= 0 ? focus : null,
      scrollTop: typeof scrollTop === "number" ? scrollTop : 0,
      resetToFirst: false
    };
  }

  function platformLogoCandidates(platform) {
    var out = [];
    var seen = {};
    function push(value) {
      var text = trimText(value);
      if (!text || seen[text]) return;
      seen[text] = true;
      out.push(text);
    }
    if (!platform) return out;
    push(platform.logo_path);
    push(platform.url_logo);
    return out;
  }

  function collectionListPaths() {
    return {
      manual: "/api/collections",
      smart: "/api/collections/smart",
      virtual: "/api/collections/virtual?type=collection"
    };
  }

  function normalizeCollectionRows(payload, kind) {
    var list = [];
    if (payload && Object.prototype.toString.call(payload) === "[object Array]") list = payload;
    else if (payload && payload.items) list = payload.items;
    else if (payload && payload.collections) list = payload.collections;
    var out = [];
    for (var i = 0; i < list.length; i++) {
      var row = list[i] || {};
      if (row.id == null) continue;
      var count = row.rom_count;
      if (count == null && row.roms && row.roms.length != null) count = row.roms.length;
      out.push({
        id: String(row.id),
        kind: kind || row.kind || "manual",
        name: trimText(row.name) || "Collection",
        romCount: count == null ? 0 : Number(count) || 0
      });
    }
    return out;
  }

  function collectionRomsOptions(collection, limit, offset) {
    var options = { limit: limit, offset: offset };
    if (!collection) return options;
    if (collection.kind === "smart") options.smartCollectionId = collection.id;
    else if (collection.kind === "virtual") options.virtualCollectionId = collection.id;
    else options.collectionId = collection.id;
    return options;
  }

  function gameScreenshotNav(view, input) {
    view = view || {};
    var focus = view.focus || "column";
    var count = view.count || 0;
    var index = view.index || 0;
    var columnId = view.columnId || "game-card";
    function shotState(focusName, shotIndex, open) {
      return { focus: focusName, index: shotIndex, count: count, open: open, columnId: columnId, leaveScreen: false };
    }
    if (count < 1) return { focus: "column", index: 0, count: 0, open: false, columnId: columnId, leaveScreen: false };
    if (index < 0) index = 0;
    if (index >= count) index = count - 1;
    if (focus === "column" && input === "right") return shotState("preview", index, false);
    if (focus === "preview" && input === "left") return shotState("column", index, false);
    if (focus === "preview" && input === "confirm") return shotState("carousel", index, true);
    if (focus === "carousel" && input === "right") return shotState("carousel", index + 1 < count ? index + 1 : index, true);
    if (focus === "carousel" && input === "left") return shotState("carousel", index > 0 ? index - 1 : 0, true);
    if (focus === "carousel" && input === "back") return shotState("preview", index, false);
    if (focus === "carousel") return shotState("carousel", index, true);
    return shotState(focus, index, false);
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

  function detectSignIn(username, secret) {
    var user = trimText(username);
    var value = trimText(secret);
    if (isClientToken(value)) return { method: "token", username: user, secret: value };
    if (isPairCode(value)) return { method: "pair", username: user, secret: value };
    if (!value) return { method: "", error: "Enter a password, token, or pairing code." };
    if (!user) return { method: "", error: "Username is required for a password." };
    return { method: "password", username: user, secret: value };
  }

  function nextPageFocus(existingIds, addedIds) {
    var keep = [];
    var existing = existingIds || [];
    var added = addedIds || [];
    var i;
    for (i = 0; i < existing.length; i++) keep.push(existing[i]);
    for (i = 0; i < added.length; i++) keep.push(added[i]);
    return {
      keepIds: keep,
      focusId: added.length ? String(added[0]) : null,
      resetToFirst: false
    };
  }

  function romSummary(rom) {
    if (!rom) return "";
    var text = trimText(rom.summary);
    if (!text) text = trimText(rom.description);
    return text;
  }

  function pushScreenshot(out, seen, value) {
    if (value == null) return;
    if (Object.prototype.toString.call(value) === "[object Array]") {
      for (var i = 0; i < value.length; i++) pushScreenshot(out, seen, value[i]);
      return;
    }
    if (typeof value === "object") {
      pushScreenshot(out, seen, value.url || value.path || value.download_path || "");
      return;
    }
    var text = trimText(value);
    if (!text || seen[text]) return;
    seen[text] = true;
    out.push(text);
  }

  function romScreenshotPaths(rom) {
    var out = [];
    var seen = {};
    if (!rom) return out;
    pushScreenshot(out, seen, rom.merged_screenshots);
    if (!out.length) {
      pushScreenshot(out, seen, rom.path_screenshots);
      pushScreenshot(out, seen, rom.url_screenshots);
      pushScreenshot(out, seen, rom.screenshot_path);
    }
    return out;
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

  function zoneItems(list, zone) {
    var pool = [];
    for (var i = 0; i < list.length; i++) if ((list[i].zone || "page") === zone) pool.push(list[i]);
    return pool;
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
    if (direction === "left" || direction === "right") {
      if (zone === "carousel") return current.id;
      if (direction === "right" && zone === "page") {
        var previewHit = nearestInDirection(current, zoneItems(list, "preview"), "right");
        if (previewHit) return previewHit;
      }
      if (direction === "left" && zone === "preview") {
        var column = zoneItems(list, "page");
        return nearestInDirection(current, column, "left") || (column.length ? column[0].id : current.id);
      }
      var row = [];
      for (i = 0; i < list.length; i++) {
        if ((list[i].zone || "page") !== zone) continue;
        if (!sameFocusRow(current, list[i])) continue;
        row.push(list[i]);
      }
      return nearestInDirection(current, row, direction) || current.id;
    }
    if (direction === "up" && zone === "footer") {
      var hasGrid = false;
      for (i = 0; i < list.length; i++) if (list[i].zone === "grid") hasGrid = true;
      var want = hasGrid ? "grid" : "page";
      return nearestInDirection(current, zoneItems(list, want), direction) || current.id;
    }
    var inside = nearestInDirection(current, zoneItems(list, zone), direction);
    if (inside) return inside;
    if (direction === "up") {
      var above = [];
      if (zone === "grid") above = zoneItems(list, "chrome").concat(zoneItems(list, "tab"));
      else if (zone === "tab") above = zoneItems(list, "chrome");
      else if (zone === "chrome") above = zoneItems(list, "tab");
      return nearestInDirection(current, above, "up") || current.id;
    }
    if (direction !== "down") return current.id;
    if (zone === "chrome" || zone === "tab") {
      var below = zoneItems(list, "tab").concat(zoneItems(list, "chrome")).concat(zoneItems(list, "grid")).concat(zoneItems(list, "page"));
      var next = nearestInDirection(current, below, "down");
      if (next) return next;
    }
    return nearestInDirection(current, zoneItems(list, "footer"), "down") || current.id;
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
    backTarget: backTarget,
    planLibrarySearch: planLibrarySearch,
    footerControls: footerControls,
    hardwareKeyAction: hardwareKeyAction,
    listPlace: listPlace,
    platformLogoCandidates: platformLogoCandidates,
    collectionListPaths: collectionListPaths,
    normalizeCollectionRows: normalizeCollectionRows,
    collectionRomsOptions: collectionRomsOptions,
    gameScreenshotNav: gameScreenshotNav,
    isPairCode: isPairCode,
    isClientToken: isClientToken,
    detectSignIn: detectSignIn,
    nextPageFocus: nextPageFocus,
    romSummary: romSummary,
    romScreenshotPaths: romScreenshotPaths,
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
      scrape: null,
      gamesKind: "platform",
      libraryTab: "consoles",
      libraryQuery: "",
      libraryReturn: null,
      collections: null,
      collection: null,
      shots: { paths: [], index: 0, open: false, columnId: "game-card" },
      listPlace: null
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

    function setFocus(el, keepTyping, scroll) {
      markFocused(el);
      if (!el) return;
      var typing = doc.activeElement && (doc.activeElement.tagName === "INPUT" || doc.activeElement.tagName === "TEXTAREA");
      if (keepTyping && typing) {
        if (scroll !== false && el.scrollIntoView) el.scrollIntoView({ block: "nearest", inline: "nearest" });
        return;
      }
      if (el.tagName === "INPUT" || el.tagName === "TEXTAREA") el.focus();
      else if (typing && doc.activeElement && doc.activeElement.blur) doc.activeElement.blur();
      if (scroll !== false && el.scrollIntoView) el.scrollIntoView({ block: "nearest", inline: "nearest" });
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
      if (title) {
        if (name === "platforms" && state.libraryTab === "collections") title.textContent = "Collections";
        else if (name === "games" && state.gamesKind === "library") title.textContent = "All consoles";
        else if (name === "games" && state.gamesKind === "collection") title.textContent = state.collection ? state.collection.name : "Collection";
        else if (name === "games" && state.gamesKind === "platform") title.textContent = state.platform ? platformDisplayName(state.platform) : "Games";
        else if (name === "game") {
          var shownTitle = state.gamesKind === "platform" ? state.platform : (platformForRom(state.game) || state.platform);
          title.textContent = shownTitle ? platformDisplayName(shownTitle) : (state.game && (state.game.name || state.game.fs_name) || "Game");
        }
        else title.textContent = name.charAt(0).toUpperCase() + name.slice(1);
      }
      var subtitle = doc.getElementById("subtitle");
      if (subtitle) {
        if (name === "connect") subtitle.textContent = "";
        else if (name === "platforms" && state.libraryTab === "collections") subtitle.textContent = "Confirm opens a collection.";
        else if (name === "platforms") subtitle.textContent = "Confirm opens a console. The console field only filters this list.";
        else if (name === "games" && state.gamesKind === "library") subtitle.textContent = state.libraryQuery || "";
        else if (name === "games" && state.gamesKind === "collection") subtitle.textContent = state.collection ? (state.collection.romCount + " games") : "";
        else if (name === "games") subtitle.textContent = state.platform ? platformDisplayName(state.platform) : "";
        else if (name === "game") {
          var shown = state.gamesKind === "platform" ? state.platform : (platformForRom(state.game) || state.platform);
          subtitle.textContent = shown ? platformDisplayName(shown) : "";
        }
        else subtitle.textContent = "ROM root and folder names Cocoon already scans.";
      }
      paintChrome();
    }

    function paintChrome() {
      var platforms = state.screen === "platforms";
      var games = state.screen === "games";
      var consoles = state.libraryTab !== "collections";
      var showConsole = platforms && consoles;
      var showGame = games && state.gamesKind === "platform";
      var showLibrary = platforms || games;
      var consoleLabel = doc.getElementById("console-filter-label");
      var gameLabel = doc.getElementById("game-search-label");
      var libraryLabel = doc.getElementById("library-search-label");
      var find = doc.getElementById("find-bar");
      if (consoleLabel) consoleLabel.hidden = !showConsole;
      if (gameLabel) gameLabel.hidden = !showGame;
      if (libraryLabel) libraryLabel.hidden = !showLibrary;
      if (find) find.hidden = !showConsole && !showGame && !showLibrary;
      var platformGrid = doc.getElementById("platform-grid");
      var collectionGrid = doc.getElementById("collection-grid");
      var tabs = doc.getElementById("library-tabs");
      if (platformGrid) platformGrid.hidden = !(platforms && consoles);
      if (collectionGrid) collectionGrid.hidden = !(platforms && !consoles);
      if (tabs) tabs.hidden = !platforms;
      var tabConsoles = doc.getElementById("tab-consoles");
      var tabCollections = doc.getElementById("tab-collections");
      if (tabConsoles) tabConsoles.classList.toggle("is-selected", consoles);
      if (tabCollections) tabCollections.classList.toggle("is-selected", !consoles);
      var download = doc.getElementById("btn-download");
      if (download) download.hidden = !footerControls(state.screen).download;
    }

    function focusDefault() {
      if (state.screen === "platforms") {
        var gridId = state.libraryTab === "collections" ? "#collection-grid" : "#platform-grid";
        var card = doc.querySelector(gridId + " .is-focused") || doc.querySelector(gridId + " [data-card]:not([hidden])");
        if (card) { setFocus(card, false); return; }
        setFocus(doc.getElementById(state.libraryTab === "collections" ? "tab-collections" : "tab-consoles"), false);
        return;
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
        setFocus(doc.getElementById("btn-sign-in"), false);
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
      var logos = platformLogoCandidates(platform);
      if (logos.length && state.session) {
        var head = doc.createElement("span");
        head.className = "card-head";
        var img = doc.createElement("img");
        img.className = "platform-logo";
        img.alt = "";
        img.width = 36;
        img.height = 36;
        var id = "logo-" + platformKey(platform);
        var urls = [];
        for (var i = 0; i < logos.length; i++) urls.push(absoluteUrl(state.session.baseUrl, logos[i]));
        img.setAttribute("data-cover-id", id);
        img.setAttribute("data-cover-urls", JSON.stringify(urls));
        var title = btn.querySelector(".card-title");
        head.appendChild(img);
        if (title) head.appendChild(title);
        btn.insertBefore(head, btn.firstChild);
        if (host.RommNative && host.RommNative.fetchImage) host.RommNative.fetchImage(id, JSON.stringify(urls));
      }
      btn.addEventListener("click", function () { openPlatform(platform); });
      return btn;
    }

    function rememberListPlace() {
      if (state.screen !== "games") return;
      var main = doc.querySelector("main");
      var ids = [];
      var cards = doc.querySelectorAll("#game-grid [data-card]");
      for (var i = 0; i < cards.length; i++) {
        if (cards[i].id === "btn-more") continue;
        ids.push(cards[i].getAttribute("data-id"));
      }
      var el = doc.querySelector("#game-grid .is-focused");
      var focusedId = el ? el.getAttribute("data-id") : null;
      if (el && el.id === "btn-more") {
        focusedId = "more";
        ids.push("more");
      }
      state.listPlace = listPlace(ids, focusedId, main ? main.scrollTop : 0);
    }

    function restoreListPlace() {
      var place = state.listPlace;
      if (!place || !place.focusedId || place.resetToFirst) return false;
      var card = place.focusedId === "more"
        ? doc.getElementById("btn-more")
        : doc.querySelector('#game-grid [data-id="' + place.focusedId + '"]');
      if (!card) return false;
      var main = doc.querySelector("main");
      if (main) main.scrollTop = place.scrollTop || 0;
      setFocus(card, false, false);
      if (main) main.scrollTop = place.scrollTop || 0;
      return true;
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
        var coverId = img.getAttribute("data-cover-id");
        if (/^\d+$/.test(String(coverId || ""))) host.RommNative.fetchCover(Number(coverId), urls);
        else if (host.RommNative.fetchImage) host.RommNative.fetchImage(coverId, urls);
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
            if (!queued) continue;
            var coverId = node.getAttribute("data-cover-id");
            if (/^\d+$/.test(String(coverId || ""))) host.RommNative.fetchCover(Number(coverId), queued);
            else if (host.RommNative.fetchImage) host.RommNative.fetchImage(coverId, queued);
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
        loadGames(state.gamesLoaded, false);
      });
      grid.appendChild(btn);
    }

    function currentRomsRequests(offset) {
      var options = { limit: PAGE_SIZE, offset: offset || 0 };
      if (state.gamesKind === "library") options.search = state.libraryQuery || "";
      else if (state.gamesKind === "collection") {
        var extra = collectionRomsOptions(state.collection, PAGE_SIZE, offset || 0);
        options.collectionId = extra.collectionId;
        options.smartCollectionId = extra.smartCollectionId;
        options.virtualCollectionId = extra.virtualCollectionId;
      } else {
        options.platformId = state.platform ? state.platform.id : null;
        options.search = state.gameSearch || "";
      }
      return romsRequests(options);
    }

    function loadGames(offset, replace) {
      if (state.gamesKind === "platform" && !state.platform) return;
      if (state.gamesKind === "collection" && !state.collection) return;
      var requests = currentRomsRequests(offset);
      var raw = native("gallery", requests.first, requests.retry);
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
      var previousIds = [];
      if (!replace) {
        var kept = grid.querySelectorAll("[data-card]");
        for (var c = 0; c < kept.length; c++) {
          if (kept[c].id === "btn-more") continue;
          previousIds.push(kept[c].getAttribute("data-id"));
        }
      }
      var addedIds = [];
      for (var a = 0; a < page.items.length; a++) addedIds.push(String(page.items[a].id));
      appendGames(page.items);
      state.gamesLoaded += page.items.length;
      state.gamesTotal = page.total;
      if (hasMore(page)) appendMore();
      setStatus(replace && !page.items.length ? "No games." : "");
      if (replace) {
        focusDefault();
        return;
      }
      var plan = nextPageFocus(previousIds, addedIds);
      if (!plan.focusId) return;
      var fresh = grid.querySelector('[data-id="' + plan.focusId + '"]');
      if (fresh) setFocus(fresh, false);
    }

    function openPlatform(platform) {
      if (!canOpenPlatform(platform)) return;
      state.platform = platform;
      state.gamesKind = "platform";
      state.collection = null;
      state.gameSearch = "";
      state.game = null;
      state.libraryQuery = "";
      state.libraryReturn = null;
      state.returnScreen = "platforms";
      var gameSearch = doc.getElementById("game-search");
      if (gameSearch) gameSearch.value = "";
      var library = doc.getElementById("library-search");
      if (library) library.value = "";
      showScreen("games");
      loadGames(0, true);
    }

    function selectTab(tab) {
      state.libraryTab = tab === "collections" ? "collections" : "consoles";
      if (state.screen !== "platforms") showScreen("platforms");
      else paintChrome();
      if (state.libraryTab === "collections") loadCollections();
      else focusDefault();
    }

    function renderCollections() {
      var grid = doc.getElementById("collection-grid");
      if (!grid) return;
      grid.textContent = "";
      var rows = state.collections || [];
      for (var i = 0; i < rows.length; i++) {
        var row = rows[i];
        var btn = makeButton("collection-" + row.kind + "-" + row.id, row.name, row.romCount + (row.romCount === 1 ? " game" : " games"));
        btn.setAttribute("data-focus-id", "collection-" + row.kind + "-" + row.id);
        (function (item) {
          btn.addEventListener("click", function () { openCollection(item); });
        })(row);
        grid.appendChild(btn);
      }
      if (!rows.length) setStatus("No collections.");
      else setStatus("");
      focusDefault();
    }

    function loadCollections() {
      if (state.collections) { renderCollections(); return; }
      setStatus("Loading collections…");
      var raw = native("collections");
      var result = parseNative(raw);
      if (!result) { setStatus("Could not reach RomM."); return; }
      if (result.logout) { forceLogout(result.error); return; }
      if (!result.ok) { setStatus(result.error || "Could not load collections."); return; }
      var manual = normalizeCollectionRows(result.manual, "manual");
      var smart = normalizeCollectionRows(result.smart, "smart");
      var virtual = normalizeCollectionRows(result.virtual, "virtual");
      state.collections = manual.concat(smart).concat(virtual);
      renderCollections();
    }

    function openCollection(item) {
      state.collection = item;
      state.gamesKind = "collection";
      state.game = null;
      state.gameSearch = "";
      state.returnScreen = "platforms";
      showScreen("games");
      setStatus("Loading games…");
      loadGames(0, true);
    }

    function submitGameSearch() {
      var input = doc.getElementById("game-search");
      var plan = planGameSearch({ mode: "submit", query: input ? input.value : "", previous: state.gameSearch });
      if (!plan.fetch) return;
      state.gameSearch = plan.query;
      state.gamesKind = "platform";
      loadGames(0, true);
    }

    function rememberLibraryReturn() {
      if (state.gamesKind === "library" && state.libraryReturn) return;
      state.libraryReturn = {
        screen: state.screen,
        gamesKind: state.gamesKind,
        tab: state.libraryTab,
        platform: state.platform,
        gameSearch: state.gameSearch,
        collection: state.collection
      };
    }

    function restoreLibraryReturn() {
      var saved = state.libraryReturn;
      state.libraryQuery = "";
      state.libraryReturn = null;
      var library = doc.getElementById("library-search");
      if (library) library.value = "";
      if (saved && saved.gamesKind === "platform" && saved.platform && saved.screen === "games") {
        state.platform = saved.platform;
        state.collection = null;
        state.gamesKind = "platform";
        state.gameSearch = saved.gameSearch || "";
        var gameSearch = doc.getElementById("game-search");
        if (gameSearch) gameSearch.value = state.gameSearch;
        showScreen("games");
        loadGames(0, true);
        return;
      }
      state.gamesKind = "platform";
      state.libraryTab = saved && saved.tab ? saved.tab : "consoles";
      state.collection = saved ? saved.collection : null;
      showScreen("platforms");
      if (state.libraryTab === "collections" && !state.collections) loadCollections();
      else focusDefault();
    }

    function submitLibrary() {
      var input = doc.getElementById("library-search");
      var plan = planLibrarySearch({ mode: "submit", query: input ? input.value : "", previous: state.libraryQuery });
      if (plan.kind === "draft" || !plan.fetch && !plan.restore) return;
      if (plan.restore) {
        if (state.gamesKind === "library") restoreLibraryReturn();
        return;
      }
      rememberLibraryReturn();
      setStatus("Searching all consoles…");
      var requests = romsRequests({ search: plan.query, limit: PAGE_SIZE, offset: 0 });
      var raw = native("gallery", requests.first, requests.retry);
      var result = parseNative(raw);
      if (!result) { setStatus("Could not reach RomM."); return; }
      if (result.logout) { forceLogout(result.error); return; }
      if (!result.ok) { setStatus(result.error || "Could not search games."); return; }
      state.libraryQuery = plan.query;
      state.gamesKind = "library";
      state.collection = null;
      showScreen("games");
      var page = normalizeRomPage(result.page);
      var grid = doc.getElementById("game-grid");
      grid.textContent = "";
      state.romsById = {};
      state.gamesLoaded = 0;
      appendGames(page.items);
      state.gamesLoaded = page.items.length;
      state.gamesTotal = page.total;
      if (hasMore(page)) appendMore();
      setStatus("");
      focusDefault();
    }

    function platformForRom(rom) {
      if (!rom) return null;
      var id = rom.platform_id;
      if (memoryPlatforms && id != null) {
        for (var i = 0; i < memoryPlatforms.length; i++) {
          if (String(memoryPlatforms[i].id) === String(id)) return memoryPlatforms[i];
        }
      }
      var nested = rom.platform || {};
      var slug = rom.platform_slug || nested.slug || "";
      if (!slug && id == null) return null;
      return {
        id: id,
        slug: slug,
        fs_slug: rom.platform_fs_slug || nested.fs_slug || slug,
        name: rom.platform_name || nested.name || slug,
        display_name: rom.platform_display_name || nested.display_name || ""
      };
    }

    function destinationFor() {
      refreshFolders();
      var platform = state.platform;
      if (state.game && state.gamesKind !== "platform") platform = platformForRom(state.game) || platform;
      return resolveDestination(map, {
        rommSlug: platform ? platform.slug : "",
        rommFsSlug: platform ? (platform.fs_slug || platform.slug) : "",
        layout: state.layout,
        existingFolders: state.existingFolders
      });
    }

    function shotView() {
      var shots = state.shots || { paths: [], index: 0, open: false, columnId: "game-card" };
      var focus = "column";
      var el = doc.querySelector(".is-focused");
      var id = el ? (el.getAttribute("data-focus-id") || el.id) : "";
      if (shots.open) focus = "carousel";
      else if (id === "shot-preview") focus = "preview";
      return { focus: focus, index: shots.index || 0, count: (shots.paths || []).length, columnId: shots.columnId || "game-card" };
    }

    function paintShot(img, index) {
      if (!img || !state.session || !state.shots || !state.shots.paths[index]) return;
      var romId = state.game ? state.game.id : "game";
      var id = "shot-" + romId + "-" + index;
      var urls = JSON.stringify([absoluteUrl(state.session.baseUrl, state.shots.paths[index])]);
      img.alt = "";
      img.removeAttribute("src");
      img.setAttribute("data-cover-id", id);
      img.setAttribute("data-cover-urls", urls);
      if (host.RommNative && host.RommNative.fetchImage) host.RommNative.fetchImage(id, urls);
    }

    function applyShotNav(nav) {
      var main = doc.querySelector("main");
      var top = main ? main.scrollTop : 0;
      state.shots.index = nav.index;
      state.shots.open = !!nav.open;
      state.shots.columnId = nav.columnId || state.shots.columnId;
      var preview = doc.getElementById("shot-preview");
      var carousel = doc.getElementById("shot-carousel");
      var count = doc.getElementById("shot-count");
      if (carousel) carousel.hidden = !nav.open;
      if (nav.open) {
        paintShot(doc.getElementById("shot-carousel-img"), nav.index);
        if (count) count.textContent = (nav.index + 1) + " / " + state.shots.paths.length;
        setFocus(carousel, false, false);
      } else if (nav.focus === "preview" && preview) {
        paintShot(doc.getElementById("shot-preview-img"), nav.index);
        setFocus(preview, false, false);
      } else {
        var column = doc.getElementById(nav.columnId || "game-card");
        if (column) setFocus(column, false, false);
      }
      if (main) main.scrollTop = top;
    }

    function showGameDetails(rom) {
      var summary = doc.getElementById("game-summary");
      var text = romSummary(rom);
      if (summary) {
        summary.textContent = text;
        summary.hidden = !text;
      }
      var paths = romScreenshotPaths(rom);
      var limit = paths.length > 8 ? 8 : paths.length;
      var kept = [];
      for (var i = 0; i < limit; i++) kept.push(paths[i]);
      state.shots = { paths: kept, index: 0, open: false, columnId: "game-card" };
      var preview = doc.getElementById("shot-preview");
      var carousel = doc.getElementById("shot-carousel");
      if (carousel) carousel.hidden = true;
      if (!preview) return;
      if (!kept.length || !state.session) {
        preview.hidden = true;
        return;
      }
      preview.hidden = false;
      paintShot(doc.getElementById("shot-preview-img"), 0);
    }

    function showCoverNote(visible) {
      var note = doc.getElementById("cover-note");
      if (!note) return;
      note.hidden = !visible;
      note.textContent = visible ? "Cover stays in this app. CocoonFE does not document a cover file Cocoon scans." : "";
    }

    function openGame(rom) {
      rememberListPlace();
      state.game = rom;
      state.returnScreen = "games";
      showScreen("game");
      var dest = destinationFor();
      var fileName = sanitizeFilename(rom.fs_name || rom.name || "rom.bin");
      doc.getElementById("game-title").textContent = rom.name || rom.fs_name || "Game";
      doc.getElementById("game-file").textContent = rom.fs_name || fileName;
      doc.getElementById("game-dest").textContent = dest.folderName + "/" + fileName;
      paintCover(doc.getElementById("game-cover"), rom, "large");
      showGameDetails(rom);
      showCoverNote(!!rom.shelfCover || romCoverCandidates(rom, "large").length > 0);
      var note = doc.getElementById("rescan-note");
      if (note) {
        note.hidden = true;
        note.textContent = "";
      }
      var main = doc.querySelector("main");
      if (main) main.scrollTop = 0;
      setFocus(doc.getElementById("game-card"), false, false);
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

    function signIn() {
      var base;
      try { base = normalizeBaseUrl(doc.getElementById("server-url").value); }
      catch (err) { setStatus(err.message); return; }
      var detected = detectSignIn(doc.getElementById("username").value, doc.getElementById("secret").value);
      if (!detected.method) { setStatus(detected.error); return; }
      if (detected.method === "token") {
        setStatus("Checking token…");
        handleAuth(native("loginToken", base, detected.secret));
        return;
      }
      if (detected.method === "pair") {
        setStatus("Pairing…");
        handleAuth(native("loginPair", base, detected.secret));
        return;
      }
      setStatus("Signing in…");
      handleAuth(native("loginPassword", base, detected.username, detected.secret));
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

    function goBack() {
      var target = backTarget({
        screen: state.screen,
        carousel: !!(state.shots && state.shots.open),
        gamesKind: state.gamesKind,
        returnScreen: state.returnScreen
      });
      if (target.closeCarousel) {
        applyShotNav(gameScreenshotNav(shotView(), "back"));
        return;
      }
      if (target.finish) {
        if (host.RommNative && host.RommNative.finishApp) host.RommNative.finishApp();
        return;
      }
      if (target.screen === state.screen && state.screen === "platforms") return;
      var returningToList = state.screen === "game" && target.screen === "games";
      if (target.tab) state.libraryTab = target.tab;
      showScreen(target.screen);
      if (target.screen === "platforms" && state.platformQuery) applyPlatformFilter(state.platformQuery);
      if (target.screen === "platforms" && state.libraryTab === "collections" && !state.collections) {
        loadCollections();
        return;
      }
      if (target.screen === "settings") {
        paintLayout();
        fillScraperForm(state.session);
      }
      if (returningToList && restoreListPlace()) return;
      focusDefault();
    }

    function activate() {
      var active = doc.activeElement;
      var el = doc.querySelector(".is-focused");
      if (state.screen === "connect") { signIn(); return; }
      if (active && active.id === "console-filter") return;
      if (active && active.id === "library-search") { submitLibrary(); return; }
      if (active && active.id === "game-search") { submitGameSearch(); return; }
      if (state.shots && state.shots.open) return;
      if (!el) return;
      if (el.id === "console-filter") return;
      if (el.id === "library-search") { submitLibrary(); return; }
      if (el.id === "game-search") { submitGameSearch(); return; }
      if (el.id === "shot-preview") {
        applyShotNav(gameScreenshotNav(shotView(), "confirm"));
        return;
      }
      if (el.id === "tab-consoles") { selectTab("consoles"); return; }
      if (el.id === "tab-collections") { selectTab("collections"); return; }
      if (el.getAttribute("data-card") && !el.hidden) { el.click(); return; }
      el.click();
    }

    function onHardwareKey(name) {
      var now = Date.now();
      if (lastHardware.name === name && now - lastHardware.at < 32) return;
      lastHardware.name = name;
      lastHardware.at = now;
      if (state.screen === "game" && state.shots && state.shots.open) {
        if (name === "left" || name === "right" || name === "back") {
          applyShotNav(gameScreenshotNav(shotView(), name));
          return;
        }
        return;
      }
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

    doc.getElementById("btn-sign-in").addEventListener("click", signIn);
    doc.getElementById("btn-download").addEventListener("click", function () { startDownload(focusedRom()); });
    doc.getElementById("tab-consoles").addEventListener("click", function () { selectTab("consoles"); });
    doc.getElementById("tab-collections").addEventListener("click", function () { selectTab("collections"); });
    doc.getElementById("shot-preview").addEventListener("click", function () {
      applyShotNav(gameScreenshotNav(shotView(), "confirm"));
    });
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
    doc.getElementById("console-filter").addEventListener("input", function (event) {
      applyPlatformFilter(event.target.value);
    });
    doc.getElementById("game-search").addEventListener("input", function (event) {
      planGameSearch({ mode: "draft", query: event.target.value, previous: state.gameSearch });
    });
    doc.getElementById("library-search").addEventListener("input", function (event) {
      planLibrarySearch({ mode: "draft", query: event.target.value, previous: state.libraryQuery });
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
