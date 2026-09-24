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
        versionCode = 1
        versionName = "0.1.0"

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

    testImplementation("junit:junit:4.13.2")
}
