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
        val coreRepoUrl = System.getenv("HEYCYAN_CORE_MAVEN_URL")
        if (!coreRepoUrl.isNullOrBlank()) {
            maven {
                url = uri(coreRepoUrl)
                credentials {
                    username = System.getenv("HEYCYAN_CORE_MAVEN_USER")
                    password = System.getenv("HEYCYAN_CORE_MAVEN_PASSWORD")
                }
            }
        }
        mavenLocal()
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
val useLocalSharedCore = (gradle.startParameter.projectProperties["useLocalSharedCore"] ?: "true").toBoolean()
if (useLocalSharedCore && sharedCoreDir.exists()) {
    includeBuild(sharedCoreDir)
}
