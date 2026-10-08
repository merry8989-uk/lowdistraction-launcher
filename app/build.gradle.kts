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
        versionCode = 12
        versionName = "1.11"
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
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// ---------------------------------------------------------------------------
// Internet lockdown guard.
//
// The app must never be able to reach the network. The manifest declares the
// INTERNET permission with tools:node="remove", which strips it from the
// merged manifest. This task makes sure nobody ever quietly drops that guard:
// the build fails if INTERNET appears in the manifest without it.
// ---------------------------------------------------------------------------
tasks.register("checkNoInternet") {
    group = "verification"
    description = "Fails the build if the INTERNET permission is not stripped."
    doLast {
        val manifest = file("src/main/AndroidManifest.xml")
        if (!manifest.exists()) return@doLast
        manifest.readLines().forEachIndexed { index, line ->
            if (line.contains("android.permission.INTERNET") &&
                !line.contains("tools:node=\"remove\"")
            ) {
                throw GradleException(
                    "AndroidManifest.xml line ${index + 1}: the INTERNET permission must never " +
                        "be grantable. Keep it declared with tools:node=\"remove\" so it is " +
                        "stripped from the merged manifest."
                )
            }
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("checkNoInternet")
}
