pluginManagement {
    plugins { id("io.github.supermonster003.autojs6-native-alignment") version "1.8.0" }
    providers.gradleProperty("autojs.buildPlugins.includeBuild").orNull?.let { includeBuild(it) }
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

rootProject.name = "autojs6-plugin-lua-runtime"
include(":app")
include(":host-lifecycle-test")
