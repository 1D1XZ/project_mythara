plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mythara.wear"
    // Wear OS 3 = API 30; we floor here so we run on the original
    // Pixel Watch + every newer Wear OS device. compileSdk/target track
    // the phone-app module so the toolchain matches.
    compileSdk = 36
    defaultConfig {
        // MUST match the phone app's applicationId exactly. The Wearable
        // Data Layer delivers MessageClient.sendMessage only to the app
        // with the *same package name* on the peer node — a mismatched
        // id means the phone's WearableListenerService never fires and
        // PTT silently goes nowhere. So: base id == phone base id, and
        // the debug buildType carries the same `.debug` suffix below.
        applicationId = "com.mythara"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            // Same `.debug` suffix as the phone-app debug build → both
            // resolve to `com.mythara.debug`, so the system pairs them
            // and the Data Layer routes between them.
            applicationIdSuffix = ".debug"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.watchface)
    implementation(libs.androidx.wear.watchface.complications.data.source)
    implementation(libs.play.services.wearable)
    // Wear OS Health Services — the supported HR API for Galaxy Watch.
    // Replaces our legacy SensorManager.TYPE_HEART_RATE path which
    // never fires onSensorChanged on most Samsung watches.
    implementation(libs.androidx.health.services.client)
    debugImplementation(libs.androidx.ui.tooling)
}
