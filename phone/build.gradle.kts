plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    // The Kotlin namespace differs from the watch's, but the applicationId MUST be identical: the
    // Wear Data Layer only routes messages/data between two apps with the SAME package name AND the
    // SAME signing certificate. Both modules use the default debug keystore on this machine; see
    // docs/AI_SETUP.md for how to keep that true for any other signing setup.
    namespace = "com.peaceantz.stagescope.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.peaceantz.stagescope"
        // Android 13+: runtime notification permission, on-device SpeechRecognizer and file-sourced
        // recognition are all available. The owner's phone is a Pixel 10 (a new Android version);
        // the actual installed version is verified at install time, see docs/AI_SETUP.md.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // android.jar stubs return defaults instead of throwing, so pure-Kotlin classes that
            // touch trivial platform helpers (Log, TextUtils) stay unit-testable on the JVM.
            isReturnDefaultValues = true
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
    implementation(project(":shared"))

    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Wear OS Data Layer (phone <-> watch) and Google user-data authorization (Gmail/Calendar).
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // Durable background work: WearableListenerService persists + acks, then hands off to WorkManager.
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // One HTTP stack for all four provider adapters, Gmail and Calendar (fixed approved hosts only).
    // Pinned to 5.3.2: OkHttp 5.5.0's Android artifact requires compileSdk 37, and this project stays on
    // compileSdk 36 / AGP 8.13 (verify-then-pin, not latest-always).
    implementation("com.squareup.okhttp3:okhttp:5.3.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
