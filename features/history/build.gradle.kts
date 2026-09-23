import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * `:features:history` — the History screen implementation (moved out of `:shared`'s `ui/` in
 * Phase 2 of the module-graph refactor, see docs/architecture.md): `HistoryScreen.kt` with its
 * private row composable and date/time formatting, `HistoryScreenFactoryImpl`, and
 * `registerHistoryScreenDi()`. Since the per-feature `ViewModel` split (migration step 2) the
 * module also owns history's whole MVI stack — `HistoryViewModel`, `HistoryActionProcessor`,
 * the five history reducers and `HistoryReducersTest` (moved from `:shared`'s commonTest with
 * them; fakes duplicated there because `:shared`'s test source set is not consumable across
 * modules).
 *
 * Same rules as `:features:main` (read its header first). Needs `kotlinx-datetime` because the
 * record stamps are formatted here (the store and `VoiceRecord` itself stay in `:shared`), and
 * the lifecycle `ViewModel` artifact + coroutines for `HistoryViewModel` (same artifacts
 * `:shared`'s `MainActivityViewModel` builds against). No `@Preview` composables in this screen,
 * hence no compose.preview, and no instrumented test moved to it.
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
            implementation(libs.kotlinx.datetime)
            implementation(libs.di.koin.core)
            // ViewModel + viewModelScope for HistoryViewModel — the same multiplatform lifecycle
            // artifact :shared's MainActivityViewModel uses.
            implementation(libs.lifecycle.viewmodel.compose.multiplatform)
            implementation(libs.kotlinx.coroutines.core)
            // koinInject: since the Voyager migration the <Name>ScreenFactoryImpl is this screen's
            // root and resolves its own view model. koin-core alone carries the module DSL
            // (singleOf/factoryOf) the register*ScreenDi function uses; the koin-compose-viewmodel
            // artifact went with viewModelOf, whose Kind.Factory semantics would hand the root a
            // fresh view model per resolution.
            implementation(libs.di.koin.compose)
            implementation(projects.shared)
            // The MVI base types (`mvi/`) HistoryActionProcessor/HistoryViewModel build on moved
            // to :core in step 5 of the module-graph refactor (package unchanged — no source edits
            // here). See the same note in :features:main's build file for why :core can keep
            // :shared as `implementation` from this module's point of view.
            implementation(projects.core)
            api(projects.featuresApi.history)
        }
        androidMain.get().dependencies {
            implementation(project.dependencies.enforcedPlatform(libs.androidx.compose.bom.get()))
        }
        // The moved HistoryReducersTest. commonTest reaches both the android-unit-test and the
        // desktop-test compilations through the default hierarchy template (androidUnitTest and
        // desktopTest each dependsOn commonTest) — the same wiring :shared builds its 286-test
        // run on; :shared's extra jvmCommonTest layer exists only for its JVM-only sources and
        // is not needed for a purely common test.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.github.devapro.pttdroid.features.history"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        disable += setOf("GradleDependency")
    }
}
