package com.audioexplainer

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.util.Hashtable
import javax.swing.JComponent
import javax.swing.JSlider

/**
 * **"Explain This (Audio) with…"** — the per-run overrides, shown before anything is spawned and seeded
 * from the saved defaults.
 *
 * Settings hold what almost every run should use. One run in ten wants something else, and walking to
 * Settings and back to change it is worse than the thing it fixes — so this exists, and the plain menu
 * entry above it stays exactly one click.
 *
 * **Nothing here writes back to Settings.** A one-off must not silently become the new default; that is
 * how a reader ends up wondering why every explanation went terse. The instruction *history* is the one
 * exception ([AudioExplainerSettings.rememberInstruction]), because retyping "use simple terms" on every
 * run is precisely the friction this dialog exists to remove.
 *
 * The line under the slider changes with the stop because the word on its own means nothing: `medium`
 * only tells you something next to what `xhigh` costs. The numbers on it are `Speed.md`'s measurements,
 * and the stops it has no measurement for say so rather than guessing.
 */
class ExplainOptionsDialog(project: Project?) : DialogWrapper(project) {

    private val settings = AudioExplainerSettings.getInstance()

    private val effortSlider = JSlider(0, AudioExplainerSettings.EFFORT_LEVELS.size - 1, seedEffortIndex()).apply {
        majorTickSpacing = 1
        paintTicks = true
        paintLabels = true
        snapToTicks = true
        labelTable = Hashtable<Int, JComponent>().also { t ->
            AudioExplainerSettings.EFFORT_LEVELS.forEachIndexed { i, level -> t[i] = JBLabel(level) }
        }
    }

    private val effortNote = JBLabel().apply { preferredSize = Dimension(JBUI.scale(440), JBUI.scale(52)) }

    private val depthCombo = ComboBox(AudioExplainerSettings.DEPTH_LEVELS.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { depthOptionLabel(it) }
        selectedItem = AudioExplainerSettings.DEPTH_LEVELS
            .firstOrNull { it == settings.state.explainDepth } ?: AudioExplainerSettings.DEPTH_STANDARD
    }

    private val instructions = JBTextArea(4, 48).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    /** The last few non-empty entries; picking one fills the box. Absent entirely when there are none. */
    private val history: List<String> = settings.state.explainInstructionHistory.filter { it.isNotBlank() }
    private val historyCombo = ComboBox((listOf(RECENT_PLACEHOLDER) + history).toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.replace('\n', ' ').take(90) }
        addActionListener {
            val picked = selectedItem as? String
            if (picked != null && picked != RECENT_PLACEHOLDER) instructions.text = picked
        }
    }

    init {
        title = "Explain This (Audio) with…"
        setOKButtonText("Explain")
        effortSlider.addChangeListener { refreshEffortNote() }
        refreshEffortNote()
        init()
    }

    override fun getDimensionServiceKey(): String = "AudioExplainer.ExplainOptionsDialog"

    override fun getPreferredFocusedComponent(): JComponent = instructions

    override fun createCenterPanel(): JComponent {
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent("Effort:", effortSlider)
            .addComponentToRightColumn(effortNote)
            .addLabeledComponent("Depth:", depthCombo)
            .addSeparator()
            .addLabeledComponent("Additional instructions:", JBScrollPane(instructions))
        if (history.isNotEmpty()) builder.addLabeledComponent("Reuse:", historyCombo)
        builder.addComponentToRightColumn(
            JBLabel(
                "<html><body style='width:440px'>Anything that <i>narrows</i> or restyles the walkthrough " +
                    "works — \"use simple terms\", \"skip the error handling\". Anything that would replace " +
                    "the plan itself does not: the plugin still needs the walkthrough back as data.</body></html>",
            ),
        )
        return builder.panel.apply {
            border = JBUI.Borders.empty(10)
            preferredSize = Dimension(JBUI.scale(560), preferredSize.height)
        }
    }

    // ── what the caller reads back ────────────────────────────────────────────

    /** The chosen `--effort`, never blank: opening this dialog *is* choosing one for this run. */
    fun effort(): String = AudioExplainerSettings.EFFORT_LEVELS[effortSlider.value]

    fun depth(): String = depthCombo.selectedItem as? String ?: AudioExplainerSettings.DEPTH_STANDARD

    /** The typed text, or null when the box is empty — null is what keeps the prompt free of the block. */
    fun extraInstructions(): String? = instructions.text.trim().takeIf { it.isNotEmpty() }

    override fun doOKAction() {
        // The one thing that persists. Values do not; see the class doc.
        extraInstructions()?.let { settings.rememberInstruction(it) }
        super.doOKAction()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Where the slider starts: the saved effort, or `medium` when it is blank (inherit has no stop). */
    private fun seedEffortIndex(): Int {
        val saved = AudioExplainerSettings.getInstance().state.explainEffort
        val i = AudioExplainerSettings.EFFORT_LEVELS.indexOf(saved)
        return if (i >= 0) i else AudioExplainerSettings.EFFORT_LEVELS.indexOf(AudioExplainerSettings.EFFORT_MEDIUM)
    }

    private fun refreshEffortNote() {
        effortNote.text = "<html><body style='width:440px'>" + EFFORT_NOTES[effortSlider.value] + "</body></html>"
    }

    private companion object {
        const val RECENT_PLACEHOLDER = "Recent…"

        /**
         * One line per stop, in [AudioExplainerSettings.EFFORT_LEVELS] order. The measured ones quote
         * `Speed.md`'s table (one 200-line C# method, n = 1 per variant); the two that were never measured
         * say so instead of interpolating a number that would read as a result.
         */
        val EFFORT_NOTES = listOf(
            "<b>low</b> — the fastest, and the likeliest to get a line number or a call chain wrong. " +
                "Measured: 74s, against 255s at <code>xhigh</code>.",
            "<b>medium</b> — about half the time of <code>xhigh</code>, same plan shape. " +
                "Measured: 118s vs 255s. This is what the plugin ships.",
            "<b>high</b> — between <code>medium</code> and <code>xhigh</code>. Not measured.",
            "<b>xhigh</b> — what a run inherited before this control existed, if that is your CLI default. " +
                "Measured: 255s (mean of two runs).",
            "<b>max</b> — the most careful, and the slowest. Not measured; expect more than <code>xhigh</code>.",
        )
    }
}

/**
 * The label for one depth, used by this dialog **and** by the Settings combo — one spelling, so the two
 * places a reader meets "Brief" cannot come to describe different things.
 */
internal fun depthOptionLabel(code: String?): String = when (code) {
    AudioExplainerSettings.DEPTH_BRIEF -> "Brief — one frame, up to 4 steps, flat"
    AudioExplainerSettings.DEPTH_DETAILED -> "Detailed — up to 3 frames from the target, asides and links encouraged"
    AudioExplainerSettings.DEPTH_DEEP -> "Deep — up to 4 frames, may follow calls into other files"
    else -> "Standard — 1–2 frames (the default)"
}
