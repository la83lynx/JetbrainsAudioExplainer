package com.audioexplainer

import kotlinx.serialization.Serializable

/**
 * Wire types mirroring code-explainer's `vscode-extension/src/types.ts` EXACTLY, so the reused
 * agent skill (`explainer.sh`, SKILL.md, docs) talks to this Kotlin server unchanged.
 *
 * Line numbers are **1-based** and ranges are **inclusive** on both ends (see highlight.ts upstream).
 * Do NOT reintroduce a segment-level `ttsText` — that field only appears in a stale upstream doc
 * (`docs/walkthrough.md`); the real contract puts narration (`ttsText`) on each [Highlight] and a
 * full-segment markdown `explanation` on the [Segment].
 */

/** A 1-based inclusive line range with its own narration (`ttsText`) and optional sidebar label. */
@Serializable
data class Highlight(
    val start: Int,
    val end: Int,
    /** Spoken narration for these specific lines; the extension only speaks a highlight if present. */
    val ttsText: String = "",
    /** Optional short per-highlight label (sidebar tooltip). Absent when null. */
    val explanation: String? = null,
    /**
     * Optional sentence-length aside for the in-editor callout — the margin note a reader would want
     * next to this code ("Capped at 3 so a broken transaction can't loop forever."). [explanation] is a
     * 2-5 word sidebar label and reads thin in a callout box, so [EditorHighlighter] prefers this when
     * present and falls back to [explanation]. Purely additive: plans without it render as before.
     */
    val note: String? = null,
    /**
     * Optional **one-line worked example** — an `input → output` pair or a tiny call-and-result showing
     * this code in action (`Parse("2024-01") → DateOnly(2024, 1, 1)`). Drawn below [note] in the same
     * editor callout, in the editor's *upright* font so it reads as code rather than as prose.
     *
     * Deliberately one line: the callout word-wraps, so a multi-line snippet would be reflowed into a
     * run-on paragraph and lose exactly the structure that made it a snippet. [EditorHighlighter] keeps
     * the first line and marks the cut; fenced multi-line examples need a different renderer
     * (MoreUI.md §4.3 B4) and are not supported yet.
     */
    val example: String? = null,
    /**
     * Optional **text anchor**: a literal substring of these lines naming the one identifier this step is
     * really about (`"customerId"`). [EditorHighlighter] resolves it by plain text search over
     * `[start, end]` — never PSI, so it behaves identically in Rider — and briefly flashes it.
     *
     * Deliberately a string rather than a column: the agent can copy it out of the code it just read,
     * whereas a column number is arithmetic it would get wrong. Unresolvable anchors are dropped
     * silently (no flash, no error), so a hallucinated token costs nothing.
     */
    val token: String? = null,
    /**
     * Which occurrence of [token] within `[start, end]` to anchor to, 1-based. Only needed when the
     * token repeats and a later one is meant; the default first match is almost always right.
     */
    val occurrence: Int = 1,
)

/**
 * One end of a [Relation] — a **text-search locator**, the same shape [Highlight.token] established and
 * for the same reason: the agent can copy a name out of code it just read, whereas a column number is
 * arithmetic it would get wrong. Never PSI, so Rider behaves like IntelliJ.
 *
 * @param file The endpoint's file. Absent ⇒ the segment's own file, which is the common case. A *different*
 *   file is what makes an endpoint "outside": no on-screen arrow can point at it, so the relation renders as
 *   a navigational pointer instead (MoreUI.md §4.3 B3).
 * @param line 1-based line within [file]. `0` = unspecified, which is legitimate for an off-screen target
 *   named only by [symbol] — it just can't anchor an arrow.
 * @param token A literal substring of [line]. Optional, and it does real work: the plugin requires it to be
 *   present on that line, so a token that isn't there condemns the endpoint (a cheap check on the line
 *   number the agent guessed). It also names the endpoint in the pointer form.
 * @param occurrence Which match of [token] on that line, 1-based. Rarely needed.
 * @param symbol Human-readable label for an off-screen target (`"RateTable.Lookup"`). Display only — it is
 *   never resolved as a symbol, because that would mean PSI.
 */
@Serializable
data class Endpoint(
    val file: String? = null,
    val line: Int = 0,
    val token: String? = null,
    val occurrence: Int = 1,
    val symbol: String? = null,
)

/**
 * A relationship between two places in the code, drawn by [EditorHighlighter] as an arrow when both ends
 * are lines of the file on screen, and as a clickable gutter pointer + callout line when they are not.
 *
 * The division of labour is deliberate: **the agent describes the semantics, the plugin picks the picture.**
 * An agent cannot know whether both endpoints are visible, how far apart they land, or whether the target
 * file is even open, so it never chooses between "arrow" and "pointer" — it states the relation and the
 * renderer degrades gracefully (MoreUI.md §4.3 B3).
 *
 * @param kind [KIND_FLOW] (A feeds B — one arrowhead) or [KIND_BACKFORTH] (they refer to each other — two).
 *   Anything else, including the draft's `"outside"`, is read as [KIND_FLOW]: `ignoreUnknownKeys` already
 *   makes the wire format tolerant, and an unfamiliar kind should cost an arrowhead, not the whole relation.
 * @param note Optional short phrase naming what flows or why they're linked ("parsed value"). Drawn beside
 *   the arrow, or after the target in the pointer form.
 */
@Serializable
data class Relation(
    val kind: String = KIND_FLOW,
    val from: Endpoint = Endpoint(),
    val to: Endpoint = Endpoint(),
    val note: String? = null,
) {
    companion object {
        const val KIND_FLOW = "flow"
        const val KIND_BACKFORTH = "backforth"
    }
}

/** One step of a walkthrough: a file region with a full-segment explanation and sub-highlights. */
@Serializable
data class Segment(
    /** Stable id used to target `goto` / `replace_segment` / `insert_after` / `remove_segments`. */
    val id: Int,
    /** Absolute path when sent live; stored relative-to-workspace in saved files. */
    val file: String,
    val start: Int,
    val end: Int,
    val title: String = "",
    /** Markdown explanation of the whole segment. */
    val explanation: String = "",
    val highlights: List<Highlight> = emptyList(),
    /**
     * Optional relationships between places in this segment's code. Per-segment rather than per-highlight
     * because a relation *spans* highlights — but rendered per-step: only relations with an endpoint inside
     * the active highlight are drawn, so the editor shows the arrows belonging to the step being narrated
     * instead of every arrow in the segment at once.
     *
     * Endpoint `file`s take part in [WalkthroughStorage]'s relative-path rewrite, like [file] itself.
     */
    val relations: List<Relation> = emptyList(),
)

/**
 * The walkthrough payload: the body of a `set_plan` message (minus its `type`) and the on-disk
 * saved-file shape (`{title, segments}`) are both this.
 *
 * [language] is the walkthrough's single language code (see [Languages]); every human-facing string
 * in the plan (title, segment titles & explanations, highlight `ttsText` & labels) is written in it.
 * Missing/blank ⇒ `"en"`, so old saved files and legacy `set_plan` messages remain valid
 * (`ignoreUnknownKeys` on decode + this default on encode).
 */
@Serializable
data class Plan(
    val title: String = "",
    val segments: List<Segment> = emptyList(),
    val language: String = Languages.DEFAULT_CODE,
)

/**
 * Extension → agent action delivered over `/api/actions` long-poll. Matches upstream
 * `UserActionMessage`. `type` is fixed to `"user_action"`. (Produced by the sidebar in Phase 4;
 * upstream never actually emits one today, but the channel is part of the contract.)
 */
@Serializable
data class UserAction(
    val action: String,
    val segmentId: Int,
    val question: String? = null,
    val type: String = "user_action",
)
