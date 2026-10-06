package com.audioexplainer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Swing fallback sidebar (Phase 4) for environments where JCEF is unavailable
 * (`JBCefApp.isSupported()` is false, or the browser fails to initialise). No webview / audio — just
 * the walkthrough title, status, current segment, and the core transport controls, driving the same
 * [Walkthrough] state machine (which in turn drives the tree-of-code surface via [TreePlanController]).
 */
class AudioExplainerSwingSidebar(
    private val project: Project,
    parentDisposable: Disposable,
) : WalkthroughListener {

    private val walkthrough = service<ExplainerServerService>().server.walkthrough
    private val tracker = ExplainSessionTracker.getInstance(project)

    private val genLabel = JBLabel("").apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        isVisible = false
    }
    private val titleLabel = JBLabel("No walkthrough loaded").apply { font = font.deriveFont(font.size + 2f) }
    private val statusLabel = JBLabel("idle")
    private val segmentLabel = JBLabel("")
    private val playPauseBtn = JButton("Play")

    // Phase 7.5 #5 — one-level undo of a replaced walkthrough.
    private val undoPanel: JPanel
    private val trackerListener = ExplainSessionTracker.Listener { renderGeneration(it) }

    val component: JComponent

    init {
        val controls = JPanel(FlowLayout(FlowLayout.CENTER, 6, 6)).apply {
            add(JButton("◀ Prev").also { it.addActionListener { walkthrough.prevStep() } })
            add(playPauseBtn.also { it.addActionListener { walkthrough.togglePlayPause() } })
            add(JButton("Next ▶").also { it.addActionListener { walkthrough.nextStep() } })
            add(JButton("Stop").also { it.addActionListener { walkthrough.stop() } })
        }

        undoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            isVisible = false
            add(JBLabel("New walkthrough replaced the previous one."))
            add(JButton("Undo").also { it.addActionListener { walkthrough.restorePrevious() } })
            add(JButton("Keep").also {
                it.addActionListener {
                    walkthrough.clearPrevious()
                    undoPanel.isVisible = false
                }
            })
        }

        component = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12)
            add(genLabel)
            add(undoPanel)
            add(titleLabel)
            add(JBLabel(" ")) // spacer
            add(segmentLabel)
            add(statusLabel)
            add(controls)
            add(JBLabel("JCEF unavailable — showing basic controls.").apply {
                horizontalAlignment = SwingConstants.LEFT
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            })
        }

        walkthrough.addListener(this)
        tracker.addListener(trackerListener)
        Disposer.register(parentDisposable, Disposable {
            walkthrough.removeListener(this)
            tracker.removeListener(trackerListener)
        })
        render(walkthrough.getState())
        renderGeneration(tracker.current)
    }

    override fun onPlan(state: WalkthroughSnapshot) = render(state)
    override fun onStep(index: Int) = render(walkthrough.getState())
    override fun onStatus(status: String) = render(walkthrough.getState())

    private fun render(state: WalkthroughSnapshot) {
        ApplicationManager.getApplication().invokeLater {
            titleLabel.text = state.title.ifEmpty { "No walkthrough loaded" }
            statusLabel.text = "Status: ${state.status}"
            val now = walkthrough.currentStepView()
            segmentLabel.text = if (now != null) {
                "Step ${state.stepIndex + 1}/${state.stepCount}: ${now.title}"
            } else {
                ""
            }
            playPauseBtn.text = if (state.status == Walkthrough.STATUS_PLAYING) "Pause" else "Play"
            undoPanel.isVisible = walkthrough.hasPrevious()
        }
    }

    private fun renderGeneration(gen: ExplainSessionState) {
        ApplicationManager.getApplication().invokeLater {
            when (gen.phase) {
                ExplainPhase.GENERATING -> {
                    genLabel.text = "⏳ Generating${if (gen.displayName.isNotEmpty()) ": ${gen.displayName}" else "…"}" +
                        (gen.fraction?.let { " — ${(it * 100).toInt()}%" } ?: "")
                    genLabel.isVisible = true
                }
                ExplainPhase.FAILED -> {
                    genLabel.text = "⚠ Generation failed: ${gen.message}"
                    genLabel.isVisible = true
                }
                ExplainPhase.IDLE -> genLabel.isVisible = false
            }
        }
    }
}
