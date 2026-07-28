package com.audioexplainer

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider

/**
 * Non-modal native editor for one robotic (espeak) voice variant's full parameter set — runtime
 * (Pitch / Liveliness / Word gap) and Layer-2 (Clarity / Smoothness / Flutter / Breath). Opened from the
 * sidebar's "Advanced…" button. Buttons: **▶ Test** (preview with the current, uncommitted values — does
 * not close; while a preview plays it becomes **■ Test** and clicking it stops playback, then flips back
 * to ▶ on its own when the sample finishes), **Reset to default** (fills defaults; still needs Save),
 * **Cancel**, **Save voice** (persist + close). Non-modal so it fits a "tweak → Test → tweak → Save" loop
 * and never blocks the webview's audio. Size is remembered ([getDimensionServiceKey]).
 *
 * `roughness` is espeak-native (0 = smoothest); shown here as "Smoothness" = 10 − roughness.
 *
 * The finish callback handed to [onTest] must be invoked on the EDT (the sidebar marshals it); a
 * generation token ([playGen]) drops stale finishes so starting a new preview never resets the button of
 * the one that just started.
 */
class EspeakVoiceDialog(
    project: Project,
    voiceLabel: String,
    initial: EspeakProfile,
    private val onTest: (tune: EspeakTune, onFinished: () -> Unit) -> Unit,
    private val onStop: () -> Unit,
    private val onSave: (EspeakProfile) -> Unit,
    private val onClosed: () -> Unit,
) : DialogWrapper(project) {

    private val pitch = slider(0, 100, initial.pitch)
    private val range = slider(0, 100, initial.range)
    private val wordGap = slider(0, 20, initial.wordGap)
    private val clarity = slider(80, 160, initial.consonants)
    private val smoothness = slider(0, 10, 10 - initial.roughness)
    private val flutter = slider(0, 20, initial.flutter)
    private val breath = slider(0, 8, initial.breath)

    // Test/Stop toggle state — touched only on the EDT (button clicks + [notifyPreviewFinished]).
    private lateinit var testAction: AbstractAction
    private var playing = false
    private var playGen = 0

    init {
        title = "Change $voiceLabel voice"
        isModal = false
        init()
    }

    override fun getDimensionServiceKey(): String = "AudioExplainer.EspeakVoiceDialog"

    override fun createCenterPanel(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Pitch", row(pitch))
            .addLabeledComponent("Liveliness", row(range))
            .addLabeledComponent("Word gap", row(wordGap))
            .addSeparator()
            .addLabeledComponent("Clarity", row(clarity))
            .addLabeledComponent("Smoothness", row(smoothness))
            .addLabeledComponent("Flutter", row(flutter))
            .addLabeledComponent("Breath", row(breath))
            .panel
        panel.border = JBUI.Borders.empty(10)
        panel.preferredSize = Dimension(380, panel.preferredSize.height)
        return panel
    }

    override fun createActions(): Array<Action> {
        testAction = object : AbstractAction("$PLAY Test") {
            override fun actionPerformed(e: ActionEvent) = if (playing) stopPreview() else startPreview()
        }
        val reset = object : AbstractAction("Reset to default") {
            override fun actionPerformed(e: ActionEvent) = setSliders(EspeakProfile())
        }
        val cancel = object : AbstractAction("Cancel") {
            override fun actionPerformed(e: ActionEvent) = doCancelAction()
        }
        val save = object : AbstractAction("Save voice") {
            override fun actionPerformed(e: ActionEvent) {
                onSave(currentProfile())
                close(DialogWrapper.OK_EXIT_CODE)
            }
        }
        save.putValue(DialogWrapper.DEFAULT_ACTION, true)
        return arrayOf(testAction, reset, cancel, save)
    }

    /** Start a preview: flip the button to ■ Stop and register a stale-proof finish callback. */
    private fun startPreview() {
        val gen = ++playGen
        setPlaying(true)
        onTest(currentTune()) { if (gen == playGen) setPlaying(false) }
    }

    /** Stop the current preview (button click while playing). Bumps [playGen] so the pending finish is dropped. */
    private fun stopPreview() {
        playGen++
        setPlaying(false)
        onStop()
    }

    private fun setPlaying(value: Boolean) {
        playing = value
        testAction.putValue(Action.NAME, if (value) "$STOP Test" else "$PLAY Test")
    }

    override fun dispose() {
        onClosed()
        super.dispose()
    }

    private fun currentProfile(): EspeakProfile = EspeakProfile().also {
        it.pitch = pitch.value
        it.range = range.value
        it.wordGap = wordGap.value
        it.consonants = clarity.value
        it.roughness = 10 - smoothness.value
        it.flutter = flutter.value
        it.breath = breath.value
    }

    private fun currentTune(): EspeakTune = EspeakTune.from(currentProfile())

    private fun setSliders(p: EspeakProfile) {
        pitch.value = p.pitch
        range.value = p.range
        wordGap.value = p.wordGap
        clarity.value = p.consonants
        smoothness.value = 10 - p.roughness
        flutter.value = p.flutter
        breath.value = p.breath
    }

    private fun slider(min: Int, max: Int, value: Int) = JSlider(min, max, value.coerceIn(min, max))

    /** A slider plus a live numeric read-out on its right. */
    private fun row(s: JSlider): JComponent {
        val value = JBLabel(s.value.toString())
        value.preferredSize = Dimension(30, value.preferredSize.height)
        s.addChangeListener { value.text = s.value.toString() }
        return JPanel(BorderLayout(6, 0)).apply {
            add(s, BorderLayout.CENTER)
            add(value, BorderLayout.EAST)
        }
    }

    private companion object {
        const val PLAY = "▶" // ▶ idle: click to preview
        const val STOP = "■" // ■ playing: click to stop
    }
}
