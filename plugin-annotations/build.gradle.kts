@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.binary.compatibility.validator)
    `maven-publish`
}

kotlin {
    explicitApi()

    androidNativeArm64()
    androidNativeX64()

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    js().nodejs()

    jvm()

    linuxArm64()
    linuxX64()

    macosArm64()

    mingwX64()

    wasmJs().nodejs()
    wasmWasi().nodejs()

    applyDefaultHierarchyTemplate()

    sourceSets {
        // "unixMain" is not in the default hierarchy and cannot be used as a
        // real intermediate source set because pthread types differ across
        // platform families. Instead, we add the shared POSIX source directory
        // as an extra source root to every POSIX-capable family source set.
        // This avoids a compileUnixMainKotlinMetadata step entirely.
        // Note: androidNativeMain is safe here because only 64-bit targets remain
        // (androidNativeArm64, androidNativeX64) so pthread_t is uniformly 64-bit.
        val unixSrcDir = "src/unixMain/kotlin"
        appleMain.get().kotlin.srcDir(unixSrcDir)
        linuxMain.get().kotlin.srcDir(unixSrcDir)
        androidNativeMain.get().kotlin.srcDir(unixSrcDir)
        // mingwX64 uses its own stub in src/mingwMain/kotlin (no pthreads).
    }
}
