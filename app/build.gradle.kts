plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is driven by environment variables so CI can inject the
// keystore through repository secrets. When they are absent (a plain local
// build), the release build falls back to the debug key so it still compiles.
val signingKeystorePath = System.getenv("SIGNING_KEYSTORE")
val signingKeystoreFile = signingKeystorePath?.let { file(it) }
val hasReleaseSigning = signingKeystoreFile != null &&
    signingKeystoreFile.exists() && signingKeystoreFile.length() > 0L

android {
    namespace = "com.lowdistraction.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lowdistraction.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "1.10"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = signingKeystoreFile
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
