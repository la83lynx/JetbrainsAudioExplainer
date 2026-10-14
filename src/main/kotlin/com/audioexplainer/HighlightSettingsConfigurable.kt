package com.audioexplainer

import com.audioexplainer.tree.ChevronStyle
import com.audioexplainer.tree.NestLeader
import com.audioexplainer.tree.NodeDeemphasis
import com.audioexplainer.tree.NodeElision
import com.audioexplainer.tree.NodePath
import com.audioexplainer.tree.StepCondense
import com.audioexplainer.tree.StepFold
import com.audioexplainer.tree.TreePreview
import com.audioexplainer.tree.TreeStyle
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
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
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import java.awt.GraphicsEnvironment
import java.awt.event.ActionListener
import java.text.NumberFormat
import java.text.ParseException
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel
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
 * The page is five `collapsibleGroup`s **in the order of the job** — *Audio engines* (set up once),
 * *Language & narration* (chosen per walkthrough), *Appearance*, *Code tree*, *Executables (advanced)* —
 * and the sections you rarely reopen start collapsed. Collapsing is presentation only: the DSL builds
 * every row whatever its section's state, so bindings, `onReset`/`onApply`/`onIsModified` and the status
 * labels [refreshEngineStatusLabels] writes into all work while a section is folded.
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

    /**
     * One picker per link slot, in slot order — `LinkArt.SLOTS` of them, which is the same 5 that caps a
     * step's links and sizes the palette.
     *
     * `selectedColor = null` is a slot the reader has not set, and [ColorPanel] renders that as an empty
     * swatch, which is exactly the state it means: *derive this one*. That is why these are built empty
     * rather than seeded with the derived hue — a picker showing slot 3's rotated colour would make the
     * default look like a choice, and clearing it back would then be impossible through the dialog.
     */
    private val arrowSlotPanels: List<ColorPanel> = List(LinkArt.slots()) { ColorPanel() }

    /**
     * The five pickers as **one cell**, because the Kotlin UI DSL hangs `onReset`/`onApply`/`onIsModified`
     * off a `Cell` and not off a `Row`: five cells would need five copies of the same three callbacks, each
     * able to drift from the others. One panel, one binding, one place the palette is read and written.
     */
    private val arrowSlotRow: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        isOpaque = false
        arrowSlotPanels.forEach { add(it) }
        // **The only way back to "derive".** `ColorPanel` has no clear affordance of its own — pressing it
        // opens a chooser, and every colour a chooser offers is a colour — so a slot set once could
        // otherwise never be un-set except through Reset to defaults, which would take every other
        // appearance knob with it. `setSelectedColor(null)` is reachable from code, so the way back is a
        // control rather than a gesture nobody would find.
        add(ActionLink("Clear") { arrowSlotPanels.forEach { p -> p.selectedColor = null } })
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
            // vswhere, once per session — so the compiler line under Kokoro is answered rather than
            // "not checked yet" the first time anyone opens this page.
            runCatching { tts.msvcBuildTools(refresh = false) }
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
    private lateinit var frameCombo: ComboBox<String>
    private lateinit var proseFontCombo: ComboBox<String>
    private lateinit var leaderCheck: JBCheckBox

    private lateinit var calloutRadiusSpinner: JBIntSpinner
    private lateinit var calloutBarSpinner: JBIntSpinner
    private lateinit var calloutScaleSpinner: JBIntSpinner
    private lateinit var calloutBelowCheck: JBCheckBox
    private lateinit var arrowThicknessSpinner: JBIntSpinner
    private lateinit var arrowHeadSpinner: JBIntSpinner
    private lateinit var arrowSquareCheck: JBCheckBox
    private lateinit var arrowLabelsCheck: JBCheckBox

    // ── The code tree (Phase 4) ────────────────────────────────────────────────
    // §1c's verdict table has a column saying, per alternative, whether it became a setting; these eight are
    // that column. What is missing from it is deliberate and is listed in the group's own comment, because a
    // reader looking for the chrome switch should find out here that it is a rule rather than hunt for it.
    private lateinit var treeLeaderCombo: ComboBox<String>
    private lateinit var treeChevronCombo: ComboBox<String>
    private lateinit var treeStepFoldCombo: ComboBox<String>
    private lateinit var treePathCombo: ComboBox<String>
    private lateinit var treeDeemphasisCombo: ComboBox<String>
    private lateinit var treeElisionCombo: ComboBox<String>
    private lateinit var treeMaxHeightSpinner: JBIntSpinner
    private lateinit var treeGrowCheck: JBCheckBox
    private lateinit var treeGrowHostsCheck: JBCheckBox
    private lateinit var treeCondenseCombo: ComboBox<String>

    /**
     * **The still frame above the Code-tree controls** — five of that section's dropdowns choose a *shape*,
     * and a shape is the thing prose is worst at. It draws with the production geometry rather than a
     * lookalike (see [TreePreview]); this page's job is only to keep it fed with what the combos currently
     * say, which [refreshTreePreview] does on every pick.
     */
    private val treePreview = TreePreview()

    /**
     * **The still frame above the Appearance controls.** One frame for the whole section, because its
     * settings are not independent — the accent feeds the gutter bar, the non-active tint, the callout's hue
     * and the arrow palette, and the two dim percentages only mean anything next to each other and the glow.
     * See [AppearancePreview]; [refreshAppearancePreview] keeps it fed.
     */
    private val appearancePreview = AppearancePreview()

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
    private var cppStatusLabel: JBLabel? = null

    override fun createPanel(): DialogPanel = panel {
        // **Section order is the order of the job.** What you set up once is at the top, then what you
        // choose per walkthrough, then how it all looks; the advanced escape hatches are last. Every
        // section collapses, and the ones you rarely reopen start collapsed, so the page opens as a
        // readable table of contents instead of one long scroll.

        // **Setup first.** Per-engine status, provisioning and engine-specific info/links (Phase L7
        // cleanup), one sub-group per synthesizer tier. Replaces the old modal "Set up TTS" chooser.
        // Only Kokoro — the primary tier — is open by default; the other three are opt-in.
        collapsibleGroup("Audio engines") {
            collapsibleGroup("Kokoro — neural (local, primary)") {
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
                }.rowComment("Voice model ≈ 340 MB; the Japanese & Chinese packs are a second, larger download. Both run in the background, with a console showing the installer's output.")

                // The Japanese pack's hidden prerequisite, surfaced *before* the four-minute attempt that
                // would otherwise end in a pip traceback. Windows-only: the failure is an MSVC one, and
                // elsewhere a toolchain is the norm.
                if (SystemInfo.isWindows) {
                    row {
                        cppStatusLabel = JBLabel(cppStatusText())
                        cell(cppStatusLabel!!)
                    }
                    row {
                        button("Get Microsoft C++ Build Tools…") { BrowserUtil.browse(BUILD_TOOLS_URL) }
                        button("Re-check compiler") { recheckCpp() }
                    }.rowComment(
                        "<b>Japanese only</b>, and only sometimes: <code>misaki[ja]</code> pulls " +
                            "<b>mojimoji</b>, a C extension with no CPython 3.13 wheel, so pip compiles it " +
                            "here. Chinese needs nothing. The download is free — pick the <i>Desktop " +
                            "development with C++</i> workload; a Python version that has a mojimoji wheel " +
                            "is the other way past.",
                    )
                }
            }.apply { expanded = true }

            collapsibleGroup("espeak — robotic (bundled, synthesized lower quality)") {
                row {
                    espeakStatusLabel = JBLabel(espeakStatusText())
                    cell(espeakStatusLabel!!)
                }.rowComment(
                    "Ships with kokoro-onnx — no download. Robotic but intelligible; the fallback for the " +
                        "~100 languages Kokoro can't voice, offered everywhere as “Robotic (F/M)”. Pitch, " +
                        "liveliness and word-gap are tuned from the sidebar, per variant.",
                )
            }.apply { expanded = false }

            collapsibleGroup("Windows SAPI — system voices${if (SystemInfo.isWindows) "" else " (Windows only)"}") {
                if (SystemInfo.isWindows) {
                    row {
                        sapiStatusLabel = JBLabel(sapiStatusText())
                        cell(sapiStatusLabel!!)
                    }
                    row {
                        button("Rescan Windows voices") { recheckSapi() }
                    }.rowComment("Discovered via System.Speech and offered per voice. Their culture maps to a walkthrough language, so they can add languages Kokoro and espeak never listed.")
                    row {
                        cell(ActionLink("Get NaturalVoiceSAPIAdapter (GitHub)", ActionListener { BrowserUtil.browse(ADAPTER_URL) }))
                    }.rowComment(
                        "Optional third-party SAPI5 provider — you install it, we don't bundle it. Adds the " +
                            "Windows “natural” voices and, with its Edge voices enabled, neural Azure voices " +
                            "for ~140 locales. Those are <b>online</b>: every sentence is sent to Microsoft.",
                    )
                } else {
                    row {
                        comment("Windows only — not available on this OS. macOS/Linux narrate with Kokoro + espeak.")
                    }
                }
            }.apply { expanded = false }

            collapsibleGroup("Piper — offline neural") {
                row {
                    piperStatusLabel = JBLabel(piperStatusText())
                    cell(piperStatusLabel!!)
                }.rowComment(
                    "Offline neural voices for the ~40 languages Kokoro can't voice — better than the " +
                        "robotic tier, and no online calls. Each voice is a ~20–75 MB download, kept under " +
                        "the Kokoro model directory.",
                )
                row {
                    button("Install Piper (pip)") {
                        TtsSetup.installPiper(anyOpenProject()) { refreshEngineStatusLabels() }
                    }
                    button("Manage voices…") {
                        PiperVoiceManagerDialog(anyOpenProject()).show()
                        refreshEngineStatusLabels()
                    }
                }.rowComment("Install the package once, then browse the catalog (~170 voices / ~50 languages) to download or remove voices.")
            }.apply { expanded = false }

            row {
                button("Re-check engines") { recheckAll() }
            }.rowComment("Re-probes the Kokoro prerequisites and rescans Windows voices (background), then refreshes the status above.")
        }.apply { expanded = true }

        // **Then what gets generated**, which precedes how it is spoken — so this sits above language and
        // narration rather than beside them. Three knobs over one subprocess: how much walkthrough to ask
        // for, how hard the agent thinks per turn, and which model does it. These are also the values the
        // per-run "Explain This (Audio) with…" dialog is seeded from.
        collapsibleGroup("Explanation") {
            row("Depth:") {
                comboBox(AudioExplainerSettings.DEPTH_LEVELS)
                    .bindItem(
                        { state.explainDepth },
                        { state.explainDepth = it ?: AudioExplainerSettings.DEPTH_STANDARD },
                    )
                    .applyToComponent { renderer = SimpleListCellRenderer.create("") { depthOptionLabel(it) } }
            }.rowComment(
                "How much walkthrough to ask for. <b>Depth is the knob that buys back time</b> — output " +
                    "volume is the best single predictor of how long a run takes; effort mostly buys " +
                    "correctness. Depth never asks for anything the plan schema does not already allow.",
            )
            row("Effort:") {
                comboBox(listOf("") + AudioExplainerSettings.EFFORT_LEVELS)
                    .bindItem({ state.explainEffort }, { state.explainEffort = it ?: "" })
                    .applyToComponent { renderer = SimpleListCellRenderer.create("") { effortLabel(it) } }
            }.rowComment(
                "<b>The plugin ships <code>medium</code>, overriding your CLI default on purpose.</b> Blank " +
                    "inherits <code>~/.claude/settings.json</code>'s <code>effortLevel</code> — which, chosen " +
                    "for interactive coding, was measured costing a walkthrough 255s where medium took 118s " +
                    "for the same plan shape.",
            )
            row("Model:") {
                comboBox(listOf("") + AudioExplainerSettings.MODEL_ALIASES)
                    .bindItem({ state.explainModel }, { state.explainModel = it?.trim() ?: "" })
                    .applyToComponent { renderer = SimpleListCellRenderer.create("") { modelLabel(it) } }
            }.rowComment("Blank keeps your own configured model, which is usually the right one.")
        }.apply { expanded = true }

        // **Then what a walkthrough is made of.** The language governs the prose *and* the narration, so
        // it leads and the audio switch, voice and speed read as consequences of it — which is also why
        // the voice combo is rebuilt from the language selected two rows above it.
        collapsibleGroup("Language & narration") {
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
            }.rowComment("Language for NEW ‘Explain This’ generations — a loaded walkthrough keeps its own. Each entry lists the voice tiers it offers; Japanese & Chinese need extra packs (Audio engines, above).")
            row {
                // The old "Engine" combo only ever meant on/off (the synthesizer tier is per-voice), so it's
                // just a checkbox now. `ttsEngine` still stores kokoro/off under the hood for back-compat and
                // the "Turn off audio" setup action.
                checkBox("Enable narration (audio)")
                    .bindSelected({ state.ttsEngine != ENGINE_OFF },
                        { state.ttsEngine = if (it) ENGINE_KOKORO else ENGINE_OFF })
            }.rowComment("Off = the walkthrough shows silently. The synthesizer tier (Kokoro · espeak · Windows SAPI · Piper) is chosen per voice below.")
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
            }.rowComment("Filtered to the language above, and refilled when you change it.")
            row("Speed (%):") {
                clampedSpinner(25..400).bindIntValue(state::ttsSpeedPercent)
            }.rowComment("100 = normal. The sidebar's speed buttons write here too.")
        }.apply { expanded = true }

        // **Then how it looks.** One umbrella so the whole appearance block folds away in a click, and
        // the accent leads it because every other colour in here is derived from that one swatch.
        collapsibleGroup("Appearance") {
            row {
                cell(appearancePreview)
            }.rowComment(
                "One narrated step — a callout, a context line, the active line and a non-active one, with a " +
                    "relation running to the right. Every control below redraws it.",
            )
            collapsibleGroup("Accent colour & non-active lines") {
                row("Accent colour:") {
                    // The code tree's outline, caption band and collapse hint are all derived from this, so
                    // its still frame two sections down has to be told when the swatch moves.
                    accentPanel.addActionListener { refreshTreePreview() }
                    cell(accentPanel)
                        .onReset { accentPanel.selectedColor = parseColor(state.accentColor) }
                        .onApply { accentPanel.selectedColor?.let { state.accentColor = toHex(it) } }
                        .onIsModified { toHex(accentPanel.selectedColor ?: return@onIsModified false) != state.accentColor }
                }.rowComment("The one colour every other cue here is derived from: the overview mark, the gap gutter bar, the non-active tint, and the relationship-arrow palette.")
                row {
                    tintCheck = checkBox("Tint non-active highlight lines")
                        .bindSelected(state::nonActiveTint).component
                }.rowComment("Gives non-active highlights their own cue so they don't rely on the gutter bar alone.")
                row("Tint strength (%):") {
                    tintSpinner = clampedSpinner(0..100).bindIntValue(state::nonActiveTintPercent).component
                }
            }.apply { expanded = true }

            collapsibleGroup("Active line") {
                row("Glow colour:") {
                    cell(glowPanel)
                        .onReset { glowPanel.selectedColor = parseColor(state.activeGlowColor) }
                        .onApply { glowPanel.selectedColor?.let { state.activeGlowColor = toHex(it) } }
                        .onIsModified { toHex(glowPanel.selectedColor ?: return@onIsModified false) != state.activeGlowColor }
                }
                row("Glow strength (%):") {
                    glowSpinner = clampedSpinner(0..100).bindIntValue(state::activeGlowPercent).component
                }
                row {
                    overviewCheck = checkBox("Show accent mark in the overview ruler")
                        .bindSelected(state::overviewRulerMark).component
                }
            }.apply { expanded = false }

            collapsibleGroup("Gap (context) lines") {
                row {
                    gapCheck = checkBox("Show accent gutter bar").bindSelected(state::gapGutter).component
                }
                row("Gutter bar width (px):") {
                    gapWidthSpinner = clampedSpinner(1..8).bindIntValue(state::gapGutterWidth).component
                }
            }.apply { expanded = false }

            collapsibleGroup("Dimming (segment spotlight)") {
                row("Outside-segment brightness (%):") {
                    outsideSpinner = clampedSpinner(0..100).bindIntValue(state::outsideDimPercent).component
                }.rowComment("Lower = more dimmed.")
                row("Within-segment brightness (%):") {
                    withinSpinner = clampedSpinner(0..100).bindIntValue(state::withinDimPercent).component
                }.rowComment("Keep this clearly brighter than the outside value.")
            }.apply { expanded = false }

            collapsibleGroup("Callouts") {
                row("Frame style:") {
                    frameCombo = comboBox(FRAME_IDS)
                        .bindItem(
                            { state.calloutFrame },
                            { state.calloutFrame = it ?: AudioExplainerSettings.CALLOUT_FRAME_BAR },
                        )
                        .applyToComponent { renderer = SimpleListCellRenderer.create("") { frameLabel(it) } }
                        .component
                }.rowComment("All five place the same text — only the decoration changes.")
                row("Description font:") {
                    proseFontCombo = comboBox(proseFonts())
                        .bindItem(
                            { state.calloutProseFont },
                            { state.calloutProseFont = it ?: "" },
                        )
                        .applyToComponent { renderer = SimpleListCellRenderer.create("") { proseFontLabel(it) } }
                        .component
                }.rowComment("The prose aside and the relation pointer lines. A worked example stays on the editor's code font — an example is code.")
                row("Tint strength (%):") {
                    calloutTintSpinner = clampedSpinner(0..100).bindIntValue(state::calloutTintPercent).component
                }.rowComment("How far the box's background is pulled toward the accent colour.")
                row("Opacity (%):") {
                    calloutOpacitySpinner = clampedSpinner(0..100).bindIntValue(state::calloutOpacityPercent).component
                }.rowComment("Lets code, or an arrow with nowhere else to run, read through the box. Only the <i>fill</i> fades — at 0 the text, bar and outline all still draw.")
                row("Corner radius (px):") {
                    calloutRadiusSpinner = clampedSpinner(0..16).bindIntValue(state::calloutCornerRadius).component
                }
                row("Accent bar width (px):") {
                    calloutBarSpinner = clampedSpinner(1..10).bindIntValue(state::calloutBarWidth).component
                }.rowComment("Only the Bar frame style draws one.")
                row("Text size (% of editor font):") {
                    calloutScaleSpinner = clampedSpinner(60..160).bindIntValue(state::calloutTextScalePercent).component
                }.rowComment("The wrap follows the size, so a larger box fits fewer words per line — the per-paragraph line caps don't move.")
                row {
                    calloutBelowCheck = checkBox("Dock the callout below the active lines")
                        .bindSelected(state::calloutBelow).component
                }.rowComment("Default is above, which matches reading order: the aside, then the code it describes.")
                row {
                    leaderCheck = checkBox("Draw a leader to the annotated line")
                        .bindSelected(state::calloutLeader).component
                }.rowComment("A speech-bubble tail off the box's near edge, pointing the way it is docked. Costs a few px of editor height per step.")
            }.apply { expanded = false }

            collapsibleGroup("Relationship arrows") {
                row("Link colours:") {
                    cell(arrowSlotRow)
                        .onReset { arrowSlotPanels.forEachIndexed { i, p -> p.selectedColor = slotColor(i) } }
                        .onApply {
                            state.arrowSlotColors = MutableList(LinkArt.slots()) { i ->
                                arrowSlotPanels[i].selectedColor?.let { toHex(it) } ?: ""
                            }
                        }
                        .onIsModified {
                            arrowSlotPanels.indices.any { i ->
                                (arrowSlotPanels[i].selectedColor?.let { toHex(it) } ?: "") != storedSlot(i)
                            }
                        }
                }
                    .rowComment(
                        "One colour per link on a step, in draw order. An empty swatch keeps that link's " +
                            "derived colour — the first is the accent, the rest spread around the wheel from " +
                            "it. “Clear” puts them all back to derived.",
                    )
                row("Thickness (tenths of a px):") {
                    arrowThicknessSpinner = clampedSpinner(8..40).bindIntValue(state::arrowThicknessTenths).component
                }.rowComment("10 is the 1 px line links ship with. Past about 25, several links leaving one row can start to intersect — hovering still tells them apart, so the range is left open.")
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
                }.rowComment("Off leaves that text to the callout box and the portal's tooltip, which carry it anyway.")
            }.apply { expanded = false }
        }.apply { expanded = true }

        // The shape of the tree itself — structure rather than theme, which is why it is its own section
        // and not an Appearance sub-group: everything above decorates lines *inside* one node.
        collapsibleGroup("Code tree") {
            row {
                cell(treePreview)
            }.rowComment(
                "One nested call, redrawn as you change the five shape settings below. The outer node is the " +
                    "active one and the frame inside it is not, so de-emphasis has somewhere to show.",
            )
            row("Nesting leader:") {
                treeLeaderCombo = comboBox(TREE_LEADERS)
                    .bindItem(
                        { state.treeLeader },
                        { state.treeLeader = it ?: AudioExplainerSettings.DEF_TREE_LEADER },
                    )
                    .applyToComponent {
                        renderer = SimpleListCellRenderer.create("") { treeLeaderLabel(it) }
                        // Fires on a pick *and* on every reset, so the frame can never show a mode the
                        // combo is not on — which is also what makes "Reset to defaults" redraw it.
                        addActionListener { refreshTreePreview() }
                    }
                    .component
            }.rowComment(
                "One outline in three shapes. Diagonals and Brace take the edge the box and the frame each " +
                    "give up, which is why there is no “none”; Brace also clears a caption band a diagonal " +
                    "cuts across.",
            )
            row("Collapse control:") {
                treeChevronCombo = comboBox(TREE_CHEVRONS)
                    .bindItem(
                        { state.treeChevron },
                        { state.treeChevron = it ?: AudioExplainerSettings.DEF_TREE_CHEVRON },
                    )
                    .applyToComponent {
                        renderer = SimpleListCellRenderer.create("") { treeChevronLabel(it) }
                        // Fires on a pick *and* on every reset, so the frame can never show a mode the
                        // combo is not on — which is also what makes "Reset to defaults" redraw it.
                        addActionListener { refreshTreePreview() }
                    }
                    .component
            }.rowComment(
                "<b>Bottom edge</b> is the default and the only one that costs the token no width: it marks " +
                    "the box while a frame is folded away, and while one is open it becomes the accent " +
                    "triangle in the strip. <b>None</b> hides the hint, not the gesture — the box still " +
                    "toggles on a click.",
            )
            row("Fold nodes a step does not use:") {
                treeStepFoldCombo = comboBox(TREE_STEP_FOLDS)
                    .bindItem(
                        { state.treeStepFold },
                        { state.treeStepFold = it ?: AudioExplainerSettings.DEF_TREE_STEP_FOLD },
                    )
                    .applyToComponent { renderer = SimpleListCellRenderer.create("") { treeStepFoldLabel(it) } }
                    .component
            }.rowComment(
                "A frame the current step is not talking about spends height on code the narration is not " +
                    "about. <b>Always</b> folds those as each step starts; <b>Only when over the line " +
                    "budget</b> is what shipped before. Never while the step is inside it, and stepping back " +
                    "into a folded one opens it. No still frame here — it is a behaviour over time.",
            )
            row("Fold code a step does not use:") {
                treeCondenseCombo = comboBox(TREE_CONDENSES)
                    .bindItem(
                        { state.treeCondense },
                        { state.treeCondense = it ?: AudioExplainerSettings.DEF_TREE_CONDENSE },
                    )
                    .applyToComponent { renderer = SimpleListCellRenderer.create("") { treeCondenseLabel(it) } }
                    .component
            }.rowComment(
                "Inside the frames that stay open: what of each frame's lines the current step folds behind " +
                    "<code>⋯ N lines ⋯</code> for itself. Headers, call lines, link ends and the step's own " +
                    "lines always stay; a fold opens on a click, and the next step shows the frame as it needs it.",
            )
            row("File path in captions:") {
                treePathCombo = comboBox(TREE_PATHS)
                    .bindItem(
                        { state.treePath },
                        { state.treePath = it ?: AudioExplainerSettings.DEF_TREE_PATH },
                    )
                    .applyToComponent {
                        renderer = SimpleListCellRenderer.create("") { treePathLabel(it) }
                        // Fires on a pick *and* on every reset, so the frame can never show a mode the
                        // combo is not on — which is also what makes "Reset to defaults" redraw it.
                        addActionListener { refreshTreePreview() }
                    }
                    .component
            }.rowComment(
                "Only nodes that carry a caption show one — a frame nested in its caller's own file has no " +
                    "header band to put a path in.",
            )
            row("Push non-active nodes back with:") {
                treeDeemphasisCombo = comboBox(TREE_DEEMPHASIS)
                    .bindItem(
                        { state.treeDeemphasis },
                        { state.treeDeemphasis = it ?: AudioExplainerSettings.DEF_TREE_DEEMPHASIS },
                    )
                    .applyToComponent {
                        renderer = SimpleListCellRenderer.create("") { treeDeemphasisLabel(it) }
                        // Fires on a pick *and* on every reset, so the frame can never show a mode the
                        // combo is not on — which is also what makes "Reset to defaults" redraw it.
                        addActionListener { refreshTreePreview() }
                    }
                    .component
            }.rowComment(
                "Every option is a background. Nothing here dims the <i>text</i> — a foreground dim takes the " +
                    "syntax colouring with it, which is the one thing a real editor per node exists to keep.",
            )
            row("Hidden lines:") {
                treeElisionCombo = comboBox(TREE_ELISIONS)
                    .bindItem(
                        { state.treeElision },
                        { state.treeElision = it ?: AudioExplainerSettings.DEF_TREE_ELISION },
                    )
                    .applyToComponent {
                        renderer = SimpleListCellRenderer.create("") { treeElisionLabel(it) }
                        // Fires on a pick *and* on every reset, so the frame can never show a mode the
                        // combo is not on — which is also what makes "Reset to defaults" redraw it.
                        addActionListener { refreshTreePreview() }
                    }
                    .component
            }.rowComment(
                "A node shows the lines the walkthrough asked for and folds the rest away. <b>Openable</b> " +
                    "clicks open, and hovering it shows the hidden code; <b>Sealed</b> cannot be opened — " +
                    "the platform's limitation, not a choice about tidiness.",
            )
            row("Node height limit (px):") {
                treeMaxHeightSpinner = clampedSpinner(TreeStyle.MIN_MAX_HEIGHT..TreeStyle.MAX_MAX_HEIGHT, step = 20)
                    .bindIntValue(state::treeMaxHeight).component
            }.rowComment(
                "A ceiling, not a size: a node is the smaller of its excerpt, its content and this — so a " +
                    "six-line member gets six lines, and only a long one is capped and scrolls.",
            )
            row {
                treeGrowCheck = checkBox("Let every node grow to its full content")
                    .bindSelected(state::treeGrowNodes).component
            }.rowComment(
                "Ignores the limit above. A node as tall as its content has nothing to scroll, so a step can " +
                    "no longer bring its lines into view inside it — the page scrolls instead.",
            )
            // Every combo the refresh reads exists by now; reset fires the listeners anyway, so this only
            // matters for the instant between building the panel and the platform's first reset.
            refreshTreePreview()
            row {
                treeGrowHostsCheck = checkBox("Grow the outermost node when it hosts a nested frame")
                    .bindSelected(state::treeGrowHosts).component
            }.rowComment(
                "On by default: capping the outermost node puts a second scrollbar inside the first. Nested " +
                    "hosts are always sized to their excerpt plus whatever is embedded in them.",
            )
            // Was the group's own rowComment; a rowComment on a collapsible header keeps showing while
            // the section is folded, so the note lives inside the fold it belongs to.
            row {
                comment(
                    "Three things about the tree are <b>not</b> here, and each was decided rather than " +
                        "left out: the caption band is chosen by a node's role (outermost, or a different " +
                        "file from its parent), the invoking token is always boxed because every leader " +
                        "starts at its corners, and nested frames are never indented — the nested gutter " +
                        "is the boundary.",
                )
            }
        }.apply { expanded = false }

        collapsibleGroup("Executables (advanced)") {
            row("Claude CLI path:") {
                textField().bindText({ state.claudeExecutablePath }, { state.claudeExecutablePath = it.trim() })
                    .comment("Blank = auto-detect (PATH + common install dirs). Set if the IDE can't find it.")
            }
            row("Python path:") {
                textField().bindText({ state.pythonExecutablePath }, { state.pythonExecutablePath = it.trim() })
                    .comment("Blank = auto-detect. Used to launch the Kokoro TTS server.")
            }
            row {
                button("Check permissions and settings") { runDiagnostics() }
            }.rowComment(
                "Read-only checks over the things that decide whether an Explain This run can work at all — " +
                    "the CLI and its version, which effort is actually in force, the skill install, the " +
                    "loopback server, and a real POST through the platform's own helper with non-ASCII text " +
                    "in it. Every row names its fix. It uses the <i>saved</i> values, so apply first.",
            )
        }.apply { expanded = false }

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
                overviewCheck.isSelected = true
                tintCheck.isSelected = true
                gapCheck.isSelected = true
                calloutBelowCheck.isSelected = false
                arrowSquareCheck.isSelected = false
                arrowLabelsCheck.isSelected = true
                leaderCheck.isSelected = true
                frameCombo.selectedItem = AudioExplainerSettings.CALLOUT_FRAME_BAR
                proseFontCombo.selectedItem = ""
                accentPanel.selectedColor = parseColor(AudioExplainerSettings.DEF_ACCENT_COLOR)
                glowPanel.selectedColor = parseColor(AudioExplainerSettings.DEF_ACTIVE_GLOW_COLOR)
                // Empty, not "the derived colour" — see [arrowSlotPanels]. Reset restores the *rule* that
                // produces the palette, which is the only thing that can survive a change of accent.
                arrowSlotPanels.forEach { it.selectedColor = null }
                // **The tree's defaults are the judged verdicts** (§1c), so this button restores the shape
                // nine sandbox rounds settled rather than a set of neutral starting values. That is the
                // whole reason they are constants on the settings class instead of literals in `TreeStyle`:
                // Reset and the style resolver have to read the same number.
                treeLeaderCombo.selectedItem = AudioExplainerSettings.DEF_TREE_LEADER
                treeChevronCombo.selectedItem = AudioExplainerSettings.DEF_TREE_CHEVRON
                treeStepFoldCombo.selectedItem = AudioExplainerSettings.DEF_TREE_STEP_FOLD
                treePathCombo.selectedItem = AudioExplainerSettings.DEF_TREE_PATH
                treeDeemphasisCombo.selectedItem = AudioExplainerSettings.DEF_TREE_DEEMPHASIS
                treeElisionCombo.selectedItem = AudioExplainerSettings.DEF_TREE_ELISION
                treeMaxHeightSpinner.value = AudioExplainerSettings.DEF_TREE_MAX_HEIGHT
                treeGrowCheck.isSelected = false
                treeGrowHostsCheck.isSelected = true
                treeCondenseCombo.selectedItem = AudioExplainerSettings.DEF_TREE_CONDENSE
                // `ColorPanel.setSelectedColor` notifies nobody, so the two frames are told by hand rather
                // than left showing the palette this button just replaced.
                refreshAppearancePreview()
                refreshTreePreview()
            }
                // Runs after every binding/colour write above (cells apply in creation order), so the
                // live re-render sees the just-applied values.
                .onApply { refreshLiveWalkthrough() }
        }.rowComment("Applies to the fields above; save with OK/Apply.")

        // Last, because it subscribes to every control on the page and then draws once.
        wireAppearancePreview()
    }

    /** [slot]'s stored override as a colour, or null when it is blank — see [arrowSlotPanels]. */
    private fun slotColor(slot: Int): Color? =
        state.arrowSlotColors.getOrNull(slot)?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }?.let {
            try {
                Color(it.toInt(16))
            } catch (_: NumberFormatException) {
                null
            }
        }

    /**
     * [slot]'s stored override in the same normalised form [toHex] produces, so `isModified` compares two
     * hex strings rather than a hex string against whatever case the settings file happened to carry.
     */
    private fun storedSlot(slot: Int): String = slotColor(slot)?.let { toHex(it) } ?: ""

    /**
     * Redraw the Code-tree still frame from the **live combos**, not from [state].
     *
     * The Kotlin UI DSL only writes `state` on apply, so a preview that read the settings would be one OK
     * behind every pick. The values are handed to [TreeStyle] as a throwaway `State` so the preview resolves
     * a persisted string to an enum through the one function the renderer uses; nothing here parses a mode
     * name itself.
     *
     * Guarded on the last of the five combos: each one's listener fires while the panel is still being built,
     * when the combos below it do not exist yet.
     */
    private fun refreshTreePreview() {
        if (!this::treeElisionCombo.isInitialized) return
        val picked = AudioExplainerSettings.State().apply {
            treeLeader = treeLeaderCombo.selectedItem as? String ?: state.treeLeader
            treeChevron = treeChevronCombo.selectedItem as? String ?: state.treeChevron
            treePath = treePathCombo.selectedItem as? String ?: state.treePath
            treeDeemphasis = treeDeemphasisCombo.selectedItem as? String ?: state.treeDeemphasis
            treeElision = treeElisionCombo.selectedItem as? String ?: state.treeElision
        }
        treePreview.show(
            TreeStyle.nest(picked),
            TreeStyle.node(picked),
            accentPanel.selectedColor ?: parseColor(state.accentColor),
        )
    }

    /**
     * Redraw the Appearance still frame from the **live controls**, not from [state].
     *
     * Same rule as [refreshTreePreview] and for the same reason: the DSL writes `state` on apply, so a frame
     * fed from the settings would be one OK behind every spinner. The pending values are assembled into a
     * throwaway `State` and handed to [SpotlightRenderer.palette] — the renderer's own colour derivation —
     * so nothing here re-blends a tint or re-clamps a percentage.
     */
    private fun refreshAppearancePreview() {
        if (!this::arrowLabelsCheck.isInitialized) return
        appearancePreview.show(
            AudioExplainerSettings.State().apply {
                accentColor = toHex(accentPanel.selectedColor ?: parseColor(state.accentColor))
                activeGlowColor = toHex(glowPanel.selectedColor ?: parseColor(state.activeGlowColor))
                outsideDimPercent = outsideSpinner.number
                withinDimPercent = withinSpinner.number
                activeGlowPercent = glowSpinner.number
                overviewRulerMark = overviewCheck.isSelected
                nonActiveTint = tintCheck.isSelected
                nonActiveTintPercent = tintSpinner.number
                gapGutter = gapCheck.isSelected
                gapGutterWidth = gapWidthSpinner.number
                calloutFrame = frameCombo.selectedItem as? String ?: state.calloutFrame
                calloutProseFont = (proseFontCombo.selectedItem as? String).orEmpty()
                calloutTintPercent = calloutTintSpinner.number
                calloutOpacityPercent = calloutOpacitySpinner.number
                calloutCornerRadius = calloutRadiusSpinner.number
                calloutBarWidth = calloutBarSpinner.number
                calloutTextScalePercent = calloutScaleSpinner.number
                calloutBelow = calloutBelowCheck.isSelected
                calloutLeader = leaderCheck.isSelected
                arrowThicknessTenths = arrowThicknessSpinner.number
                arrowHeadSize = arrowHeadSpinner.number
                arrowSquareElbow = arrowSquareCheck.isSelected
                arrowLabels = arrowLabelsCheck.isSelected
                arrowSlotColors = MutableList(LinkArt.slots()) { i ->
                    arrowSlotPanels[i].selectedColor?.let { toHex(it) } ?: ""
                }
            },
        )
    }

    /**
     * Subscribe the Appearance frame to every control that feeds it, **in one place and after the panel is
     * built**.
     *
     * Two dozen controls across six sub-groups reach this one picture, and hanging a listener off each at its
     * own row is two dozen chances to forget one — a forgotten one is a control that silently does nothing
     * in the preview, which is worse than having no preview. `probes/AppearanceBench.java` asserts that every
     * value of every control changes the frame; this list is what that assertion is really checking.
     *
     * Checkboxes are wired through `addItemListener` and not `addActionListener`: `setSelected` fires the
     * former and not the latter, and "Reset to defaults" sets them rather than clicking them.
     */
    private fun wireAppearancePreview() {
        val refresh = { refreshAppearancePreview() }
        (listOf(accentPanel, glowPanel) + arrowSlotPanels).forEach { it.addActionListener { refresh() } }
        listOf(
            outsideSpinner, withinSpinner, glowSpinner, tintSpinner, gapWidthSpinner,
            calloutTintSpinner, calloutOpacitySpinner, calloutRadiusSpinner, calloutBarSpinner,
            calloutScaleSpinner, arrowThicknessSpinner, arrowHeadSpinner,
        ).forEach { it.addChangeListener { refresh() } }
        listOf(
            overviewCheck, tintCheck, gapCheck, calloutBelowCheck, leaderCheck,
            arrowSquareCheck, arrowLabelsCheck,
        ).forEach { it.addItemListener { refresh() } }
        listOf(frameCombo, proseFontCombo).forEach { it.addActionListener { refresh() } }
        refresh()
    }


    /**
     * Re-style the currently-showing walkthrough (if any) with the new settings.
     *
     * This used to find `EditorHighlighter`s. When the in-place surface was deleted the filter would have
     * matched nothing and Apply would have become a silent no-op — the settings would take effect only on the
     * next render, which for an open tree could be never.
     *
     * Every *colour* is derived per render (constraint 11), so re-rendering the current step is the re-style
     * for those. The **tree's shape** is not: the leader mode decides which border edges are drawn at all, and
     * painting a step never revisits them. `TreePlanController.refresh` is where the two are told apart, and
     * this call is the same either way.
     *
     * **The sidebar needs telling too, and did not used to be told** (K6). Its accent is a CSS variable the
     * plugin writes from `accentColor`, but only when the page is built or the IDE's LAF changes — so an
     * Apply moved the tree and left the panel on the old colour. Same listener list, same reason it works:
     * a `Walkthrough` outlives any one tool window, so whoever is registered is whoever is open.
     */
    private fun refreshLiveWalkthrough() {
        val listeners = service<ExplainerServerService>().server.walkthrough.getListeners()
        listeners.filterIsInstance<TreePlanController>().forEach { it.refresh() }
        listeners.filterIsInstance<AudioExplainerSidebar>().forEach { it.refreshTheme() }
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
        cppStatusLabel?.text = cppStatusText()
    }

    /**
     * The compiler line under the Kokoro group, and what it says depends on whether it *matters*: a machine
     * with `misaki[ja]` already importable has nothing left to compile, and saying "no build tools" there
     * would be a warning about a problem that cannot happen.
     *
     * Reads the cache only ([TtsService.cachedMsvcBuildTools]) — the panel is built on the EDT and vswhere is
     * a subprocess. The init block's warm-up and "Re-check compiler" are what fill it.
     */
    private fun cppStatusText(): String {
        val tts = TtsService.getInstance()
        if (tts.cachedStatusOrNull()?.misakiJa == true) return "Japanese pack is installed — no compiler needed."
        return when (tts.cachedMsvcBuildTools()) {
            // Found — but if only the `-all` query saw it, say so. The installer's "incomplete" flag comes
            // and goes during an update, and pip has been seen building against an instance wearing it, so
            // this is a footnote on a working toolchain, not a reason to send anyone shopping.
            true -> tts.flaggedIncompleteMsvcInstall()?.let { path ->
                "<html>C++ build tools found at <code>${path.replace("&", "&amp;")}</code> — the Visual " +
                    "Studio Installer currently flags that instance <b>incomplete</b>. Builds usually " +
                    "still find it; <b>Repair</b> it there if a compile fails.</html>"
            } ?: "C++ build tools found ✓ — a source-only dependency can be compiled."
            false -> "<html>No C++ build tools found — <b>Japanese</b> may fail to install without them.</html>"
            null -> "Not checked yet — click “Re-check compiler”."
        }
    }

    /** Re-probe the MSVC toolchain off the EDT (vswhere is a subprocess), then refresh the line. */
    private fun recheckCpp() {
        cppStatusLabel?.text = "Checking…"
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { TtsService.getInstance().msvcBuildTools(refresh = true) }
            ApplicationManager.getApplication().invokeLater({ refreshEngineStatusLabels() }, ModalityState.any())
        }
    }

    /** "Re-check engines": re-probe Kokoro prerequisites + rescan SAPI voices off the EDT, then refresh. */
    /**
     * Run [ExplainerDiagnostics] off the EDT behind a modal progress, then show the rows.
     *
     * It reads the **saved** state, not the pending controls: a check that reported on values you have not
     * applied yet would be reporting on a run that cannot happen. The row comment says so. The server is
     * handed in so the round-trip check can compare what arrived rather than what was printed.
     */
    private fun runDiagnostics() {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        val ctx = DiagnosticContext(
            state = state,
            isWindows = SystemInfo.isWindows,
            server = runCatching { service<ExplainerServerService>().server }.getOrNull(),
            projectPath = project?.basePath,
        )
        var results: List<DiagnosticResult> = emptyList()
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { results = ExplainerDiagnostics.run(ctx) },
            "Checking Permissions and Settings…",
            true,
            project,
        )
        DiagnosticsDialog(project, results).show()
    }

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
        private const val BUILD_TOOLS_URL = "https://visualstudio.microsoft.com/visual-cpp-build-tools/"

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
         * Label for an effort value; "" is the first item and is the *inherit* one.
         *
         * The numbers are `Speed.md`'s, and only the measured stops carry one — `high` and `max` were never
         * run, and a plausible-looking interpolation on a settings page reads as a result.
         */
        private fun effortLabel(value: String): String = when (value) {
            "" -> "Default — inherit your CLI's own effortLevel"
            AudioExplainerSettings.EFFORT_LOW -> "low — fastest (74s measured), likeliest to get a line number wrong"
            AudioExplainerSettings.EFFORT_MEDIUM -> "medium — 118s measured, same plan shape (shipped default)"
            AudioExplainerSettings.EFFORT_HIGH -> "high — between medium and xhigh (not measured)"
            AudioExplainerSettings.EFFORT_XHIGH -> "xhigh — 255s measured"
            AudioExplainerSettings.EFFORT_MAX -> "max — the most careful and the slowest (not measured)"
            else -> value
        }

        /** Label for a `--model` alias; "" is the first item and leaves the user's own model alone. */
        private fun modelLabel(value: String): String =
            if (value.isEmpty()) "Default — your configured model" else value

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

        // ── The code tree's combos (Phase 4) ───────────────────────────────────
        //
        // **The items come off the enums, not out of a list here.** A hand-written list is a second
        // enumeration of the same fact, and the one that silently goes stale is always the one further from
        // the code — a fourth leader mode would be drawable, persistable and invisible in this dialog. The
        // labels below are a `when` for the same reason: adding a constant makes the compiler's exhaustive
        // check the reminder, and an unlabelled value still shows up under its own name.
        private val TREE_LEADERS = NestLeader.entries.map { it.name }
        private val TREE_CHEVRONS = ChevronStyle.entries.map { it.name }
        private val TREE_STEP_FOLDS = StepFold.entries.map { it.name }
        private val TREE_CONDENSES = StepCondense.entries.map { it.name }
        private val TREE_PATHS = NodePath.entries.map { it.name }
        private val TREE_DEEMPHASIS = NodeDeemphasis.entries.map { it.name }
        private val TREE_ELISIONS = NodeElision.entries.map { it.name }

        /** Prose for a value, or the constant's own name — which is what an unlabelled new mode should read as. */
        private fun label(name: String?, prose: Map<String, String>): String =
            prose[name?.uppercase()] ?: name.orEmpty()

        private fun treeLeaderLabel(name: String?): String = label(
            name,
            mapOf(
                "CORNERS" to "Diagonals — box corners out to the frame's corners",
                "BRACE" to "Brace — out along the box's own level, then down",
                "ARROW" to "Arrow — the box's edge continued, turning down into an arrowhead",
            ),
        )

        private fun treeChevronLabel(name: String?): String = label(
            name,
            mapOf(
                "HORIZONTAL" to "Chevron beside the box — » to expand, « to collapse",
                "VERTICAL" to "Chevron beside the box — ˅ to expand, ˄ to collapse",
                "BOTTOM_BORDER" to "A thickened bottom edge on the box while collapsed, no chevron",
                "NONE" to "Nothing — the box still toggles on a click",
            ),
        )

        private fun treeCondenseLabel(name: String?): String = label(
            name,
            mapOf(
                "OFF" to "Nothing — every frame shows all it keeps",
                "ANCESTORS" to "The frames above a nested step",
                "LINKS" to "Those, and the stretch between a long link's ends",
                "STEP" to "Everything the step does not touch",
            ),
        )

        private fun treeStepFoldLabel(name: String?): String = label(
            name,
            mapOf(
                "NEVER" to "Never — the tree only moves when you move it",
                "OVER_BUDGET" to "Only when the scene is over the line budget",
                "ALWAYS" to "Always — fold anything the step is not about",
            ),
        )

        private fun treePathLabel(name: String?): String = label(
            name,
            mapOf(
                "FULL" to "Full path",
                "PROJECT_RELATIVE" to "Relative to the project root",
                "BASENAME" to "File name only",
            ),
        )

        private fun treeDeemphasisLabel(name: String?): String = label(
            name,
            mapOf(
                "NONE" to "Nothing — every node looks the same",
                "TINT" to "A wash over the node",
                "BORDER" to "A dimmed frame",
                "TINT_AND_BORDER" to "A wash and a dimmed frame",
            ),
        )

        private fun treeElisionLabel(name: String?): String = label(
            name,
            mapOf(
                "OPENABLE" to "Openable — click a folded run to read it",
                "SEALED" to "Sealed — a tidier “⋯ N lines ⋯” rule that cannot be opened",
            ),
        )

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
