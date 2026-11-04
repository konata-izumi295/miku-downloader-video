
pluginManagement {

    repositories {
        google()

        mavenCentral()

        gradlePluginPortal()
    }
}

dependencyResolutionManagement {

    repositoriesMode.set(
        RepositoriesMode.FAIL_ON_PROJECT_REPOS
    )

    repositories {

        // Repositorio oficial de Android.
        google()

        // Aquí está publicado yt-dlp Android.
        mavenCentral()
    }
}

// Nombre de nuestro proyecto.
rootProject.name = "mikudownloadvideo"

// Módulo principal de la aplicación.
include(":app")
