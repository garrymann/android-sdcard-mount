pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()

        // FR-301: libsu is distributed via JitPack, not Maven Central.
        //
        // exclusiveContent means JitPack is consulted for this group and nothing else, and no other
        // repository may serve it. A plain `content { includeGroup(...) }` filter is not exclusive.
        //
        // Gradle has no per-flavor repository scoping — repositories are per-build or per-project,
        // never per-variant. The guarantee FR-822/FR-823 actually need is that libsu is absent from
        // the play artifact, and that comes from declaring it on `fossImplementation` in
        // app/build.gradle.kts, which is checked by the verifyPlayHasNoRootCode task.
        exclusiveContent {
            forRepository {
                maven {
                    name = "JitPack"
                    url = uri("https://jitpack.io")
                }
            }
            filter { includeGroupByRegex("com\\.github\\.topjohnwu.*") }
        }
    }
}

rootProject.name = "removable-storage-manager"

include(":app")
include(":core-fs")
