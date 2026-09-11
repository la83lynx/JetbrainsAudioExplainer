package com.audioexplainer

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

/**
 * Phase 7 — the guided TTS provisioning tasks. A plugin cannot hook its own Marketplace install, so
 * provisioning is a first-run/on-demand flow instead: the [firstRunPrompt] balloon (shown once when the
 * user first presses Play with Kokoro not set up) offers a one-click install or an opt-out, and the
 * **Settings → Tools → Audio Explainer → Audio engines** section drives the same [install] /
 * [installLanguagePacks] background tasks from its per-engine buttons (Phase L7 cleanup — the old modal
 * "Set up TTS" chooser was retired in favour of the by-engine settings UI).
 *
 * Kokoro is the primary engine; the robotic espeak tier (L6) and — on Windows — the System.Speech tier
 * (L7) ride the same server and are chosen per voice. The install helpers here are Kokoro/pack specific;
 * the Windows voices are auto-discovered and installing neural ones is the user's own
 * NaturalVoiceSAPIAdapter step (linked from the settings section).
 */
object TtsSetup {
    private const val GROUP = "Audio Explainer"

    private val settings get() = AudioExplainerSettings.getInstance()
    private val tts get() = TtsService.getInstance()

    private fun anyProject(project: Project?): Project? =
        project ?: ProjectManager.getInstance().openProjects.firstOrNull()

    /** First-run, non-modal offer shown once when the user presses Play with Kokoro not yet set up. */
    fun firstRunPrompt(project: Project?) {
        ApplicationManager.getApplication().invokeLater {
            val p = anyProject(project) ?: return@invokeLater
            NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                .createNotification(
                    "Set up narration audio",
                    "The walkthrough is playing silently. Install the local <b>Kokoro</b> voice model " +
                        "(~340 MB) for spoken narration, or turn audio off.",
                    NotificationType.INFORMATION,
                )
                .addAction(NotificationAction.createSimple("Install Kokoro (~340 MB)") { install(p) })
                .addAction(NotificationAction.createSimple("Turn off audio") { setEngine(AudioExplainerSettings.ENGINE_OFF, p) })
                .notify(p)
        }
    }

    /**
     * Background install: pip dependencies (if needed) → model download (if needed) → re-check. [onDone]
     * (if given) runs on the EDT with `ModalityState.any()` after the task finishes — the settings section
     * passes it to refresh its per-engine status labels while the (modal) Settings dialog is still open.
     */
    fun install(project: Project?, onDone: (() -> Unit)? = null) {
        settings.state.ttsSetupPrompted = true
        ProgressManager.getInstance().run(object : Task.Backgroundable(anyProject(project), "Setting up Kokoro TTS", true) {
            override fun run(indicator: ProgressIndicator): Unit = withDone(onDone) {
                indicator.isIndeterminate = true
                var status = tts.status(refresh = true)
                if (status.availability == TtsService.Availability.PYTHON_MISSING) {
                    notify(project, NotificationType.ERROR, "Kokoro setup failed",
                        "No Python interpreter was found on PATH. Install Python 3.10+ and try again.")
                    return
                }
                if (status.availability == TtsService.Availability.KOKORO_MISSING ||
                    status.availability == TtsService.Availability.UNKNOWN
                ) {
                    if (!tts.installDependencies(indicator)) {
                        notify(project, NotificationType.ERROR, "Kokoro setup failed",
                            "<code>pip install kokoro-onnx onnxruntime</code> did not complete. See the IDE log for details.")
                        return
                    }
                    status = tts.status(refresh = true)
                }
                if (indicator.isCanceled) return
                if (status.availability == TtsService.Availability.MODEL_MISSING) {
                    indicator.isIndeterminate = false
                    if (!tts.downloadModel(indicator)) {
                        notify(project, NotificationType.ERROR, "Kokoro setup failed",
                            "The voice model download did not complete. Check your connection and try again.")
                        return
                    }
                }
                val fin = tts.status(refresh = true)
                if (fin.ready) {
                    settings.state.ttsEngine = AudioExplainerSettings.ENGINE_KOKORO
                    tts.restart()
                    notify(project, NotificationType.INFORMATION, "Kokoro TTS ready",
                        "Narration is set up. Press <b>Play</b> in the Audio Explainer sidebar to hear the walkthrough.")
                } else {
                    notify(project, NotificationType.WARNING, "Kokoro setup incomplete",
                        "Setup ran but the prerequisites still aren't all present (${fin.availability}). " +
                            "Try again from <b>Settings → Tools → Audio Explainer → Audio engines</b>.")
                }
            }
        })
    }

    /**
     * Background install of the Japanese/Chinese misaki packs (Phase L5), then re-check + report which
     * of the two ended up importable. Independent of the Kokoro model download; if the base model isn't
     * present yet the packs still install, and the notification points the user to install Kokoro too.
     */
    fun installLanguagePacks(project: Project?, onDone: (() -> Unit)? = null) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(anyProject(project), "Installing Japanese/Chinese TTS packs", true) {
            override fun run(indicator: ProgressIndicator): Unit = withDone(onDone) {
                indicator.isIndeterminate = true
                val status = tts.status(refresh = true)
                if (status.availability == TtsService.Availability.PYTHON_MISSING) {
                    notify(project, NotificationType.ERROR, "TTS pack install failed",
                        "No Python interpreter was found on PATH. Install Python 3.10+ and try again.")
                    return
                }
                if (!tts.installLanguagePacks(indicator, listOf("ja", "zh"))) {
                    notify(project, NotificationType.ERROR, "TTS pack install failed",
                        "<code>pip install misaki[ja] misaki[zh]</code> did not complete. See the IDE log for details.")
                    return
                }
                if (indicator.isCanceled) return
                val fin = tts.status(refresh = true)
                tts.restart()
                val ready = listOfNotNull(
                    if (fin.misakiJa) "Japanese" else null,
                    if (fin.misakiZh) "Chinese" else null,
                )
                when {
                    ready.isEmpty() -> notify(project, NotificationType.WARNING, "TTS packs incomplete",
                        "The misaki packs ran but still aren't importable. Try again, or check the IDE log.")
                    !fin.ready -> notify(project, NotificationType.INFORMATION, "TTS packs installed",
                        "${ready.joinToString(" & ")} narration is set up. Install the Kokoro voice model too " +
                            "(Settings → Audio engines → Kokoro → Install) to actually hear audio.")
                    else -> notify(project, NotificationType.INFORMATION, "TTS packs installed",
                        "${ready.joinToString(" & ")} narration is ready. Generate or load a walkthrough in that language.")
                }
            }
        })
    }

    /**
     * Background install of the Piper offline-neural tier (Phase L8): `pip install piper-tts`, then re-check
     * + restart the server so it routes `engine=piper`. Additive — reuses the onnxruntime/numpy Kokoro
     * already pulled. [onDone] runs on the EDT (`ModalityState.any()`) so the settings panel can refresh.
     */
    fun installPiper(project: Project?, onDone: (() -> Unit)? = null) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(anyProject(project), "Installing Piper (offline-neural TTS)", true) {
            override fun run(indicator: ProgressIndicator): Unit = withDone(onDone) {
                indicator.isIndeterminate = true
                val status = tts.status(refresh = true)
                if (status.availability == TtsService.Availability.PYTHON_MISSING) {
                    notify(project, NotificationType.ERROR, "Piper setup failed",
                        "No Python interpreter was found on PATH. Install Python 3.10+ and try again.")
                    return
                }
                if (!tts.installPiper(indicator)) {
                    notify(project, NotificationType.ERROR, "Piper setup failed",
                        "<code>pip install piper-tts</code> did not complete. See the IDE log for details.")
                    return
                }
                if (indicator.isCanceled) return
                val fin = tts.status(refresh = true)
                if (fin.piperImportable) {
                    tts.restart()
                    notify(project, NotificationType.INFORMATION, "Piper installed",
                        "The offline-neural tier is installed. Add a voice under <b>Settings → Tools → Audio " +
                            "Explainer → Audio engines → Piper</b> to narrate a new language.")
                } else {
                    notify(project, NotificationType.WARNING, "Piper setup incomplete",
                        "Setup ran but <code>piper-tts</code> still isn't importable. Try again, or check the IDE log.")
                }
            }
        })
    }

    /**
     * Background download of one Piper voice model (Phase L8): installs `piper-tts` first if needed, fetches
     * the voice via `--download-piper <key>`, then re-checks + restarts. [voiceKey] is an HF piper-voices key
     * (chosen in the [PiperVoiceManagerDialog]). [onDone] runs on the EDT so the settings panel can refresh.
     */
    fun downloadPiperVoice(project: Project?, voiceKey: String, onDone: (() -> Unit)? = null) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(anyProject(project), "Downloading Piper voice", true) {
            override fun run(indicator: ProgressIndicator): Unit = withDone(onDone) {
                indicator.isIndeterminate = true
                val status = tts.status(refresh = true)
                if (status.availability == TtsService.Availability.PYTHON_MISSING) {
                    notify(project, NotificationType.ERROR, "Piper voice download failed",
                        "No Python interpreter was found on PATH. Install Python 3.10+ and try again.")
                    return
                }
                // piper-tts must be importable before a downloaded voice can be synthesized — install if missing.
                if (!status.piperImportable && !tts.installPiper(indicator)) {
                    notify(project, NotificationType.ERROR, "Piper voice download failed",
                        "<code>pip install piper-tts</code> did not complete (needed before downloading a voice).")
                    return
                }
                if (indicator.isCanceled) return
                indicator.isIndeterminate = false
                if (!tts.downloadPiperVoice(indicator, voiceKey)) {
                    notify(project, NotificationType.ERROR, "Piper voice download failed",
                        "The voice “$voiceKey” did not download. Check your connection and try again.")
                    return
                }
                val fin = tts.status(refresh = true)
                tts.restart()
                if (fin.piperVoices.any { it.key == voiceKey }) {
                    notify(project, NotificationType.INFORMATION, "Piper voice ready",
                        "“$voiceKey” is installed. Pick it in the Audio Explainer sidebar to narrate that language.")
                } else {
                    notify(project, NotificationType.WARNING, "Piper voice incomplete",
                        "The download ran but “$voiceKey” isn't showing as installed. Try again, or check the IDE log.")
                }
            }
        })
    }

    /**
     * Background delete of one installed Piper voice model (Phase L9): removes its `.onnx`/`.onnx.json` via
     * `--delete-piper <key>`, then re-checks + restarts so the language/voice menus drop it. [onDone] runs on
     * the EDT (`ModalityState.any()`) so the voice-manager dialog / settings panel can refresh.
     */
    fun deletePiperVoice(project: Project?, voiceKey: String, onDone: (() -> Unit)? = null) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(anyProject(project), "Deleting Piper voice", false) {
            override fun run(indicator: ProgressIndicator): Unit = withDone(onDone) {
                indicator.isIndeterminate = true
                indicator.text = "Removing “$voiceKey”…"
                if (!tts.deletePiperVoice(voiceKey)) {
                    notify(project, NotificationType.ERROR, "Piper voice delete failed",
                        "“$voiceKey” could not be removed. See the IDE log for details.")
                    return
                }
                tts.status(refresh = true)  // re-discover installed voices → updates the language/voice menus
                tts.restart()               // drop the deleted voice from the running server's in-memory cache
                notify(project, NotificationType.INFORMATION, "Piper voice removed",
                    "“$voiceKey” was deleted and its disk space freed.")
            }
        })
    }

    private fun setEngine(engine: String, project: Project?) {
        settings.state.ttsEngine = engine
        settings.state.ttsSetupPrompted = true
        tts.restart()
        val msg = when (engine) {
            AudioExplainerSettings.ENGINE_OFF -> "Audio narration turned off. The walkthrough will show silently."
            else -> "Narration engine set to ${engineLabel(engine)}."
        }
        notify(project, NotificationType.INFORMATION, "Audio Explainer", msg)
    }

    private fun engineLabel(engine: String): String = when (engine) {
        AudioExplainerSettings.ENGINE_KOKORO -> "Kokoro (local neural TTS)"
        AudioExplainerSettings.ENGINE_OFF -> "Off (silent)"
        else -> engine
    }

    private fun notify(project: Project?, type: NotificationType, title: String, content: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                .createNotification(title, content, type)
                .notify(anyProject(project))
        }
    }

    /**
     * Run [block] (a background task body) and then fire [onDone] on the EDT with `ModalityState.any()`,
     * on **every** exit path — normal completion, an early `return`, or an exception. `inline` so the
     * `return`s inside the install bodies stay non-local (they return from `run`) while still passing
     * through the `finally`. The `any()` modality lets the callback update the Settings dialog's status
     * labels while that (modal) dialog is still open.
     */
    private inline fun withDone(noinline onDone: (() -> Unit)?, block: () -> Unit) {
        try {
            block()
        } finally {
            if (onDone != null) ApplicationManager.getApplication().invokeLater(onDone, ModalityState.any())
        }
    }
}
