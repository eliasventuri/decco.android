plugins {
    id("com.android.application")
}

android {
    namespace = "com.decco.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.decco.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        multiDexEnabled = true
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // NanoHTTPD (lightweight HTTP server)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // LibTorrent4j (torrent engine)
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-34")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-34")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-34")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:2.1.0-34")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-34")

    // Media3 ExoPlayer — native video player
    val media3Version = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

    // Media3 Software Decoder Extensions (Option B: Universal Support)
    // Using Jellyfin pre-compiled version which includes native binaries for FFmpeg (Audio)
    val jellyfinMedia3Version = "1.6.1+2"
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$jellyfinMedia3Version")
}
