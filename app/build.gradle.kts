import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

abstract class CopyVersionedApk : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val input: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @TaskAction
    fun copyApk() {
        val artifacts = builtArtifactsLoader.get().load(input.get())
            ?: error("Unable to read APK metadata from ${input.get().asFile}")
        val artifact = artifacts.elements.singleOrNull()
            ?: error("Expected one APK for ${artifacts.variantName}, found ${artifacts.elements.size}")
        val versionName = artifact.versionName?.takeIf(String::isNotBlank)
            ?: error("Missing version name for ${artifacts.variantName}")
        val outputDirectory = output.get().asFile
        outputDirectory.deleteRecursively()
        check(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
            "Unable to create $outputDirectory"
        }
        Files.copy(
            Path.of(artifact.outputFile),
            outputDirectory.resolve("vrcx-android-$versionName.apk").toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

android {
    namespace = "com.vrcx.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vrcx.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.6.1"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(System.getenv("VRCX_KEYSTORE_FILE") ?: "release-keystore.jks")
            storePassword = System.getenv("VRCX_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("VRCX_KEY_ALIAS") ?: "vrcx-android"
            keyPassword = System.getenv("VRCX_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        warningsAsErrors = true
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "OldTargetApi",
        )
    }

    sourceSets {
        // MigrationTestHelper reads the exported schemas as assets and Robolectric
        // serves the variant's merged assets, so they ride along in debug only —
        // which is why DatabaseMigrationTest lives in src/testDebug.
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants { variant ->
        val variantTaskName = variant.name.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        val copyTask = tasks.register<CopyVersionedApk>("copy${variantTaskName}VersionedApk") {
            output.set(layout.buildDirectory.dir("outputs/versioned-apk/${variant.name}"))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
        }
        variant.artifacts.use(copyTask)
            .wiredWith(CopyVersionedApk::input)
            .toListenTo(SingleArtifact.APK)
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    parallel = true
}

kover {
    reports {
        variant("debug") {
            verify {
                rule {
                    minBound(56)
                }
            }
        }
    }
}

dependencies {
    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Testing
    testImplementation(composeBom)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.junit4)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
