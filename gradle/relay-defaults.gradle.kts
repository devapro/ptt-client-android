import java.util.Properties

/**
 * The single parser for the two build-time config files that must keep working in a plain
 * checkout with no Gradle properties set (F-Droid builds that way): `version.properties` and
 * `relay.properties`, both at the repository root.
 *
 * `:shared` cannot use `BuildConfig` (it is not an Android application module), so its version
 * and relay defaults have to reach it as generated Kotlin instead — see the `generatePttDefaults`
 * task in `shared/build.gradle.kts`. `:app` still uses `BuildConfig`. Both read the values from
 * here so there is exactly one copy of each regex, not a second one that can silently drift.
 *
 * Applied via `apply(from = "$rootDir/gradle/relay-defaults.gradle.kts")` from every module that
 * needs these values (`:app`, `:shared`, `:desktopApp`). The logic mirrors, field for field, what
 * `app/build.gradle.kts` used to parse inline — including how it fails a missing/malformed file.
 */

data class PttAppVersion(val name: String, val code: Int)

data class PttRelayDefaults(val host: String, val port: Int, val tls: Boolean)

fun readAppVersion(rootDir: File): PttAppVersion {
    val appVersionName: String = rootDir.resolve("version.properties")
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

    return PttAppVersion(appVersionName, appVersionCode)
}

fun readRelayDefaults(
    rootDir: File,
    gradlePropertyOverride: String?,
    envOverride: String?,
): PttRelayDefaults {
    val defaultRelay: String = (
        gradlePropertyOverride
            ?: envOverride
            ?: rootDir.resolve("relay.properties")
                .takeIf { it.isFile }
                ?.let { file ->
                    Properties()
                        .apply { file.inputStream().use { stream -> load(stream) } }
                        .getProperty("defaultRelay")
                }
        )
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: error("relay.properties is missing or has no defaultRelay")

    val defaultRelayParts = Regex("""^(ws|wss)://([A-Za-z0-9._-]+|\[[0-9A-Fa-f:.]+]):([0-9]{1,5})$""")
        .matchEntire(defaultRelay)
        ?: error("defaultRelay: '$defaultRelay' is not scheme://host:port, e.g. ws://10.0.2.2:8000")

    val host = defaultRelayParts.groupValues[2]
    val port = defaultRelayParts.groupValues[3].toInt().also {
        require(it in 1..65_535) { "defaultRelay: port $it is outside 1..65535" }
    }
    val tls = defaultRelayParts.groupValues[1] == "wss"

    return PttRelayDefaults(host, port, tls)
}

val pttAppVersion = readAppVersion(rootDir)
val pttRelayDefaults = readRelayDefaults(
    rootDir = rootDir,
    gradlePropertyOverride = providers.gradleProperty("pttDefaultRelay").orNull,
    envOverride = System.getenv("PTT_DEFAULT_RELAY"),
)

extra["appVersionName"] = pttAppVersion.name
extra["appVersionCode"] = pttAppVersion.code
extra["defaultRelayHost"] = pttRelayDefaults.host
extra["defaultRelayPort"] = pttRelayDefaults.port
extra["defaultRelayTls"] = pttRelayDefaults.tls
