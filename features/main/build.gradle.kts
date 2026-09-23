import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * `:features:main` — the Main screen implementation (moved out of `:shared`'s `ui/` in Phase 2 of
 * the module-graph refactor, see docs/architecture.md): `MainScreen.kt` with its private
 * subcomposables and previews, `MainScreenFactoryImpl`, and `registerMainScreenDi()`. Since the
 * Main slice (migration step 4a) the module also owns Main's whole MVI stack — `MainViewModel`,
 * `MainActionProcessor`, the nine main reducers and `MainReducersTest`; `MainState`/`MainAction`
 * live in `:features-api:main`, this module's own api — so unlike the Phase 2 note this module is
 * no longer presentation only.
 *
 * Depends on `:shared` (the MVI model types it renders, the `ui/theme/` it composes, and the
 * single `composeResources/` owner, whose `Res` this module reads across the module edge), on
 * `:core` (the `mvi/` base and the `ui/components/` widgets — both moved out of `:shared` into
 * `:core` in step 5 of the module-graph refactor, packages unchanged), and on its own api. It
 * does NOT depend on any other feature, implementation or api, and never will: cross-feature
 * navigation edges are api-only and live in `:ui-shell` until Phase 4b decides otherwise.
 *
 * Same plugin set / targets / guards as `:shared` — read `shared/build.gradle.kts`'s comments
 * before changing anything here (deprecated `com.android.library` for CMP-9547 resource
 * packaging; never `iosX64()`; the androidMain compose-BOM force for the compileSdk-36 rule).
 * No `composeResources/` here — all Compose resources stay in `:shared` by decision.
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

    val enableIos = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac ||
        providers.gradleProperty("enableIosTargets").orNull == "true"

    if (enableIos) {
        listOf(iosArm64(), iosSimulatorArm64())
        // No binaries.framework — only :ui-shell exports the PTTdroidShared framework.
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // Reading :shared's generated Res (stringResource/painterResource accessors are in
            // this artifact, and :shared scopes its own copy `implementation`, so each consumer
            // needs its own — same as :app's comment in app/build.gradle.kts).
            implementation(compose.components.resources)
            // The moved MainScreen.kt carries @Preview composables.
            implementation(compose.preview)
            implementation(libs.di.koin.core)
            // ViewModel + viewModelScope for MainViewModel — the same multiplatform lifecycle
            // artifact :shared's MainActivityViewModel and :features:history/:settings build
            // against.
            implementation(libs.lifecycle.viewmodel.compose.multiplatform)
            implementation(libs.kotlinx.coroutines.core)
            // koinInject: since the Voyager migration MainScreenFactoryImpl is the screen root and
            // resolves its own view model, SettingsRepository and HistoryBadge. (koin-core alone
            // carries the module DSL — singleOf/factoryOf — that registerMainScreenDi uses; the
            // koin-compose-viewmodel artifact that previously supplied viewModelOf is gone, since
            // Koin 4's viewModelOf is a Kind.Factory and would give this root a second view model
            // every time it resolved one.)
            implementation(libs.di.koin.compose)
            implementation(projects.shared)
            // The MVI base types (`mvi/`) and the `ui/components/` widgets this screen composes
            // moved to :core in step 5 of the module-graph refactor (packages unchanged —
            // `com.github.devapro.pttdroid.mvi` / `...ui.components` — so this module's sources
            // needed no import edits). :core keeps :shared as `implementation`: the :shared types
            // that appear in :core's widget signatures (PttUiStatus, AudioOutput) resolve from
            // the :shared edge above, not transitively — verified by assembleDebug.
            implementation(projects.core)
            // api scope: MainScreenFactoryImpl's public surface IS the contract interface.
            api(projects.featuresApi.main)
            // Main's root *pushes* these two destinations, so it needs their Screen markers — and
            // nothing else. This is the only cross-feature edge in :features:main: implementation,
            // not api (nothing in Main's public surface names them), and it is contract-only, so
            // there is still no :features:x -> :features:y dependency anywhere (the badge keeps
            // crossing that boundary through :shared's HistoryBadge instead).
            implementation(projects.featuresApi.settings)
            implementation(projects.featuresApi.history)
        }
        androidMain.get().dependencies {
            // CompileSdk-36 protection, identical to :shared/:core — see their comments.
            implementation(project.dependencies.enforcedPlatform(libs.androidx.compose.bom.get()))
        }
        // MainReducersTest (migration step 4a). commonTest reaches both the android-unit-test
        // and the desktop-test compilations through the default hierarchy template — same wiring
        // :features:history's and :features:settings's reducer tests build on.
        // datastore-preferences is needed only for the test's FakeDataStore (SettingsRepository
        // takes the interface); :shared declares it as implementation, so it does not reach this
        // module transitively.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.datastore.preferences)
        }
        androidInstrumentedTest.get().dependencies {
            // MainScreenTest moved here with the screen it tests (Phase 2). Same dependency set
            // :shared's androidInstrumentedTest uses — see its build file for the rationale on
            // each (ui-test-manifest/ui-tooling have no debugImplementation in a KMP library, so
            // they are plain implementation here too).
            implementation(libs.androidx.junit)
            implementation(libs.androidx.espresso.core)
            implementation(project.dependencies.enforcedPlatform(libs.androidx.compose.bom.get()))
            implementation(libs.androidx.ui.test.junit4)
            implementation(libs.androidx.ui.tooling)
            implementation(libs.androidx.ui.test.manifest)
            // The test resolves string/plural resources with runBlocking/getPluralString.
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.components.resources)
        }
    }
}

android {
    namespace = "com.github.devapro.pttdroid.features.main"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        // For :features:main:connectedDebugAndroidTest — the moved MainScreenTest.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        disable += setOf("GradleDependency") // compileSdk 37 suggestion, not actionable (CLAUDE.md)
    }
}
