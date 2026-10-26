pluginManagement {
    repositories {
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Prebuilt wldroid AARs (wlroots compositor + virgl + shims) fetched by
        // scripts/fetch-wldroid.sh from the project's GitHub releases. Must
        // come first: public mirrors serve bogus metadata for unknown
        // coordinates, which would shadow the local artifacts.
        val wldroidRepo = rootProject.projectDir.resolve("third_party/wldroid-maven")
        if (!wldroidRepo.isDirectory) {
            throw GradleException(
                "third_party/wldroid-maven is missing — run scripts/fetch-wldroid.sh first",
            )
        }
        maven(url = uri(wldroidRepo)) {
            content { includeGroup("nu.shel.wldroid") }
        }
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
        maven(url = "https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "FileManager"
include(":app")
include(":container")
include(":core")
include(":daemon")
include(":hub")
include(":provider")
include(":pulse")
include(":uds")
include(":wayland")
