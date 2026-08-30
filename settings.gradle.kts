import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

abstract class ExclusiveBuildService :
    BuildService<ExclusiveBuildService.Parameters>,
    AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val lockFile: RegularFileProperty
    }

    private val lockChannel =
        parameters.lockFile.get().asFile.let { lockFile ->
            lockFile.parentFile.mkdirs()
            FileChannel.open(lockFile.toPath(), CREATE, WRITE)
        }

    private val buildLock =
        try {
            lockChannel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } ?: run {
            lockChannel.close()
            throw GradleException(
                "Another Gradle build is already running for this repository. " +
                    "Wait for it to finish before starting a new one.",
            )
        }

    override fun close() {
        buildLock.release()
        lockChannel.close()
    }
}

val exclusiveBuildService =
    gradle.sharedServices.registerIfAbsent(
        "exclusiveBuild",
        ExclusiveBuildService::class,
    ) {
        parameters.lockFile.set(rootDir.resolve(".gradle/exclusive-build.lock"))
    }

exclusiveBuildService.get()

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "vrcx-android"
include(":app")
