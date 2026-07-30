package com.audioexplainer

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
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
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.event.ActionListener
import java.text.NumberFormat
import java.text.ParseException
import javax.swing.DefaultComboBoxModel
import javax.swing.JSpinner
import javax.swing.text.DefaultFormatterFactory
import javax.swing.text.NumberFormatter

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

    /** Disabled while arrows follow the accent, so the picker never shows a colour that isn't in use. */
    private val arrowColorPanel = ColorPanel().apply {
        selectedColor = parseColor(state.arrowColor)
        isEnabled = !state.arrowUseAccent
    }

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
            // Warm the installed-font list here too, so building the panel doesn't enumerate fonts on the EDT.
            runCatching { proseFonts() }
            ApplicationManager.getApplication().invokeLater({ refreshEngineStatusLabels() }, ModalityState.any())
        }
    }

    private lateinit var outsideSpinner: JBIntSpinner
    private lateinit var withinSpinner: JBIntSpinner
    private lateinit var glowSpinner: JBIntSpinner
    private lateinit var tintSpinner: JBIntSpinner
    private lateinit var gapWidthSpinner: JBIntSpinner
    private lateinit var calloutTintSpinner: JBIntSpinner
    private lateinit var calloutOpacitySpinner: JBIntSpinner
    private lateinit var overviewCheck: JBCheckBox
    private lateinit var tintCheck: JBCheckBox
    private lateinit var gapCheck: JBCheckBox
    private lateinit var calloutCheck: JBCheckBox
    private lateinit var exampleCheck: JBCheckBox
    private lateinit var flashCheck: JBCheckBox
    private lateinit var flashMsSpinner: JBIntSpinner
    private lateinit var relationsCheck: JBCheckBox
    private lateinit var frameCombo: ComboBox<String>
    private lateinit var proseFontCombo: ComboBox<String>
    private lateinit var leaderCheck: JBCheckBox

    /**
     * Live sample of the description font, kept in sync with the family combo *and* the text-size spinner —
     * the two settings that decide the face the callout actually draws prose in. Its own colour is the
     * dialog's, not the callout's: over a settings background the softened body colour reads as disabled
     * text, and the thing being previewed here is the font.
     */
    private val proseFontPreview = JBLabel(PROSE_PREVIEW_TEXT)
    private lateinit var calloutRadiusSpinner: JBIntSpinner
    private lateinit var calloutBarSpinner: JBIntSpinner
    private lateinit var calloutScaleSpinner: JBIntSpinner
    private lateinit var calloutBelowCheck: JBCheckBox
    private lateinit var arrowAccentCheck: JBCheckBox
    private lateinit var arrowThicknessSpinner: JBIntSpinner
    private lateinit var arrowHeadSpinner: JBIntSpinner
    private lateinit var arrowSquareCheck: JBCheckBox
    private lateinit var arrowLabelsCheck: JBCheckBox

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
                outsideSpinner = clampedSpinner(0..100).bindIntValue(state::outsideDimPercent).component
            }.rowComment("Lower = more dimmed. Lines outside the current segment.")
            row("Within-segment brightness (%):") {
                withinSpinner = clampedSpinner(0..100).bindIntValue(state::withinDimPercent).component
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
                glowSpinner = clampedSpinner(0..100).bindIntValue(state::activeGlowPercent).component
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
                tintSpinner = clampedSpinner(0..100).bindIntValue(state::nonActiveTintPercent).component
            }
        }

        group("Gap (context) lines") {
            row {
                gapCheck = checkBox("Show accent gutter bar").bindSelected(state::gapGutter).component
            }
            row("Gutter bar width (px):") {
                gapWidthSpinner = clampedSpinner(1..8).bindIntValue(state::gapGutterWidth).component
            }
        }

        group("Step decorations") {
            row {
                calloutCheck = checkBox("Docked callout by the active lines").bindSelected(state::callout).component
            }.rowComment("Shows the step's short label in the editor. Nothing is drawn for walkthroughs without per-step labels. Appearance and placement: “Callout appearance” below.")
            row {
                exampleCheck = checkBox("Include the step's worked example").bindSelected(state::calloutExample).component
            }.rowComment("Adds a one-line “input → output” illustration under the callout text, where the walkthrough gives one. Needs the callout above.")
            row {
                flashCheck = checkBox("Flash the step's key identifier").bindSelected(state::tokenFlash).component
            }.rowComment("Briefly pulses the one identifier a step names, where the walkthrough points at one. Nothing happens on steps that don't.")
            row("Flash duration (ms):") {
                flashMsSpinner = clampedSpinner(100..3000).bindIntValue(state::tokenFlashMs).component
            }
            row {
                relationsCheck = checkBox("Show relationships between lines").bindSelected(state::relations).component
            }.rowComment("Draws an arrow between two related lines of the file, or a clickable gutter pointer when the other end is in another file. Only where the walkthrough names a relationship.")
        }

        group("Callout appearance") {
            row("Frame style:") {
                frameCombo = comboBox(FRAME_IDS)
                    .bindItem(
                        { state.calloutFrame },
                        { state.calloutFrame = it ?: AudioExplainerSettings.CALLOUT_FRAME_BAR },
                    )
                    .applyToComponent { renderer = SimpleListCellRenderer.create("") { frameLabel(it) } }
                    .component
            }.rowComment("All five place the same text. None is the lightest thing to put over code; Outline keeps an edge without filling the line; Comment block disguises the aside as a <code>//</code> comment in the scheme's own comment colour.")
            row("Description font:") {
                proseFontCombo = comboBox(proseFonts())
                    .bindItem(
                        { state.calloutProseFont },
                        { state.calloutProseFont = it ?: "" },
                    )
                    .applyToComponent {
                        renderer = SimpleListCellRenderer.create("") { proseFontLabel(it) }
                        // Fires on a pick *and* on every reset (`setSelectedItem` always fires), so the sample
                        // below never shows a family the combo isn't on.
                        addActionListener { refreshProseFontPreview() }
                    }
                    .component
            }.rowComment("The prose aside and the relation pointer lines. The worked example always stays on the editor's code font — an example is code. Size follows “Text size” below.")
            row("") {
                cell(proseFontPreview)
            }.rowComment("Sample at the editor's italic face in the chosen family, at the size below.")
            row("Tint strength (%):") {
                calloutTintSpinner = clampedSpinner(0..100).bindIntValue(state::calloutTintPercent).component
            }.rowComment("How far the box's background is pulled toward the accent colour.")
            row("Opacity (%):") {
                calloutOpacitySpinner = clampedSpinner(0..100).bindIntValue(state::calloutOpacityPercent).component
            }.rowComment("100 is a solid box. Lower it to let code — or a relationship arrow with nowhere else to run — read through the callout. Only the fill fades: at 0 the text, bar and outline all still draw.")
            row("Corner radius (px):") {
                calloutRadiusSpinner = clampedSpinner(0..16).bindIntValue(state::calloutCornerRadius).component
            }.rowComment("0 squares the box off.")
            row("Accent bar width (px):") {
                calloutBarSpinner = clampedSpinner(1..10).bindIntValue(state::calloutBarWidth).component
            }.rowComment("Only the Bar frame style draws one.")
            row("Text size (% of editor font):") {
                calloutScaleSpinner = clampedSpinner(60..160).bindIntValue(state::calloutTextScalePercent).component
                // This size is half of what the description sample above is showing.
                calloutScaleSpinner.addChangeListener { refreshProseFontPreview() }
            }.rowComment("The wrap follows the size, so a larger box fits fewer words per line — the per-paragraph line caps don't move.")
            row {
                calloutBelowCheck = checkBox("Dock the callout below the active lines")
                    .bindSelected(state::calloutBelow).component
            }.rowComment("Default is above, which matches reading order: the aside, then the code it describes.")
            row {
                leaderCheck = checkBox("Draw a leader to the annotated line")
                    .bindSelected(state::calloutLeader).component
            }.rowComment("A speech-bubble tail off the box's near edge, a quarter of the way along it, pointing the way the box is docked. Takes the box's own tint where the frame paints one; every style but Bar also outlines it. Costs a few px of editor height per step.")
            // Both fields the sample depends on exist by now. Reset fires the combo listener anyway, so this
            // only matters for the instant between building the panel and the platform's first reset.
            refreshProseFontPreview()
        }

        group("Relationship arrows") {
            row {
                arrowAccentCheck = checkBox("Use the accent colour for arrows")
                    .bindSelected(state::arrowUseAccent).component
                // Enable/disable the picker live rather than only on reset, so unticking this has a visible
                // effect before OK is pressed.
                arrowAccentCheck.addActionListener { arrowColorPanel.isEnabled = !arrowAccentCheck.isSelected }
            }
            row("Arrow colour:") {
                cell(arrowColorPanel)
                    .onReset {
                        arrowColorPanel.selectedColor = parseColor(state.arrowColor)
                        arrowColorPanel.isEnabled = !state.arrowUseAccent
                    }
                    .onApply { arrowColorPanel.selectedColor?.let { state.arrowColor = toHex(it) } }
                    .onIsModified {
                        toHex(arrowColorPanel.selectedColor ?: return@onIsModified false) != state.arrowColor
                    }
            }.rowComment("Used when the box above is unticked. The arrow's own note follows it; the callout's pointer line stays on the accent, since that belongs to the box.")
            row("Thickness (tenths of a px):") {
                arrowThicknessSpinner = clampedSpinner(8..40).bindIntValue(state::arrowThicknessTenths).component
            }.rowComment("14 is the 1.4 px hairline arrows shipped with; 30 draws a bold diagram line.")
            row("Arrowhead size (px):") {
                arrowHeadSpinner = clampedSpinner(4..16).bindIntValue(state::arrowHeadSize).component
            }
            row {
                arrowSquareCheck = checkBox("Square the elbow corners")
                    .bindSelected(state::arrowSquareElbow).component
            }
            row {
                arrowLabelsCheck = checkBox("Show the relationship note beside the arrow")
                    .bindSelected(state::arrowLabels).component
            }.rowComment("Off leaves that text to the callout box and the gutter pointer's tooltip, which carry it anyway.")
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
                clampedSpinner(25..400).bindIntValue(state::ttsSpeedPercent)
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
                calloutTintSpinner.value = AudioExplainerSettings.DEF_CALLOUT_TINT
                calloutOpacitySpinner.value = AudioExplainerSettings.DEF_CALLOUT_OPACITY
                calloutRadiusSpinner.value = AudioExplainerSettings.DEF_CALLOUT_ARC
                calloutBarSpinner.value = AudioExplainerSettings.DEF_CALLOUT_BAR_W
                calloutScaleSpinner.value = AudioExplainerSettings.DEF_CALLOUT_TEXT_SCALE
                arrowThicknessSpinner.value = AudioExplainerSettings.DEF_ARROW_THICKNESS
                arrowHeadSpinner.value = AudioExplainerSettings.DEF_ARROW_HEAD
                flashMsSpinner.value = AudioExplainerSettings.DEF_TOKEN_FLASH_MS
                overviewCheck.isSelected = true
                tintCheck.isSelected = true
                gapCheck.isSelected = true
                calloutCheck.isSelected = true
                exampleCheck.isSelected = true
                flashCheck.isSelected = true
                relationsCheck.isSelected = true
                calloutBelowCheck.isSelected = false
                arrowSquareCheck.isSelected = false
                arrowLabelsCheck.isSelected = true
                arrowAccentCheck.isSelected = true
                leaderCheck.isSelected = true
                frameCombo.selectedItem = AudioExplainerSettings.CALLOUT_FRAME_BAR
                proseFontCombo.selectedItem = ""
                accentPanel.selectedColor = parseColor(AudioExplainerSettings.DEF_ACCENT_COLOR)
                glowPanel.selectedColor = parseColor(AudioExplainerSettings.DEF_ACTIVE_GLOW_COLOR)
                arrowColorPanel.selectedColor = parseColor(AudioExplainerSettings.DEF_ACCENT_COLOR)
                arrowColorPanel.isEnabled = false
            }
                // Runs after every binding/colour write above (cells apply in creation order), so the
                // live re-render sees the just-applied values.
                .onApply { refreshLiveWalkthrough() }
        }.rowComment("Applies to the fields above; save with OK/Apply.")
    }

    /**
     * Re-face [proseFontPreview] from the current combo family and text size, by the same rule
     * `CalloutRenderer.font` applies to a prose block: the editor's italic face, in the named family if one
     * is named, scaled by the text-size percentage. An unavailable family falls back inside `Font` here just
     * as it does there, so a family that won't render is visible in the sample rather than only in the editor.
     */
    private fun refreshProseFontPreview() {
        val base = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.ITALIC)
        val family = (proseFontCombo.selectedItem as? String)?.trim().orEmpty()
        val named = if (family.isEmpty()) base else Font(family, base.style, base.size)
        // The size spinner is built after this row, so before it exists the persisted value is the truth.
        val scale = if (this::calloutScaleSpinner.isInitialized) {
            calloutScaleSpinner.number
        } else {
            state.calloutTextScalePercent
        }
        proseFontPreview.font = if (scale == 100) named else named.deriveFont(named.size2D * scale / 100f)
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

        /** Callout frame styles, in the order they read as "least to most decoration" reversed — see [frameLabel]. */
        private val FRAME_IDS = listOf(
            AudioExplainerSettings.CALLOUT_FRAME_BAR,
            AudioExplainerSettings.CALLOUT_FRAME_OUTLINE,
            AudioExplainerSettings.CALLOUT_FRAME_SOLID,
            AudioExplainerSettings.CALLOUT_FRAME_NONE,
            AudioExplainerSettings.CALLOUT_FRAME_COMMENT,
        )

        /** Combo label for a frame style. Unknown ids read as Bar, matching the renderer's own fallback. */
        private fun frameLabel(id: String?): String = when (id) {
            AudioExplainerSettings.CALLOUT_FRAME_OUTLINE -> "Outline — accent border"
            AudioExplainerSettings.CALLOUT_FRAME_SOLID -> "Solid — tinted panel, no bar"
            AudioExplainerSettings.CALLOUT_FRAME_NONE -> "None — text only, no panel"
            AudioExplainerSettings.CALLOUT_FRAME_COMMENT -> "Comment block — “// ” lines, comment colour"
            else -> "Bar — accent bar down the left"
        }

        /**
         * Families for the description-font combo: the empty id (the editor's own font) first, then whatever
         * this machine has. Enumerating fonts is slow enough to be worth doing once — `by lazy` in a
         * companion, warmed off the EDT by [init] — and it cannot be a compile-time list because the answer
         * is per-machine.
         */
        private val PROSE_FONTS: List<String> by lazy {
            listOf("") + GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.sorted()
        }

        /** Guarded accessor: a font-enumeration failure costs the choice of family, not the settings page. */
        private fun proseFonts(): List<String> = runCatching { PROSE_FONTS }.getOrDefault(listOf(""))

        private fun proseFontLabel(family: String?): String =
            if (family.isNullOrEmpty()) "Editor font (default)" else family

        /**
         * The description-font sample. A real sentence rather than a pangram or "AaBbCc": what is being judged
         * is whether a callout-length aside is comfortable to read at a glance beside code, and letterforms
         * alone don't answer that.
         */
        private const val PROSE_PREVIEW_TEXT = "The aside about this line reads like this."

        private fun parseColor(hex: String): Color = try {
            Color(hex.trim().removePrefix("#").toInt(16))
        } catch (_: Exception) {
            Color(AudioExplainerSettings.DEF_ACCENT_COLOR.toInt(16))
        }

        private fun toHex(color: Color): String = String.format("%06X", color.rgb and 0xFFFFFF)
    }
}

/**
 * A `spinner` whose text field clamps what you type instead of refusing it.
 *
 * `JSpinner`'s number editor gives its formatter the model's min/max, so `InternationalFormatter`
 * answers out-of-range *text* with `ParseException("Value not within min/max range")` — and the
 * settings dialog commits every formatted field in `doOKAction`. One typed digit outside the range
 * (a 0 in a 0..100-now, 20..100-then opacity spinner, say) therefore threw from OK: the dialog
 * refused to close and the platform logged a SEVERE against whichever plugin owned the page. Every
 * spinner here was exposed, not just the appearance ones.
 *
 * Clamping in `stringToValue` keeps the bounds meaningful — type 500 and the commit rewrites the
 * field to the maximum, so you see what you got — while making OK unblockable. Unparseable text
 * reverts to the last committed number for the same reason.
 */
private fun Row.clampedSpinner(range: IntRange, step: Int = 1): Cell<JBIntSpinner> =
    spinner(range, step).applyToComponent {
        val field = (editor as? JSpinner.DefaultEditor)?.textField ?: return@applyToComponent
        val format = (field.formatter as? NumberFormatter)?.format as? NumberFormat
            ?: NumberFormat.getIntegerInstance()
        field.formatterFactory = DefaultFormatterFactory(ClampingIntFormatter(format, range))
    }

/** Parses like the editor's own formatter, then folds the result into [range] rather than rejecting it. */
private class ClampingIntFormatter(
    format: NumberFormat,
    private val range: IntRange,
) : NumberFormatter(format) {

    init {
        // No minimum/maximum on purpose: the bounds are applied by clamping below, and setting them here
        // would restore the very ParseException this class exists to avoid.
        valueClass = Int::class.javaObjectType
    }

    override fun stringToValue(text: String?): Any {
        val typed = try {
            super.stringToValue(text) as? Number
        } catch (_: ParseException) {
            null
        }
        val n = typed?.toInt()
            ?: (formattedTextField?.value as? Number)?.toInt()
            ?: range.first
        return n.coerceIn(range.first, range.last)
    }
}
