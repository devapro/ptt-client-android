import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * `:core` — MVI infrastructure (`com.github.devapro.pttdroid.mvi`: `MviViewModel`,
 * `ActionProcessor`, `Reducer`) and the shared widgets (`com.github.devapro.pttdroid.ui.components`:
 * `PTTButton`, `StatusCard`, `AudioOutputControl`, `ChannelSelector`, `ErrorBanner`, plus
 * `UiStatusColor`), moved out of `:shared` in step 5 of the module-graph refactor with their
 * packages unchanged so consumer diffs are import-only (Phase 1 of the refactor created this
 * module and left it empty because the machinery was still referenced from `:shared`; see
 * docs/architecture.md). This build file is a *deliberate near-copy* of
 * `shared/build.gradle.kts`: same plugin set, same targets under the same iOS guard, the same
 * `jvmCommonMain`/`jvmCommonTest` source-set edges, and the same androidMain compose-BOM force.
 * Deviating from the copy for cosmetic reasons re-opens the regressions those lines exist to
 * prevent — read `shared/build.gradle.kts`'s comments before changing anything here.
 *
 * What is deliberately NOT copied from `:shared`:
 *  - no `binaries.framework {}` on the iOS targets — `:core` exports no Objective-C framework;
 *    framework export belongs to `:ui-shell` (Phase 4).
 *  - no `generatePttDefaults` task / `relay-defaults` apply — `:shared` keeps owning
 *    `RelayDefaults`/`AppVersion` and the single generated-source site.
 *  - no `compose.resources {}` block — `:core` ships no resources of its own. Components that
 *    need strings/drawables read `:shared`'s `Res` class (generated at
 *    `com.github.devapro.pttdroid.shared.resources` with `publicResClass = true`, which exists
 *    precisely so a dependent module like this one can use it). `:shared`'s resource package and
 *    APK asset path are a documented packaging check and must not move here.
 *  - no kotlinx-serialization plugin — none of `:core`'s code uses `@Serializable` today; add it
 *    back only when something does.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // Deprecated `com.android.library`, same as :shared, for the same reason (see the CLAUDE.md
    // hard rule and shared/build.gradle.kts): `com.android.kotlin.multiplatform.library` silently
    // stops Compose resources from being packaged (CMP-9547, MissingResourceException at runtime).
    // :core consumes :shared's Res, so it must not be the module that breaks that assumption.
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

    // Same guard as :shared (see the long comment there): iOS targets are declared only on a Mac
    // host or with -PenableIosTargets=true, and NEVER iosX64() — Compose Multiplatform publishes
    // no iosX64 variant of any Compose artifact, and declaring it breaks appleMain dependency
    // resolution outright.
    val enableIos = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac ||
        providers.gradleProperty("enableIosTargets").orNull == "true"

    if (enableIos) {
        listOf(iosArm64(), iosSimulatorArm64())
        // No target.binaries.framework {} here — see the file header.
    }

    sourceSets {
        // The same JVM-only intermediate :shared uses for OkHttp/Ktor-CIO/`javax.net.ssl` code.
        // Empty in :core so far, but kept as a deliberate edge so JVM-only core infra (e.g. a
        // future JVM-only helper shared by the Android and desktop compilations but not iOS)
        // lands in one place instead of being duplicated — mirrors the CLAUDE.md warning about
        // adding JVM-only code to androidMain/desktopMain separately.
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmCommonMain)
        val desktopMain by getting { dependsOn(jvmCommonMain) }
        val jvmCommonTest by creating { dependsOn(commonTest.get()) }
        androidUnitTest.get().dependsOn(jvmCommonTest)
        val desktopTest by getting { dependsOn(jvmCommonTest) }

        commonMain.dependencies {
            // Everything below is what the moved MVI machinery (mvi/) and shared components
            // (ui/components/) import; nothing in :core needs more. Koin, ktor, DataStore,
            // kotlinx-datetime and serialization stay out — they are :shared's (and later
            // :features' concerns), and an unneeded dependency here is just slower
            // configuration plus a wider F-Droid audit surface.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            // Note: the moved components also import androidx.compose.animation.* — deliberately
            // not declared here; :shared never declared it either, it arrives transitively through
            // compose.foundation (the same edge :features:settings' ErrorBanner usage relies on).
            // The moved components carry `@Preview` annotations
            // (androidx.compose.ui.tooling.preview.Preview, multiplatform artifact).
            implementation(compose.preview)
            implementation(libs.kotlinx.coroutines.core)
            // :shared's PttLog (ActionProcessor's error logging), PttUiStatus, the theme, and the
            // `data.settings` types the widgets take as parameters all sit behind this edge.
            // `implementation`, not `api`, even though :core's *public* widget signatures name
            // :shared types (PttUiStatus, AudioOutput, AppSettings): every consumer of those
            // signatures — today :features:main — already depends on :shared directly, so the
            // types resolve on each consumer's own classpath and `api` would widen the boundary
            // for no compiling benefit (verified by `assembleDebug` after the Phase 5 move).
            implementation(projects.shared)
        }
        androidMain.get().dependencies {
            // Identical to :shared's androidMain (copy the rationale, not just the line): without
            // this force, Compose Multiplatform 1.12.0 pulls androidx.compose.* android artifacts
            // at 1.12.0, which requires compileSdk 37 — forbidden here (compileSdk pinned to 36).
            implementation(project.dependencies.enforcedPlatform(libs.androidx.compose.bom.get()))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidInstrumentedTest.get().dependencies {
            // Only what PTTButtonTest (moved here with PTTButton in Phase 5) actually uses:
            // AndroidJUnit4 + JUnit4 assertions (via androidx.junit), the Compose UI test rule,
            // and the test manifest that supplies the activity createComposeRule launches.
            // :shared's block also carried espresso (its other instrumented tests use it) and
            // ui-tooling (debug @Preview inflation) — neither is needed here, so they stay out.
            implementation(libs.androidx.junit)
            implementation(project.dependencies.enforcedPlatform(libs.androidx.compose.bom.get()))
            implementation(libs.androidx.ui.test.junit4)
            // The KMP androidLibrary target has no separate "debugImplementation" configuration;
            // androidInstrumentedTest is already test-only, so ui-test-manifest (which :app scopes
            // to debugImplementation) goes here as a plain implementation dep instead — same as in
            // :shared's androidInstrumentedTest block.
            implementation(libs.androidx.ui.test.manifest)
        }
    }
}

android {
    namespace = "com.github.devapro.pttdroid.core"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        // For :core:connectedDebugAndroidTest — PTTButtonTest's 7 Compose UI tests, moved here
        // from :shared with the widget they test (Phase 5 of the module-graph refactor).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        disable += setOf(
            // Same as :shared: suggests bumping compileSdk 36 -> 37, which the CLAUDE.md hard
            // rule forbids (API 37 is not installed on this machine). Not actionable.
            "GradleDependency",
        )
    }
}
