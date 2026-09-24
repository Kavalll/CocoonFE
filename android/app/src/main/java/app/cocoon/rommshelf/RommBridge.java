package app.cocoon.rommshelf;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RommBridge {
    private static final String PREFS = "cocoon_romm_shelf";
    private final Context context;
    private final SharedPreferences prefs;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public interface Host {
        void evalJs(String script);
        void pickTree();
        void finishApp();
        void noteScriptParsed();
    }

    public RommBridge(Context context, Host host) {
        this.context = context;
        this.host = host;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @JavascriptInterface
    public void onScriptParsed() {
        host.noteScriptParsed();
    }

    @JavascriptInterface
    public void finishApp() {
        host.finishApp();
    }

    @JavascriptInterface
    public void pickRomRoot() {
        host.pickTree();
    }

    @JavascriptInterface
    public String getSession() {
        return sessionJson().toString();
    }

    @JavascriptInterface
    public void saveSettings(String layout) {
        if ("cocoon".equals(layout) || "romm".equals(layout) || "alias".equals(layout)) {
            prefs.edit().putString("layout", layout).apply();
        }
    }

    @JavascriptInterface
    public void logout() {
        prefs.edit()
                .remove("baseUrl")
                .remove("username")
                .remove("password")
                .remove("token")
                .remove("refreshToken")
                .remove("expiresAt")
                .remove("ssUser")
                .remove("ssPassword")
                .remove("ssDevId")
                .remove("ssDevPassword")
                .remove("sgdbKey")
                .apply();
    }

    @JavascriptInterface
    public void saveTheme(String theme) {
        prefs.edit().putString("theme", "dark".equals(theme) ? "dark" : "bright").apply();
    }

    @JavascriptInterface
    public void saveScraper(String user, String pass, String devId, String devPass, String sgdbKey) {
        prefs.edit()
                .putString("ssUser", user == null ? "" : user)
                .putString("ssPassword", pass == null ? "" : pass)
                .putString("ssDevId", devId == null ? "" : devId)
                .putString("ssDevPassword", devPass == null ? "" : devPass)
                .putString("sgdbKey", sgdbKey == null ? "" : sgdbKey)
                .apply();
    }

    @JavascriptInterface
    public void clearScraper() {
        prefs.edit()
                .remove("ssUser")
                .remove("ssPassword")
                .remove("ssDevId")
                .remove("ssDevPassword")
                .remove("sgdbKey")
                .apply();
    }

    @JavascriptInterface
    public String loginPassword(String baseUrl, String username, String password) {
        try {
            String base = normalizeBaseUrl(baseUrl);
            String body = "grant_type=password&username=" + urlEnc(username)
                    + "&password=" + urlEnc(password)
                    + "&scope=" + urlEnc("roms.read platforms.read assets.read collections.read");
            HttpResult result = send("POST", base + "/api/token", body, "application/x-www-form-urlencoded", false);
            if (!result.ok()) return error(result, false);
            JSONObject token = new JSONObject(result.body);
            saveToken(base, username, password, token);
            return okSession();
        } catch (Exception err) {
            return errorMessage(err.getMessage(), false);
        }
    }

    @JavascriptInterface
    public String loginToken(String baseUrl, String token) {
        try {
            String base = normalizeBaseUrl(baseUrl);
            String trimmed = token == null ? "" : token.trim();
            prefs.edit()
                    .putString("baseUrl", base)
                    .putString("token", trimmed)
                    .remove("refreshToken")
                    .putLong("expiresAt", 0L)
                    .apply();
            HttpResult check = authed("GET", base + "/api/heartbeat");
            if (!check.ok() && !check.logout && (check.status == 404 || check.status == 405)) {
                check = authed("GET", base + "/api/platforms");
            }
            if (check.logout) {
                logout();
                return error(check, true);
            }
            if (!check.ok()) {
                logout();
                return error(check, false);
            }
            return okSession();
        } catch (Exception err) {
            return errorMessage(err.getMessage(), false);
        }
    }

    @JavascriptInterface
    public String loginPair(String baseUrl, String code) {
        try {
            String base = normalizeBaseUrl(baseUrl);
            String body = new JSONObject().put("code", code == null ? "" : code.trim()).toString();
            HttpResult result = send("POST", base + "/api/client-tokens/exchange", body, "application/json", false);
            if (!result.ok()) return error(result, false);
            JSONObject json = new JSONObject(result.body);
            String raw = json.optString("raw_token", "");
            if (raw.isEmpty()) return errorMessage("Pairing did not return a token.", false);
            prefs.edit()
                    .putString("baseUrl", base)
                    .putString("token", raw)
                    .remove("username")
                    .remove("password")
                    .remove("refreshToken")
                    .putLong("expiresAt", 0L)
                    .apply();
            return okSession();
        } catch (Exception err) {
            return errorMessage(err.getMessage(), false);
        }
    }

    @JavascriptInterface
    public String platforms() {
        try {
            HttpResult result = authed("GET", base() + "/api/platforms");
            if (result.logout) return error(result, true);
            if (!result.ok()) return error(result, false);
            JSONObject payload = basePayload(result, false);
            payload.put("platforms", new JSONArray(result.body));
            return payload.toString();
        } catch (Exception err) {
            return errorMessage(err.getMessage(), false);
        }
    }

    @JavascriptInterface
    public String roms(int platformId, String search, int limit, int offset) {
        return gallery(galleryQuery(platformId, search, limit, offset), plainQuery(platformId, search, limit, offset));
    }

    @JavascriptInterface
    public String gallery(String firstPath, String retryPath) {
        try {
            HttpResult first = authed("GET", base() + safePath(firstPath));
            if (first.logout) return error(first, true);
            HttpResult chosen = first;
            if ((first.status == 500 || first.status == 422 || first.status == 400) && retryPath != null && !retryPath.trim().isEmpty()) {
                chosen = authed("GET", base() + safePath(retryPath));
                if (chosen.logout) return error(chosen, true);
            }
            if (!chosen.ok()) return error(chosen, false);
            JSONObject payload = basePayload(chosen, false);
            payload.put("page", parseJson(chosen.body));
            return payload.toString();
        } catch (Exception err) {
            return errorMessage(err.getMessage(), false);
        }
    }

    @JavascriptInterface
    public String collections() {
        try {
            HttpResult manual = authed("GET", base() + "/api/collections");
            if (manual.logout) return error(manual, true);
            if (!manual.ok()) return error(manual, false);
            JSONObject payload = basePayload(manual, false);
            payload.put("manual", parseList(manual.body));
            payload.put("smart", optionalList("/api/collections/smart"));
            payload.put("virtual", optionalList("/api/collections/virtual?type=collection"));
            return payload.toString();
        } catch (Exception err) {
            return errorMessage(err.getMessage(), false);
        }
    }

    @JavascriptInterface
    public String listFolders() {
        try {
            DocumentFile tree = tree();
            JSONArray names = new JSONArray();
            if (tree != null) {
                DocumentFile[] children = tree.listFiles();
                if (children != null) {
                    for (DocumentFile child : children) {
                        if (child.isDirectory() && child.getName() != null) names.put(child.getName());
                    }
                }
            }
            return names.toString();
        } catch (Exception err) {
            return "[]";
        }
    }

    @JavascriptInterface
    public void fetchCover(final int id, final String urlsJson) {
        fetchImage(String.valueOf(id), urlsJson);
    }

    @JavascriptInterface
    public void fetchImage(final String id, final String urlsJson) {
        io.execute(() -> {
            try {
                JSONArray urls = parseUrlList(urlsJson);
                String base = base();
                for (int i = 0; i < urls.length(); i++) {
                    String url = urls.optString(i, "");
                    if (url.isEmpty()) continue;
                    boolean auth = sameHost(base, url);
                    HttpResult result = send("GET", url, null, null, auth, null);
                    if (auth && (result.status == 401 || result.status == 403)) {
                        result = send("GET", url, null, null, false, null);
                    }
                    if (!result.ok() || result.bytes == null || result.bytes.length == 0 || result.bytes.length > 2000000) {
                        continue;
                    }
                    String mime = imageType(result);
                    if (mime.isEmpty()) continue;
                    String data = "data:" + mime + ";base64," + Base64.encodeToString(result.bytes, Base64.NO_WRAP);
                    emit("CocoonShelf.onCover(" + JSONObject.quote(id) + "," + JSONObject.quote(data) + ")");
                    return;
                }
            } catch (Exception ignored) {
                /* a missing cover leaves the empty frame */
            }
        });
    }

    @JavascriptInterface
    public void fetchText(final int id, final String url, final String bearer) {
        io.execute(() -> {
            try {
                HttpResult result = send("GET", url, null, null, false, bearer);
                String body = result.body == null ? "" : result.body;
                if (body.length() > 200000) body = body.substring(0, 200000);
                emit("CocoonShelf.onText(" + id + "," + result.status + "," + JSONObject.quote(body) + ")");
            } catch (Exception err) {
                emit("CocoonShelf.onText(" + id + ",0,\"\")");
            }
        });
    }

    @JavascriptInterface
    public void downloadRom(final String url, final String folder, final String fileName) {
        io.execute(() -> streamDownload(url, folder, fileName));
    }

    private void streamDownload(String url, String folder, String fileName) {
        DocumentFile tree = tree();
        if (tree == null || !tree.canWrite()) {
            emitProgress("Choose a ROM folder in Settings.");
            return;
        }
        HttpURLConnection conn = null;
        try {
            conn = open(url, "GET", null, null, true);
            int status = conn.getResponseCode();
            if (status == 401 && refresh()) {
                conn.disconnect();
                conn = open(url, "GET", null, null, true);
                status = conn.getResponseCode();
            }
            if (status == 401) {
                emit("CocoonShelf.onLoggedOut(" + JSONObject.quote("Sign-in expired. Connect again.") + ")");
                return;
            }
            if (status < 200 || status >= 300) {
                emitProgress("Download failed (" + status + ").");
                return;
            }
            DocumentFile dir = tree.findFile(folder);
            if (dir == null || !dir.isDirectory()) dir = tree.createDirectory(folder);
            if (dir == null) {
                emitProgress("Could not create " + folder + ".");
                return;
            }
            DocumentFile existing = dir.findFile(fileName);
            if (existing != null) existing.delete();
            DocumentFile dest = dir.createFile("application/octet-stream", fileName);
            if (dest == null) {
                emitProgress("Could not create " + fileName + ".");
                return;
            }
            long total = conn.getContentLengthLong();
            InputStream in = conn.getInputStream();
            OutputStream out = context.getContentResolver().openOutputStream(dest.getUri());
            if (out == null) {
                emitProgress("Could not write " + fileName + ".");
                return;
            }
            byte[] buf = new byte[65536];
            long read = 0L;
            long lastEmit = 0L;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                read += n;
                long now = System.currentTimeMillis();
                if (now - lastEmit > 250) {
                    lastEmit = now;
                    emitProgress(formatBytes(read) + (total > 0 ? " / " + formatBytes(total) : ""));
                }
            }
            out.flush();
            out.close();
            in.close();
            emitProgress(formatBytes(read) + (total > 0 ? " / " + formatBytes(total) : ""));
            emit("CocoonShelf.onDownloadDone(" + JSONObject.quote("Saved") + ")");
        } catch (Exception err) {
            emitProgress(err.getMessage() == null ? "Download failed." : err.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpResult authed(String method, String url) {
        ensureFresh();
        HttpResult result = send(method, url, null, null, true);
        if (result.status == 401 && refresh()) {
            result = send(method, url, null, null, true);
        }
        if (result.status == 401) result.logout = true;
        return result;
    }

    private void ensureFresh() {
        String token = prefs.getString("token", "");
        if (token == null || token.isEmpty() || token.startsWith("rmm_")) return;
        long expiresAt = prefs.getLong("expiresAt", 0L);
        if (expiresAt > 0 && expiresAt < System.currentTimeMillis() + 30000L) refresh();
    }

    private boolean refresh() {
        String refreshToken = prefs.getString("refreshToken", "");
        if (refreshToken == null || refreshToken.isEmpty()) return false;
        try {
            String body = "grant_type=refresh_token&refresh_token=" + urlEnc(refreshToken);
            HttpResult result = send("POST", base() + "/api/token", body, "application/x-www-form-urlencoded", false);
            if (!result.ok()) return false;
            JSONObject token = new JSONObject(result.body);
            String access = token.optString("access_token", "");
            if (access.isEmpty()) return false;
            long expires = token.optLong("expires", 0L);
            prefs.edit()
                    .putString("token", access)
                    .putString("refreshToken", token.optString("refresh_token", refreshToken))
                    .putLong("expiresAt", expires > 0 ? System.currentTimeMillis() + expires * 1000L : 0L)
                    .apply();
            return true;
        } catch (Exception err) {
            return false;
        }
    }

    private void saveToken(String base, String username, String password, JSONObject token) {
        long expires = token.optLong("expires", 0L);
        prefs.edit()
                .putString("baseUrl", base)
                .putString("username", username)
                .putString("password", password)
                .putString("token", token.optString("access_token", ""))
                .putString("refreshToken", token.optString("refresh_token", ""))
                .putLong("expiresAt", expires > 0 ? System.currentTimeMillis() + expires * 1000L : 0L)
                .apply();
    }

    private HttpResult send(String method, String url, String body, String contentType, boolean withAuth) {
        return send(method, url, body, contentType, withAuth, null);
    }

    private HttpResult send(String method, String url, String body, String contentType, boolean withAuth, String bearer) {
        HttpResult result = new HttpResult();
        HttpURLConnection conn = null;
        try {
            conn = open(url, method, body, contentType, withAuth, bearer);
            result.status = conn.getResponseCode();
            int hops = 0;
            String current = url;
            while (result.status >= 300 && result.status < 400 && hops < 3) {
                String location = conn.getHeaderField("Location");
                if (location == null || location.isEmpty()) break;
                URL next = new URL(new URL(current), location);
                boolean stay = sameHost(current, next.toString());
                conn.disconnect();
                current = next.toString();
                conn = open(current, method, null, null, withAuth && stay, stay ? bearer : null);
                result.status = conn.getResponseCode();
                hops += 1;
            }
            result.contentType = conn.getContentType();
            InputStream stream = result.status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            result.bytes = readBytes(stream);
            result.body = new String(result.bytes, StandardCharsets.UTF_8);
        } catch (Exception err) {
            result.status = 0;
            result.error = err.getMessage();
            result.body = "";
            result.bytes = new byte[0];
        } finally {
            if (conn != null) conn.disconnect();
        }
        return result;
    }

    private HttpURLConnection open(String url, String method, String body, String contentType, boolean withAuth) throws Exception {
        return open(url, method, body, contentType, withAuth, null);
    }

    private HttpURLConnection open(String url, String method, String body, String contentType, boolean withAuth, String bearer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(method);
        boolean tokenEndpoint = url.contains("/api/token");
        if (bearer != null && !bearer.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + bearer);
        } else if (withAuth && !tokenEndpoint) {
            String token = prefs.getString("token", "");
            if (token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType == null ? "application/json" : contentType);
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = conn.getOutputStream();
            out.write(bytes);
            out.close();
        }
        return conn;
    }

    private DocumentFile tree() {
        String saved = prefs.getString("romRootUri", "");
        if (saved == null || saved.isEmpty()) return null;
        return DocumentFile.fromTreeUri(context, Uri.parse(saved));
    }

    private JSONObject sessionJson() {
        JSONObject session = new JSONObject();
        try {
            session.put("baseUrl", prefs.getString("baseUrl", ""));
            session.put("username", prefs.getString("username", ""));
            session.put("password", prefs.getString("password", ""));
            session.put("token", prefs.getString("token", ""));
            session.put("refreshToken", prefs.getString("refreshToken", ""));
            session.put("expiresAt", prefs.getLong("expiresAt", 0L));
            session.put("layout", prefs.getString("layout", "cocoon"));
            session.put("theme", "dark".equals(prefs.getString("theme", "bright")) ? "dark" : "bright");
            session.put("ssUser", prefs.getString("ssUser", ""));
            session.put("ssPassword", prefs.getString("ssPassword", ""));
            session.put("ssDevId", prefs.getString("ssDevId", ""));
            session.put("ssDevPassword", prefs.getString("ssDevPassword", ""));
            session.put("sgdbKey", prefs.getString("sgdbKey", ""));
            session.put("romRootLabel", prefs.getString("romRootLabel", ""));
            session.put("hasRomRoot", !prefs.getString("romRootUri", "").isEmpty());
        } catch (JSONException ignored) {
            /* the fields above are plain strings */
        }
        return session;
    }

    private String okSession() throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("ok", true);
        payload.put("logout", false);
        payload.put("status", 200);
        payload.put("session", sessionJson());
        return payload.toString();
    }

    private JSONObject basePayload(HttpResult result, boolean logout) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("ok", result.ok());
        payload.put("logout", logout);
        payload.put("status", result.status);
        if (!result.ok()) payload.put("error", detail(result));
        return payload;
    }

    private String error(HttpResult result, boolean logout) {
        try {
            return basePayload(result, logout).toString();
        } catch (JSONException err) {
            return errorMessage(err.getMessage(), logout);
        }
    }

    private String errorMessage(String message, boolean logout) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("ok", false);
            payload.put("logout", logout);
            payload.put("status", 0);
            payload.put("error", message == null ? "Request failed." : message);
            return payload.toString();
        } catch (JSONException err) {
            return "{\"ok\":false,\"logout\":" + logout + ",\"error\":\"Request failed.\"}";
        }
    }

    private String detail(HttpResult result) {
        if (result.error != null && !result.error.isEmpty() && (result.body == null || result.body.isEmpty())) {
            return result.error;
        }
        if (result.body != null && !result.body.isEmpty()) {
            try {
                JSONObject json = new JSONObject(result.body);
                if (json.has("detail")) return String.valueOf(json.get("detail"));
            } catch (JSONException ignored) {
                /* plain text body */
            }
            return result.body.length() > 300 ? result.body.substring(0, 300) : result.body;
        }
        return "HTTP " + result.status;
    }

    private void emit(String call) {
        main.post(() -> host.evalJs(call));
    }

    private void emitProgress(String text) {
        try {
            emit("CocoonShelf.onDownloadProgress(" + JSONObject.quote(text) + ")");
        } catch (Exception ignored) {
            /* quote failed */
        }
    }

    private String base() {
        return prefs.getString("baseUrl", "").replaceAll("/+$", "");
    }

    private JSONArray optionalList(String path) {
        try {
            HttpResult result = authed("GET", base() + path);
            if (!result.ok() || result.logout) return new JSONArray();
            return parseList(result.body);
        } catch (Exception err) {
            return new JSONArray();
        }
    }

    private static Object parseJson(String body) throws JSONException {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("[")) return new JSONArray(trimmed);
        if (trimmed.startsWith("{")) return new JSONObject(trimmed);
        return new JSONObject();
    }

    private static JSONArray parseList(String body) throws JSONException {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("[")) return new JSONArray(trimmed);
        if (trimmed.startsWith("{")) {
            JSONObject obj = new JSONObject(trimmed);
            if (obj.has("items") && obj.get("items") instanceof JSONArray) return obj.getJSONArray("items");
            if (obj.has("collections") && obj.get("collections") instanceof JSONArray) return obj.getJSONArray("collections");
        }
        return new JSONArray();
    }

    private static String safePath(String path) {
        String trimmed = path == null ? "" : path.trim();
        if (!trimmed.startsWith("/api/") || trimmed.contains("://") || trimmed.contains("..")) {
            throw new IllegalArgumentException("Unsupported path.");
        }
        return trimmed;
    }

    private static String galleryQuery(int platformId, String search, int limit, int offset) {
        String query = "/api/roms?limit=" + limit + "&offset=" + offset
                + "&order_by=name&order_dir=asc&group_by_meta_id=false&platform_ids=" + platformId;
        if (search != null && !search.isEmpty()) query += "&search_term=" + urlEnc(search);
        return query;
    }

    private static String plainQuery(int platformId, String search, int limit, int offset) {
        String query = "/api/roms?limit=" + limit + "&offset=" + offset + "&platform_id=" + platformId;
        if (search != null && !search.isEmpty()) query += "&search_term=" + urlEnc(search);
        return query;
    }

    private static String normalizeBaseUrl(String url) {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Server URL is required.");
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) trimmed = "http://" + trimmed;
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    private static String urlEnc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException err) {
            return "";
        }
    }

    private static boolean sameHost(String left, String right) {
        try {
            return new URL(left).getHost().equalsIgnoreCase(new URL(right).getHost());
        } catch (Exception err) {
            return false;
        }
    }

    private static byte[] readBytes(InputStream stream) throws Exception {
        if (stream == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = stream.read(buf)) != -1) out.write(buf, 0, n);
        stream.close();
        return out.toByteArray();
    }

    private static String sniff(byte[] bytes) {
        if (bytes == null) return "";
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50) return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) return "image/jpeg";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') return "image/webp";
        if (bytes.length >= 4 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
        int sniffLen = Math.min(bytes.length, 240);
        String head = new String(bytes, 0, sniffLen, StandardCharsets.UTF_8).toLowerCase();
        if (head.contains("<svg")) return "image/svg+xml";
        return "";
    }

    private static String imageType(HttpResult result) {
        String sniffed = sniff(result.bytes);
        if (!sniffed.isEmpty()) return sniffed;
        if (result.contentType != null && result.contentType.startsWith("image/")) {
            int semi = result.contentType.indexOf(';');
            return semi > 0 ? result.contentType.substring(0, semi) : result.contentType;
        }
        return "";
    }

    private static JSONArray parseUrlList(String urlsJson) {
        if (urlsJson == null || urlsJson.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(urlsJson);
        } catch (JSONException err) {
            JSONArray one = new JSONArray();
            if (urlsJson.startsWith("http")) one.put(urlsJson);
            return one;
        }
    }

    private static String formatBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", n / (1024.0 * 1024.0));
        return String.format(java.util.Locale.US, "%.2f GB", n / (1024.0 * 1024.0 * 1024.0));
    }

    private static final class HttpResult {
        int status;
        String body = "";
        String error;
        String contentType;
        byte[] bytes = new byte[0];
        boolean logout;

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }
}
