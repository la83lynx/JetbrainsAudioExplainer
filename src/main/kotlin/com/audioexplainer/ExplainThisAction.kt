package com.audioexplainer

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile

/**
 * Phase 5 — the "Explain This (Audio)" action (the headline feature's entry point). Wired into two menus:
 *  - **Editor context menu** ([ExplainScope.CURSOR]): the file, caret line, and any selection, packaged as
 *    an [ExplainRequest]. It deliberately does **not** resolve the C# symbol under the cursor; expanding
 *    {caretLine, selection} to the enclosing method/class is Claude's job (Phase 6).
 *  - **Solution Explorer / Project view context menu** ([ExplainScope.FILE]/[DIRECTORY]): the selected
 *    file(s)/folder(s). A node with no backing local file (e.g. "Dependencies") is reported as
 *    "Item Not Explainable" instead of silently doing nothing.
 *
 * The menu label reflects the selected explanation language (localized verb, English fallback) via
 * [Languages.explainActionText], refreshed on every [update]. Both paths run the same cancellable
 * background "Explaining…" task that hands the request to [ClaudeExplainer].
 */
class ExplainThisAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // Reflect the selected explanation language in the label (both menus); recomputed on every show,
        // so changing the language in Settings takes effect on the next right-click.
        e.presentation.text = Languages.explainActionText(
            AudioExplainerSettings.getInstance().state.explanationLanguage,
        )
        val hasEditor = e.getData(CommonDataKeys.EDITOR) != null
        val hasFile = e.getData(CommonDataKeys.VIRTUAL_FILE) != null
        // This action lives in exactly two groups (EditorPopupMenu, ProjectViewPopupMenu), so update() is
        // only invoked for those + action search. In an editor it needs a file; otherwise we're in the
        // Solution Explorer / Project view, where we stay visible even for a non-file node so clicking can
        // explain WHY it isn't explainable ("Item Not Explainable"). Keying off "not an editor" rather than
        // e.place keeps this working in Rider's Solution Explorer, whose popup place string differs.
        e.presentation.isEnabledAndVisible = if (hasEditor) hasFile else true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val editorFile = e.getData(CommonDataKeys.VIRTUAL_FILE)

        // Editor scope: caret / selection. actionPerformed runs on the EDT, so reading the caret/selection
        // models here is safe.
        if (editor != null && editorFile != null) {
            launch(project, buildCursorRequest(project, editor, editorFile))
            return
        }

        // Solution Explorer scope: the selected files / folders. Nodes without a backing local file
        // (Dependencies, references, grouping nodes) contribute nothing → "Item Not Explainable".
        val selected = (e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: listOfNotNull(editorFile))
            .filter { it.isValid && it.isInLocalFileSystem }
        if (selected.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "Only a file or folder can be audio-explained. Select a source file, folder, or project node.",
                "Item Not Explainable",
            )
            return
        }
        launch(project, buildSelectionRequest(project, selected))
    }

    private fun launch(project: Project, request: ExplainRequest) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Explaining code…", /* canBeCancelled = */ true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "Preparing an audio walkthrough of ${request.displayName}…"
                    ClaudeExplainer.getInstance().explain(project, request, indicator)
                }
            },
        )
    }

    private fun buildCursorRequest(project: Project, editor: Editor, file: VirtualFile): ExplainRequest {
        val caretLine = editor.caretModel.logicalPosition.line + 1 // 1-based, matches the plan schema

        val selectionModel = editor.selectionModel
        val selectionText = selectionModel.selectedText?.takeIf { it.isNotBlank() }
        var selStartLine: Int? = null
        var selEndLine: Int? = null
        if (selectionText != null) {
            selStartLine = editor.offsetToLogicalPosition(selectionModel.selectionStart).line + 1
            val end = editor.offsetToLogicalPosition(selectionModel.selectionEnd)
            // A selection that ends at column 0 actually covers up to the previous line's end.
            selEndLine = (end.line + 1).let { if (end.column == 0 && it > selStartLine) it - 1 else it }
        }

        val absolutePath = file.path
        return ExplainRequest(
            projectBasePath = project.basePath,
            absolutePath = absolutePath,
            file = relativePath(project, absolutePath),
            caretLine = caretLine,
            selectionText = selectionText,
            selectionStartLine = selStartLine,
            selectionEndLine = selEndLine,
            scope = ExplainScope.CURSOR,
        )
    }

    private fun buildSelectionRequest(project: Project, files: List<VirtualFile>): ExplainRequest {
        val primary = files.first()
        // A single ordinary file → FILE (explain the whole file). A folder, a project/solution file, or a
        // multi-selection → DIRECTORY (Claude tours the subtree rather than one file).
        val scope = when {
            files.size > 1 || primary.isDirectory -> ExplainScope.DIRECTORY
            (primary.extension?.lowercase() ?: "") in PROJECT_FILE_EXTENSIONS -> ExplainScope.DIRECTORY
            else -> ExplainScope.FILE
        }
        return ExplainRequest(
            projectBasePath = project.basePath,
            absolutePath = primary.path,
            file = relativePath(project, primary.path),
            scope = scope,
            extraPaths = files.drop(1).map { relativePath(project, it.path) },
        )
    }

    /** Project-relative path when [absolutePath] lives under the project root, else the absolute path.
     *  VirtualFile.getPath() and project.basePath both use forward slashes on every OS. */
    private fun relativePath(project: Project, absolutePath: String): String {
        val base = project.basePath ?: return absolutePath
        return if (absolutePath.startsWith(base)) {
            absolutePath.removePrefix(base).trimStart('/').ifEmpty { absolutePath }
        } else {
            absolutePath
        }
    }

    private companion object {
        /** Files that stand in for a whole project/solution — explained as a tour, not as one file. */
        val PROJECT_FILE_EXTENSIONS = setOf("sln", "slnx", "csproj", "fsproj", "vbproj")
    }
}
