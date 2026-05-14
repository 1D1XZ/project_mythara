// Mythara TACTICAL watch face — Watch Face Format (WFF).
//
// WFF watch faces are RESOURCE-ONLY bundles: no Kotlin/Java, hasCode=false.
// The Wear OS renderer parses res/raw/watchface.xml and draws everything.
// This module exists separately from :wear (which holds the PTT app code)
// because a WFF face must ship as its own code-free APK — and because
// Wear OS 5+ blocks the legacy AndroidX Canvas watch-face renderer.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mythara.watchface"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mythara.watchface"
        // WFF v1 floors at API 33 / Wear OS 4. The renderer is the system's.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            // Keep resources — WFF needs every raw/xml/drawable intact.
            isShrinkResources = false
        }
    }
}
