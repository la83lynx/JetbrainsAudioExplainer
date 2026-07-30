// Phase 1-2 skeleton. Verify plugin/dependency versions against current releases before first build:
//   - IntelliJ Platform Gradle Plugin: https://github.com/JetBrains/intellij-platform-gradle-plugin/releases
//   - Rider platform version must be an existing build (targeting latest stable Rider 2026.1.4 / branch 261).
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.audioexplainer"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build and run against the locally installed Rider 2026.1.4 (build 261.26222.60).
        // A full local install includes the .NET/ReSharper backend that `runIde` needs to actually launch;
        // the downloadable riderRD package is compile-only. Folder name is stale — build.txt confirms 261.
        // For CI, swap back to `rider("2026.1.4")` (compile-only there).
        local("C:/Program Files/JetBrains/JetBrains Rider 2024.3.2")
    }
    // Used by ExplainerServer for the wire protocol. If a bundled-version clash appears at runtime,
    // switch to compileOnly and rely on the platform-bundled kotlinx-serialization.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "261.*" // widen or clear once you decide the supported range
        }
    }
}

kotlin {
    jvmToolchain(21)
}

/**
 * Dev-run diagnostics.
 *
 * Where the log actually is — this cost a debugging session, so it is written down:
 *  1. NOT `build/idea-sandbox/`. That tree is a leftover from an older Gradle-plugin sandbox layout
 *     and its `log/`+`system/` dirs are empty forever, which looks exactly like "no log is written".
 *     The live sandbox is under `.intellijPlatform/sandbox/<project>/<ide>/`, so the log is:
 *         .intellijPlatform/sandbox/JetbrainsAudioExplainer/RD-2026.1.4/log/idea.log
 *     The IDE prints its own log location during startup ("Log directory: file:///…") — trust that
 *     line over any remembered path.
 *  2. `runIde`'s **stdout carries WARN and above only**, so `debug`/`info` never appear there. Teeing
 *     the console (`.\gradlew.bat runIde 2>&1 | Tee-Object -FilePath build\runIde.log`) is still worth
 *     doing for startup/crash diagnosis — and never pipe through `Select-Object -Last N`, which buffers
 *     the whole stream and then discards the startup lines — but read idea.log for our own output.
 *  3. Our `log.debug` calls are dropped unless the category is enabled. This turns them on for every
 *     `runIde`, so the decoration/narration timeline is visible without touching code. They land in
 *     idea.log at level FINE (java.util.logging's name for DEBUG) — grep for `#com.audioexplainer`.
 */
tasks.runIde {
    systemProperty("idea.log.debug.categories", "#com.audioexplainer")
}
