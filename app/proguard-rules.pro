# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\elias\AppData\Local\Android\Sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Keep LibTorrent (JNI)
-keep class com.frostwire.jlibtorrent.** { *; }
-keep class org.libtorrent4j.** { *; }
-dontwarn com.frostwire.jlibtorrent.**
-dontwarn org.libtorrent4j.**

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Keep ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep JSON (used in version check)
-keep class org.json.** { *; }

# Keep App Classes (Safety measure for launch crash)
-keep class com.decco.android.** { *; }
-keep class com.decco.android.updater.Release { *; }
-keep class com.decco.android.updater.Asset { *; }

# Keep Javascript Bridge
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Generics
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Prevent warnings (Release builds can be strict)
-dontwarn javax.annotation.**
-dontwarn sun.misc.**

# Fix Retrofit/Gson ClassCastException with Suspend Functions
-keep class kotlin.coroutines.** { *; }
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class com.google.gson.** { *; }

# Keep data classes and interface for Retrofit
-keep interface com.decco.android.updater.GitHubApi { *; }
-keep class com.decco.android.updater.Release { *; }
-keep class com.decco.android.updater.Asset { *; }
-keep class com.decco.android.updater.UpdateManager { *; }

# Ensure metadata and signatures are kept
-keep class kotlin.Metadata { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
