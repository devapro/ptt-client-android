import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * `:ui-shell` — the single root host and composition root of the app (Phase 2 of the module-graph
 * refactor, see docs/architecture.md's target graph). It holds `App()` (iOS's UI root),
 * `rememberPttHostUiEffects` (the desktop/iOS `PttUiEffects` host the roots compose their screens
 * under), `MainViewController.kt` and `KoinIos.kt` (iOS's entry points, both moved from `:shared`'s
 * iosMain — the Objective-C names `MainViewControllerKt`/`KoinIosKt` derive from the *file* names,
 * so `iosApp`'s Swift needed no change), and since this phase it — not `:shared` — declares the
 * `PTTdroidShared` iOS framework. That move is exactly why the phase could not stop at "screens
 * moved": `:shared` cannot depend on the features, so the code that hosts the Navigator has to
 * live somewhere that can, and on iOS that somewhere also has to be the framework the Xcode project
 * links. Step 6 retired this module's `PttScreenHost` interim router: the destinations are
 * Voyager `Screen`s from `:features-api:*` now, each resolving its own wiring from the feature.
 *
 * Same plugin set, targets, guards and `jvmCommonMain` wiring as `:shared`/`:core` (read
 * `shared/build.gradle.kts`'s comments first): deprecated `com.android.library` per the CMP-9547
 * resource-packaging rule; iOS targets behind `HostManager.hostIsMac || -PenableIosTargets=true`;
 * NEVER `iosX64()` (Compose publishes no iosX64 variant and declaring it breaks appleMain
 * resolution). No `composeResources/` and no `compose.resources {}` block here — `:shared` is the
 * single resource owner and this module reads its `Res` like every other consumer.
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
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            // The framework `:shared` used to export (Phase 2 move — same baseName and same
            // static linkage, so iosApp's `import PTTdroidShared` and its
            // embedAndSignAppleFrameworkForXcode build phase keep working once the build phase
            // points at :ui-shell; see iosApp/iosApp.xcodeproj and .github/workflows/ios.yml).
            // This is the ONLY module that exports a framework — :shared's iOS targets no longer
            // declare one. Still no iosX64() (see the CLAUDE.md hard rule and the long comment
            // in shared/build.gradle.kts this line was copied out of).
            target.binaries.framework {
                baseName = "PTTdroidShared"
                isStatic = true
            }
        }
    }

    sourceSets {
        // The same JVM-only intermediate :shared keeps, wired the same way, so this module can
        // host a genuinely JVM-only root later without duplicating androidMain/desktopMain code.
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmCommonMain)
        val desktopMain by getting { dependsOn(jvmCommonMain) }
        val jvmCommonTest by creating { dependsOn(commonTest.get()) }
        androidUnitTest.get().dependsOn(jvmCommonTest)
        val desktopTest by getting { dependsOn(jvmCommonTest) }

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // For getString() in rememberPttHostUiEffects, resolving :shared's Res strings off
            // the composition — each consumer needs its own copy (see :app's build file comment).
            implementation(compose.components.resources)
            // launch in rememberPttHostUiEffects (comes in transitively through :shared's api
            // edge, named here because the code depends on it directly — the same convention
            // :shared's own build file states).
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.di.koin.core)
            // koinInject — App.kt resolves MainViewModel/SettingsRepository for the session kick.
            implementation(libs.di.koin.compose)
            // App.kt references `koinInject<MainViewModel>()` and calls a member whose class
            // extends :core's MviViewModel — naming a supertype member's receiver needs the
            // supertype on this module's classpath. The feature modules keep :core as
            // `implementation`, so the edge is declared here rather than leaked through their
            // public surface.
            implementation(libs.lifecycle.viewmodel.compose.multiplatform)
            implementation(projects.shared)
            // The MVI base (`mvi/`) moved to :core in step 5 of the module-graph refactor.
            // App.kt names the MainViewModel and calls a member it inherits/delegates through
            // MviViewModel (`.onAction` inside `onMicPermissionResult` resolves against the
            // supertype), which Kotlin only allows with that supertype on *this* module's
            // classpath — the feature modules keep :core as `implementation`, so the edge is
            // declared here rather than leaked through their public surface.
            implementation(projects.core)
            // api, not implementation: hosts (`:app`, `:desktopApp`, iOS's KoinIos.kt) depend on
            // :ui-shell alone for the root and must see registerMainScreenDi()/etc. to aggregate
            // the Koin graph; the contract interfaces are api-transitive through the features.
            api(projects.features.main)
            api(projects.features.settings)
            api(projects.features.history)
            // Explicit too (also visible via the features' own api edges) because the Voyager
            // `Screen` markers App.kt renders are what Navigator takes.
            api(projects.featuresApi.main)
            api(projects.featuresApi.settings)
            api(projects.featuresApi.history)
        }
        androidMain.get().dependencies {
            // CompileSdk-36 protection, identical to :shared/:core — see their comments.
            implementation(project.dependencies.enforcedPlatform(libs.androidx.compose.bom.get()))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.github.devapro.pttdroid.uishell"
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
