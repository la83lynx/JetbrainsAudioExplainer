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
)

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
