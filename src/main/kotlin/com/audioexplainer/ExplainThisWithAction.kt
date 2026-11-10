package com.audioexplainer

import com.intellij.openapi.project.Project

/**
 * **"Explain This (Audio) with…"** — the sibling menu entry that opens [ExplainOptionsDialog] first.
 *
 * It sits directly under the plain entry in both menus, and the ordering is the design: the common case
 * must not grow a click, so the one-click entry stays first and stays unchanged. This one is
 * [ExplainThisAction] with [decorate] overridden — the target is resolved by exactly the same code, and
 * only what is attached to the resulting request differs.
 *
 * Cancelling the dialog cancels the invocation; nothing is spawned before it is dismissed.
 */
class ExplainThisWithAction : ExplainThisAction() {

    override fun label(code: String): String = Languages.explainWithActionText(code)

    override fun decorate(project: Project, request: ExplainRequest): ExplainRequest? {
        val dialog = ExplainOptionsDialog(project)
        if (!dialog.showAndGet()) return null
        return request.copy(
            effort = dialog.effort(),
            depth = dialog.depth(),
            extraInstructions = dialog.extraInstructions(),
            harness = dialog.harness(),
            provider = dialog.provider(),
        )
    }
}
