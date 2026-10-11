import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * `:features-api:settings` — contract module for the Settings screen (Phase 2 of the
 * module-graph refactor, see docs/architecture.md). Same shape and same rules as
 * `:features-api/main/build.gradle.kts` (read that file's header first): depends on `:shared`
 * only because `CreateSettingsScreen` speaks `SettingsFormState`/`SettingsEdit`, which
 * migration step 3 moved here (the `AppSettings`-family types the form parses still live in
 * `:shared`, which this module exposes as `api`); never on `:features:*`, on `:core`, or on
 * another feature's api. `SettingsFormStateTest` moved here with the type it tests —
 * `SettingsEdit` itself lags behind in `:shared` only because the legacy `MainAction` still
 * carries it until step 4.
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
            // Voyager Screen supertype of SettingsScreen — api scope, see
            // features-api/main/build.gradle.kts's comment at the same line.
            api(libs.voyager.navigator)
        }
        androidMain.get().dependencies {
            // CompileSdk-36 protection, identical to :shared/:core — see their comments.
            implementation(project.dependencies.enforcedPlatform(libs.androidx.compose.bom.get()))
        }
        // The moved SettingsFormStateTest — plain commonTest reaches both the android-unit-test
        // and the desktop-test compilations through the default hierarchy template, the same
        // wiring :features:history's moved reducers test uses (no jvmCommonTest layer needed for
        // a purely common test).
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "com.github.devapro.pttdroid.features.api.settings"
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
