plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The VRCX version string comes from web/Version so GetVersion() matches upstream exactly ("VRCX <version>").
val vrcxVersion: String = rootProject.file("../web/Version").readText().trim()

// The app's own version comes from VERSION at the repository root: MAJOR.MINOR.PATCH, or
// MAJOR.MINOR.PATCH-alpha.N / -beta.N / -rc.N for a prerelease.
val appVersion: String = rootProject.file("../VERSION").readText().trim()

// versionCode must grow with every release, and a prerelease must sort below the release it precedes:
// MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100 + stage, where stage is alpha.N = N, beta.N = 33 + N,
// rc.N = 66 + N (N from 1 to 32) and 99 for the release itself.
val appVersionCode: Int = run {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?$""").matchEntire(appVersion)
        ?: error("VERSION must be MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-(alpha|beta|rc).N, not '$appVersion'")
    val (major, minor, patch, label, ordinal) = match.destructured
    require(minor.toInt() < 100 && patch.toInt() < 100) { "MINOR and PATCH must be below 100 in '$appVersion'" }
    val stage = if (label.isEmpty()) {
        99
    } else {
        require(ordinal.toInt() in 1..32) { "The prerelease number in '$appVersion' must be 1 to 32" }
        mapOf("alpha" to 0, "beta" to 33, "rc" to 66).getValue(label) + ordinal.toInt()
    }
    major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + stage
}

// Release signing from VRCX_KEYSTORE_FILE, VRCX_KEYSTORE_PASSWORD, VRCX_KEY_ALIAS and VRCX_KEY_PASSWORD (defaults to the
// store password), set in the environment of a release build. Without them, assembleRelease builds an unsigned APK.
fun signingValue(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }

val releaseStoreFile: String? = signingValue("VRCX_KEYSTORE_FILE")

android {
    namespace = "io.github.vrcxandroid"
    compileSdk = 35

    defaultConfig {
        // The id of the 1.x app this one replaces, so its users get 2.x as an update (host/LegacyAppCleanup.kt).
        applicationId = "com.vrcx.android"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "VRCX_VERSION", "\"$vrcxVersion\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            // `npm run build:android` in web/ writes the page to web/build/android/web/, served as assets/web/.
            assets.srcDir("../../web/build/android")
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("VRCX_KEYSTORE_PASSWORD")
                keyAlias = signingValue("VRCX_KEY_ALIAS") ?: "vrcx-android"
                keyPassword = signingValue("VRCX_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Phones and tablets only: the bundled SQLite ships one native library per ABI (about 1.5-2 MB each).
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
        debug {
            // x86_64 as well, for the emulator.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = false
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-brotli:4.12.0")
    implementation("androidx.sqlite:sqlite-bundled:2.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // data
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    // appapi
    androidTestImplementation("junit:junit:4.13.2")
    // host
    // play-services-code-scanner pulls in Fragment 1.0.0, which breaks Activity Result permission requests
    // (lint InvalidFragmentVersionForActivityResult, fatal for release builds).
    implementation("androidx.fragment:fragment:1.8.5")
}
