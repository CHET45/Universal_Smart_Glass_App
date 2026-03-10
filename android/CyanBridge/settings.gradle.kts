pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        jcenter()
        maven { url = uri("https://jitpack.io") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        jcenter()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "CyanBridge"
include(":app")
include(":LIB_GLASSES_SDK")

// Shared core (optional local composite build)
val sharedCoreDir = file("../../../heycyan-android-core")
if (sharedCoreDir.exists()) {
    includeBuild(sharedCoreDir)
}
