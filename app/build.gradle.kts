plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tifusi.vpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tifusi.vpn"
        minSdk = 26
        targetSdk = 34
        // CI passes its run number, which is also the release tag (v<number>), so the About tab
        // can compare this build against the latest release.
        val buildNumber = providers.gradleProperty("tifusi.buildNumber").getOrElse("1").toInt()
        versionCode = buildNumber
        versionName = buildNumber.toString()

        buildConfigField("String", "DEFAULT_PANEL_URL", "\"${providers.gradleProperty("tifusi.panelUrl").getOrElse("")}\"")
        buildConfigField("String", "SUPPORT_TELEGRAM", "\"${providers.gradleProperty("tifusi.supportTelegram").getOrElse("")}\"")
        buildConfigField("String", "UPDATE_REPO", "\"${providers.gradleProperty("tifusi.updateRepo").getOrElse("")}\"")

        // Phones only: dropping the emulator (x86) native libraries of WireGuard and ML Kit
        // roughly halves the APK. armeabi-v7a keeps older 32-bit Samsung models working.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // A fixed debug key kept in the repo, so every CI build carries the same signature and installs
    // over the previous one. It protects nothing secret; it only keeps in-app updates working.
    signingConfigs {
        getByName("debug") {
            storeFile = file("tifusi-debug.p12")
            storeType = "pkcs12"
            storePassword = "tifusi-debug"
            keyAlias = "tifusi"
            keyPassword = "tifusi-debug"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    // Per-app language switching (Persian/English) with persistence on Android 8-12.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    // Local persistence for saved VPN profiles
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // WireGuard official Android backend (VpnService based tunnel)
    implementation("com.wireguard.android:tunnel:1.0.20230706")

    // QR scanning of the panel's subscription codes. The bundled ML Kit model needs no Google Play
    // services download, which is unreliable from Iran.
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
