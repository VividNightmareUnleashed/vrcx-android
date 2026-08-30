plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.spotless)
}

val spotlessRatchetFrom = providers.gradleProperty("spotlessRatchetFrom").orElse("origin/main")

spotless {
    // Enforce formatting on every touched file without creating a repository-wide
    // formatting-only rewrite. Existing files join the gate when they change.
    ratchetFrom(spotlessRatchetFrom.get())
    kotlin {
        target("app/src/**/*.kt")
        targetExclude("app/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}
