import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * `:features-api:history` — contract-only module for the History screen (Phase 2 of the
 * module-graph refactor, see docs/architecture.md). Same shape and same rules as
 * `:features-api/main/build.gradle.kts` (read that file's header first).
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.jetbrainsKotlinCompose)
}

kotlin {
    applyDefaultHierarchyTemplate()
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    // Same guard as :shared — iOS targets only on a Mac host or with -PenableIosTargets=true,
    // and never iosX64() (no Compose artifact publishes an iosX64 variant).
    val enableIos = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac ||
        providers.gradleProperty("enableIosTargets").orNull == "true"

    if (enableIos) {
        listOf(iosArm64(), iosSimulatorArm64())
    }

    sourceSets {
        commonMain.dependencies {
            // compose.runtime for @Composable (the factory's Create function and
            // Screen.Content), koin-compose for the koinInject that Content does.
            implementation(compose.runtime)
            implementation(libs.di.koin.compose)
            api(projects.shared)
            // Voyager Screen supertype of HistoryScreen — api scope, see
            // features-api/main/build.gradle.kts's comment at the same line.
            api(libs.voyager.navigator)
        }
        androidMain.get().dependencies {
            // CompileSdk-36 protection, identical to :shared/:core — see their comments.
            implementation(project.dependencies.enforcedPlatform(libs.androidx.compose.bom.get()))
        }
    }
}

android {
    namespace = "com.github.devapro.pttdroid.features.api.history"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        disable += setOf("GradleDependency") // compileSdk 37 suggestion, not actionable (CLAUDE.md)
    }
}
