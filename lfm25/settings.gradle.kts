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
        // Tesseract4Android (offline OCR that reads the payment receipt) is published on JitPack.
        // Restricted to its own group so JitPack cannot supply anything else.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("cz\\.adaptech.*") }
        }
    }
}

rootProject.name = "lfm2.5"
include(":app")
 