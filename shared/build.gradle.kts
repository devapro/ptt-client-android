import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * `version.properties` and `relay.properties`, parsed once in `gradle/relay-defaults.gradle.kts`
 * (see that file). `:shared` cannot use `BuildConfig` — it is not an Android application module —
 * so `generatePttDefaults` below writes the same values out as generated Kotlin instead.
 */
apply(from = "$rootDir/gradle/relay-defaults.gradle.kts")

val appVersionName: String by extra
val appVersionCode: Int by extra
val defaultRelayHost: String by extra
val defaultRelayPort: Int by extra
val defaultRelayTls: Boolean by extra

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.jetbrainsKotlinCompose)
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

kotlin {
    applyDefaultHierarchyTemplate()
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    // Phase 7a: iOS targets, declared only when this build can actually resolve/compile them —
    // a real Mac host (a genuine Kotlin/Native Apple toolchain, e.g. a developer's machine) or an
    // explicit opt-in (`-PenableIosTargets=true`, passed by .github/workflows/ios.yml's macOS
    // runner). This machine is Linux, which can neither resolve Apple klibs nor produce
    // Kotlin/Native binaries for Apple targets, so `enableIos` is false here and the block below
    // never runs. That is enough to keep `./gradlew build` green on Linux: an *undeclared* target
    // needs no `actual` at all (only a *declared* target with a missing actual fails to compile),
    // so commonMain's `expect fun createPttHttpClient`/`describePlatformCause`
    // (network/PttHttpClient.kt) are simply inert here rather than unsatisfied — confirmed by
    // `./gradlew projects` / `./gradlew build` still showing no iOS targets on this box (see the
    // Phase 7a report).
    val enableIos = org.jetbrains.kotlin.konan.target.HostManager.hostIsMac ||
        providers.gradleProperty("enableIosTargets").orNull == "true"

    if (enableIos) {
        // No iosX64() here: Compose Multiplatform does not publish an iosX64 variant of
        // org.jetbrains.compose.runtime:runtime (or any other Compose artifact) — confirmed
        // against Maven Central for 1.12.0 and every release back to 1.8.2. Declaring it made
        // appleMain's dependency resolution fail outright ("Source set 'appleMain' couldn't
        // resolve dependencies for all target platforms ... Unresolved platforms: [iosX64]")
        // before this could even reach a Kotlin/Native compile. iosX64 (Intel simulator) has no
        // real device or Apple Silicon simulator behind it anyway, so there is nothing lost by
        // targeting only iosArm64 (devices) and iosSimulatorArm64 (Apple Silicon simulators). Do
        // not re-add it.
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "PTTdroidShared"
                isStatic = true
            }
        }

        // applyDefaultHierarchyTemplate() (above) already creates the `iosMain` intermediate
        // source set and wires `iosMain.dependsOn(commonMain)` as soon as any `ios*` target is
        // registered — https://kotlinlang.org/docs/multiplatform-hierarchy.html#default-hierarchy-template
        // — so this only needs to add the one iOS-only dependency, the Darwin Ktor engine (already
        // in the version catalog, unused until now).
        sourceSets.getByName("iosMain") {
            dependencies {
                implementation(libs.network.ktor.darwin)
            }
        }
    }

    sourceSets {
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmCommonMain)
        val desktopMain by getting { dependsOn(jvmCommonMain) }
        val jvmCommonTest by creating { dependsOn(commonTest.get()) }
        androidUnitTest.get().dependsOn(jvmCommonTest)
        val desktopTest by getting { dependsOn(jvmCommonTest) }
        // The 39 Compose UI tests (button/main screen/settings) plus the Phase 3 DataStore
        // migration test and the Phase-3/4 TLS integration test, moved here from :app's
        // androidTest in Phase 5 — the composables and the settings/network code they exercise
        // all live in :shared now. androidInstrumentedTest already depends on androidMain (KMP's
        // default hierarchy), so commonMain types (Res.string.*, PttUiStatus, ...) are visible
        // without an extra dependsOn.

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            // `@Preview` on the composables moved here from :app in Phase 5 — same
            // `androidx.compose.ui.tooling.preview.Preview` annotation, published under this
            // multiplatform coordinate instead of the Android-only artifact.
            implementation(compose.preview)
            implementation(libs.di.koin.core)
            implementation(libs.di.koin.compose)
            implementation(libs.di.koin.compose.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose.multiplatform)
            implementation(libs.lifecycle.runtime.compose.multiplatform)
            implementation(libs.network.ktor.core)
            implementation(libs.network.ktor.websockets)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.kermit)
        }
        jvmCommonMain.dependencies {
            implementation(libs.network.ktor.okhttp)
            implementation(libs.network.ktor.server.cio)
            implementation(libs.network.ktor.server.websockets)
        }
        androidMain.get().dependencies {
            // Compose Multiplatform 1.12.0 requests androidx.compose.* android artifacts at
            // version 1.12.0 directly, which requires compileSdk 37 (hard rule caps us at 36).
            // The app pins the same group to 1.11.4 via androidx-compose-bom 2026.06.01; force
            // the same resolution here so :shared and :app agree on one (compileSdk-36-safe)
            // androidx Compose version instead of Gradle's default "highest wins".
            implementation(project.dependencies.enforcedPlatform(libs.androidx.compose.bom.get()))
            implementation(libs.di.koin)
            implementation(libs.kotlinx.coroutines.android)
            // The androidx.lifecycle:*-compose:2.10.0 force that keeps this on compileSdk 36 is
            // in the root build.gradle.kts — it also has to apply to :app, which pulls the same
            // org.jetbrains.androidx.lifecycle:*-compose:2.11.0 artifacts in transitively through
            // this module.
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmCommonTest.dependencies {
            implementation(libs.network.ktor.client.cio)
            implementation(libs.network.ktor.tls.certificates)
            // PinnedTrustTest and the InternalPttServer tests stay JUnit 4 (javax.net.ssl, the CIO
            // server) rather than kotlin.test. jvmCommonTest reaches both the android-unit-test
            // and the desktop-test compilations via the dependsOn edges above, so this makes
            // JUnit 4 available to both explicitly, rather than relying on it arriving only
            // transitively through commonTest's kotlin-test-junit resolution.
            implementation(libs.junit)
        }
        androidInstrumentedTest.get().dependencies {
            implementation(libs.androidx.junit)
            implementation(libs.androidx.espresso.core)
            implementation(project.dependencies.enforcedPlatform(libs.androidx.compose.bom.get()))
            implementation(libs.androidx.ui.test.junit4)
            // The KMP androidLibrary target has no separate "debugImplementation" configuration;
            // androidInstrumentedTest is already test-only, so ui-tooling/ui-test-manifest (which
            // :app scoped to debugImplementation) go here as plain implementation deps instead.
            implementation(libs.androidx.ui.tooling)
            implementation(libs.androidx.ui.test.manifest)
        }
    }
}

val pttDefaultsOutputDir = layout.buildDirectory.dir("generated/pttDefaults/kotlin")

val generatePttDefaults by tasks.registering {
    val host = defaultRelayHost
    val port = defaultRelayPort
    val tls = defaultRelayTls
    val versionName = appVersionName
    val versionCode = appVersionCode
    val outputDir = pttDefaultsOutputDir

    inputs.property("defaultRelayHost", host)
    inputs.property("defaultRelayPort", port)
    inputs.property("defaultRelayTls", tls)
    inputs.property("appVersionName", versionName)
    inputs.property("appVersionCode", versionCode)
    outputs.dir(outputDir)

    doLast {
        val packageDir = outputDir.get().asFile
            .resolve("com/github/devapro/pttdroid/shared/generated")
        packageDir.mkdirs()
        packageDir.resolve("RelayDefaults.kt").writeText(
            """
            |package com.github.devapro.pttdroid.shared.generated
            |
            |// Generated by the :shared generatePttDefaults task — do not edit.
            |// Source: relay.properties (via gradle/relay-defaults.gradle.kts).
            |object RelayDefaults {
            |    const val HOST: String = "$host"
            |    const val PORT: Int = $port
            |    const val TLS: Boolean = $tls
            |}
            |""".trimMargin()
        )
        packageDir.resolve("AppVersion.kt").writeText(
            """
            |package com.github.devapro.pttdroid.shared.generated
            |
            |// Generated by the :shared generatePttDefaults task — do not edit.
            |// Source: version.properties (via gradle/relay-defaults.gradle.kts).
            |object AppVersion {
            |    const val NAME: String = "$versionName"
            |    const val CODE: Int = $versionCode
            |}
            |""".trimMargin()
        )
    }
}

kotlin.sourceSets.commonMain {
    kotlin.srcDir(pttDefaultsOutputDir)
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generatePttDefaults)
}

android {
    namespace = "com.github.devapro.pttdroid.shared"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        // For :shared:connectedDebugAndroidTest — the 39 Compose UI tests plus the two Phase 3/4
        // instrumented tests, moved here from :app in Phase 5 (see androidInstrumentedTest below).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        disable += setOf(
            // Suggests bumping compileSdk 36 -> 37. Forbidden by the compileSdk-36 hard rule in
            // CLAUDE.md: several AndroidX versions pinned here require API 37, which is not
            // installed on this machine. Not actionable, so not left to accumulate as noise.
            "GradleDependency",
            // PinnedTrustManager/PinnedHostnameVerifier (network/tls/PinnedTrust.kt) are
            // deliberate: a self-hosted relay has no certificate authority to appeal to, so they
            // accept exactly one certificate/hostname and refuse everything else. They used to
            // carry `@SuppressLint` for these four checks, but that annotation is Android-only
            // and the file now lives in jvmCommonMain, shared with the desktop target — see the
            // KDoc on both classes. docs/known-issues.md: "do not add [suppressions] without the
            // same justification" — this is that justification, moved here.
            "CustomX509TrustManager",
            "TrustAllX509TrustManager",
            "BadHostnameVerifier",
            "AllowAllHostnameVerifier",
        )
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.github.devapro.pttdroid.shared.resources"
}
