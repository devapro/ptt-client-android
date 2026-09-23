pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PTTdroid"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
include(":app")
include(":shared")
include(":core")
// Phase 2 of the module-graph refactor (docs/architecture.md): feature contracts
// (`:features-api:*` — Screen + ScreenFactory types only, no implementations), their
// implementations (`:features:*` — the moved screens), and the single root host (`:ui-shell`)
// that owns App()/MainViewController and the PTTdroidShared iOS framework export.
include(":features-api:main")
include(":features-api:settings")
include(":features-api:history")
include(":features:main")
include(":features:settings")
include(":features:history")
include(":ui-shell")
include(":desktopApp")
