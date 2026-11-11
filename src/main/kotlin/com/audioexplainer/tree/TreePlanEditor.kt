package com.audioexplainer.tree

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
 * **The tree's host: an editor tab.**
 *
 * Not a tool window: its width is not a stable quantity (413/716/784 px across runs), and a tree of editors
 * cannot be laid out against a width nobody can predict. An editor tab costs a [FileEditorProvider] plus a
 * [FileEditor], at 15–19 ms per node with no leaks.
 *
 * It accepts only [TreePlanVirtualFile], so no file of the user's can reach it.
 */
class TreePlanEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = file is TreePlanVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        TreePlanFileEditor(file as TreePlanVirtualFile)

    override fun getEditorTypeId(): String = TYPE_ID

    /**
     * `HIDE_DEFAULT_EDITOR` because the platform would otherwise also offer a text editor over this file,
     * and the tree would show up as an empty text tab beside itself.
     */
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        const val TYPE_ID: String = "audioexplainer-code-tree"
    }
}

/**
 * The file the tab hangs on, carrying its own payload — the usual way a non-text editor gets one, since the
 * provider is constructed by the platform and cannot be handed anything else.
 *
 * [onDispose] is how the owner learns the user closed the tab, and is where N editors get released. Disposal
 * of an editor inside an inlay inside an editor is easy to get wrong; routing it through one callback makes
 * it a single place to get right.
 */
class TreePlanVirtualFile(
    name: String,
    val componentSupplier: () -> JComponent,
    val onDispose: () -> Unit,
) : LightVirtualFile(name) {
    init {
        isWritable = false
    }
}

class TreePlanFileEditor(private val treeFile: TreePlanVirtualFile) : UserDataHolderBase(), FileEditor {

    private val support = PropertyChangeSupport(this)

    private val panel: JComponent by lazy { treeFile.componentSupplier() }

    private var disposed = false

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel

    override fun getName(): String = "Code tree"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = !disposed

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = support.addPropertyChangeListener(listener)

    override fun removePropertyChangeListener(listener: PropertyChangeListener) =
        support.removePropertyChangeListener(listener)

    override fun getFile(): VirtualFile = treeFile

    override fun dispose() {
        if (disposed) return
        disposed = true
        treeFile.onDispose()
    }
}
