import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * `:features-api:main` — the *contract* module for the Main screen (Phase 2 of the module-graph
 * refactor, see docs/architecture.md): the pushable `Screen` destination type and the
 * `MainScreenFactory` interface. No implementations live here, and this module must never depend
 * on `:features:*`, on `:core`, or on any other feature's implementation.
 *
 * It does depend on `:shared`, and that is a documented compromise rather than an oversight:
 * `CreateMainScreen`'s argument types (`ScreenState`, `MainAction`, `AudioOutput`) live in
 * `:shared`'s `model/` and `data/settings/` until Phase 3 splits them, so a contract that speaks
 * them cannot yet be written without the edge — the same reason the reference project let three
 * of its api modules depend on its `:data`. Phase 3/4 should revisit this edge, not the shape of
 * the contract.
 *
 * Same plugin set and target guards as `:shared`/`:core` (deprecated `com.android.library` per
 * the CLAUDE.md CMP-9547 rule; never `iosX64()`). No `composeResources/` here — all resources
 * stay in `:shared` (single resource-owner decision).
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

    // Same guard as :shared/:core: iOS targets only on a Mac host or with -PenableIosTargets=true,
    // and never iosX64() (no Compose artifact publishes an iosX64 variant). Declared because
    // :ui-shell's iosMain compiles against these contracts.
    val enableIos = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac ||
        providers.gradleProperty("enableIosTargets").orNull == "true"

    if (enableIos) {
        listOf(iosArm64(), iosSimulatorArm64())
        // No binaries.framework here — only :ui-shell exports the PTTdroidShared framework.
    }

    sourceSets {
        commonMain.dependencies {
            // Near-zero by design: compose.runtime for @Composable on the factory's Create
            // function and on Screen.Content, and :shared for the model types named above.
            // compose.material3 used to be here for SnackbarHostState — a parameter of the
            // pre-Voyager Main contract; the screen root resolves the host's effects from
            // :shared's LocalPttUiEffects now, so the contract names nothing from material3.
            implementation(compose.runtime)
            // koinInject — MainScreen.Content() resolves the factory binding here, which is the
            // only reason a contract module needs DI at all (the bindings themselves stay in the
            // implementing feature).
            implementation(libs.di.koin.compose)
            api(projects.shared)
            // Routing library probe (Phase 2): voyager-navigator resolves and compiles against
            // this toolchain (Kotlin 2.4.10 / CMP 1.12.0) for androidTarget and desktop. `api`
            // scope: the Screen types below declare `cafe.adriel.voyager.core.screen.Screen` as
            // their supertype, so it is part of the contract's public surface, not an impl detail.
            api(libs.voyager.navigator)
        }
        androidMain.get().dependencies {
            // Same compileSdk-36 protection as :shared/:core's androidMain — read
            // shared/build.gradle.kts's comment before removing.
            implementation(project.dependencies.enforcedPlatform(libs.androidx.compose.bom.get()))
        }
    }
}

android {
    namespace = "com.github.devapro.pttdroid.features.api.main"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        disable += setOf(
            // Same as :shared/:core: suggests compileSdk 36 -> 37, forbidden by the CLAUDE.md
            // hard rule. Not actionable.
            "GradleDependency",
        )
    }
}
