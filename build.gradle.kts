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
