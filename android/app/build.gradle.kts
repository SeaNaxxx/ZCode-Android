import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.zcodeandroid.app"
    buildToolsVersion = "36.1.0"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.zcodeandroid.app"
        minSdk = 24
        // targetSdk 28 is deliberate: it keeps exec() of the downloaded/extracted
        // Linux userland (proot -> node) permitted on Android 10+ (W^X policy),
        // matching the Termux/proot-distro ecosystem. Revisit with a native
        // library-dir launch strategy if a higher targetSdk becomes required.
        targetSdk = 28
        versionCode = 22
        versionName = "0.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "REMOTE_CONTROL_DEFAULT_URL", "\"https://zcode.z.ai/remote\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "REMOTE_CONTROL_DEFAULT_URL", "\"https://zcode.z.ai/remote\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Release engineering foundation (dependency audit 2: Missing):
            // distributed APKs must share ONE stable key so an update installs
            // over a previous release (owner-reported INSTALL_UPDATE failure
            // traced to per-runner debug keys). The keystore is provided out of
            // band: android/keystore.properties (gitignored) or CI env vars —
            // never committed. When absent, release builds fall back to the
            // debug key (CI builds stay green; release artifacts are only for
            // distribution once the stable key exists).
            val ksProps = Properties()
            val ksPropsFile = rootProject.file("keystore.properties")
            val envStoreFile = System.getenv("ZCODE_RELEASE_STORE_FILE")
            if (ksPropsFile.isFile) {
                ksPropsFile.inputStream().use { ksProps.load(it) }
            } else if (envStoreFile != null) {
                ksProps.setProperty("storeFile", envStoreFile)
                ksProps.setProperty("storePassword", System.getenv("ZCODE_RELEASE_STORE_PASSWORD"))
                ksProps.setProperty("keyAlias", System.getenv("ZCODE_RELEASE_KEY_ALIAS"))
                ksProps.setProperty("keyPassword", System.getenv("ZCODE_RELEASE_KEY_PASSWORD"))
            }
            if (ksProps.getProperty("storeFile") != null) {
                signingConfigs {
                    create("release") {
                        storeFile = file(ksProps.getProperty("storeFile"))
                        storePassword = ksProps.getProperty("storePassword")
                        keyAlias = ksProps.getProperty("keyAlias")
                        keyPassword = ksProps.getProperty("keyPassword")
                    }
                }
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // Native shell: channel-protocol client over ws://127.0.0.1:3030/ws.
    // OkHttp WebSocket (binary frames) — no other HTTP surface is added; all
    // product traffic stays on the loopback server.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Local runtime provisioning: tar extraction (rootfs/node/dist) + xz decoding.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
