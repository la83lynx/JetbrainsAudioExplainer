package com.audioexplainer

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.ColorPanel
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.awt.Color
import java.awt.event.ActionListener
import javax.swing.DefaultComboBoxModel

/**
 * Settings page for the editor spotlight appearance (Phase 3.5), shown under
 * **Settings/Preferences → Tools → Audio Explainer** (registered as an `applicationConfigurable`
 * with `parentId="tools"`; the page's id comes from that EP registration). Reads/writes
 * [AudioExplainerSettings]; on apply it also re-styles the live walkthrough (if one is showing) so a
 * change is visible immediately.
 *
 * Spinners/checkboxes bind to the settings state via the Kotlin UI DSL. The two [ColorPanel]s have no
 * DSL binding helper, so they are wired manually through the cell lifecycle hooks
 * (`onReset`/`onApply`/`onIsModified`). The final row's `onApply` runs after every other cell's write,
 * so the live re-render sees the freshly-applied values.
 */
class HighlightSettingsConfigurable : BoundConfigurable(DISPLAY_NAME) {

    private val settings = AudioExplainerSettings.getInstance()
    private val state get() = settings.state

    private val accentPanel = ColorPanel().apply { selectedColor = parseColor(state.accentColor) }
    private val glowPanel = ColorPanel().apply { selectedColor = parseColor(state.activeGlowColor) }

    init {
        // Off the EDT: discover Windows SAPI voices (Phase L7) so the language/voice combos below include
        // them + any SAPI-only languages, and warm the Kokoro prerequisite probe (both `refresh = false`,
        // so they only run a subprocess if never probed — usually already warm). Then fill the "Audio
        // engines" status labels with `ModalityState.any()` so the update lands even though the Settings
        // dialog is modal.
        ApplicationManager.getApplication().executeOnPooledThread {
            val tts = TtsService.getInstance()
            runCatching { tts.status(refresh = false) }
            runCatching { tts.sapiVoices(refresh = false) }
            ApplicationManager.getApplication().invokeLater({ refreshEngineStatusLabels() }, ModalityState.any())
        }
    }

    private lateinit var outsideSpinner: JBIntSpinner
    private lateinit var withinSpinner: JBIntSpinner
    private lateinit var glowSpinner: JBIntSpinner
    private lateinit var tintSpinner: JBIntSpinner
    private lateinit var gapWidthSpinner: JBIntSpinner
    private lateinit var overviewCheck: JBCheckBox
    private lateinit var tintCheck: JBCheckBox
    private lateinit var gapCheck: JBCheckBox

    /** The narration voice combo (Phase L7 cleanup) — its items are rebuilt to match the currently-selected
     *  explanation language, so it always shows only voices valid for that language. */
    private lateinit var voiceCombo: ComboBox<String>

    /** Per-engine status labels in the "Audio engines" group (Phase L7 cleanup) — filled from the cached
     *  TTS status / SAPI discovery and re-filled by the "Re-check" / "Rescan" buttons. Nullable because the
     *  background warm-up in [init] may fire before [createPanel] builds them (the setters then no-op). */
    private var kokoroStatusLabel: JBLabel? = null
    private var espeakStatusLabel: JBLabel? = null
    private var sapiStatusLabel: JBLabel? = null
    private var piperStatusLabel: JBLabel? = null

    override fun createPanel(): DialogPanel = panel {
        group("Segment spotlight") {
            row("Outside-segment brightness (%):") {
                outsideSpinner = spinner(0..100).bindIntValue(state::outsideDimPercent).component
            }.rowComment("Lower = more dimmed. Lines outside the current segment.")
            row("Within-segment brightness (%):") {
                withinSpinner = spinner(0..100).bindIntValue(state::withinDimPercent).component
            }.rowComment("Non-active and context lines inside the segment. Keep clearly brighter than the outside value.")
        }

        group("Active line") {
            row("Glow colour:") {
                cell(glowPanel)
                    .onReset { glowPanel.selectedColor = parseColor(state.activeGlowColor) }
                    .onApply { glowPanel.selectedColor?.let { state.activeGlowColor = toHex(it) } }
                    .onIsModified { toHex(glowPanel.selectedColor ?: return@onIsModified false) != state.activeGlowColor }
            }
            row("Glow strength (%):") {
                glowSpinner = spinner(0..100).bindIntValue(state::activeGlowPercent).component
            }.rowComment("Whole-line background glow behind the active highlight.")
            row {
                overviewCheck = checkBox("Show accent mark in the overview ruler")
                    .bindSelected(state::overviewRulerMark).component
            }
        }

        group("Accent & non-active highlights") {
            row("Accent colour:") {
                cell(accentPanel)
                    .onReset { accentPanel.selectedColor = parseColor(state.accentColor) }
                    .onApply { accentPanel.selectedColor?.let { state.accentColor = toHex(it) } }
                    .onIsModified { toHex(accentPanel.selectedColor ?: return@onIsModified false) != state.accentColor }
            }.rowComment("Used for the overview mark, the gap gutter bar, and the non-active tint.")
            row {
                tintCheck = checkBox("Tint non-active highlight lines")
                    .bindSelected(state::nonActiveTint).component
            }.rowComment("Gives non-active highlights their own cue so they don't rely on the gutter bar alone.")
            row("Tint strength (%):") {
                tintSpinner = spinner(0..100).bindIntValue(state::nonActiveTintPercent).component
            }
        }

        group("Gap (context) lines") {
            row {
                gapCheck = checkBox("Show accent gutter bar").bindSelected(state::gapGutter).component
            }
            row("Gutter bar width (px):") {
                gapWidthSpinner = spinner(1..8).bindIntValue(state::gapGutterWidth).component
            }
        }

        group("Narration (audio)") {
            row {
                // The old "Engine" combo only ever meant on/off (the synthesizer tier is per-voice), so it's
                // just a checkbox now. `ttsEngine` still stores kokoro/off under the hood for back-compat and
                // the "Turn off audio" setup action.
                checkBox("Enable narration (audio)")
                    .bindSelected({ state.ttsEngine != ENGINE_OFF },
                        { state.ttsEngine = if (it) ENGINE_KOKORO else ENGINE_OFF })
            }.rowComment("Off = the walkthrough shows silently. The synthesizer tier (Kokoro neural · espeak robotic · Windows SAPI) is chosen per voice below.")
            row("Explanation language:") {
                comboBox(languageCodes())
                    .bindItem({ state.explanationLanguage }, { state.explanationLanguage = it ?: Languages.DEFAULT_CODE })
                    .applyToComponent {
                        renderer = SimpleListCellRenderer.create("") { languageLabel(it) }
                        // Live-filter the voice combo to this language (Phase L7 cleanup), like the sidebar.
                        addActionListener {
                            if (this@HighlightSettingsConfigurable::voiceCombo.isInitialized) {
                                rebuildVoiceCombo(selectedItem as? String)
                            }
                        }
                    }
            }.rowComment("Language for NEW ‘Explain This’ generations. A loaded walkthrough keeps its own language. Each entry lists the voice tiers it offers; Japanese & Chinese need extra TTS packs (see Audio engines below).")
            row("Voice:") {
                // Items are rebuilt to the selected explanation language (see [rebuildVoiceCombo]); managed
                // manually (onReset/onApply/onIsModified) rather than bound, since the model is dynamic.
                voiceCombo = comboBox(emptyList<String>())
                    .applyToComponent { renderer = SimpleListCellRenderer.create("") { voiceLabel(it) } }
                    .onReset {
                        rebuildVoiceCombo(state.explanationLanguage)
                        voiceCombo.selectedItem = validVoiceFor(state.explanationLanguage, state.ttsVoice)
                    }
                    .onApply { (voiceCombo.selectedItem as? String)?.let { state.ttsVoice = it } }
                    .onIsModified { (voiceCombo.selectedItem as? String) != state.ttsVoice }
                    .component
            }.rowComment("Voices for the explanation language above — updates when you change it. A loaded walkthrough narrates with a voice matching its own language.")
            row("Speed (%):") {
                spinner(25..400).bindIntValue(state::ttsSpeedPercent)
            }.rowComment("100 = normal. The sidebar's speed buttons write here too.")
        }

        // Per-engine section (Phase L7 cleanup) — status, provisioning, and engine-specific info/links,
        // one collapsible sub-group per synthesizer tier. Replaces the old modal "Set up TTS" chooser.
        group("Audio engines") {
            group("Kokoro — neural (local, primary)") {
                row {
                    kokoroStatusLabel = JBLabel(kokoroStatusText(TtsService.getInstance().cachedStatusOrNull()))
                    cell(kokoroStatusLabel!!)
                }
                row("Model directory:") {
                    textField().bindText({ state.kokoroModelDir }, { state.kokoroModelDir = it.trim() })
                        .comment("Blank = default (~/.cache/audio-explainer).")
                }
                row {
                    button("Install / reinstall Kokoro…") {
                        TtsSetup.install(anyOpenProject()) { refreshEngineStatusLabels() }
                    }
                    button("Add / reinstall Japanese & Chinese voices…") {
                        TtsSetup.installLanguagePacks(anyOpenProject()) { refreshEngineStatusLabels() }
                    }
                }.rowComment("Kokoro voice model ≈ 340 MB. Japanese & Chinese need the extra misaki packs (large download). Both run in the background; the status above updates when they finish.")
            }

            group("espeak — robotic (bundled, synthesized lower quality)") {
                row {
                    espeakStatusLabel = JBLabel(espeakStatusText())
                    cell(espeakStatusLabel!!)
                }.rowComment("Ships with kokoro-onnx — no download. Robotic but intelligible; the fallback for the ~100 languages Kokoro can't voice, and offered on every language as “Robotic (F/M)”.")
                row {
                    comment("Tune the robotic voices from the sidebar — the inline Pitch / Liveliness / Word-gap sliders and the Advanced… editor, per RoboSynth LoQ (F/M) variant (shared across languages).")
                }
            }

            group("Windows SAPI — system voices${if (SystemInfo.isWindows) "" else " (Windows only)"}") {
                if (SystemInfo.isWindows) {
                    row {
                        sapiStatusLabel = JBLabel(sapiStatusText())
                        cell(sapiStatusLabel!!)
                    }
                    row {
                        button("Rescan Windows voices") { recheckSapi() }
                    }.rowComment("Discovered via System.Speech and offered per voice (ids sapi:<lang>:<name>). Culture → walkthrough language, so SAPI voices can add languages Kokoro/espeak never listed.")
                    row {
                        cell(ActionLink("Get NaturalVoiceSAPIAdapter (GitHub)", ActionListener { BrowserUtil.browse(ADAPTER_URL) }))
                    }.rowComment(
                        "Optional third-party SAPI5 provider (you install it — we don't bundle it). It re-exposes the Windows " +
                            "“natural” voices and, with its Edge online voices enabled, neural Azure voices for ~140 locales. " +
                            "Those are online: each sentence is sent to Microsoft and needs internet; the classic/offline voices don't.",
                    )
                } else {
                    row {
                        comment("Windows only — not available on this OS. macOS/Linux narrate with Kokoro + espeak.")
                    }
                }
            }

            group("Piper — offline neural") {
                row {
                    piperStatusLabel = JBLabel(piperStatusText())
                    cell(piperStatusLabel!!)
                }.rowComment(
                    "Offline neural voices (VITS, via piper-tts) for the ~40 languages Kokoro can't voice — " +
                        "higher quality than the robotic espeak tier, no online calls, cross-platform. Each " +
                        "voice is a per-voice model download (~20–75 MB), stored under the Kokoro model directory (…/piper).",
                )
                row {
                    button("Install Piper (pip)") {
                        TtsSetup.installPiper(anyOpenProject()) { refreshEngineStatusLabels() }
                    }
                    button("Manage voices…") {
                        PiperVoiceManagerDialog(anyOpenProject()).show()
                        refreshEngineStatusLabels()
                    }
                }.rowComment("Install the package once, then browse the full catalog (~170 voices / ~50 languages) to download or remove voices. Downloads run in the background; the status above updates when they finish.")
            }

            row {
                button("Re-check engines") { recheckAll() }
            }.rowComment("Re-probes the Kokoro prerequisites and rescans Windows voices (background), then refreshes the status above.")
        }

        group("Executables (advanced)") {
            row("Claude CLI path:") {
                textField().bindText({ state.claudeExecutablePath }, { state.claudeExecutablePath = it.trim() })
                    .comment("Blank = auto-detect (PATH + common install dirs). Set if the IDE can't find it.")
            }
            row("Python path:") {
                textField().bindText({ state.pythonExecutablePath }, { state.pythonExecutablePath = it.trim() })
                    .comment("Blank = auto-detect. Used to launch the Kokoro TTS server.")
            }
        }

        row {
            button("Reset to defaults") {
                outsideSpinner.value = AudioExplainerSettings.DEF_OUTSIDE_DIM
                withinSpinner.value = AudioExplainerSettings.DEF_WITHIN_DIM
                glowSpinner.value = AudioExplainerSettings.DEF_ACTIVE_GLOW
                tintSpinner.value = AudioExplainerSettings.DEF_NONACTIVE_TINT
                gapWidthSpinner.value = AudioExplainerSettings.DEF_GAP_WIDTH
                overviewCheck.isSelected = true
                tintCheck.isSelected = true
                gapCheck.isSelected = true
                accentPanel.selectedColor = parseColor(AudioExplainerSettings.DEF_ACCENT_COLOR)
                glowPanel.selectedColor = parseColor(AudioExplainerSettings.DEF_ACTIVE_GLOW_COLOR)
            }
                // Runs after every binding/colour write above (cells apply in creation order), so the
                // live re-render sees the just-applied values.
                .onApply { refreshLiveWalkthrough() }
        }.rowComment("Applies to the fields above; save with OK/Apply.")
    }

    /** Re-style the currently-showing walkthrough (if any) with the new settings. */
    private fun refreshLiveWalkthrough() {
        service<ExplainerServerService>().server.walkthrough.getListeners()
            .filterIsInstance<EditorHighlighter>()
            .forEach { it.refresh() }
    }

    /**
     * Repopulate [voiceCombo] with the voices eligible for [langCode] (Phase L7 cleanup), keeping the
     * current selection if it's still valid, otherwise selecting that language's default voice. Called on
     * reset and whenever the explanation-language combo changes.
     */
    private fun rebuildVoiceCombo(langCode: String?) {
        val ids = Languages.voicesFor(langCode).map { it.id }
        val current = voiceCombo.selectedItem as? String
        voiceCombo.model = DefaultComboBoxModel(ids.toTypedArray())
        voiceCombo.selectedItem = if (current != null && current in ids) current else Languages.defaultVoiceFor(langCode)
    }

    /** [voiceId] if it's valid for [langCode], else that language's default (used to seed the combo on reset). */
    private fun validVoiceFor(langCode: String?, voiceId: String?): String =
        if (Languages.isVoiceValidFor(langCode, voiceId)) voiceId!! else Languages.defaultVoiceFor(langCode)

    // ── Audio engines section (Phase L7 cleanup) ──────────────────────────────

    private fun anyOpenProject(): Project? = ProjectManager.getInstance().openProjects.firstOrNull()

    /** Re-fill the per-engine status labels from the CACHED status/voices only — no subprocess, so it's
     *  safe on the EDT (called after a background probe/install finishes, via `ModalityState.any()`). */
    private fun refreshEngineStatusLabels() {
        val tts = TtsService.getInstance()
        kokoroStatusLabel?.text = kokoroStatusText(tts.cachedStatusOrNull())
        espeakStatusLabel?.text = espeakStatusText()
        sapiStatusLabel?.text = sapiStatusText()
        piperStatusLabel?.text = piperStatusText()
    }

    /** "Re-check engines": re-probe Kokoro prerequisites + rescan SAPI voices off the EDT, then refresh. */
    private fun recheckAll() {
        kokoroStatusLabel?.text = "Checking…"
        piperStatusLabel?.text = "Checking…"
        if (SystemInfo.isWindows) sapiStatusLabel?.text = "Scanning…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val tts = TtsService.getInstance()
            runCatching { tts.status(refresh = true) }
            if (SystemInfo.isWindows) runCatching { tts.sapiVoices(refresh = true) }
            ApplicationManager.getApplication().invokeLater({ refreshEngineStatusLabels() }, ModalityState.any())
        }
    }

    /** "Rescan Windows voices": re-probe SAPI only (a ~0.3–1 s PowerShell call) off the EDT, then refresh. */
    private fun recheckSapi() {
        sapiStatusLabel?.text = "Scanning…"
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { TtsService.getInstance().sapiVoices(refresh = true) }
            ApplicationManager.getApplication().invokeLater({ refreshEngineStatusLabels() }, ModalityState.any())
        }
    }

    /** Kokoro readiness line for the status label (HTML so the "languages ready" detail wraps to a 2nd line). */
    private fun kokoroStatusText(status: TtsService.TtsStatus?): String {
        if (status == null) return "Not checked yet — click “Re-check engines”."
        val head = when (status.availability) {
            TtsService.Availability.READY -> "Ready ✓  (python ${status.python ?: "?"}, onnxruntime ${status.onnxruntime ?: "?"})"
            TtsService.Availability.PYTHON_MISSING -> "Not ready — no Python interpreter on PATH (install Python 3.10+)."
            TtsService.Availability.KOKORO_MISSING -> "Not ready — kokoro-onnx not installed (use Install below)."
            TtsService.Availability.MODEL_MISSING -> "Not ready — voice model not downloaded (use Install below)."
            TtsService.Availability.SCRIPT_MISSING -> "Not ready — the TTS server script is missing."
            TtsService.Availability.UNKNOWN -> "Status unknown (the prerequisite probe failed)."
        }
        if (!status.ready) return head
        val langs = status.languagesReady.joinToString(", ").ifEmpty { "en" }
        val pending = listOfNotNull(
            if (!status.misakiJa) "Japanese" else null,
            if (!status.misakiZh) "Chinese" else null,
        )
        val tail = if (pending.isEmpty()) "" else "  ·  ${pending.joinToString(" & ")} need extra packs"
        return "<html>$head<br>Languages ready: $langs$tail</html>"
    }

    /** espeak availability line — the bundled robotic tier; the count comes from the cached `--check`. */
    private fun espeakStatusText(): String {
        val n = TtsService.getInstance().cachedEspeakLanguages().size
        return if (n > 0) "Available ✓ — $n languages (bundled with kokoro-onnx, no download)."
        else "Bundled with kokoro-onnx — click “Re-check engines” to list the available languages."
    }

    /** Windows SAPI voice count line — voices installed, how many are neural "online", across how many langs. */
    private fun sapiStatusText(): String {
        val voices = TtsService.getInstance().cachedSapiVoices()
        if (voices.isEmpty()) return "No Windows voices discovered yet — click “Rescan Windows voices”."
        val online = voices.count { it.voiceName.contains("Online", ignoreCase = true) }
        val langs = voices.map { it.code }.distinct().size
        val onlineStr = if (online > 0) " ($online neural online)" else ""
        return "${voices.size} voices installed$onlineStr across $langs languages."
    }

    /** Piper (offline-neural) status line — package installed? how many voices across how many languages? */
    private fun piperStatusText(): String {
        val status = TtsService.getInstance().cachedStatusOrNull()
            ?: return "Not checked yet — click “Re-check engines”."
        if (!status.piperImportable) return "Not installed — click “Install Piper (pip)” to add the offline-neural tier."
        val voices = status.piperVoices
        if (voices.isEmpty()) return "Installed ✓ — no voices yet. Click “Manage voices…” to download one."
        val langs = voices.map { it.code }.distinct().size
        return "Installed ✓ — ${voices.size} voice${plural(voices.size)} across $langs language${plural(langs)}."
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    companion object {
        /** NaturalVoiceSAPIAdapter — the third-party SAPI5 provider the Windows-SAPI section links to (L7). */
        private const val ADAPTER_URL = "https://github.com/gexgd0419/NaturalVoiceSAPIAdapter"

        private const val DISPLAY_NAME = "Audio Explainer"

        private const val ENGINE_KOKORO = AudioExplainerSettings.ENGINE_KOKORO
        private const val ENGINE_OFF = AudioExplainerSettings.ENGINE_OFF

        /**
         * All language codes for the menu, from the canonical [Languages] registry. Computed at panel-build
         * time (not class-load) so runtime-discovered Windows SAPI voices + SAPI-only languages (Phase L7)
         * are included — the [init] block warms the discovery cache first.
         */
        private fun languageCodes(): List<String> = Languages.allLanguages().map { it.code }

        private const val PACK_NOTE = "  ·  needs TTS pack"

        /**
         * Label for a language code: name, autonym, and the voice tiers it offers (e.g.
         * "English (English) — kokoro, espeak, WinSAPI"), plus a "needs TTS pack" hint for ja/zh until their
         * misaki packs are installed. The tier list is derived from the (SAPI-merged) voice list, so it's
         * truthful per machine.
         */
        private fun languageLabel(code: String): String {
            val lang = Languages.byCode(code)
            val base = "${lang.displayName} (${lang.autonym}) — ${Languages.voiceTiers(lang).joinToString(", ")}"
            return if (needsPack(lang)) base + PACK_NOTE else base
        }

        /** Label for a voice id — the voice name plus its engine badge ("Heart (US, F) — kokoro"). The combo
         *  is already filtered to one language, so the language name isn't repeated. */
        private fun voiceLabel(id: String): String {
            val voice = Languages.byCode(Languages.languageOfVoice(id)).voices.firstOrNull { it.id == id }
            return "${voice?.label ?: id} — ${Languages.engineTag(id)}"
        }

        /** True when [lang] needs the misaki packs and the last (cached) `--check` didn't list it ready. */
        private fun needsPack(lang: Language): Boolean =
            lang.requiresExtraDeps && lang.code !in TtsService.getInstance().cachedLanguagesReady()

        private fun parseColor(hex: String): Color = try {
            Color(hex.trim().removePrefix("#").toInt(16))
        } catch (_: Exception) {
            Color(AudioExplainerSettings.DEF_ACCENT_COLOR.toInt(16))
        }

        private fun toHex(color: Color): String = String.format("%06X", color.rgb and 0xFFFFFF)
    }
}
