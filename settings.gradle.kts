pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "FileManager"
include(":agent")
include(":app")
include(":container")
include(":core")
include(":daemon")
include(":hub")
include(":proxy")
include(":provider")
include(":pulse")
include(":uds")
include(":wayland")
