import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * `:features:settings` — the Settings screen implementation (moved out of `:shared`'s `ui/` in
 * Phase 2 of the module-graph refactor, see docs/architecture.md): `SettingsScreen.kt` with its
 * private section subcomposables and previews, `SettingsScreenFactoryImpl`, and
 * `registerSettingsScreenDi()`. Since the per-feature `ViewModel` split (migration step 3) the
 * module also owns settings' whole MVI stack — `SettingsViewModel`, `SettingsActionProcessor`,
 * the six settings reducers (five, plus `ReconnectReducer` so `SaveSettings`'s chain stays inside
 * one processor) and `SettingsReducersTest` (moved from `:shared`'s commonTest with them).
 * `SettingsFormState`/`SettingsState`/`SettingsAction` live in `:features-api:settings`, this
 * module's own api — so unlike the Phase 2 note this module is no longer presentation only.
 *
 * Same rules as `:features:main` (read its header first). Needs the lifecycle `ViewModel`
 * artifact + coroutines for `SettingsViewModel` (same artifacts `:shared`'s
 * `MainActivityViewModel` and `:features:history` build against).
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
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.preview)
            implementation(libs.di.koin.core)
            // ViewModel + viewModelScope for SettingsViewModel — the same multiplatform
            // lifecycle artifact :shared's MainActivityViewModel and :features:history use.
            implementation(libs.lifecycle.viewmodel.compose.multiplatform)
            implementation(libs.kotlinx.coroutines.core)
            // koinInject: since the Voyager migration the <Name>ScreenFactoryImpl is this screen's
            // root and resolves its own view model. koin-core alone carries the module DSL
            // (singleOf/factoryOf) the register*ScreenDi function uses; the koin-compose-viewmodel
            // artifact went with viewModelOf, whose Kind.Factory semantics would hand the root a
            // fresh view model per resolution.
            implementation(libs.di.koin.compose)
            implementation(projects.shared)
            // The MVI base types (`mvi/`) SettingsActionProcessor/SettingsViewModel build on moved
            // to :core in step 5 of the module-graph refactor (package unchanged — no source edits
            // here). See the same note in :features:main's build file for why :core can keep
            // :shared as `implementation` from this module's point of view.
            implementation(projects.core)
            api(projects.featuresApi.settings)
        }
        androidMain.get().dependencies {
            implementation(project.dependencies.enforcedPlatform(libs.androidx.compose.bom.get()))
        }
        // The moved SettingsReducersTest. commonTest reaches both the android-unit-test and the
        // desktop-test compilations through the default hierarchy template (androidUnitTest and
        // desktopTest each dependsOn commonTest) — the same wiring :features:history's moved
        // HistoryReducersTest builds on. datastore-preferences is needed only for the test's
        // FakeDataStore (SettingsRepository takes the interface); :shared declares it as
        // implementation, so it does not reach this module transitively.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.datastore.preferences)
        }
        androidInstrumentedTest.get().dependencies {
            // SettingsScreenTest moved here with the screen it tests (Phase 2) — same set as
            // :features:main's androidInstrumentedTest, same rationale.
            implementation(libs.androidx.junit)
            implementation(libs.androidx.espresso.core)
            implementation(project.dependencies.enforcedPlatform(libs.androidx.compose.bom.get()))
            implementation(libs.androidx.ui.test.junit4)
            implementation(libs.androidx.ui.tooling)
            implementation(libs.androidx.ui.test.manifest)
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.components.resources)
        }
    }
}

android {
    namespace = "com.github.devapro.pttdroid.features.settings"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        disable += setOf("GradleDependency")
    }
}
