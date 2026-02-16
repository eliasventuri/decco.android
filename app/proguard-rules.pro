# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\elias\AppData\Local\Android\Sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Keep LibTorrent (JNI)
-keep class com.frostwire.jlibtorrent.** { *; }
-keep class org.libtorrent4j.** { *; }

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep ExoPlayer
-keep class androidx.media3.** { *; }

# Keep Javascript Bridge
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Generics
-keepattributes Signature
-keepattributes *Annotation*
