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
    }
}

rootProject.name = "StageScope"

// :app is the Wear OS watch application (kept at this path so existing scripts/APK paths work).
// :shared holds the serializable contracts + pure logic both apps use; :phone is the Pixel companion.
include(":app")
include(":shared")
include(":phone")
