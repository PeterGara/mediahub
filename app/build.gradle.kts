plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.petergara.mediahub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.petergara.mediahub"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Lokálny REST server (loopback) pre AI príkazy
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // OpenAI-compatible volania do AiGateway
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Čítanie/zápis tagov (ID3, Vorbis, MP4) – offline
    implementation("net.jthink:jaudiotagger:3.0.1")

    // Prehrávač hudby (ExoPlayer + MediaSession = notifikácia, lock screen)
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}
