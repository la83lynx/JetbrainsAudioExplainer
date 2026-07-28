package com.audioexplainer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * The Piper voice-manager dialog (Phase L9) — the one place a child dialog beats an inline settings group.
 * Browses the full rhasspy/piper-voices catalog (~170 voices, fetched + cached by the server via
 * `--piper-catalog`), lets the user search by language/quality, download a voice (with the shared background
 * task that installs `piper-tts` if needed), delete an installed one (freeing disk), and shows total disk
 * usage. Install-first by design (the user chose this): a language becomes selectable for narration once a
 * voice for it is downloaded — this dialog is how you reach the whole catalog to do that.
 *
 * Opened modally from **Settings → Tools → Audio Explainer → Audio engines → Piper**. The catalog load and
 * post-op re-discovery run off the EDT; UI updates marshal back with `ModalityState.any()` so they land while
 * this (modal) dialog is open. Download/delete reuse [TtsSetup] (background task + notification + server
 * restart); the buttons disable while an op is in flight and re-enable + reload in its `onDone`.
 */
class PiperVoiceManagerDialog(private val project: Project?) : DialogWrapper(project) {

    private val tts = TtsService.getInstance()

    /** One catalog voice plus whether it's currently installed on this machine. */
    private data class Row(val v: TtsService.PiperCatalogVoice, val installed: Boolean)

    private var catalog: TtsService.PiperCatalog? = null
    private var allRows: List<Row> = emptyList()
    private var opInFlight = false

    private val model = ListTableModel<Row>(
        LangColumn(), VoiceColumn(), QualityColumn(), SpeakersColumn(), SizeColumn(), StatusColumn(),
    )
    private val table = TableView(model)
    private val searchField = SearchTextField()
    private val noteLabel = JBLabel()
    private val infoLabel = JBLabel()
    private val refreshButton = JButton("Refresh catalog")
    private val downloadButton = JButton("Download")
    private val deleteButton = JButton("Delete")

    init {
        title = "Manage Piper Voices"
        setOKButtonText("Close")
        isResizable = true
        init()
        loadCatalog(refresh = false)
    }

    /** Only a Close button — this dialog acts on the model itself, nothing to OK/Cancel. */
    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        table.setShowGrid(false)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.selectionModel.addListSelectionListener { updateButtons() }
        table.emptyText.text = "Loading catalog…"
        setColumnWidths()

        searchField.textEditor.emptyText.text = "Search language, voice, or quality"
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
        refreshButton.addActionListener { loadCatalog(refresh = true) }
        downloadButton.addActionListener { downloadSelected() }
        deleteButton.addActionListener { deleteSelected() }

        noteLabel.foreground = UIUtil.getContextHelpForeground()
        noteLabel.isVisible = false

        val searchRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(searchField, BorderLayout.CENTER)
            add(refreshButton, BorderLayout.EAST)
        }
        val north = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            noteLabel.alignmentX = Component.LEFT_ALIGNMENT
            searchRow.alignmentX = Component.LEFT_ALIGNMENT
            add(noteLabel)
            add(searchRow)
        }
        val south = JPanel(BorderLayout()).apply {
            add(infoLabel, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                add(downloadButton)
                add(deleteButton)
            }, BorderLayout.EAST)
        }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(north, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
            add(south, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(500))
        }.also { updateButtons() }
    }

    private fun setColumnWidths() {
        val cm = table.columnModel
        val widths = intArrayOf(230, 170, 70, 64, 84, 90)
        for (i in widths.indices) if (i < cm.columnCount) cm.getColumn(i).preferredWidth = JBUI.scale(widths[i])
    }

    // ── Loading / filtering ───────────────────────────────────────────────────

    private fun loadCatalog(refresh: Boolean) {
        setBusy(true)
        infoLabel.text = if (refresh) "Refreshing catalog…" else "Loading catalog…"
        table.emptyText.text = "Loading catalog…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val cat = runCatching { tts.piperCatalog(refresh) }
                .getOrElse { TtsService.PiperCatalog(emptyList(), "none", it.message) }
            val installed = tts.cachedPiperVoices().map { it.key }.toSet()
            ApplicationManager.getApplication().invokeLater({ populate(cat, installed) }, ModalityState.any())
        }
    }

    private fun populate(cat: TtsService.PiperCatalog, installedKeys: Set<String>) {
        catalog = cat
        allRows = cat.voices.map { Row(it, it.key in installedKeys) }
        table.emptyText.text = if (cat.voices.isEmpty()) "No voices — couldn't load the catalog." else "No matches"
        applyFilter()
        refreshNote()
        infoLabel.text = statusText(cat)
        setBusy(false)
    }

    /** Rebuild the installed flags from the (freshly re-checked) cache without re-fetching the catalog —
     *  called after a download/delete completes. */
    private fun reloadInstalledFromCache() {
        val cat = catalog ?: return
        val installed = tts.cachedPiperVoices().map { it.key }.toSet()
        allRows = allRows.map { it.copy(installed = it.v.key in installed) }
        applyFilter()
        refreshNote()
        infoLabel.text = statusText(cat)
        setBusy(false)
    }

    private fun applyFilter() {
        val q = searchField.text.trim().lowercase()
        model.items = if (q.isEmpty()) allRows else allRows.filter { r ->
            val v = r.v
            v.nameEnglish.lowercase().contains(q) || v.nameNative.lowercase().contains(q) ||
                v.key.lowercase().contains(q) || v.code.lowercase().contains(q) ||
                (v.locale?.lowercase()?.contains(q) == true) || (v.quality?.lowercase()?.contains(q) == true)
        }
        updateButtons()
    }

    private fun refreshNote() {
        val show = !tts.cachedPiperImportable()
        noteLabel.isVisible = show
        if (show) noteLabel.text = "<html>Downloaded voices need the Piper package — install it from the " +
            "<b>Piper</b> section in Audio engines settings to narrate with them.</html>"
    }

    private fun statusText(cat: TtsService.PiperCatalog): String {
        val installed = tts.cachedPiperVoices()
        val disk = installed.sumOf { it.sizeBytes }
        val head = "${cat.voices.size} voices · ${installed.size} installed (${humanSize(disk)} on disk)"
        return when {
            cat.voices.isEmpty() ->
                "Couldn't load the catalog${cat.error?.let { " — $it" } ?: ""}. Check your connection and Refresh."
            cat.offline -> "$head · offline — showing the cached catalog (Refresh to update)"
            else -> head
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────────────

    private fun downloadSelected() {
        val row = table.selectedObject ?: return
        if (opInFlight || row.installed) return
        setBusy(true)
        infoLabel.text = "Downloading “${row.v.key}” — progress in the status bar…"
        TtsSetup.downloadPiperVoice(project, row.v.key) { reloadInstalledFromCache() }
    }

    private fun deleteSelected() {
        val row = table.selectedObject ?: return
        if (opInFlight || !row.installed) return
        setBusy(true)
        infoLabel.text = "Deleting “${row.v.key}”…"
        TtsSetup.deletePiperVoice(project, row.v.key) { reloadInstalledFromCache() }
    }

    /** Disable the action buttons while an op (load/download/delete) is in flight; re-enable + re-derive
     *  download/delete enablement from the selection when it finishes. */
    private fun setBusy(busy: Boolean) {
        opInFlight = busy
        refreshButton.isEnabled = !busy
        searchField.isEnabled = !busy
        if (busy) {
            downloadButton.isEnabled = false
            deleteButton.isEnabled = false
        } else {
            updateButtons()
        }
    }

    private fun updateButtons() {
        if (opInFlight) return
        val sel = table.selectedObject
        downloadButton.isEnabled = sel != null && !sel.installed
        deleteButton.isEnabled = sel != null && sel.installed
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    private fun humanSize(bytes: Long): String {
        if (bytes <= 0) return "—"
        val mb = bytes / 1_000_000.0
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024) else String.format("%.0f MB", mb)
    }

    /** "de_DE-thorsten-medium" → "Thorsten"; "uk_UA-ukrainian_tts-medium" → "Ukrainian Tts". */
    private fun voiceName(key: String): String {
        val parts = key.split('-')
        val mid = if (parts.size >= 3) parts.subList(1, parts.size - 1).joinToString("-") else key
        return mid.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    private fun langText(v: TtsService.PiperCatalogVoice): String =
        if (v.nameNative.isNotBlank() && v.nameNative != v.nameEnglish) "${v.nameEnglish} — ${v.nameNative}"
        else v.nameEnglish

    // ── Columns ─────────────────────────────────────────────────────────────────

    private inner class LangColumn : ColumnInfo<Row, String>("Language") {
        override fun valueOf(item: Row): String = langText(item.v)
    }

    private inner class VoiceColumn : ColumnInfo<Row, String>("Voice") {
        override fun valueOf(item: Row): String = voiceName(item.v.key)
    }

    private class QualityColumn : ColumnInfo<Row, String>("Quality") {
        override fun valueOf(item: Row): String = item.v.quality ?: ""
    }

    private class SpeakersColumn : ColumnInfo<Row, String>("Speakers") {
        override fun valueOf(item: Row): String = if (item.v.speakers > 1) item.v.speakers.toString() else "1"
    }

    private inner class SizeColumn : ColumnInfo<Row, String>("Size") {
        override fun valueOf(item: Row): String = humanSize(item.v.sizeBytes)
    }

    private class StatusColumn : ColumnInfo<Row, String>("") {
        override fun valueOf(item: Row): String = if (item.installed) "Installed ✓" else ""
    }
}
