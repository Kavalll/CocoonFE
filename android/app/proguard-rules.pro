# Debug builds do not minify. Keep the JavascriptInterface if release minify is turned on later.
-keepclassmembers class app.cocoon.rommshelf.RommBridge {
    @android.webkit.JavascriptInterface <methods>;
}
