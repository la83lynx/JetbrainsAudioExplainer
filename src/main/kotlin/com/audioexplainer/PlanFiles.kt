package com.audioexplainer

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * **The file a plan path names**: absolute as written, relative against [basePath], and refreshed so a file
 * written a moment ago is found.
 *
 * One copy, because two callers need it to agree: the tree resolves a frame's file with it before mounting,
 * and [ProjectSourceLines] resolves the same path to check the plan against it. If the two disagreed, the
 * reply could say a file resolves when the tree then fails to find it.
 *
 * Off-EDT only — the refresh is synchronous. It is also a slow operation, so never call it inside a read
 * action.
 */
internal fun resolvePlanFile(filePath: String, basePath: String?): VirtualFile? = try {
    val nio = Path.of(filePath)
    val abs = if (nio.isAbsolute) nio else basePath?.let { Path.of(it).resolve(nio) } ?: nio
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(abs.normalize())
} catch (_: Exception) {
    null
}

/**
 * [SourceLines] over the project on disk, **read through the document** when there is one — so an unsaved edit
 * is checked the way the tree will show it, rather than the way the disk last saw it.
 *
 * Off-EDT only, for [resolvePlanFile]'s reason; the document read takes its own read action.
 */
internal class ProjectSourceLines(private val basePath: String?) : SourceLines {
    override fun lines(path: String): List<String>? {
        val file = resolvePlanFile(path, basePath)?.takeIf { it.isValid && !it.isDirectory } ?: return null
        val text = ReadAction.compute<String?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)?.text
        } ?: return null
        // A document's text is `\n`-separated whatever the file's own separators, and its line count counts
        // the empty line after a trailing newline — as `split` does.
        return text.split('\n')
    }
}
