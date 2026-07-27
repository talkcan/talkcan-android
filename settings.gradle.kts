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

rootProject.name = "Talkcan"
include(":app")
include(":sleepwalker-core")

// Point :sleepwalker-core at the flake input checkout when SLEEPWALKER_CORE_PATH
// is set (Nix devshell). Falls back to the in-tree vendored copy otherwise.
val sleepwalkerCorePath = System.getenv("SLEEPWALKER_CORE_PATH")
if (!sleepwalkerCorePath.isNullOrEmpty()) {
    project(":sleepwalker-core").projectDir = file(sleepwalkerCorePath)
    // The module lives in a read-only Nix store path; redirect its build
    // output to the Talkcan repo's build directory.
    gradle.beforeProject {
        if (project.path == ":sleepwalker-core") {
        project.layout.buildDirectory = rootProject.layout.buildDirectory.dir("sleepwalker-core")
        }
    }
}
