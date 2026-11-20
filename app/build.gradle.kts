plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.supplices.kemimotowear"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.supplices.kemimotowear"
        minSdk = 30
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.0"
    }

    val keystorePath = System.getenv("OPENKEMI_KEYSTORE_PATH")
    val keystorePassword = System.getenv("OPENKEMI_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("OPENKEMI_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("OPENKEMI_KEY_PASSWORD")
    val hasReleaseSigning = !keystorePath.isNullOrBlank() &&
        !keystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
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
            // Fail-closed: never fall back to the public Android debug keystore for release builds.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
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
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Wear OS Compose (Material)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.compose.ui.tooling)

    // Horologist UI helpers for Wear OS
    implementation(libs.horologist.compose.layout)

    // Wear OS Tiles & ProtoLayout
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material)

    // Watchface Complications & Concurrency
    implementation(libs.wear.watchface.complications)
    implementation(libs.guava)

    // Security & Encrypted SharedPreferences (for BLE MAC / IRK metadata storage)
    implementation(libs.androidx.security.crypto)
}
