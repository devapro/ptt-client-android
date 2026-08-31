// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.jetbrainsKotlinAndroid) apply false
    alias(libs.plugins.jetbrainsKotlinJvm) apply false
    alias(libs.plugins.jetbrainsKotlinCompose) apply false
    alias(libs.plugins.jetbrainsKotlinSerialization) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
}

/**
 * Compose Multiplatform 1.12.0 (`:shared`) requests androidx.compose.* and
 * org.jetbrains.androidx.lifecycle's android variants at versions that require compileSdk 37
 * (hard rule caps this repo at 36 — see shared/build.gradle.kts for the compose BOM half of this
 * fix). :app pulls the same lifecycle-compose artifacts in transitively through `:shared`, so the
 * pin has to apply to every subproject's dependency resolution, not just `:shared`'s own — one
 * place, so it can't drift between the two.
 *
 * Excludes configurations whose name contains "ios" (Phase 7a). `org.jetbrains.androidx.lifecycle`
 * publishes its *android* target as a capability/module substitution onto the plain
 * `androidx.lifecycle:*-compose` coordinates this forces — that is the whole reason forcing these
 * two GAVs affects the androidx.lifecycle-vs-org.jetbrains.androidx.lifecycle conflict at all — but
 * `configurations.all` applied that same force to `:shared`'s iOS native configurations too, where
 * no such substitution exists and `androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0` publishes
 * no `ios_simulator_arm64`/`iosArm64` variant to match against (nor `iosX64` — not a target this
 * module declares at all; see the `enableIos` guard in `shared/build.gradle.kts`). Confirmed on this Linux
 * machine: `./gradlew -PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64` failed with
 * "No matching variant of androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0 was found" before
 * this exclusion, and resolves past that point after it. No KMP target's configuration names
 * contain "ios" other than the iOS ones (androidTarget's are prefixed "android", desktop's
 * "desktop", :app's plain AGP configurations have neither), so this exclusion cannot silently
 * un-force the Android/desktop cases it exists for.
 */
subprojects {
    configurations.matching { !it.name.contains("ios", ignoreCase = true) }.configureEach {
        resolutionStrategy {
            force(
                "androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0",
                "androidx.lifecycle:lifecycle-runtime-compose:2.10.0",
            )
        }
    }
}