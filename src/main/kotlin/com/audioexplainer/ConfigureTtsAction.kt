package com.audioexplainer

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

/**
 * **Tools → Audio Explainer → Audio engines…**. Opens the plugin's settings page, whose "Audio engines"
 * section carries the per-engine status, provisioning (install Kokoro / Japanese & Chinese packs), and
 * the Windows SAPI voice info + NaturalVoiceSAPIAdapter link. Always available (it's an IDE-wide setup step).
 */
class ConfigureTtsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, HighlightSettingsConfigurable::class.java)
    }
}
