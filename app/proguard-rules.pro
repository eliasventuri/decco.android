# Decco Android ProGuard Rules

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep LibTorrent4j
-keep class org.libtorrent4j.** { *; }
-keep class org.libtorrent4j.swig.** { *; }

# Keep FFmpegKit
-keep class com.arthenica.ffmpegkit.** { *; }

# Keep DeccoJsBridge methods for WebView
-keepclassmembers class com.decco.android.MainActivity$DeccoJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
