package com.audioexplainer

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * **Manage providers**: the profiles Explain This can run on, each a name, a base URL, a model id and an API key.
 *
 * Works on copies — [profiles] and [keys] are the result, read by the caller only after OK, which also writes
 * the keys to the password safe off the EDT. [keys] arrives already read for the same reason: nothing here
 * touches the password safe.
 */
class ProviderManagerDialog(
    initial: List<ProviderProfile>,
    initialKeys: Map<String, String?>,
    selectedId: String?,
) : DialogWrapper(true) {

    val profiles: List<ProviderProfile> get() = model.items
    val keys: MutableMap<String, String?> = initialKeys.toMutableMap()
    /** The profile showing when OK was pressed — the caller may select it on the Settings page. */
    val selectedId: String? get() = current?.id

    private val model = CollectionListModel(initial.map { it.copy() })
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = SimpleListCellRenderer.create("") { it.name.ifBlank { "(unnamed)" } }
    }
    private val nameField = JBTextField()
    private val urlField = JBTextField()
    private val modelCombo = ComboBox<String>().apply { isEditable = true }
    private val browseButton = JButton("Browse…").apply {
        toolTipText = "Fetch the provider's model list (no key needed) and pick one."
        addActionListener {
            ProviderModels.browse(modelCombo, urlField.text) { id -> modelCombo.selectedItem = id }
        }
    }
    private val keyField = JBPasswordField()
    private val keyNote = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val warning = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private var form: JPanel? = null
    private var current: ProviderProfile? = null

    init {
        title = "Audio Explainer — Manage Providers"
        init()
        list.addListSelectionListener { if (!it.valueIsAdjusting) show(list.selectedValue) }
        urlField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refreshBrowse()
        })
        val start = model.items.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0.takeIf { model.size > 0 }
        if (start != null) list.selectedIndex = start else show(null)
    }

    override fun createCenterPanel(): JComponent {
        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction { button ->
                JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<ProviderPreset>("Add From Preset", Providers.PRESETS) {
                    override fun getTextFor(value: ProviderPreset) = value.label
                    override fun onChosen(value: ProviderPreset, finalChoice: Boolean): PopupStep<*>? {
                        doFinalStep { add(value) }
                        return PopupStep.FINAL_CHOICE
                    }
                }).show(button.preferredPopupPoint)
            }
            .setRemoveAction {
                val gone = list.selectedValue ?: return@setRemoveAction
                current = null
                keys[gone.id] = null
                model.remove(gone)
                if (model.size > 0) list.selectedIndex = 0 else show(null)
            }
            .disableUpDownActions()
            .createPanel()
            .apply { preferredSize = Dimension(JBUI.scale(200), JBUI.scale(260)) }

        keyNote.text = "Stored in the IDE password safe, never in the settings file."
        form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponent("Base URL:", urlField)
            .addTooltip("The Anthropic-style endpoint Claude Code is pointed at, without /v1/messages.")
            .addLabeledComponent("Model:", JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                add(modelCombo, BorderLayout.CENTER)
                add(browseButton, BorderLayout.EAST)
            })
            .addTooltip("Any model id the provider lists; Browse… fetches OpenRouter's list.")
            .addLabeledComponent("API key:", keyField)
            .addComponentToRightColumn(keyNote)
            .addComponent(warning)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        return JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            add(decorated, BorderLayout.WEST)
            add(form!!, BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(680), JBUI.scale(300))
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = if (model.size > 0) nameField else list

    private fun add(preset: ProviderPreset) {
        val taken = model.items.map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) preset.label else "${preset.label} $it" }.first { it !in taken }
        val p = Providers.newProfile(preset, name)
        model.add(p)
        list.selectedIndex = model.size - 1
    }

    /** Write the form back into the profile it shows, then show [next]. */
    private fun show(next: ProviderProfile?) {
        flush()
        current = next
        val enabled = next != null
        listOf(nameField, urlField, modelCombo, keyField).forEach { it.isEnabled = enabled }
        nameField.text = next?.name.orEmpty()
        urlField.text = next?.baseUrl.orEmpty()
        val suggestions = (listOfNotNull(next?.model?.takeIf { it.isNotBlank() }) +
            (Providers.preset(next?.preset)?.models ?: emptyList())).distinct()
        modelCombo.removeAllItems()
        suggestions.forEach { modelCombo.addItem(it) }
        modelCombo.selectedItem = next?.model.orEmpty()
        keyField.text = next?.let { keys[it.id] }.orEmpty()
        refreshBrowse()
        keyNote.text = if (next != null && !Providers.needsKey(next)) "Not needed for a local Ollama."
        else "Stored in the IDE password safe, never in the settings file."
        warning.text = if (next == null) "Add a provider with +." else
            "<html>Unsupported by Anthropic. Your code is sent to <b>${Providers.host(next.baseUrl).ifBlank { "the provider" }}</b>.</html>"
    }

    private fun refreshBrowse() {
        browseButton.isEnabled = current != null && ProviderModels.canList(urlField.text)
    }

    private fun flush() {
        val p = current ?: return
        p.name = nameField.text.trim()
        p.baseUrl = urlField.text.trim()
        p.model = ((modelCombo.editor.editorComponent as? JTextField)?.text ?: modelCombo.selectedItem?.toString()).orEmpty().trim()
        keys[p.id] = String(keyField.password).trim().ifEmpty { null }
        list.repaint()
    }

    override fun doValidate(): ValidationInfo? {
        flush()
        for (p in model.items) {
            val where = "\"${p.name.ifBlank { "(unnamed)" }}\""
            when {
                p.name.isBlank() -> return ValidationInfo("A provider needs a name.")
                Providers.baseUrlProblem(p.baseUrl) != null -> return ValidationInfo("$where: the base URL ${Providers.baseUrlProblem(p.baseUrl)}.")
                p.model.isBlank() -> return ValidationInfo("$where: set a model id.")
            }
        }
        if (model.items.map { it.name }.toSet().size != model.size) return ValidationInfo("Two providers share a name.")
        return null
    }

    override fun doOKAction() {
        flush()
        super.doOKAction()
    }
}
