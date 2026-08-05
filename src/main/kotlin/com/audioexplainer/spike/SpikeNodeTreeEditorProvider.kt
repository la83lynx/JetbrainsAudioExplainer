package com.audioexplainer.spike

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent

/**
 * **B2** — the host Arch B needs and Arch A got for free.
 *
 * Phase 0's S6c opened the story in an ordinary editor tab with no provider of ours at all, because a
 * `LightVirtualFile` named `story.cs` *is* a text file. A tree of N editors is not, so an editor tab now
 * costs a `FileEditorProvider` + `FileEditor`. This is that plumbing, and whether it works cheaply is the
 * whole of B2.
 *
 * THROWAWAY with the rest of the package; Phase 1 writes the real one if B2 goes green.
 */
internal class SpikeNodeTreeEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = file is SpikeTreeVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        SpikeNodeTreeFileEditor(file as SpikeTreeVirtualFile)

    override fun getEditorTypeId(): String = TYPE_ID

    /**
     * `HIDE_DEFAULT_EDITOR` because the platform would otherwise also offer a text editor over this file,
     * and a tree that shows up as an empty text tab next to itself is not the thing being measured.
     */
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        const val TYPE_ID: String = "audioexplainer-spike-node-tree"
    }
}

/**
 * The file the tab hangs on. It carries its own payload, which is the usual way a non-text editor gets one:
 * the provider is constructed by the platform and cannot be handed anything else.
 *
 * [onDispose] is how the probe learns that the user closed the tab, and is where N editors get released —
 * the disposal path Phase 4 calls the main integration risk, exercised here rather than inspected.
 */
internal class SpikeTreeVirtualFile(
    name: String,
    val componentSupplier: () -> JComponent,
    val onDispose: () -> Unit,
) : LightVirtualFile(name) {
    init {
        isWritable = false
    }
}

internal class SpikeNodeTreeFileEditor(private val treeFile: SpikeTreeVirtualFile) : UserDataHolderBase(), FileEditor {

    private val support = PropertyChangeSupport(this)

    private val panel: JComponent by lazy { treeFile.componentSupplier() }

    private var disposed = false

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel

    override fun getName(): String = "Node tree (spike)"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = !disposed

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = support.addPropertyChangeListener(listener)

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = support.removePropertyChangeListener(listener)

    override fun getFile(): VirtualFile = treeFile

    override fun dispose() {
        if (disposed) return
        disposed = true
        treeFile.onDispose()
    }
}
