plugins {
    alias(libs.plugins.android.application)
    // No kotlin-android: AGP 9 built-in Kotlin covers this module. See root build.gradle.kts.
    alias(libs.plugins.compose.compiler)
}

// ---------------------------------------------------------------------------------------------
// Automatic versioning
// ---------------------------------------------------------------------------------------------
// versionCode and versionName are derived from git and are never hand-edited. Cutting a release is
// `git tag v1.2.3`; nothing in the repository changes.
//
// ### Why providers.exec and not ProcessBuilder
//
// gradle.properties enables the configuration cache. A bare `ProcessBuilder("git", ...)` here would
// be an *undeclared* configuration input: Gradle would serialise whatever string the first build
// computed and reuse it, so the version would silently freeze at whichever commit happened to be
// HEAD when the cache entry was written — a stale versionName is exactly the kind of bug that is
// invisible until a release is already out. `providers.exec` is backed by the same ValueSource
// machinery Gradle re-evaluates on every build precisely because inputs like this cannot be
// declared, so the version tracks HEAD and a change to it invalidates the entry.
//
// Same discipline as verifyPlayHasNoRootCode at the bottom of this file: nothing below captures a
// live Gradle script object, only plain File/String values.

/** Repo root as a plain File, so the exec actions below capture no Gradle object. */
val repoRoot: File = rootDir

/** Trimmed stdout of a git command, or null when git is unavailable or the command failed. */
fun gitOutput(vararg args: String): String? {
    val execOutput = providers.exec {
        workingDir(repoRoot)
        commandLine(listOf("git") + args)
        // `git describe` exits 128 with "fatal: No names found" when no tag is reachable, and a
        // missing binary throws outright. Neither is a build failure — both mean "use the
        // version.properties fallback", so the exit code is inspected rather than enforced.
        isIgnoreExitValue = true
    }
    return runCatching {
        if (execOutput.result.get().exitValue != 0) return@runCatching null
        execOutput.standardOutput.asText.get().trim().ifEmpty { null }
    }.getOrNull()
}

// Read through providers.fileContents rather than File.readText() so the file is a tracked
// configuration input: editing version.properties then invalidates the configuration cache.
val fallbackBaseVersion: String = providers
    .fileContents(rootProject.layout.projectDirectory.file("version.properties"))
    .asText
    .map { text -> Regex("""(?m)^\s*baseVersion\s*=\s*(\S+)\s*$""").find(text)?.groupValues?.get(1) }
    .orNull
    ?: "0.0.0"

// --long keeps the `-0-g<sha>` suffix even on an exact tag match, so one regex covers both the
// on-tag and the ahead-of-tag case. --match keeps any non-release tag out of the answer.
val gitDescribe: String? = gitOutput("describe", "--tags", "--long", "--dirty", "--match=v[0-9]*")
val describedVersion = gitDescribe?.let {
    Regex("""^v(\d+)\.(\d+)\.(\d+)-(\d+)-g([0-9a-f]+)(-dirty)?$""").find(it)
}

// versionCode packs the semver into one monotonically increasing integer:
//     major * 1_000_000 + minor * 10_000 + patch * 100 + min(commitsSinceTag, 99)
// The min() is what preserves monotonicity. Without the cap, 150 commits past v0.2.0 would produce
// 200150, which the following v0.2.1 release (200100) would fail to beat. Capping at 99 means dev
// builds fill the gap below the next patch release and can never overtake it. Ceiling is well under
// Play's 2_100_000_000 limit: major 2100 is the first value that would overflow it.
val resolvedVersion: Pair<String, Int> = if (describedVersion != null) {
    val (major, minor, patch, sinceTag, sha, dirtyMarker) = describedVersion.destructured
    val commits = sinceTag.toInt()
    val dirty = dirtyMarker.isNotEmpty()
    val semver = "$major.$minor.$patch"

    val name = if (commits == 0 && !dirty) {
        semver
    } else {
        // '+' introduces semver *build metadata*, which does not reorder the version — correct here,
        // because this build is after $semver, not a pre-release of it. A '-dev' suffix would sort
        // the string before the tag it is ahead of, contradicting the versionCode.
        buildString {
            append(semver).append('+').append(commits).append(".g").append(sha)
            if (dirty) append(".dirty")
        }
    }
    val code = major.toInt() * 1_000_000 +
        minor.toInt() * 10_000 +
        patch.toInt() * 100 +
        commits.coerceAtMost(99)
    name to code
} else {
    // No reachable v* tag: bootstrap from version.properties. Here '-dev.N' *is* right, because a
    // pre-tag build genuinely precedes the release, and a semver pre-release sorts before it — so
    // the string ordering and the integer ordering agree.
    val commits = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0
    val shortSha = gitOutput("rev-parse", "--short=7", "HEAD")
    // --untracked-files=no matches `git describe --dirty`, which considers only tracked
    // modifications. Verified: with one untracked file, describe reports no -dirty while a bare
    // `status --porcelain` does — so without this flag the same working tree would be labelled
    // dirty on the fallback path and clean on the tagged path. A git failure returns null and is
    // read as clean, erring away from labelling a release build "dirty" on a tooling hiccup.
    val dirty = gitOutput("status", "--porcelain", "--untracked-files=no") != null

    val name = buildString {
        append(fallbackBaseVersion).append("-dev.").append(commits)
        if (shortSha != null) append("+g").append(shortSha)
        if (dirty) append(".dirty")
    }
    // Deliberately two digits: below the 100 that even v0.0.1 yields, so the first tagged release
    // outranks every pre-tag build. Floor of 1 because Android rejects versionCode 0.
    name to commits.coerceIn(1, 99)
}

val appVersionName: String = resolvedVersion.first
val appVersionCode: Int = resolvedVersion.second

// ---------------------------------------------------------------------------------------------
// Release signing
// ---------------------------------------------------------------------------------------------
// The keystore never enters the repository. CI decodes it from repository secrets and passes its
// location and password in the environment (see .github/workflows/release.yml). Read through
// providers.environmentVariable so the values are tracked configuration-cache inputs.
//
// Without these variables the release build type is left unsigned rather than falling back to the
// debug key: a debug key differs per machine, and an APK signed with it could never be updated by
// the next release.
val releaseKeystorePath: String? = providers.environmentVariable("RSM_KEYSTORE_PATH").orNull
val releaseKeystorePassword: String? = providers.environmentVariable("RSM_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias: String = providers.environmentVariable("RSM_KEY_ALIAS").orNull ?: "rsm-release"

android {
    namespace = "dev.rsm"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Kept distinct from the Kotlin namespace: the applicationId must be a reverse domain the
        // project controls, and it can never change once published to Play or F-Droid.
        applicationId = "io.github.garrymann.rsm"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        // Derived above from git. Do not replace with a literal — see the versioning block.
        versionCode = appVersionCode
        versionName = appVersionName

        // dev.rsm.ui.about.AboutScreen reads these. Declared explicitly, under APP_* names, rather
        // than relying on AGP's generated VERSION_NAME/VERSION_CODE: the generated pair is an AGP
        // implementation detail that has been on and off by module type across releases, and the
        // distinct names cannot collide with it either way.
        buildConfigField("String", "APP_VERSION_NAME", "\"$appVersionName\"")
        buildConfigField("int", "APP_VERSION_CODE", "$appVersionCode")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        // Off by default since AGP 8; ROOT_ENABLED below needs it.
        buildConfig = true
        compose = true
        // The privileged filesystem interface (FR-301c, FR-933) is AIDL, in src/foss/aidl.
        aidl = true
    }

    // FR-823: one codebase, one productFlavor dimension, flavors named exactly play and foss.
    // FR-822: root code paths additionally gated behind a build flag.
    //
    // The two obligations are not redundant. The flag alone would leave root code in the Play
    // artifact and merely decline to run it, which FR-823 AC-8 rejects explicitly. The source-set
    // split is what keeps it out of the binary; the flag is defence in depth and a compile-time
    // switch for anything in src/main that must not reach for a root capability.
    flavorDimensions += "distribution"
    productFlavors {
        register("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ROOT_ENABLED", "false")
            buildConfigField("String", "DISTRIBUTION", "\"play\"")
        }
        register("foss") {
            dimension = "distribution"
            buildConfigField("boolean", "ROOT_ENABLED", "true")
            buildConfigField("String", "DISTRIBUTION", "\"foss\"")
        }
    }

    signingConfigs {
        if (releaseKeystorePath != null && releaseKeystorePassword != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storeType = "pkcs12"
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                // PKCS12 keystores carry one password for the store and its keys.
                keyPassword = releaseKeystorePassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // With built-in Kotlin, kotlin.compilerOptions.jvmTarget defaults to
    // android.compileOptions.targetCompatibility, so it is not set again here.

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    implementation(project(":core-fs"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // FR-301 / FR-822 / FR-823: root support is a foss-flavor dependency. Declaring it on
    // `fossImplementation` — not `implementation` — is what keeps libsu out of the Play artifact.
    // verifyPlayHasNoRootCode below fails the build if that ever regresses.
    "fossImplementation"(libs.libsu.core)
    "fossImplementation"(libs.libsu.service)
    "fossImplementation"(libs.libsu.nio)
}

// ---------------------------------------------------------------------------------------------
// FR-822 / FR-823 enforcement
// ---------------------------------------------------------------------------------------------
// FR-823 AC-8 is the requirement most likely to rot: it holds on the day it is written and breaks
// the first time somebody adds a convenient import to src/main. Two assertions, both cheap:
//
//   1. No libsu artifact may appear on any play-variant runtime classpath.
//   2. Nothing in src/main may reference libsu or the root package. Root capabilities are reached
//      only through interfaces whose implementations live in src/foss and src/play.
// src/main is shared by both artifacts, so it may not name libsu or the root implementation package
// at all: root capability is reached only through interfaces declared in dev.rsm.access.
val forbiddenInSharedSource = listOf(
    "com.github.topjohnwu",
    "dev.rsm.root",
)

// src/play legitimately implements those interfaces — with a type that reports root unavailable —
// so only the libsu reference is forbidden there.
val forbiddenInPlaySource = listOf(
    "com.github.topjohnwu",
)

val verifyPlayHasNoRootCode by tasks.registering {
    group = "verification"
    description = "Fails if root code or libsu can reach the Play artifact (FR-822, FR-823)."

    // Config cache forbids capturing live Gradle/script objects (FileTrees, Configurations,
    // `projectDir`) in doLast; everything the task action touches below is resolved to plain,
    // serializable values (File/String) here, at configuration time.
    val mainSourceFiles = fileTree("src/main") { include("**/*.kt", "**/*.java") }.files
    val playSourceFiles = fileTree("src/play") { include("**/*.kt", "**/*.java") }.files
    val playRuntimeConfigurations = configurations.matching {
        it.isCanBeResolved && it.name.startsWith("play") && it.name.endsWith("RuntimeClasspath")
    }
    val configurationProblems = playRuntimeConfigurations.flatMap { configuration ->
        configuration.incoming.artifactView { lenient(true) }.artifacts.artifacts.mapNotNull { artifact ->
            val id = artifact.id.componentIdentifier.displayName
            if (id.contains("topjohnwu")) "${configuration.name} resolves '$id'" else null
        }
    }
    val projectDirectory = projectDir
    // Also plain data: doLast may not read `forbiddenInSharedSource`/`forbiddenInPlaySource`
    // directly, since those top-level script properties are only reachable through the script
    // instance itself, which is exactly the "Gradle script object reference" the cache disallows.
    val sharedSourceNeedles = forbiddenInSharedSource
    val playSourceNeedles = forbiddenInPlaySource

    inputs.files(mainSourceFiles, playSourceFiles)
    outputs.upToDateWhen { false }

    doLast {
        val problems = mutableListOf<String>()

        fun scan(files: Iterable<File>, needles: List<String>) {
            files.forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    needles.forEach { needle ->
                        if (line.contains(needle)) {
                            problems += "${file.relativeTo(projectDirectory)}:${index + 1} references '$needle'"
                        }
                    }
                }
            }
        }
        scan(mainSourceFiles, sharedSourceNeedles)
        scan(playSourceFiles, playSourceNeedles)
        problems += configurationProblems

        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Root code can reach the Play artifact. FR-822/FR-823 violated:")
                    problems.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Root capability must be reached through an interface in src/main whose")
                    appendLine("implementations live in src/foss (real) and src/play (unavailable).")
                }
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyPlayHasNoRootCode) }

// Lets a release be sanity-checked without assembling an artifact, and gives CI something to echo
// into a tag or changelog. Captures plain values, so it is configuration-cache clean.
val printAppVersion by tasks.registering {
    group = "help"
    description = "Prints the git-derived versionName and versionCode."

    val name = appVersionName
    val code = appVersionCode
    val describe = gitDescribe ?: "(no v* tag reachable — version.properties fallback)"

    doLast {
        logger.lifecycle("versionName: $name")
        logger.lifecycle("versionCode: $code")
        logger.lifecycle("git describe: $describe")
    }
}
