import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * `packageVersion` below has to be the same versionName every other module ships — see
 * `gradle/relay-defaults.gradle.kts` for why it is read from `version.properties` rather than
 * written here a second time.
 */
apply(from = "$rootDir/gradle/relay-defaults.gradle.kts")

val appVersionName: String by extra

plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.jetbrainsKotlinCompose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    // :shared depends on these `implementation`-scoped, so they do not reach this module
    // transitively; Main.kt starts Koin itself, reads the graph through the Compose integration
    // (`koinInject` — see its kdoc for why not `koinViewModel`), and resolves a CMP string
    // resource directly for the one snackbar event it forwards.
    implementation(libs.di.koin.core)
    implementation(libs.di.koin.compose)
    implementation(compose.components.resources)
    implementation(compose.material3)
    implementation(libs.lifecycle.viewmodel.compose.multiplatform)
}

compose.desktop {
    application {
        mainClass = "com.github.devapro.pttdroid.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "PTTdroid"
            packageVersion = appVersionName
        }
    }
}
