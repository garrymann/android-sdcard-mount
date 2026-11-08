// Root build file.
//
// ---------------------------------------------------------------------------------------------
// AGP 9 built-in Kotlin — read before adding a plugin to any module
// ---------------------------------------------------------------------------------------------
// AGP 9.0 introduced built-in Kotlin support and enables it by default. Consequences:
//
//   * DO NOT apply `org.jetbrains.kotlin.android` to an Android module. It is incompatible with the
//     new DSL and fails with "Cannot add extension with name 'kotlin'". Every Compose sample
//     published before mid-2026 still tells you to apply it.
//   * `android { kotlinOptions { } }` is gone; use `kotlin { compilerOptions { } }`.
//   * `kotlin-kapt` is incompatible with built-in Kotlin. There is therefore no annotation
//     processing anywhere in this project — no Hilt, no Room. Dependency wiring is a hand-rolled
//     ServiceLocator (app/.../ServiceLocator.kt). That is a deliberate constraint. If a dependency
//     ever demands KSP, reopen the decision rather than adding a processor quietly.
//   * A non-Android module (:core-fs) is not covered by built-in Kotlin and still applies
//     `org.jetbrains.kotlin.jvm`.
//
// The classpath pin below exists because AGP 9.3.1 declares kotlin-gradle-plugin 2.2.10
// transitively. Since no Kotlin plugin is applied to :app, the effective Kotlin version would
// otherwise be decided by conflict resolution between AGP and the Compose compiler plugin.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradlePlugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
