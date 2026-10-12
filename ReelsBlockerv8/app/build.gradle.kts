// App-level build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace   = "com.example.reelsblocker"
    compileSdk  = 35                    // Android 15 (latest as of 2026)

    defaultConfig {
        applicationId  = "com.example.reelsblocker"
        minSdk         = 26             // Android 8.0 — covers 99%+ of active devices
        targetSdk      = 35
        versionCode    = 1
        versionName    = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable      = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false   // We use manual findViewById for simplicity
        buildConfig = true
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Material 3 — for Theme.Material3 and tonal buttons
    implementation(libs.material)

    // CardView (used in layouts)
    implementation(libs.androidx.cardview)

    // Lifecycle (optional but recommended for future expansion)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
