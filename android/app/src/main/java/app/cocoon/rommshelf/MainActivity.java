package app.cocoon.rommshelf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity implements RommBridge.Host {
    private static final String TAG = "CocoonRommShelf";
    private static final int REQ_TREE = 41;
    private static final String PAGE_ORIGIN = "https://shelf.cocoon.local/";

    private WebView webView;
    private int htmlLength;
    private volatile boolean scriptParsed;
    private boolean bootLogged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.web);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        webView.setBackgroundColor(0xFF0E1114);
        webView.addJavascriptInterface(new RommBridge(this, this), "RommNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                scheduleBootLog();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                return true;
            }
        });
        String html = readPage();
        htmlLength = html.length();
        webView.loadDataWithBaseURL(PAGE_ORIGIN, html, "text/html", "UTF-8", null);
        new Handler(Looper.getMainLooper()).postDelayed(this::checkFallback, 2000);
    }

    @Override
    public void evalJs(String script) {
        if (webView == null) return;
        webView.evaluateJavascript(script, null);
    }

    @Override
    public void pickTree() {
        runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, REQ_TREE);
        });
    }

    @Override
    public void finishApp() {
        runOnUiThread(this::finish);
    }

    @Override
    public void noteScriptParsed() {
        scriptParsed = true;
        runOnUiThread(this::logBoot);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException err) {
            Log.w(TAG, "Could not persist ROM root", err);
        }
        DocumentFile tree = DocumentFile.fromTreeUri(this, uri);
        String label = tree != null && tree.getName() != null ? tree.getName() : uri.getLastPathSegment();
        getSharedPreferences("cocoon_romm_shelf", MODE_PRIVATE)
                .edit()
                .putString("romRootUri", uri.toString())
                .putString("romRootLabel", label == null ? "" : label)
                .apply();
        evalJs("CocoonShelf.onRomRoot(" + JSONObject.quote(label == null ? "" : label) + ")");
    }

    @Override
    public void onBackPressed() {
        forwardKey("back");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        String action = keyAction(event.getKeyCode());
        if (action == null) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            forwardKey(action);
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) return true;
        return super.dispatchKeyEvent(event);
    }

    private void forwardKey(String action) {
        if (webView == null) return;
        webView.evaluateJavascript("CocoonShelf.onHardwareKey(" + JSONObject.quote(action) + ")", null);
    }

    static String keyAction(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return "right";
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_START:
                return "confirm";
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_ESCAPE:
                return "back";
            default:
                return null;
        }
    }

    private String readPage() {
        try {
            InputStream in = getResources().openRawResource(R.raw.shelf);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception err) {
            Log.e(TAG, "Missing shelf page", err);
            return "<html><body><div id=\"boot-fallback\">Missing shelf page.</div></body></html>";
        }
    }

    private void scheduleBootLog() {
        new Handler(Looper.getMainLooper()).postDelayed(this::logBoot, 400);
    }

    private void logBoot() {
        if (bootLogged) return;
        if (!scriptParsed) return;
        bootLogged = true;
        Log.i(TAG, "boot version=" + BuildConfig.VERSION_NAME + " htmlLength=" + htmlLength + " scriptParsed=true");
    }

    private void checkFallback() {
        if (isFinishing() || webView == null) return;
        if (!bootLogged) {
            bootLogged = true;
            Log.i(TAG, "boot version=" + BuildConfig.VERSION_NAME + " htmlLength=" + htmlLength + " scriptParsed=" + scriptParsed);
        }
        webView.evaluateJavascript(
                "(function(){var el=document.getElementById('boot-fallback');return !!(el&&!el.hidden);})()",
                value -> {
                    if (!"true".equals(value)) return;
                    new AlertDialog.Builder(this)
                            .setTitle("Cocoon RomM Shelf")
                            .setMessage("The page is still on its startup fallback. The script did not run.")
                            .setPositiveButton("Close", null)
                            .show();
                }
        );
    }
}
