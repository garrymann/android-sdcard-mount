// :core-fs — pure JVM. No Android dependency, by design and by rule.
//
// Everything that parses a partition table, identifies a filesystem, speaks SCSI, or reads and
// writes FAT lives here, behind the BlockDevice interface. That is what makes the FR-920 reference
// corpus runnable as plain JUnit on a Windows developer machine with no emulator and no device:
// the tests drive ImageBlockDevice over a byte array.
//
// If you find yourself wanting `android.util.Log` or a `Context` in this module, the abstraction is
// in the wrong place. Pass a value in, or put the code in :app.

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Treat the module's own warnings as signal; this code writes to users' filesystems.
        allWarningsAsErrors.set(false)
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Gradle 9 fails a Test task that runs but discovers nothing, which is the behaviour we want:
    // a forgotten useJUnitPlatform() should be loud, not silently green.
    testLogging {
        events("failed", "skipped")
        showStackTraces = true
    }
}

// Guard: this module must never gain an Android import. Cheap, runs in `check`, fails loudly.
val verifyNoAndroidImports by tasks.registering {
    group = "verification"
    description = "Fails if :core-fs references the Android SDK, which would break JVM-only testing."
    val sources = fileTree("src") { include("**/*.kt") }
    inputs.files(sources)
    outputs.upToDateWhen { false }
    // Config cache forbids capturing live Gradle/script objects (the FileTree, `projectDir`) in
    // doLast; resolve to plain File values here so the task action only closes over serializable data.
    val sourceFiles = sources.files
    val projectDirectory = projectDir
    doLast {
        val offenders = sourceFiles.filter { file ->
            file.readLines().any { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "core-fs must stay Android-free, but these files import the Android SDK:\n" +
                    offenders.joinToString("\n") { "  ${it.relativeTo(projectDirectory)}" }
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyNoAndroidImports) }
