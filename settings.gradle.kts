pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "JPQ"
include(":core", ":app")
include(":phoneProbe")
project(":phoneProbe").projectDir=file("tools/android/phone-probe")
