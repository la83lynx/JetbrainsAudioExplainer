// Targets Rider 2026.1 (branch 261) and later. When bumping, check the IntelliJ Platform Gradle Plugin releases:
//   https://github.com/JetBrains/intellij-platform-gradle-plugin/releases
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
        // Day to day: the locally installed Rider (2026.1.5, build 261.27258.81) — a full install carries the
        // .NET/ReSharper backend that `runIde` needs to launch. Folder name is stale; build.txt confirms 261.
        // Override the path with -PlocalRider=<dir>.
        // A release or CI build (-Prelease): the published Rider, reproducible and machine-independent.
        if (providers.gradleProperty("release").isPresent) {
            rider("2026.1.4") { useInstaller = false }
        } else {
            local(providers.gradleProperty("localRider").orElse("C:/Program Files/JetBrains/JetBrains Rider 2024.3.2"))
        }
        pluginVerifier()
    }
    // Used by ExplainerServer for the wire protocol. If a bundled-version clash appears at runtime,
    // switch to compileOnly and rely on the platform-bundled kotlinx-serialization.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

// The IDE ships the Kotlin stdlib; bundling the copy kotlinx-serialization drags in is flagged by the
// Marketplace. Excluded on the configuration, because a per-dependency exclude does not survive Gradle's
// redirect to the `-jvm` variant.
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            // No upper bound: a pure platform plugin, so it loads in later IDEs; verifyPlugin is the check.
            untilBuild = provider { null }
        }
    }
    // Secrets come from the environment only — never from a committed file. See RELEASING.md (local).
    signing {
        certificateChainFile = providers.environmentVariable("CERTIFICATE_CHAIN_FILE").map { layout.projectDirectory.file(it) }
        privateKeyFile = providers.environmentVariable("PRIVATE_KEY_FILE").map { layout.projectDirectory.file(it) }
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // -PpluginChannel=beta stages a release; the default channel is stable.
        channels = providers.gradleProperty("pluginChannel").map { listOf(it) }.orElse(listOf("default"))
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

// LICENSE carries the plugin's MIT licence and code-explainer's notice, which must travel with the reused
// assets: at the plugin's root in the .zip, and in the jar's META-INF.
tasks.prepareSandbox {
    from(layout.projectDirectory.file("LICENSE")) {
        into(pluginName)
    }
}
tasks.jar {
    from(layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
    }
}

// Gaps in the Gradle plugin's signing tasks (2.18.1 on Gradle 9): both hold a Project reference the
// configuration cache cannot store, and verifyPluginSignature reads signPlugin's output without depending on it.
tasks.signPlugin {
    notCompatibleWithConfigurationCache("holds a Project reference")
}
tasks.verifyPluginSignature {
    dependsOn(tasks.signPlugin)
    notCompatibleWithConfigurationCache("holds a Project reference")
}

kotlin {
    jvmToolchain(21)
}

/**
 * Dev-run diagnostics.
 *
 * Where the log is:
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

    // The sandbox reopens whichever solution was last used; open a real .NET solution by hand the first time.
    //
    // Close any running sandbox IDE first: a live one holds a Windows jar lock and prepareSandbox fails.
}
