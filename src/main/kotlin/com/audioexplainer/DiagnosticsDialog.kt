package com.audioexplainer

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.datatransfer.StringSelection
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The result of **"Check permissions and settings"**: one row per check, each carrying its status, what
 * was found, and the fix.
 *
 * Deliberately a list of labels rather than a table. The remedy is a sentence, not a cell — a table would
 * either clip it or give every row the height of the longest one, and the remedy is the part that makes
 * the row worth reading. **Copy report** is here because the first thing anybody does with a diagnostic is
 * paste it somewhere.
 */
class DiagnosticsDialog(project: Project?, private val results: List<DiagnosticResult>) : DialogWrapper(project) {

    init {
        title = "Audio Explainer — permissions and settings"
        setOKButtonText("Close")
        init()
    }

    override fun getDimensionServiceKey(): String = "AudioExplainer.DiagnosticsDialog"

    override fun createActions(): Array<Action> = arrayOf(
        object : AbstractAction("Copy report") {
            override fun actionPerformed(e: ActionEvent) {
                CopyPasteManager.getInstance().setContents(StringSelection(ExplainerDiagnostics.asText(results)))
            }
        },
        okAction,
    )

    override fun createCenterPanel(): JComponent {
        val rows = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 10)
        }
        val failed = results.count { it.status == CheckStatus.FAIL }
        val warned = results.count { it.status == CheckStatus.WARN }
        rows.add(
            JBLabel(
                when {
                    failed > 0 -> "<html><b>$failed check(s) failed</b>, $warned warning(s). Each row names its fix.</html>"
                    warned > 0 -> "<html>Nothing failed, <b>$warned warning(s)</b>. Each row names its fix.</html>"
                    else -> "<html><b>Everything checked out.</b> The rows below say what was looked at.</html>"
                },
            ),
        )
        rows.add(Box.createVerticalStrut(JBUI.scale(8)))

        for (r in results) {
            rows.add(
                JBLabel("<html><body style='width:560px'>${mark(r.status)} <b>${esc(r.name)}</b> — ${esc(r.detail)}</body></html>")
                    .apply { foreground = colorFor(r.status) },
            )
            rows.add(
                JBLabel("<html><body style='width:540px'>${esc(r.remedy)}</body></html>").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.empty(1, 18, 8, 0)
                },
            )
        }

        return JBScrollPane(rows).apply {
            preferredSize = Dimension(JBUI.scale(660), JBUI.scale(520))
            border = JBUI.Borders.empty()
        }
    }

    private fun mark(status: CheckStatus): String = when (status) {
        CheckStatus.PASS -> "&#10003;" // ✓
        CheckStatus.WARN -> "&#9888;"  // ⚠
        CheckStatus.FAIL -> "&#10007;" // ✗
        CheckStatus.INFO -> "&#8505;"  // ℹ
    }

    /** Theme-aware, and never colour alone — the glyph carries the same information for anyone who can't
     *  separate these hues. */
    private fun colorFor(status: CheckStatus): JBColor = when (status) {
        CheckStatus.FAIL -> JBColor(0xC7222A, 0xFF6B6B)
        CheckStatus.WARN -> JBColor(0x9A6700, 0xD8A657)
        else -> JBColor(UIUtil.getLabelForeground(), UIUtil.getLabelForeground())
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
