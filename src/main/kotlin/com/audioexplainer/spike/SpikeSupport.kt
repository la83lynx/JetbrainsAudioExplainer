package com.audioexplainer.spike

import com.audioexplainer.Highlight
import com.audioexplainer.Segment
import com.audioexplainer.tree.CodeNodeSpec
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Plumbing shared by the surviving spike vehicles — `SpikeNodeViewAction` (Phase 1a′, one node) and
 * `SpikeNestedNodeAction` (Phase 1a‴/1a⁗, Arch C nesting).
 *
 * THROWAWAY, like everything in this package. It exists because every bench needs the same "write pixels and a
 * pass/fail file to disk" discipline, and a second copy of it would be a second thing to keep honest — the same
 * argument Phase 1a makes about the renderer. Which is also why [lineOfAnchor] moved here in the cleanup pass:
 * it was declared in the Phase 0b bench that got deleted, and both survivors resolve their fixtures with it.
 */

/** Accumulating pass/fail report, rewritten to disk after every stage so a late crash keeps early answers. */
internal class Report(private val file: Path) {
    private val sb = StringBuilder()
    var failures = 0
        private set
    private var passes = 0
    private var skips = 0

    fun title(s: String) {
        sb.append("=".repeat(96)).append('\n').append(s).append('\n').append("=".repeat(96)).append('\n')
    }

    fun section(s: String) {
        sb.append('\n').append("── ").append(s).append(' ').append("─".repeat((90 - s.length).coerceAtLeast(3))).append('\n')
    }

    fun line(s: String) {
        sb.append("    ").append(s).append('\n')
    }

    fun blank() {
        sb.append('\n')
    }

    fun pass(s: String) {
        passes++; sb.append("  PASS  ").append(s).append('\n')
    }

    fun fail(what: String, why: String) {
        failures++; sb.append("  FAIL  ").append(what).append(": ").append(why).append('\n')
    }

    fun skip(what: String, why: String) {
        skips++; sb.append("  SKIP  ").append(what).append(": ").append(why).append('\n')
    }

    fun info(s: String) {
        sb.append("  INFO  ").append(s).append('\n')
    }

    fun summary() = "$passes pass, $failures fail, $skips skipped"

    fun flush() {
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, sb, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            // Nothing useful to do — the notification still carries the summary.
        }
    }
}

/** Paint [comp] into an image, or null if it was never laid out. */
internal fun snapshotOf(comp: JComponent): BufferedImage? {
    val w = comp.width
    val h = comp.height
    if (w <= 0 || h <= 0) return null
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    try {
        comp.paint(g)
    } finally {
        g.dispose()
    }
    return img
}

/** Paint [comp] into a PNG under [out]. The point of every spike here: pixels, not claims. */
internal fun captureComponent(comp: JComponent, out: Path, name: String, rep: Report): BufferedImage? {
    return try {
        val img = snapshotOf(comp)
        if (img == null) {
            rep.fail("capture $name", "component not laid out (${comp.width}x${comp.height})")
            return null
        }
        Files.createDirectories(out)
        ImageIO.write(img, "png", out.resolve(name).toFile())
        rep.pass("wrote $name (${img.width}x${img.height}, ${distinctColoursOf(img)} distinct colours)")
        img
    } catch (t: Throwable) {
        rep.fail("capture $name", "threw: $t")
        null
    }
}

/**
 * How many pixels sit within [tolerance] of [target], per channel.
 *
 * B3b's objective question is "is the connector still drawn after a child editor repainted itself?", and
 * counting the connector's own colour answers it without anyone having to look at the PNG.
 */
internal fun countPixelsNear(img: BufferedImage, target: java.awt.Color, tolerance: Int): Int {
    var hits = 0
    var y = 0
    while (y < img.height) {
        var x = 0
        while (x < img.width) {
            val p = img.getRGB(x, y)
            val dr = Math.abs(((p shr 16) and 0xFF) - target.red)
            val dg = Math.abs(((p shr 8) and 0xFF) - target.green)
            val db = Math.abs((p and 0xFF) - target.blue)
            if (dr <= tolerance && dg <= tolerance && db <= tolerance) hits++
            x++
        }
        y++
    }
    return hits
}

/**
 * Distinct 5-bit-per-channel colours in the image — a blunt but objective backstop for "is there colour?",
 * independent of whether the highlighter *claims* to have coloured anything. Uncoloured monospace text over
 * one background lands in the low tens (antialias blends along a single fg→bg ramp); lexed C# lands far
 * above that, and semantically coloured C# higher still.
 */
internal fun distinctColoursOf(img: BufferedImage): Int {
    val seen = HashSet<Int>()
    var y = 0
    while (y < img.height) {
        var x = 0
        while (x < img.width) {
            val p = img.getRGB(x, y)
            seen += ((p shr 19) and 0x1F shl 10) or ((p shr 11) and 0x1F shl 5) or ((p shr 3) and 0x1F)
            x += 2
        }
        y += 2
    }
    return seen.size
}

/**
 * What the highlighters in [doc] actually **are**, which is the question a count cannot answer.
 *
 * Phase 0 §6.3 is the standing reason this exists: an earlier run read "84 highlighters appeared" as
 * semantic colour, when on invalid stitched C# they were just as likely to be error markers.
 * `getTextAttributesKey()` names the category the platform assigned; `getErrorStripeTooltip()` carries the
 * daemon's `HighlightInfo` when the marker came from analysis rather than colouring. Between them the two
 * separate "coloured" from "diagnosed" without needing an impl class.
 *
 * @return how many markers carried a `ReSharper.*` colour category — B5's actual subject.
 */
internal fun classifyMarkersTo(project: Project, doc: Document, label: String, rep: Report): Int {
    val all = try {
        DocumentMarkupModel.forDocument(doc, project, false)?.allHighlighters
    } catch (t: Throwable) {
        rep.line("$label: classify threw: $t")
        null
    } ?: return 0

    val keys = HashMap<String, Int>()
    val tooltips = HashMap<String, Int>()
    var stripes = 0
    for (h in all) {
        val k = try {
            h.textAttributesKey?.externalName
        } catch (_: Throwable) {
            null
        } ?: "<no key>"
        keys[k] = (keys[k] ?: 0) + 1
        try {
            if (h.getErrorStripeMarkColor(null) != null) stripes++
            h.errorStripeTooltip?.let { tooltips[it.javaClass.simpleName] = (tooltips[it.javaClass.simpleName] ?: 0) + 1 }
        } catch (_: Throwable) {
            // A marker mid-teardown — skip it rather than lose the whole classification.
        }
    }

    rep.line("$label: ${all.size} markers, $stripes carrying an error-stripe mark")
    rep.line(
        "$label: attribute keys → " +
            keys.entries.sortedByDescending { it.value }.take(12).joinToString(", ") { "${it.key}×${it.value}" },
    )
    if (tooltips.isNotEmpty()) {
        rep.line("$label: stripe-tooltip types → " + tooltips.entries.joinToString(", ") { "${it.key}×${it.value}" })
    }

    val diagnostic = keys.keys.count { k ->
        DIAGNOSTIC_HINTS.any { k.contains(it, true) }
    }
    rep.line("$label: $diagnostic of ${keys.size} distinct keys look like DIAGNOSTIC categories rather than colour categories")

    // The number B5 is actually about: ReSharper's own colour categories, diagnostics excluded.
    val semantic = keys.entries
        .filter { (k, _) -> k.startsWith("ReSharper.", true) && DIAGNOSTIC_HINTS.none { k.contains(it, true) } }
        .sumOf { it.value }
    rep.line("$label: $semantic marker(s) carry a ReSharper.* COLOUR category")
    return semantic
}

private val DIAGNOSTIC_HINTS = listOf("ERROR", "WARNING", "WRONG", "UNRESOLVED", "DEPRECAT", "UNUSED", "SUGGESTION")

/**
 * Find fixture files by name anywhere under the project root. **Pooled thread only** — VFS traversal is disk
 * I/O and the platform asserts against slow operations on the EDT.
 *
 * By name rather than by path because the bench must fail *loudly and correctly* when the sandbox reopened the
 * wrong solution: an empty result means "this is not the fixture project", which is a skip with a
 * notification, not a component defect. Everything the node views measure is meaningless without
 * `fixtures/TreeSpike/TreeSpike.sln` open, since semantic colour comes from the backend that loaded it.
 */
internal fun findFixtureFilesByName(project: Project, wanted: Set<String>): Map<String, VirtualFile> {
    val found = HashMap<String, VirtualFile>()
    val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return found
    val skip = setOf(
        "bin", "obj", "build", "out", ".git", ".gradle", ".idea", ".kotlin", ".intellijPlatform", "node_modules",
    )
    var visited = 0
    val queue = ArrayDeque(listOf(base))
    while (queue.isNotEmpty() && visited < MAX_FIXTURE_VISIT && found.size < wanted.size) {
        val dir = queue.removeFirst()
        val children = try {
            dir.children ?: continue
        } catch (_: Throwable) {
            continue
        }
        for (child in children) {
            visited++
            if (child.isDirectory) {
                if (child.name !in skip && !child.name.startsWith(".")) queue.addLast(child)
            } else if (child.name in wanted) {
                found.putIfAbsent(child.name, child)
            }
        }
    }
    return found
}

private const val MAX_FIXTURE_VISIT = 8_000

/**
 * The 1-based line of the first occurrence of [anchor] in [editorText], or null if it is not there.
 *
 * **Text search, not PSI**, and that is a project rule rather than a shortcut: §4.4 rule 2 committed this project
 * to text-search-only locators so the same plan resolves in Rider, where PSI for C# is not ours to query. A
 * fixture that names lines by anchor text therefore resolves exactly the way an agent-authored plan will have to.
 *
 * Null is a real answer and callers must treat it as one — the fixture drifting out of step with the C# it names
 * is the most likely reason a bench shows an empty node, and it should read as "anchor not found" rather than as a
 * component defect.
 */
internal fun lineOfAnchor(editorText: CharSequence, anchor: String): Int? {
    val at = editorText.indexOf(anchor)
    if (at < 0) return null
    var line = 1
    for (i in 0 until at) if (editorText[i] == '\n') line++
    return line
}

/**
 * A hand-written walkthrough step list for one node: its **last** kept range is the segment (the method body;
 * the first kept range is the class header, which is context), split into up to three highlights.
 *
 * Prose on every highlight on purpose — the docked callout is a block inlay laid out at *node* width, not at
 * editor-tab width, and whether it wraps into something readable there is a question a bare plan would hide.
 * Shared by the 1a′ node bench and the 1a″ tree bench so a difference between them is a difference in the
 * component under test.
 */
internal fun syntheticSegment(spec: CodeNodeSpec): Segment {
    val body = spec.keep.last()
    val len = body.last - body.first + 1
    val chunk = (len / 3).coerceAtLeast(2)
    val highlights = mutableListOf<Highlight>()
    var start = body.first
    var n = 1
    while (start <= body.last && highlights.size < 3) {
        val end = minOf(start + chunk - 1, body.last)
        highlights += Highlight(
            start = start,
            end = end,
            explanation = "${spec.title} · part $n",
            note = "Step $n of ${spec.title}: lines $start-$end of ${spec.file.name}. " +
                "This sentence is long on purpose, so the callout has to wrap at node width.",
            example = if (n == 1) "// worked example: Apply(1200, 4) → 4800" else null,
        )
        start = end + 1
        n++
    }
    return Segment(
        id = spec.depth,
        file = spec.file.path,
        start = body.first,
        end = body.last,
        title = spec.title,
        explanation = spec.title,
        highlights = highlights,
    )
}
