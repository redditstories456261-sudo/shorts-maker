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
        // FFmpegKit (maintained fork) + yt-dlp-android releases are published here:
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ShortsMaker"
include(":app")
