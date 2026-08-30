import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The version lives in `version.properties` at the repository root, not in a git tag.
 *
 * F-Droid builds a plain checkout of the tagged commit with no Gradle properties set, so a
 * version that only exists in CI would build as whatever the fallback happened to be. The
 * release workflow refuses to publish a tag that disagrees with this file, which keeps the tag
 * and the artifact honest without making the build depend on git.
 *
 * versionCode is derived rather than tracked separately: F-Droid requires it to increase, and
 * `major*10000 + minor*100 + patch` guarantees that for any version below 100.100.100.
 */
val appVersionName: String = rootProject.file("version.properties")
    .takeIf { it.isFile }
    ?.let { file ->
        Properties()
            .apply { file.inputStream().use { stream -> load(stream) } }
            .getProperty("versionName")
            ?.trim()
            ?.removePrefix("v")
    }
    ?.takeIf { it.isNotEmpty() }
    ?: error("version.properties is missing or has no versionName")

val appVersionCode: Int = appVersionName.substringBefore('-').split('.')
    .map { it.toIntOrNull() ?: error("version.properties: '$appVersionName' is not major.minor.patch") }
    .let { parts ->
        require(parts.size == 3) { "version.properties: '$appVersionName' is not major.minor.patch" }
        require(parts.all { it in 0..99 }) { "version.properties: each part of '$appVersionName' must be 0..99" }
        parts[0] * 10_000 + parts[1] * 100 + parts[2]
    }

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.jetbrainsKotlinCompose)
    alias(libs.plugins.jetbrainsKotlinSerialization)
}

android {
    namespace = "com.github.devapro.pttdroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.devapro.pttdroid"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    /**
     * Release signing, supplied by the environment so no key material is ever in the repository.
     *
     * Android will not install an update signed with a different key, so this key has to outlive
     * every release. It lives in CI secrets; see `docs/fdroid.md`.
     *
     * When the variables are absent the config is simply not created and `assembleRelease`
     * produces an unsigned APK — which is the right outcome for a local build, and which the
     * publishing workflow checks for rather than shipping by accident.
     */
    val keystorePath: String? = System.getenv("PTT_KEYSTORE_PATH")
    if (!keystorePath.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("PTT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PTT_KEY_ALIAS")
                keyPassword = System.getenv("PTT_KEY_PASSWORD")
                // minSdk is 24, so the v1 JAR signature nothing above API 23 reads is dead
                // weight; AGP drops it here regardless of what this flag says. v3 carries the
                // rotation proof, which matters for a key that has to outlive every release.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    /**
     * Keep the Play dependency-metadata blob out of the APK.
     *
     * It is an encrypted blob whose contents cannot be reproduced from source, which breaks
     * F-Droid's reproducible-build check, and it exists to report the dependency tree to Google
     * Play — a store this app is not on.
     */
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance)
    implementation(libs.di.koin)
    implementation(libs.di.koin.androidx.compose)
    implementation(libs.network.ktor.core)
    implementation(libs.network.ktor.okhttp)
    implementation(libs.network.ktor.websockets)
    implementation(libs.network.ktor.server.cio)
    implementation(libs.network.ktor.server.websockets)
    implementation(libs.logs.timber)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.network.ktor.client.cio)
    testImplementation(libs.network.ktor.tls.certificates)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
