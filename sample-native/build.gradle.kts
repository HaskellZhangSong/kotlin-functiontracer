plugins {
    // Use the same Kotlin version as the plugin itself.
    kotlin("multiplatform") version "2.3.20"

    // Apply the local function-tracer compiler plugin (resolved via includeBuild in settings).
    id("dev.songzh.functiontracer")
}

group = "org.example"
version = "1.0.0"

kotlin {
    // ── macOS targets ──────────────────────────────────────────────────────────
    // macosX64 is deprecated in Kotlin 2.3 (tier-3); use macosArm64 on Apple Silicon.
    macosArm64 {
        binaries { executable { entryPoint = "org.example.main" } }
    }

    // ── Linux targets ─────────────────────────────────────────────────────────
    linuxArm64 {
        binaries { executable { entryPoint = "org.example.main" } }
    }
    linuxX64 {
        binaries { executable { entryPoint = "org.example.main" } }
    }

    sourceSets {
        commonMain.dependencies {
            // plugin-annotations is added automatically by the compiler plugin,
            // but we declare it explicitly here so the IDE can resolve @Trace.
            implementation("dev.songzh.functiontracer:plugin-annotations:0.1.0-SNAPSHOT")
        }

        // POSIX (pthread) code lives in src/unixMain/kotlin and is added as a
        // srcDir to every target instead of an intermediate source set, so it is
        // never compiled as common metadata (which cannot see platform.posix).
        val unixSrcDir = "src/unixMain/kotlin"
        macosArm64Main.get().kotlin.srcDir(unixSrcDir)
        linuxArm64Main.get().kotlin.srcDir(unixSrcDir)
        linuxX64Main.get().kotlin.srcDir(unixSrcDir)
    }
}

// ── Plugin configuration ─────────────────────────────────────────────────────
functionTracer {
    // false  → only functions annotated with @Trace are instrumented (default)
    // true   → every non-inline function in the module is instrumented
    traceAll = false
    logFile = "/tmp/trace.log"       // write trace output to a file; omit (or set to "") for stdout
}
