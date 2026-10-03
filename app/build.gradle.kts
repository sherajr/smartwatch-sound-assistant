plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.peaceantz.stagescope"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.peaceantz.stagescope"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main").kotlin.srcDirs("src/main/kotlin")
        getByName("test").kotlin.srcDirs("src/test/kotlin")
    }
}

kotlin {
    jvmToolchain(17)
}

// The watch's double-pinch gesture (tap tempo) is reached through the Wear SDK's GestureInputManager, a system library
// on Wear OS 7+ watches (declared optional in AndroidManifest.xml). Its compile-time stub ships with SDK platform 37
// only; this app still compiles against API 36. The usual route, Wear Compose 1.7's Modifier.oneHandedGesture, needs
// AGP 9.1 + compileSdk 37 (its AAR metadata says so), which would move the whole toolchain for one feature -- so the
// stub is a compileOnly dependency instead (never packaged; the watch provides the real classes). Install it with:
//   sdkmanager "platforms;android-37.0"
val wearSdkStub = androidComponents.sdkComponents.sdkDirectory.map {
    it.file("platforms/android-37.0/optional/wear-sdk.jar")
}
tasks.named("preBuild") {
    doFirst {
        check(wearSdkStub.get().asFile.isFile) {
            "Missing ${wearSdkStub.get().asFile} -- install SDK Platform 37.0: sdkmanager \"platforms;android-37.0\" " +
                "(compile-time stub for the double-pinch gesture; compileSdk stays 36)."
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.wear.compose:compose-navigation:1.6.2")
    implementation("androidx.wear:wear-tooling-preview:1.0.0")

    // Tile surface: androidx.wear.tiles.TileService (system binding contract) building its layout
    // with the newer androidx.wear.protolayout builders -- see docs/STATUS.md for why this pairing
    // (not a separate "Wear Widgets" stack) is the current supported baseline.
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.tiles:tiles-material:1.6.2")
    implementation("androidx.concurrent:concurrent-futures:1.3.0")
    // Tiles' onTileRequest/onTileResourcesRequest return com.google.common.util.concurrent.
    // ListenableFuture. Without this, Guava's own published Gradle metadata silently substitutes
    // the lightweight `listenablefuture:1.0` stub with an intentionally *empty* artifact (a
    // known Guava/Gradle interop gotcha), and the type fails to resolve at all.
    implementation("com.google.guava:guava:33.7.1-android")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")
    debugImplementation("androidx.wear.tiles:tiles-tooling:1.6.2")
    implementation("androidx.wear.tiles:tiles-tooling-preview:1.6.2")

    // Watch-face complication data source, sharing SurfaceSummaryRepository -- no separate DB.
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // AI assistant (phone-backed). The watch app stays fully usable without any of this: the
    // instruments never touch the network, an account, or Google Play services.
    implementation(project(":shared"))
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    // Foreground check before measurement is resumed after a voice interaction.
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    // "Continue on phone": opens the stored task on the paired phone (RemoteActivityHelper).
    implementation("androidx.wear:wear-remote-interactions:1.2.0")
    // The watch's own text-input screen (RemoteInput) for "Type instead". 1.2.0 is the stable release (the alpha-only note this
    // project once carried is out of date); dictation itself needs no library -- it is a plain RecognizerIntent.
    implementation("androidx.wear:wear-input:1.2.0")
    // Double pinch (tap tempo) -- see wearSdkStub above.
    compileOnly(files(wearSdkStub))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
