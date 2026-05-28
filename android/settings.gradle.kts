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
        // Repo Maven privado de Mapbox — token va en gradle.properties (no en repo).
        // Si no tienes MAPBOX_DOWNLOADS_TOKEN, comenta este bloque. Mapbox solo se
        // usa en BarrioMapScreen; sin él la app igual compila si esa pantalla
        // está stubbed.
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            authentication {
                create<BasicAuthentication>("basic")
            }
            credentials {
                username = "mapbox"
                password = providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN").orElse("").get()
            }
        }
    }
}

rootProject.name = "RAIZ"
include(":app")
