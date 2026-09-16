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
        val versionBase = providers.gradleProperty("tifusi.versionBase").getOrElse("0").toInt()
        versionCode = buildNumber
        versionName = "1.${(buildNumber - versionBase).coerceAtLeast(0)}"
        buildConfigField("int", "VERSION_BASE", "$versionBase")

        buildConfigField("String", "DEFAULT_PANEL_URL", "\"${providers.gradleProperty("tifusi.panelUrl").getOrElse("")}\"")
        buildConfigField("String", "SUPPORT_TELEGRAM", "\"${providers.gradleProperty("tifusi.supportTelegram").getOrElse("")}\"")
        buildConfigField("String", "UPDATE_REPO", "\"${providers.gradleProperty("tifusi.updateRepo").getOrElse("")}\"")

        // Phones only: dropping the emulator (x86) native libraries of the Xray core and ML Kit
        // roughly halves the APK. armeabi-v7a keeps older 32-bit Samsung models working.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // The UI ships in these two languages only (see res/xml/locales_config.xml). Without this,
        // every AppCompat and Material string is carried in ~80 more.
        resourceConfigurations += listOf("en", "fa")
    }

    // One APK per phone architecture next to the universal one. The universal build stays the
    // published tifusi-vpn.apk so a wrong pick can never leave someone unable to install; the
    // per-ABI files are about half its size and are what the in-app updater picks when the
    // release carries one for this phone.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    // The Xray AAR bundles ~28 MB of geoip/geosite databases. The VLESS config routes by plain
    // CIDRs and never loads them, so they stay out of the APK.
    androidResources {
        ignoreAssetsPatterns += listOf("!geoip.dat", "!geosite.dat", "!geoip-only-cn-private.dat")
    }

    // The Xray core is a ~35 MB native library per ABI. Stored compressed it roughly halves the
    // download, which matters more on slow, filtered connections than the extraction at install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
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
            // Published builds are release builds signed with the same fixed key as debug, so they
            // install over earlier debug releases. Play Protect scans debuggable APKs from outside
            // the store much longer, which stalled in-app updates.
            signingConfig = signingConfigs.getByName("debug")
            // R8 drops the code nothing reaches — most of it the unused half of Compose and the
            // ~1000 icons of material-icons-extended — and shrinkResources then drops the
            // resources that code referenced. proguard-rules.pro keeps the parts the Xray core
            // reaches through JNI, which R8 cannot see.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    // Xray core for VLESS/REALITY (2dust/AndroidLibXrayLite v26.9.9). Not in git: CI downloads it into
    // app/libs before building; for a local build, fetch the same release asset there first.
    implementation(files("libs/libv2ray.aar"))

    // QR scanning of the panel's subscription codes. The bundled ML Kit model needs no Google Play
    // services download, which is unreliable from Iran.
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only a stub in local unit tests; the real one lets the config builder run there.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
