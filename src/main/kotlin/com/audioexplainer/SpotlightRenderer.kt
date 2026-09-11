package com.audioexplainer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.geom.GeneralPath
import javax.swing.Timer
import kotlin.math.abs

/**
 * **One step's worth of decoration for one editor**, in the schema's own vocabulary: line ranges, roles,
 * and the prose that goes in the callout. What [SpotlightRenderer.paintStep] takes.
 *
 * This is the signature the tree wanted from the start — *(line ranges, roles, palette)* — and the reason it
 * was deferred through the whole of Phase 1: it follows the schema, so writing it earlier would have meant
 * inventing the schema twice. The palette half is not a parameter because it never was one: every colour is
 * derived per render from [AudioExplainerSettings] and the current scheme, which is constraint 11 and the
 * thing that makes a theme switch a re-*render* rather than a re-measure.
 *
 * All line numbers are **1-based and inclusive**, like everything the agent authors.
 *
 * @param active The step's ranges in this editor's frame. **May be disjoint, and stays disjoint** — the
 *   glow is drawn per range, because lighting the span between two named ranges would light code the plan
 *   did not name. `active[0]` is the primary range: where the callout docks and what a relation is
 *   measured against.
 * @param kept The frame's `keep` ranges. Non-active kept lines read as *context*; anything else inside the
 *   extent reads as a *gap* and gets the gutter bar. Before roles existed, "kept" could only be inferred
 *   from the other steps' own ranges; a plan states them, which is what made the roles worth adding.
 * @param bounds Optional outer extent, dimmed wholesale beyond it. The union with [active] and [kept] is
 *   always taken, so this can only *widen* the region — a caller cannot accidentally hide a range it asked
 *   to draw.
 * @param label A short label (the old `explanation`), used in the callout only when [note] is absent.
 * @param note One sentence for the callout — preferred over [label] there, which reads thin in a box.
 * @param example One line, drawn under [note] in the editor's upright font.
 * @param token Literal substring of the primary range to flash; [occurrence] picks which match.
 * @param relations The frame's relations. Only those with an endpoint inside the primary range are drawn,
 *   so the arrows stay in step with the narration.
 * @param file The frame's file, which a relation endpoint's own `file` is compared against to decide
 *   whether it is somewhere this editor can point at.
 * @param role Whether this editor is the step's **primary** frame or one of its helpers — Phase 3's second
 *   level of attention, and the fifth line role the tree needed. See [FrameRole].
 */
class StepPaint(
    val active: List<LineRange>,
    val kept: List<LineRange> = emptyList(),
    val bounds: LineRange? = null,
    val label: String? = null,
    val note: String? = null,
    val example: String? = null,
    val token: String? = null,
    val occurrence: Int = 1,
    /** 1-based line holding [token]; 0 resolves it over the whole active extent (the legacy shape). */
    val tokenLine: Int = 0,
    val relations: List<Relation> = emptyList(),
    val file: String = "",
    val role: FrameRole = FrameRole.PRIMARY,
)

/**
 * **Which of a multi-frame step's frames this editor is** — and the answer to *"a tree needs a fifth line
 * role"* (`TreeOfCode.rules.md`, *Correction carried forward*).
 *
 * The in-place surface had one editor, so `dim` / `segmentDim` / `gap` / `active` were the whole vocabulary:
 * a line was outside the step, kept but not active, elided, or active. A tree puts N frames on screen at
 * once and a step may light ranges in several of them, so the reader needs to tell *"this whole frame is not
 * what is being talked about"* from *"this line inside the active frame is not the focus"* — which no
 * per-line role could express, because both frames' lines carried identical roles.
 *
 * Three tiers do express it, and they are all backgrounds (constraint 1 — never a foreground dim, which is
 * what flattened the Phase 0 prototype to grey and would destroy the semantic colour Arch B was chosen for):
 *
 * - **[PRIMARY]** — the step's own frame. Full glow, the callout, the token flash, and the container's
 *   reveal. Unchanged from what shipped.
 * - **[HELPER]** — a frame the step also lights. The same ranges, glowed at [HELPER_GLOW_MIX] of the way
 *   back to the editor background, with a callout in the same softened key. Visibly secondary on purpose:
 *   Phase 1 judged one box per step to read well, so a second must not compete with it.
 * - **A frame the step does not touch at all** gets no glow whatever and keeps only
 *   `CodeNodeView.setActive`'s whole-node wash — which is the *node*-level half of the same distinction, and
 *   why this enum has two values and not three.
 */
enum class FrameRole { PRIMARY, HELPER }

/**
 * **One relation this step wants drawn as a link**, reported so the surface that owns the editors can
 * number it, colour it and pair it up.
 *
 * A [SpotlightRenderer] owns exactly one document, so it can only ever draw *one* end of a link that leaves
 * the file: what it cannot know is whether the other end is on screen at all, because only the surface
 * holding the tree knows which frame owns which file. So the renderer reports, and
 * [com.audioexplainer.tree.TreePlanView] turns matching reports into [LinkHalf]s.
 *
 * **Round 6 widened this from "what I could not draw" to "every link".** A same-editor relation used to be
 * drawn on the spot by a renderer of its own, which is exactly why an in-file arrow and a portal on one step
 * could come out the same colour, on the same column, with hover on one and not the other. There is one
 * allocator now and it is the tree's, so a link's slot — its colour, its rail, its channel — is assigned
 * once across every editor on screen. [local] is the only thing the renderer knows that the tree does not.
 *
 * @param line 0-based line **in the reporting editor** - the end of the relation that is in this step.
 * @param origin That end, as the plan stated it. Carries no `file` when the plan left it implicit.
 * @param target The other end.
 * @param note The relation's own `note`, for the tooltip and the hovered label.
 * @param outgoing True when the end in this step is the relation's `from`, i.e. this end *leads* to
 *   [target] rather than being led to.
 * @param local True when [target] resolves to a line of **this same document**, near enough to join with a
 *   drawn line rather than a portal at each end — the [LinkJoin] decision, made where the document is.
 */
class LinkRequest(
    val line: Int,
    val origin: Endpoint,
    val target: Endpoint,
    val note: String?,
    val outgoing: Boolean,
    val local: Boolean,
    /** [Relation.KIND_BACKFORTH]: the link arrives at *both* ends. See [Relation.kind]. */
    val mutual: Boolean = false,
)

/**
 * **One end of one link**: what to draw in *this* editor, and where its partner leads.
 *
 * The two ends of a link are mirrored by construction — each points at the other — and they share a
 * [pairId], a [slot] and a [join], which is what makes them one thing rather than two marks that happen to
 * match. What differs between them is derived per end by whichever editor holds it: [LinkAnchor] from
 * whether [token] resolves, and the direction from live screen geometry. See [LinkArt].
 *
 * **What this replaced, and why it is worth recording:** the first build drew a rounded chip carrying a
 * glyph and a pair *number* at the start of the line. It was rejected on sight, and the reason generalises
 * — a chip marks a **row**, and a relation connects **tokens**. So the number went with it: what pairs the
 * two ends now is a shared colour, a shared shape, and **hovering either end brightening both** (see
 * [SpotlightRenderer.setHoveredPair]), which says *these two are one link* in a way a number to match up
 * never did. Before either shape there was a gutter icon, which a node never painted at all:
 * `isLineMarkerAreaShown = false` there, so the affordance was absent rather than unclear.
 *
 * @param line 0-based line in this editor holding this end of the link.
 * @param pairId 1-based, identical in both halves. **Not drawn** — it is how a hover on one half finds the
 *   other, and how the log names a pair.
 * @param lane **The link's routing lane within its step**, 0-based and identical in both halves — the
 *   reader's *"each link has a Nr. x"*. It picks the palette entry and the column this end's vertical is
 *   drawn on. The colour itself is *not* passed: it is derived per render from this editor's own scheme
 *   (constraint 11), so a theme switch re-colours both halves without either of them being told anything.
 *   The **rail**'s offset is no longer this number — see [LinkShape.fan], which is counted per row.
 * @param lanes **The whole step's lane layout**, in lane order, identical in every half in every editor.
 *   Carried rather than derived because a node can only see its own links, and the width of lane *k* depends
 *   on what lanes *0..k-1* are joined by ([LinkArt.laneAxes]): a node holding one portal and a node holding a
 *   same-editor link and that same portal have to put the portal on the same screen column, and the second
 *   node is the only one that knows there is a same-editor lane inside it. Round 6's item 8 — *"the portal in
 *   child node is pushed left, but in parent node it isn't"* — is this list not existing.
 * @param join Whether the two ends meet on a drawn line or at a portal each — see [LinkJoin]. Identical in
 *   both halves, and decided by the reporting editor: only it knows whether the far end is its own.
 * @param outgoing False on the end the link *arrives* at, which is the end that gets the arrowhead.
 * @param token The identifier this end is anchored to, as the plan spelled it. When it does not resolve on
 *   [line] the half still draws, from the end of the row's text — degraded, not dropped, because a link
 *   with one loose end is still worth showing.
 * @param occurrence Which match of [token] on that line, 1-based.
 * @param tooltip The whole sentence, for a hover.
 * @param label The relation's own `note` and nothing else, drawn beside this end while it is hovered. Null
 *   for a relation that carried no note. Distinct from [tooltip], which is a formatted sentence about where
 *   the link goes — that reads well in a balloon and badly as one line of italic beside a portal.
 * @param target Where a click on this half goes. The far half's target is the near end, so a link is
 *   navigable from either side.
 * @param targetBelow **Which way this link goes**, and the drawing is built on it: the connector leaves the
 *   token's top corner and dives when the other end is below, and leaves the bottom corner and climbs when it
 *   is above (see [LinkArt]). Set by the owner from the order of the two ends, and **opposite in the two
 *   halves by construction** — which is what stops one end diving while the other also dives. The renderer
 *   refines it from live screen geometry where it can (`targetBelowFor`); this is the answer when it cannot.
 */
data class LinkHalf(
    val line: Int,
    val pairId: Int,
    val lane: Int,
    val lanes: List<LinkJoin>,
    val join: LinkJoin,
    val outgoing: Boolean,
    val token: String?,
    val occurrence: Int,
    val tooltip: String,
    val label: String?,
    val target: Endpoint,
    val targetBelow: Boolean,
    /**
     * [Relation.KIND_BACKFORTH]: the link arrives at both ends, so this half draws an arrowhead whether or not
     * it is the [outgoing] one.
     *
     * **Identical in both halves**, unlike [outgoing] and [targetBelow], which are opposite by construction —
     * it is a property of the relation and not of an end, and a half that disagreed with its partner about it
     * would draw one head and one tail on a link that means neither.
     */
    val mutual: Boolean = false,
)

/**
 * **The editors whose portals share one vertical column**, so the two ends of a link line up on screen.
 *
 * Without this each renderer picks its channel from its own node's code, and two nodes holding different
 * code land on different x — the two ends of one link at two different places, which is the *"lets feel
 * portals more connected"* that was missing. The group is the smallest thing that fixes it: every member
 * asks it for the channel, it answers with the rightmost demand of any member, and they all get the same
 * number.
 *
 * **Screen coordinates are the basis**, and they have to be. The nest indents a child node inside its
 * parent, and each editor scrolls on its own, so two editors' content coordinates do not share an origin.
 * `contentComponent.locationOnScreen` already accounts for both — including the horizontal scroll, since
 * that moves the content component inside its viewport — so the alignment holds at *any* scroll position and
 * not merely the default one it was asked for.
 *
 * Recomputed on every paint rather than cached, and that is the same trade the rest of this class makes: a
 * cached column would be wrong after a scroll, a font change or a fold, and the recomputation is a handful
 * of `offsetToXY` calls over the few nodes on screen.
 *
 * Owned by [com.audioexplainer.tree.TreePlanView], rebuilt per step, and left null anywhere a portal is
 * merely local — a single editor with both ends of a link in it already shares one channel by construction.
 */
class LinkAlignGroup {

    private val members = mutableListOf<SpotlightRenderer>()

    /** Join the column. Idempotent, since a step may re-register a node it already registered. */
    fun join(renderer: SpotlightRenderer) {
        if (members.none { it === renderer }) members += renderer
    }

    /**
     * **Where every lane of the step sits**, in [caller]'s own content coordinates, or null when nothing can
     * be aligned to.
     *
     * Round 7 answered this with a single base column and fixed offsets from it, and round 8 replaced it
     * because that base was one number for the whole step: the *widest* row any link touched. A link off a
     * short line was routed out past a long one, and the hovered label after it had nothing left to be
     * drawn in — *"On-hover link labels are always trimmed, because same-editor links are routed as to the
     * right side as possible."*
     *
     * So the exchange is now **per lane**. Every member reports what each lane needs to clear *its own*
     * link's code, the maxima are taken lane by lane, and [LinkArt.alignedLaneAxes] packs them left to
     * right. Three things survive from round 7 unchanged, because they are what the group is for:
     *
     * - a lane lands on the same **screen** x in every node, so the two ends of a link line up;
     * - the packing keeps [LinkArt.LANE_GAP] between neighbours, so the routing rules cannot be violated by
     *   a lane that had a large demand;
     * - the *narrowest* member's right edge and left edge bound the whole fan, so no node clamps on its own
     *   and splits the column — round 6's *"the portal in child node is pushed left, but in parent node it
     *   isn't"*.
     *
     * A member with no links on screen, a disposed editor, a component not yet showing, or a viewport too
     * narrow to hold the fan at all contributes nothing rather than a zero — the last of those because a
     * mid-mount node reporting a 40 px viewport would otherwise drag every column in the tree onto the code.
     */
    fun axesFor(caller: SpotlightRenderer): IntArray? {
        val origin = caller.linkOriginScreenX() ?: return null
        val joins = caller.linkLaneLayout() ?: return null
        // **[LinkArt.NO_DEMAND], not 0.** Round 8 started the merge at zero and took the max, which throws
        // away every demand on a monitor left of the primary — see [LinkArt.NO_DEMAND].
        val demands = IntArray(joins.size) { LinkArt.NO_DEMAND }
        var room = Int.MAX_VALUE
        var left = Int.MIN_VALUE
        var any = false
        for (m in members) {
            if (!m.linkFanFits()) continue
            val d = m.laneDemandsScreenX() ?: continue
            val r = m.linkRoomScreenX() ?: continue
            val l = m.linkLeftScreenX() ?: continue
            // A member on a different step's layout has nothing to say about these lanes. It cannot happen
            // within one step — the layout is the step's and every half carries it — but a node mid-remount
            // can still be holding the previous one.
            if (d.size != demands.size) continue
            for (i in demands.indices) if (d[i] > demands[i]) demands[i] = d[i]
            if (r < room) room = r
            if (l > left) left = l
            any = true
        }
        if (!any) return null
        return LinkArt.alignedLaneAxes(origin, joins, demands, room, left, caller.linkLineHeight())
    }

    /**
     * **Screen y of the other end of [pairId]**, or null when it cannot be found.
     *
     * This is what lets a half draw itself as diving or climbing without being told: both halves run the same
     * comparison with the operands swapped, so they agree by construction rather than by coordination. The
     * caller's own row is excluded by its line, which also makes a link with both ends in one editor work
     * — that node holds two rows for the pair, and each finds the other.
     *
     * Screen coordinates for the reason [channelXFor] uses them: two editors in a nest share no origin.
     */
    fun partnerScreenY(caller: SpotlightRenderer, pairId: Int, callerLine: Int): Int? {
        for (m in members) {
            val y = m.linkRowScreenY(pairId, if (m === caller) callerLine else -1)
            if (y != null) return y
        }
        return null
    }
}

/**
 * The editor "spotlight" **drawing** stack — "given an [Editor], a segment and which of its highlights is
 * active, draw it". Originally the back half of `EditorHighlighter`; split out in Phase 1a of
 * `TreeOfCode.rules.md` so the tree-of-code node views can drive the same decorations without going through the
 * walkthrough listener, which resolves files and opens tabs. That listener is now [TreePlanController], and
 * the split still holds: nothing here resolves a file, creates an editor or touches a thread — the
 * controller does the file resolution and the thread marshalling, and each node view creates its own
 * editor.
 *
 * A port of code-explainer's `vscode-extension/src/highlight.ts`. Reproduces the four VS Code
 * `TextEditorDecorationType` layers with IntelliJ [RangeHighlighter]s:
 *
 * | upstream decoration | this port |
 * |---|---|
 * | `dimDecoration` (opacity 0.35, outside segment)        | whole-line foreground blended 35 % toward bg |
 * | `segmentDimDecoration` (opacity 0.55, non-active highlights) | whole-line foreground blended 55 % |
 * | `activeDecoration` (soft white bg + gold overview ruler)     | whole-line bg glow + gold error-stripe mark |
 * | `gapDecoration` (opacity 0.55 + dotted gold left border)     | 55 % foreground + gold gutter bar |
 *
 * Two decorations have no upstream counterpart: a **docked callout** — a block [Inlay] above the active
 * lines carrying the highlight's own prose aside (`note`, else the short `explanation` label) and its
 * one-line worked `example`; and **relations** ([Segment.relations]) — a drawn arrow between two lines of
 * this file, or one half of a [LinkHalf] link when the other end is somewhere this editor cannot point
 * at (the other half is drawn by whoever owns the editor it belongs to).
 * Both are additive and settings-gated; a plan carrying none of those fields renders exactly as before.
 *
 * (A third, a numbered gutter step badge with click-to-replay, was built and then removed — the number
 * duplicated the sidebar's own step list and the click duplicated its outline, so it cost gutter width for
 * nothing. The sidebar remains the only place that answers "which step is this".)
 *
 * Notes on the mapping: VS Code decorations use CSS `opacity`; IntelliJ has no per-highlighter
 * opacity, so we emulate dimming by overriding the whole-line foreground with a colour blended
 * toward the editor background (this flattens syntax colours on dimmed lines, which is the intended
 * "de-emphasise" effect). The gap layer's dotted CSS left-border becomes a gutter [LineMarkerRenderer]
 * bar — the closest native analogue.
 *
 * **One instance per editor, and that is load-bearing rather than tidy.** Phase 0 measured that
 * decorations are per-*editor*, not per-document (`TreeOfCode.rules.md` constraint 2): two editors over one
 * document shared none of our highlighters. So every piece of decoration state — [tracked],
 * [trackedDisposables], the flash pair, and the segment bounds a step render dims around — lives here
 * against the one [editor] this renderer was built for, and a surface showing N editors builds N of these.
 * Sharing one across editors is what the split exists to prevent.
 *
 * **EDT only.** Every method below mutates editor state; none of them marshals, because the callers
 * ([TreePlanController]'s `runOnEdt` bodies, and the node views' own EDT construction) are already there.
 * The mutable state is therefore touched serially on the EDT and needs no locking.
 *
 * [navigate] is the one thing drawing cannot do for itself: a portal's click has to open
 * a file, which means VFS resolution off the EDT. The owner supplies it — a node view passes down what the
 * tree means by "go there" ([TreePlanController]'s `followRelation`: reveal the target in another node when
 * one owns it, and only otherwise open its file).
 */
class SpotlightRenderer(
    val editor: Editor,
    private val navigate: (Endpoint) -> Unit = {},
) {

    private val log = Logger.getInstance(SpotlightRenderer::class.java)

    /**
     * The **active** range of the step currently drawn (0-based, inclusive), or `-1` when nothing is.
     *
     * Distinct from [StepPaint.bounds], which is the whole segment: this is the block that has the glow, and it is
     * what [stepBounds] measures. Kept because a nested node's *ancestors* have to scroll to the step as well, and an
     * ancestor has no way to ask what a renderer two editors down is pointing at.
     */
    private var activeStart = -1
    private var activeEnd = -1

    /** Highlighters we added to [editor]'s markup model, so we can clear them on transition. */
    private val tracked = mutableListOf<RangeHighlighter>()

    /**
     * Decorations the markup model does **not** own — today block-inlay callouts, later animation timers
     * and custom renderers. Removing a [RangeHighlighter] does not touch these, so they are disposed
     * explicitly in [clear] alongside it. Any future decoration belongs here.
     */
    private val trackedDisposables = mutableListOf<Disposable>()

    /**
     * The token-flash highlighter currently on screen, held **outside** [tracked] on purpose: the fade
     * replaces it once per frame, and pushing a dozen short-lived entries into [tracked] per step would
     * turn that list into churn. Its lifecycle belongs to the flash timer, which is what goes in
     * [trackedDisposables] — see [startTokenFlash].
     */
    private var flashHighlighter: RangeHighlighter? = null

    /**
     * The running fade's cancel handle (also in [trackedDisposables], so a segment transition still stops it).
     * Held separately so [startTokenFlash] can be called unconditionally: a second flash cancels the first
     * instead of leaving two timers writing [flashHighlighter].
     */
    private var flashTimer: Disposable? = null

    /**
     * The step's docked callout, held **outside** [trackedDisposables]'s anonymity so [revealCenter] can ask where it
     * is. Its lifecycle is still that list's; this is a reference, not a second owner.
     *
     * It exists because of a defect the G1c′ round found in a **capped** node, which is the first place the surface
     * has had one: *"callout partially hidden (scrolled away under header) in step 1, 2 of ValidationHandler.Handle."*
     * `ScrollType.CENTER` centres the active *line*, and the callout is an inlay drawn in the rows above it — in a
     * 9-line viewport there is not half a viewport of room up there, so the box hangs off the top. In a full-size
     * editor the same code has always had enough slack for it to be invisible.
     */
    private var calloutInlay: Inlay<*>? = null

    /**
     * Where the step's callout is docked, and the renderer drawing it — kept so [reserveLinkSpace] can find
     * out whether the box is already on the side of the row a fan needs room on, and grow *it* instead of
     * adding a second inlay there. See [reserveLinkSpace] for why that is worth four fields.
     */
    private var calloutLine = -1
    private var calloutBelow = false
    private var calloutExtra = 0
    private var calloutRenderer: CalloutRenderer? = null

    /**
     * The link ends on screen, and the highlighters drawing them.
     *
     * Held **separately from [tracked]** because their lifetime is not one step's: a link's far end lands
     * in whichever node owns it, which may be a node the step does not touch — and one that
     * [com.audioexplainer.tree.TreePlanView] has just cleared. [clear] still drops them, because "cleared"
     * has to mean nothing of ours is on screen; what makes the far end survive is that the tree paints
     * links *after* clearing and painting every node, not that they are exempt from the clear.
     *
     * [linkRows] is read at **paint** time by every link in this editor, and by every mouse move, because
     * three of the four things a link needs are properties of the *editor* rather than of one link: the base
     * column has to clear **all** of these rows' code and not merely each row's own; a joined pair's leg
     * needs its partner's rail; and a hover lights a pair rather than an end.
     */
    private val linkHighlighters = mutableListOf<RangeHighlighter>()

    /**
     * The token tints, held as **specifications rather than as highlighters**, because they now change.
     *
     * *"The tint should ALSO become bit brighter on link-hover — this way it will visually stand out on hover
     * as a complete system."* A `RangeHighlighter`'s attributes are set when it is added, so re-tinting means
     * dropping these and adding them again ([applyTints]) — a handful of markup-model calls on a mouse move
     * across a link, against the alternative of casting to `RangeHighlighterEx` for a setter the public
     * interface does not carry.
     */
    private class TintMark(val pairId: Int, val from: Int, val to: Int, val color: Color, val bg: Color)

    private val tintMarks = mutableListOf<TintMark>()
    private val tintHighlighters = mutableListOf<RangeHighlighter>()

    /** The blank rows bought for the fan — see [reserveLinkSpace]. Disposed with the rest of the links. */
    private val linkSpacers = mutableListOf<Inlay<*>>()

    /** Total height of [linkSpacers], so a node sizing itself to an excerpt can count them. */
    private var linkSpacerPx = 0

    /**
     * The **row** the pointer is on, not merely its pair — so a same-editor link's label is drawn beside the
     * end being pointed at rather than always beside its first. Null whenever [hoveredPair] is.
     */
    private var hoveredRow: Int? = null
    private var linkRows: List<LinkRow> = emptyList()

    /**
     * Every link the last [paintStep] resolved, for the tree to number and pair. Reset by [clear], so a node
     * outside the current step reports nothing rather than the previous step's links.
     */
    private var linkReqs: List<LinkRequest> = emptyList()

    /**
     * The pair currently lit, or null. Set either by this editor's own mouse — which then tells the owner,
     * so the *other* editor lights its half too — or by the owner relaying another editor's hover.
     */
    private var hoveredPair: Int? = null

    /**
     * Told when this editor's mouse enters or leaves a portal half, with the pair's id or null.
     *
     * The only cross-editor coupling the portal needs, and it buys the thing the design exists for: a hover
     * on one end lights the other, in a different editor, so *are these two one link* is answered by moving
     * the mouse rather than by matching up a number. Assigned by the owner
     * ([com.audioexplainer.tree.TreePlanView]); left null anywhere a portal is merely local.
     */
    var onLinkHover: ((Int?) -> Unit)? = null

    /**
     * The column this editor's discs share with the other editors on screen, or null to use its own.
     *
     * The second piece of cross-editor coupling, and it buys the other half of *"these two are one link"*:
     * the hover relay says the two ends belong together, and this puts them at the same x so the reader can
     * see it before touching the mouse. Assigned by the owner ([com.audioexplainer.tree.TreePlanView]) per
     * step; see [LinkAlignGroup].
     */
    var linkAlign: LinkAlignGroup? = null

    /** Installed on the first link ever drawn in this editor, so a node with no links adds no listeners. */
    private var linkMouseInstalled = false

    /**
     * One half resolved to document **offsets**. Pixels are deliberately not stored: `offsetToXY` at paint
     * time is what lets a portal track its token through scrolling, folding and a font change for free —
     * the same trade the arrow this replaced made.
     *
     * [tokenStart] == [tokenEnd] means the token did not resolve, so there is nothing to tint and the
     * connector leaves from the end of the row's text instead.
     */
    private class LinkRow(val half: LinkHalf, val tokenStart: Int, val tokenEnd: Int) {

        /**
         * **Which way this end's partner lies**, cached from the last time geometry was derived for it.
         *
         * Read by [fanOf] for the *other* rows on this line, and it has to be a cache rather than a call:
         * answering it costs a `locationOnScreen` per member of the align group, and a fan index that asked
         * it about every sibling on every paint would turn a per-row cost into a per-row-squared one. Each
         * row refreshes its own on the paint that draws it, and [reserveLinkSpace] refreshes all of them
         * once before anything is drawn, so the only staleness possible is one frame of a side that has just
         * flipped — which needs two editors to move relative to each other.
         */
        var side: Boolean = half.targetBelow

        /**
         * **Does this end take a rail off its row at all?**
         *
         * Every form does except the one round 7 took off it: a same-editor line-end link stays on the row's
         * middle from the text end to its lane (*"just turn to target"*). Ends that take no rail contend for
         * nothing, so they neither consume a fan index nor make their row ask for space.
         */
        val takesRail: Boolean
            get() = !(anchor == LinkAnchor.LINE_END && half.join == LinkJoin.LINE)

        /**
         * **[LinkAnchor.TOKEN] when this end's identifier resolved, [LinkAnchor.LINE_END] when it did not.**
         *
         * Derived here rather than carried in [LinkHalf], because only the editor holding the document can
         * answer it: the tree assigns a slot and a join to a whole link, and each end then finds out for
         * itself whether it has a token to hang off. That is also the degradation — a plan naming no token,
         * or naming one that has since moved, gets the line-end form instead of losing the link.
         */
        val anchor: LinkAnchor
            get() = if (tokenEnd > tokenStart) LinkAnchor.TOKEN else LinkAnchor.LINE_END
    }

    // ── Render API (EDT only) — mirrors highlight.ts ──

    /**
     * **The general render, and the one implementation.** Draw [paint] in this editor: dim outside its
     * bounds, de-emphasise its kept context lines, gap the rest, glow its active ranges, dock the callout,
     * draw the relations that belong to it, and reveal it.
     *
     * This is Phase 2's half of the 1a split, and since round 3 it is the **only** entry point. The renderer's
     * API was `Segment`/`Highlight`-shaped because that was the only vocabulary that existed; the tree needs
     * *(line ranges, roles, palette)* and nothing else, and writing that signature before the schema existed
     * would have meant inventing the schema twice. The `Segment`-shaped adapters over this body
     * (`spotlightSegment`, `setSegmentBounds`, `spotlightStep`) were deleted with the in-place surface that
     * was their last caller — so a step is drawn one way now, not two.
     *
     * Returns false — having drawn nothing — when [paint] has no active range, so an empty step leaves
     * whatever is on screen standing, exactly as an out-of-range highlight index used to.
     */
    fun paintStep(paint: StepPaint): Boolean {
        if (paint.active.isEmpty()) return false

        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)

        fun zero(r: LineRange): IntRange {
            val a = (r.lines().first - 1).coerceIn(0, lastLine)
            val b = (r.lines().last - 1).coerceIn(a, lastLine)
            return a..b
        }

        val active = paint.active.map(::zero).sortedBy { it.first }
        val kept = paint.kept.map(::zero)

        // Anchors the decoration timeline: everything the flash/callout logs below belongs to this step, and
        // comparing this timestamp against NarrationController's tells you how far ahead of the voice we are.
        if (log.isDebugEnabled) {
            log.debug(
                "render step: active ${active.joinToString(", ") { "${it.first + 1}-${it.last + 1}" }} " +
                    "of ${paint.kept.size} kept range(s) in ${paint.file.ifEmpty { "<editor>" }}",
            )
        }

        // The outer extent: everything past it is dimmed wholesale. Derived rather than asked for, from
        // every range this step can see — the bounds are a *consequence* of the ranges, and a plan that
        // stated them separately would have two numbers to keep in agreement.
        val declared = paint.bounds?.let(::zero)
        var effStart = declared?.first ?: active.first().first
        var effEnd = declared?.last ?: active.last().last
        for (r in kept + active) {
            effStart = minOf(effStart, r.first)
            effEnd = maxOf(effEnd, r.last)
        }
        effStart = effStart.coerceIn(0, lastLine)
        effEnd = effEnd.coerceIn(effStart, lastLine)

        clear()

        // After the clear, which resets them: the span [stepBounds] answers with until the next render. The
        // whole active extent, not the first range — an ancestor scrolling to this step has to reveal all
        // of it, and a step with two ranges 20 lines apart is one step.
        activeStart = active.first().first
        activeEnd = active.maxOf { it.last }

        val c = colors(paint.role)

        // Outer dim: everything outside the effective range.
        if (effStart - 1 >= 0) addLineRange(0, effStart - 1, c.dim)
        if (effEnd + 1 <= lastLine) addLineRange(effEnd + 1, lastLine, c.dim)

        val activeLines = HashSet<Int>()
        for (r in active) for (l in r) activeLines.add(l)
        val keptLines = HashSet<Int>()
        for (r in kept) for (l in r) keptLines.add(l)

        // Walk the effective range, coalescing consecutive same-role lines and skipping the active ones.
        // Kept-but-not-active is context (`segmentDim`); anything else inside the extent is a gap, which is
        // what the gutter bar marks. This is where a role earns its place: before the schema, "kept" could
        // only be inferred from the other steps' own ranges.
        var i = effStart
        while (i <= effEnd) {
            if (i in activeLines) { i++; continue }
            val isGap = i !in keptLines
            var j = i
            while (j + 1 <= effEnd && (j + 1) !in activeLines && ((j + 1) !in keptLines) == isGap) j++
            val h = addLineRange(i, j, if (isGap) c.gap else c.segmentDim)
            if (isGap && h != null && c.gapGutterEnabled) {
                h.lineMarkerRenderer = gutterRenderer(c.accent, c.gapGutterWidth)
            }
            i = j + 1
        }

        // Active gold highlight + overview-ruler mark, per range: a step may light two disjoint ranges, and
        // glowing the span between them would light code the plan did not name.
        for (r in active) addLineRange(r.first, r.last, c.active)

        // B3/A4 relations touching this step, resolved against the open document and already split into the
        // two pictures the editor can actually draw. Resolved BEFORE the callout because the pointer form is
        // a line *inside* that box — so both halves of the inside/outside decision are made in one place.
        val primary = active.first()
        val rel = resolveRelations(paint.relations, paint.file, primary.first + 1, primary.last + 1, lastLine)

        // Docked callout above the active lines. Added BEFORE revealCenter deliberately: the inlay adds
        // height above the first active line, so scrolling first would leave the block off-centre.
        //
        // Unconditional since the K1 round: a step with nothing to say already renders no box, so the switch
        // that used to stand here only ever hid a step that *did*. `calloutBlocks` returning empty is the
        // real gate, and it is a fact about the plan rather than a preference.
        addCallout(
            primary.first, primary.last, calloutBlocks(paint, rel.pointers, c), c,
            linkReserve(rel.links, c),
        )

        // **Every** link this step wants, published for the tree to number and pair — including the ones
        // whose two ends are both in this document, which used to be drawn right here by a renderer of their
        // own. That is round 6's unification, and it is the reason an in-file link and a portal on one step
        // can no longer collide on a colour or a column: there is one allocator, it is the tree's, and it
        // sees every editor on screen. See [LinkRequest].
        linkReqs = rel.links

        revealCenter(activeStart, activeEnd)

        // The flash deliberately does NOT ride this render. Measured, it would fire 1.8–6.0 s before the
        // first audio byte and be over long before the sentence discussing the token is spoken
        // (MoreUI.md §2.8) — so with narration engaged it waits for [flashToken]. Only when no audio
        // will ever arrive is this render the cue.
        if (flashOnRender()) {
            // One line when the beat names one, the whole active extent when it does not — the legacy
            // shape, and the reason a token in a step's *second* range used to resolve to nothing
            // (`primary` is the first range only).
            val lo = if (paint.tokenLine > 0) (paint.tokenLine - 1).coerceIn(0, lastLine) else activeStart
            val hi = if (paint.tokenLine > 0) lo else activeEnd
            startTokenFlash(paint.token, paint.occurrence, lo, hi, c)
        }
        return true
    }

    /**
     * Pulse [token] within the 1-based inclusive lines `[start, end]`. Driven by the webview's
     * `playback_started` (see [TreePlanController.flashCurrentToken]) and by a relation click.
     *
     * The anchor may fail to resolve, which [startTokenFlash] treats as validate-and-drop rather than as an
     * error — that, and a step carrying no `token` at all, are the only two ways this draws nothing.
     */
    fun flashToken(token: String?, occurrence: Int, start: Int, end: Int) {
        val c = colors()
        val lastLine = (editor.document.lineCount - 1).coerceAtLeast(0)
        val zeroStart = (start - 1).coerceIn(0, lastLine)
        val zeroEnd = (end - 1).coerceIn(zeroStart, lastLine)
        startTokenFlash(token, occurrence, zeroStart, zeroEnd, c)
    }

    /** Scroll [line] (0-based) into view and centre it. */
    fun reveal(line: Int) = revealCenter(line, line)

    /**
     * **Where a portal's click lands**: the caret on the target's own token, and the smallest scroll that
     * makes it visible.
     *
     * Three things this does that [reveal] did not, each one a reported complaint:
     *
     * - **The caret goes to the token, not the row's start.** [reveal] moved it to column 0, which for a
     *   relation naming `InvokeOn` on a 90-character line is not where the reader was sent.
     * - **`MAKE_VISIBLE`, not `CENTER`.** *"Clicking eg. source portal rescrolls target portal's view to
     *   center on it, even though the portal was visible and rescroll was not required."* A click follows a link
     *   the reader could already see; re-centring throws away the position they were reading from.
     * - **It asks for focus.** A node editor is created with `setCaretVisible(false)` and turns the caret on
     *   when focused, so a caret moved without focus lands invisibly — the click looked like it did nothing.
     *
     * Returns false when [line] is outside the document, so the caller can fall back to opening a tab. A
     * token that does not resolve is *not* a failure: the caret goes to the row's start, which is still the
     * right row.
     */
    fun landOn(line: Int, token: String?, occurrence: Int): Boolean {
        if (editor.isDisposed) return false
        val doc = editor.document
        if (line < 0 || line > (doc.lineCount - 1).coerceAtLeast(0)) return false
        val offset = resolveToken(token, occurrence, line, line)?.startOffset ?: doc.getLineStartOffset(line)
        editor.caretModel.moveToOffset(offset)
        editor.selectionModel.removeSelection()
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        editor.contentComponent.requestFocusInWindow()
        return true
    }

    /**
     * One row's own bounds in content coordinates — [stepBounds]'s shape for a single line.
     *
     * Exists so a click can be scrolled to the row it names rather than to whatever the current step
     * happens to be looking at. Null outside the document.
     */
    fun lineBounds(line: Int): Rectangle? {
        if (editor.isDisposed) return null
        val doc = editor.document
        if (line < 0 || line > (doc.lineCount - 1).coerceAtLeast(0)) return null
        val top = editor.offsetToXY(doc.getLineStartOffset(line)).y
        return Rectangle(0, top, 1, editor.lineHeight)
    }

    // ── B3: links ──

    /**
     * **Every** link the last [paintStep] resolved, for the tree to number and pair. Empty for a node
     * outside the current step, because [clear] resets it.
     */
    fun linkRequests(): List<LinkRequest> = linkReqs

    /**
     * Draw this editor's ends of the step's links, replacing any already on screen.
     *
     * Called by the surface that owns the tree, **after** every node has been painted or cleared: a far
     * end belongs to whichever node owns the other one, which the step may not touch at all, and [clear]
     * drops links along with everything else. Since round 6 that is true of **same-editor** links too, which
     * used to be drawn inside [paintStep] — see [LinkRequest] for why they moved.
     *
     * One or two range highlighters per end, and **no inlay** — which is the whole difference from the chip
     * this replaced. An inlay occupies layout, so a chip moved the row's code sideways and fired the node's
     * `InlayModel.Listener` on every step; a highlighter with a [CustomHighlighterRenderer] draws in the
     * margin the code already leaves and costs the node no geometry at all.
     *
     * **A [LinkJoin.LINE] pair gets one highlighter spanning both its rows**, because it is one polyline and
     * a renderer that could see only one end could not know where to stop. Everything else gets one per end.
     *
     * Returns how many ends were drawn, which can be fewer than asked for: a line folded away has no
     * visible row to anchor to. A **token that does not resolve is not a reason to skip** — that end becomes
     * a [LinkAnchor.LINE_END] one, because a link with one loose end still says the two files are connected.
     */
    fun paintLinks(halves: List<LinkHalf>): Int {
        clearLinks()
        droppedEnds = 0
        degradedPairs = 0
        if (halves.isEmpty()) return 0
        val c = colors()
        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)

        var rows = mutableListOf<LinkRow>()
        for (h in halves) {
            if (h.line < 0 || h.line > lastLine) continue
            val lineEnd = trimmedLineEnd(doc, h.line)
            // **The one link case Phase 2 left unverified: an end on a line the reader folded away.**
            // Skipped here, and counted, so the tree can say so — see [droppedEnds] and the degrade below.
            if (editor.foldingModel.getCollapsedRegionAtOffset(doc.getLineStartOffset(h.line)) != null) {
                droppedEnds++
                continue
            }
            val tok = resolveToken(h.token, h.occurrence, h.line, h.line)
            rows += if (tok != null) LinkRow(h, tok.startOffset, tok.endOffset)
            else LinkRow(h, lineEnd, lineEnd)
        }
        if (rows.isEmpty()) return 0
        rows = degradeOrphanedLines(halves, rows)
        linkRows = rows

        // **The lane's own colour if the reader set one, the derived hue otherwise** — see
        // [ArrowStyle.slotColors]. The fallback is what keeps the default picture exactly as judged: blank
        // slots mean `LinkArt.color(accent, lane)` answers, so slot 0 is the accent and the rest rotate off
        // it. One colour per slot rather than one base for all five, because a hue that reads badly against
        // a particular theme is a slot's problem, not the rotation's.
        for (group in rows.groupBy { it.half.pairId }.values) {
            val color = c.arrow.colorFor(group.first().half.lane)
            for (row in group) {
                // The token's own tint, only where the token resolved. EXACT_RANGE, so it marks the
                // identifier rather than the line the active glow already covers.
                if (row.tokenEnd > row.tokenStart) {
                    tintMarks += TintMark(row.half.pairId, row.tokenStart, row.tokenEnd, color, c.activeBg)
                }
            }
            // One highlighter for a joined pair, one per end otherwise. It spans every row the drawing
            // touches, so the platform paints the renderer whenever any part of the link is in view.
            val units = if (group.size > 1 && group.first().half.join == LinkJoin.LINE) {
                listOf(group)
            } else {
                group.map { listOf(it) }
            }
            for (unit in units) {
                val first = unit.minOf { it.half.line }
                val last = unit.maxOf { it.half.line }
                val h = editor.markupModel.addRangeHighlighter(
                    doc.getLineStartOffset(first),
                    doc.getLineEndOffset(last),
                    ARROW_LAYER,
                    TextAttributes(),
                    HighlighterTargetArea.LINES_IN_RANGE,
                )
                h.customRenderer = LinkRenderer(unit, color, c)
                linkHighlighters += h
            }
        }
        applyTints()
        installLinkMouse()
        return rows.size
    }

    /**
     * **A same-editor link that lost one of its two ends to a fold becomes a lone approach.**
     *
     * `Item 1′` named folding as the one link case never verified, and this is the failure it was pointing
     * at. A [LinkJoin.LINE] pair is *one drawing over two rows* — a polyline that leaves one row, turns down
     * its lane and arrives at the other. Fold the far row away and the survivor is still asked to draw a
     * line to it: a leg heading off toward a row that is not on screen, which reads as a rendering fault
     * rather than as information.
     *
     * A **portal** is already the project's answer to *"the other end is somewhere you cannot see"* — it is
     * what a cross-scene link and a too-distant same-file link both degrade to. So the survivor is re-joined
     * as one: the leg turns into its own portal and stops pretending to reach anything. It keeps its colour,
     * its lane, its token tint, its hover and its click, so following it still works and re-expanding the
     * fold restores the line.
     *
     * A [LinkJoin.PORTAL] half needs nothing: its two ends were always drawn independently, so losing one
     * leaves the other already correct.
     */
    private fun degradeOrphanedLines(halves: List<LinkHalf>, rows: MutableList<LinkRow>): MutableList<LinkRow> {
        // How many ends of each pair this editor was *asked* for, against how many survived the fold check.
        val asked = halves.groupingBy { it.pairId }.eachCount()
        val out = mutableListOf<LinkRow>()
        val kept = rows.groupingBy { it.half.pairId }.eachCount()
        for (row in rows) {
            val id = row.half.pairId
            val orphaned = row.half.join == LinkJoin.LINE && (asked[id] ?: 0) > 1 && (kept[id] ?: 0) == 1
            if (!orphaned) {
                out += row
                continue
            }
            degradedPairs++
            out += LinkRow(row.half.copy(join = LinkJoin.PORTAL), row.tokenStart, row.tokenEnd)
        }
        return out
    }

    /**
     * Ends this editor was given that landed on a folded line, for the step's log. Reset per [paintLinks].
     *
     * The instrument `Item 1′` asked for: `D of A end(s) drawn` is what says an end was lost, and a number
     * beside it is what says *why*.
     */
    var droppedEnds: Int = 0
        private set

    /** Same-editor pairs that lost one end and were re-drawn as a lone portal — see [degradeOrphanedLines]. */
    var degradedPairs: Int = 0
        private set

    /**
     * (Re)draw the token tints at the weight the current hover calls for.
     *
     * Dropped and re-added rather than mutated, for the reason [TintMark] gives. Cheap enough to run on a
     * hover change: a step holds at most ten of them.
     */
    private fun applyTints() {
        if (tintHighlighters.isNotEmpty()) {
            val model = editor.markupModel
            for (h in tintHighlighters) {
                try {
                    model.removeHighlighter(h)
                } catch (_: Throwable) {
                    // Editor/markup already disposed — same reasoning as the catches in clear().
                }
            }
            tintHighlighters.clear()
        }
        if (tintMarks.isEmpty() || editor.isDisposed) return
        val length = editor.document.textLength
        for (m in tintMarks) {
            // Same reasoning as [linkDemandX]'s clamp: these offsets were recorded when the step was painted
            // and the document is shared. This runs on a mouse move, where a throw would take the hover with
            // it.
            if (m.from < 0 || m.to > length || m.from >= m.to) continue
            val tint =
                if (m.pairId == hoveredPair) LinkArt.tintHovered(m.color, m.bg) else LinkArt.tint(m.color, m.bg)
            tintHighlighters += editor.markupModel.addRangeHighlighter(
                m.from, m.to, FLASH_LAYER,
                TextAttributes().apply { backgroundColor = tint },
                HighlighterTargetArea.EXACT_RANGE,
            )
        }
    }

    /**
     * **Buy the rows their fan needs**, as blank space above or below the rows that actually carry rails.
     *
     * *"We need to add space above/below the line to make space for routes … link lines go over the callout
     * above … link going to line 27 seems to target line 26 above."* Fan 0 sits in the row's own leading and
     * costs nothing; every rail past it is [LinkArt.railStep] further out, and the outermost of five is
     * squarely inside the neighbouring row. Round 6 borrowed that space. This buys it.
     *
     * **On demand, which is the form the reader hoped for** (*"maybe we should calculate how many lines are
     * actually going over/below a given text-line and push upper/lower only on demand"*): the height is
     * [LinkArt.railSpace] of the rails *that row* carries *on that side*, so the common one-link row asks for
     * nothing at all and only the fanned rows grow.
     *
     * Two mechanisms, and the split avoids a question rather than answering it:
     *
     * - **Where the step's callout is already docked on that side of that row, the callout grows instead.**
     *   It is drawn against the *far* edge of its own inlay, so extra height appears exactly where the rails
     *   need it — between the box and the code. Doing it with a second inlay would have meant relying on
     *   block-inlay priority to place the spacer nearer the text than the box, which is a platform detail
     *   this can simply not depend on.
     * - **Otherwise a spacer inlay**, which has no competitor on that side of that line and therefore no
     *   ordering to get right.
     *
     * **Below a row that already hosts a block inlay, nothing is bought.** That row is a nested child's call
     * line, and its frame is anchored under it: a spacer between the two would move the frame off the row
     * `LeaderArt` draws its leader to. Those rows keep the child's own `boxPadBelow` and the rails run in it.
     *
     * Called by [com.audioexplainer.tree.TreePlanView] **after every node has painted its links**, because
     * which side a rail leaves on is [targetBelowFor]'s answer and that needs the other end's row to exist.
     */
    fun reserveLinkSpace() {
        if (editor.isDisposed || linkRows.isEmpty()) return
        // **Nothing is bought for a fan that will not be drawn.** [linkLaneAxes] answers null when the fan
        // is wider than the viewport, and [shapeOf] answers null for an end with no run — in both cases the
        // renderer paints nothing, and a node that grew blank rows for an invisible link is worse than one
        // that lost the link.
        val axes = linkLaneAxes() ?: return
        val bought = HashSet<Long>()
        // **Two passes, and the second is not defensive dressing.** The spacers added by the first move this
        // editor's rows relative to every other node's, and which side a rail leaves on is a comparison of
        // live *screen* y ([targetBelowFor]) — so a pair whose two ends sat within a row of each other can
        // have its side flipped by the reservation itself. The second pass buys the other side where that
        // happened. A spacer only ever grows a gap, so an extra one is harmless, and a bounded pass cannot
        // oscillate the way re-reserving until stable could.
        for (pass in 0..1) {
            for (row in linkRows) row.side = targetBelowFor(row)
            val rails = HashMap<Long, Int>()
            for (row in linkRows) {
                if (!row.takesRail || shapeOf(row, axes) == null) continue
                val key = row.half.line * 2L + if (row.side) 1L else 0L
                rails[key] = (rails[key] ?: 0) + 1
            }
            var added = false
            for ((key, n) in rails) {
                if (!bought.add(key)) continue
                if (reserve((key / 2).toInt(), n, wantAbove = key % 2 == 1L)) added = true
            }
            if (!added) break
        }
        if (calloutExtra > 0) calloutInlay?.takeIf { it.isValid }?.let { runCatching { it.update() } }
    }

    /** One row-side's worth of [reserveLinkSpace]. True when it actually bought something. */
    private fun reserve(line: Int, rails: Int, wantAbove: Boolean): Boolean {
        val px = LinkArt.railSpace(rails, editor.lineHeight, colors().arrow.metrics.hoverStroke)
        if (px <= 0) return false
        val doc = editor.document
        if (line < 0 || line > (doc.lineCount - 1).coerceAtLeast(0)) return false
        if (calloutInlay?.isValid == true && calloutLine == line && calloutBelow != wantAbove) {
            // The box is already on this side of this row: make it taller rather than adding a second inlay.
            calloutExtra = maxOf(calloutExtra, px)
            calloutRenderer?.extraSpace = calloutExtra
            return true
        }
        val offset = if (wantAbove) doc.getLineStartOffset(line) else doc.getLineEndOffset(line)
        if (!wantAbove) {
            // A nested child's frame is anchored here, and a spacer between the two would move it off the row
            // `LeaderArt` draws its leader to. Those rows keep the child's own `boxPadBelow` and the rails run
            // in it. **Ours are excluded from the test**: on a blank line the start and end offsets are the
            // same one, so the spacer this pass may just have added *above* the row would otherwise be read
            // as a foreign inlay below it and cost the row its other side.
            val blocked = editor.inlayModel.getBlockElementsInRange(offset, offset).any {
                it.placement == Inlay.Placement.BELOW_LINE && it !in linkSpacers
            }
            if (blocked) return false
        }
        val inlay = editor.inlayModel.addBlockElement(
            offset,
            /* relatesToPrecedingText = */ !wantAbove,
            /* showAbove = */ wantAbove,
            SPACER_PRIORITY,
            LinkSpacerRenderer(px),
        ) ?: return false
        linkSpacers += inlay
        linkSpacerPx += px
        return true
    }

    /**
     * Blank rows this editor is currently holding for its fan, **not counting the callout's share of them**.
     *
     * The callout's share is already in `calloutHeight()`, which reads the inlay's own `heightInPixels` and
     * therefore includes whatever [reserveLinkSpace] added to it — so `CodeNodeView.excerptTargetHeight`
     * counting both would size a node a whole fan too tall and land it on the wrong band.
     */
    fun linkSpacerHeight(): Int = linkSpacerPx

    /** Drop the portal decorations, leaving the rest of the render standing. */
    fun clearLinks() {
        linkRows = emptyList()
        hoveredPair = null
        hoveredRow = null
        tintMarks.clear()
        applyTints()
        if (linkSpacers.isNotEmpty()) {
            for (i in linkSpacers) {
                try {
                    Disposer.dispose(i)
                } catch (_: Throwable) {
                    // Already disposed with the editor.
                }
            }
            linkSpacers.clear()
        }
        linkSpacerPx = 0
        if (calloutExtra != 0) {
            calloutExtra = 0
            calloutRenderer?.extraSpace = 0
            calloutInlay?.takeIf { it.isValid }?.let { runCatching { it.update() } }
        }
        if (linkHighlighters.isEmpty()) return
        val model = editor.markupModel
        for (h in linkHighlighters) {
            try {
                model.removeHighlighter(h)
            } catch (_: Throwable) {
                // Editor/markup already disposed — unlogged, same reasoning as the catches in clear().
            }
        }
        linkHighlighters.clear()
    }

    /**
     * Light (or unlight) the half belonging to [pairId], because the reader is hovering **the other end of
     * it in another editor**.
     *
     * This is the pair's answer to *"are these two one link"*, and it is why a portal needs an owner that
     * knows about more than one editor. Called by [com.audioexplainer.tree.TreePlanView] on every node when
     * any node reports a hover; a node with no half for that pair repaints nothing.
     */
    fun setHoveredPair(pairId: Int?) {
        if (hoveredPair == pairId) return
        val had = hoveredPair != null && linkRows.any { it.half.pairId == hoveredPair }
        val has = pairId != null && linkRows.any { it.half.pairId == pairId }
        hoveredPair = pairId
        if (pairId == null) hoveredRow = null
        // The token tints move with the link, so hovering either end brightens **four** things: this end's
        // token, its connector, the far connector and the far token. That is round 7's item 12 — *"it will
        // visually stand out on hover as a complete system"* — and it is why the tint is re-applied here
        // rather than only where the mouse is.
        if (had || has) {
            applyTints()
            repaintLinks()
        }
    }

    /**
     * **What this editor needs on its own**: clear of the code on *every* link row in it, not just one, so
     * its own channels form columns. Unclamped and in content coordinates — the clamp belongs to
     * [linkLaneAxes], because a clamped demand would drag the whole shared column left onto other nodes'
     * code.
     *
     * **A [LinkJoin.LINE] pair demands room for every row it spans**, not merely its two ends, because its
     * leg runs down past all of them — and for the widest **block inlay** crossing that span, which is
     * usually our own callout box. That was the old arrow renderer's rule and it is still the right one: a
     * block inlay is painted in a *later* pass than a custom highlighter renderer, so no [HighlighterLayer]
     * can lift a leg above the box (MoreUI.md §2.9) and it has to be routed clear of it instead.
     *
     * **Unless it cannot be cleared at all** ([LinkArt.obstructionClearable], K5). A nested code node is an
     * inlay as wide as the editor, and a demand to get past it is a demand that cannot be met.
     *
     * **One entry per lane, and each answers only for its own link** — round 8's fix for the trimmed label.
     * A lane this editor holds no row for gets [LinkArt.NO_DEMAND], meaning "no demand from here", which
     * is exactly right: the other end's node will have one, and the group takes the max.
     */
    private fun laneDemands(): IntArray {
        val joins = linkLaneLayout() ?: return IntArray(0)
        val out = IntArray(joins.size) { LinkArt.NO_DEMAND }
        val doc = editor.document
        // **Clamped to the document as it is now**, not as it was when [paintLinks] recorded these rows. A
        // node editor is a view over the shared project `Document`, so an edit in an ordinary tab can shorten
        // it under us — and this runs first inside [LinkRenderer]'s paint, where a throw costs the whole
        // node's paint pass rather than one link.
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        val gap = LinkArt.channelGap()
        for (row in linkRows) {
            val k = row.half.lane
            if (k < 0 || k >= out.size || row.half.line > lastLine) continue
            out[k] = maxOf(out[k], editor.offsetToXY(trimmedLineEnd(doc, row.half.line)).x + gap)
        }
        for (group in linkRows.groupBy { it.half.pairId }.values) {
            if (group.size < 2 || group.first().half.join != LinkJoin.LINE) continue
            val k = group.first().half.lane
            if (k < 0 || k >= out.size) continue
            val first = group.minOf { it.half.line }.coerceIn(0, lastLine)
            val last = group.maxOf { it.half.line }.coerceIn(first, lastLine)
            for (l in first..last) {
                out[k] = maxOf(out[k], editor.offsetToXY(trimmedLineEnd(doc, l)).x + gap)
            }
            // 0 from [inlayRight] means *no inlay*, not an inlay at x = 0, so it may not become a demand
            // of its own — on a screen basis that would be a floor 2 300 px right of the code.
            //
            // And an inlay the fan cannot get past may not move it either: see
            // [LinkArt.obstructionClearable]. A nested node is as wide as the editor, so clearing it is not
            // a thing that can be done — K5's far-right routing was the lane trying anyway.
            val inlay = inlayRight(first, last)
            if (inlay > 0 &&
                LinkArt.obstructionClearable(inlay + gap, joins, editor.lineHeight, channelRoom())
            ) {
                out[k] = maxOf(out[k], inlay + gap)
            }
        }
        return out
    }

    /** The step's lane layout, as every half of it carries it, or null when this editor draws no link. */
    internal fun linkLaneLayout(): List<LinkJoin>? =
        linkRows.firstOrNull()?.half?.lanes?.takeIf { it.isNotEmpty() }

    /** This editor's row height, which a [LinkAlignGroup] needs to size the lanes it is packing. */
    internal fun linkLineHeight(): Int = editor.lineHeight

    /**
     * Right edge of the widest block inlay standing **between** rows `topLine` and `bottomLine` — our own
     * callout, and anyone else's. Zero when none does, which includes every pair of rows with nothing
     * between them at all.
     *
     * The band is [LinkArt.crossesRowGap]'s, and it is the round-9 fix: a leg travels from one row's rail to
     * the other's, so what can obstruct it is what sits *between* them. An inlay docked under the lower row —
     * a nested child node, most of the time — is not on its way, and counting it routed the lane out past a
     * whole editor's width for nothing.
     *
     * A null `bounds` means the inlay is folded away and cannot collide.
     */
    private fun inlayRight(topLine: Int, bottomLine: Int): Int {
        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        if (topLine < 0 || topLine > lastLine || bottomLine < topLine || bottomLine > lastLine) return 0
        // Both bounds are laid-out y, so every inlay *above* them is already counted in the number: the band
        // is empty unless something really occupies the space between the two rows.
        val gapTop = editor.offsetToXY(doc.getLineStartOffset(topLine)).y + editor.lineHeight
        val gapBottom = editor.offsetToXY(doc.getLineStartOffset(bottomLine)).y
        if (gapBottom <= gapTop) return 0
        var x = 0
        val inlays = editor.inlayModel.getBlockElementsInRange(
            doc.getLineStartOffset(topLine),
            doc.getLineEndOffset(bottomLine),
        )
        for (inlay in inlays) {
            val b = inlay.bounds ?: continue
            if (!LinkArt.crossesRowGap(gapTop, gapBottom, b.y, b.height)) continue
            x = maxOf(x, b.x + b.width)
        }
        return x
    }

    /**
     * This editor's origin on the screen, or null when there is no answer — not showing, or disposed.
     *
     * The basis every member of a [LinkAlignGroup] agrees in. `locationOnScreen` throws rather than
     * returning null for a component that is not showing, which is a normal state during a mount, so the
     * failure is a null and the caller falls back to its own channel.
     */
    internal fun linkOriginScreenX(): Int? = try {
        val c = editor.contentComponent
        if (editor.isDisposed || !c.isShowing) null else c.locationOnScreen.x
    } catch (_: Throwable) {
        null
    }

    /** This editor's per-lane demands in screen coordinates, or null when there is no origin to add. */
    internal fun laneDemandsScreenX(): IntArray? {
        if (linkRows.isEmpty() || editor.isDisposed) return null
        val origin = linkOriginScreenX() ?: return null
        val out = laneDemands()
        // A lane with no demand from this editor must stay at "no demand" rather than becoming this node's
        // left edge, or a node holding one end of a five-link step would set a floor for the other four.
        // The test is against [LinkArt.NO_DEMAND] and not `> 0`, because a content x of 0 is a real demand
        // and a *screen* x of 0 is only the primary monitor's left edge.
        for (i in out.indices) if (out[i] != LinkArt.NO_DEMAND) out[i] += origin
        return out
    }

    /** This editor's left edge in screen coordinates, the floor a clamped fan may not cross. */
    internal fun linkLeftScreenX(): Int? {
        if (linkRows.isEmpty() || editor.isDisposed) return null
        val origin = linkOriginScreenX() ?: return null
        return origin + editor.scrollingModel.visibleArea.x
    }

    /**
     * Screen y of the row this editor holds for [pairId], skipping the one on [otherThanLine].
     *
     * Asked by a [LinkAlignGroup] on behalf of the *other* end of a link, so it can work out whether this
     * end is above or below it. Null rather than a guess when the row is absent, folded past the document's
     * end, or the component is not showing yet.
     */
    internal fun linkRowScreenY(pairId: Int, otherThanLine: Int): Int? {
        if (editor.isDisposed) return null
        val row = linkRows.firstOrNull {
            it.half.pairId == pairId && it.half.line != otherThanLine
        } ?: return null
        return screenYOfLine(row.half.line)
    }

    /** Mid-height of [line] in screen coordinates, or null when there is no laid-out answer. */
    private fun screenYOfLine(line: Int): Int? {
        val doc = editor.document
        if (line < 0 || line > (doc.lineCount - 1).coerceAtLeast(0)) return null
        val c = editor.contentComponent
        if (!c.isShowing) return null
        return try {
            c.locationOnScreen.y + editor.offsetToXY(doc.getLineStartOffset(line)).y + editor.lineHeight / 2
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * **Is the other end of [row]'s link below this one on screen?** — the one thing the geometry cannot
     * derive for itself, and what decides whether this half dives or climbs.
     *
     * Measured from live screen y where both ends can be found, because that is the only thing that is
     * actually true: the nest indents and stacks its nodes, a child's rows sit inside its parent's, and no
     * comparison of document line numbers across two files means anything. Falls back to
     * [LinkHalf.targetBelow] — the owner's answer from the order of the two ends — when a partner is not
     * resolvable, which is a mid-mount frame or a portal drawn outside a tree.
     */
    private fun targetBelowFor(row: LinkRow): Boolean {
        val group = linkAlign ?: return row.half.targetBelow
        val mine = screenYOfLine(row.half.line) ?: return row.half.targetBelow
        val theirs = group.partnerScreenY(this, row.half.pairId, row.half.line) ?: return row.half.targetBelow
        return theirs > mine
    }

    /**
     * **Every lane's axis**, in this editor's content coordinates, or null when the step draws nothing.
     *
     * The group's answer when there is one ([LinkAlignGroup.axesFor]) so the two ends of a link line up on
     * screen; this editor's own packing otherwise. Both go through [LinkArt.alignedLaneAxes], so the
     * fallback differs from the shared answer only in *whose* demands it was given — never in the rules.
     *
     * Null when the fan is wider than the viewport itself, and the caller draws nothing. Round 6 fell back
     * to the right edge and let the lanes pile up there; a pile of five links on one column is what round 7
     * removed, so it is not a fallback any more.
     */
    private fun linkLaneAxes(): IntArray? {
        if (linkRows.isEmpty() || editor.isDisposed) return null
        val joins = linkLaneLayout() ?: return null
        linkAlign?.axesFor(this)?.let { return it }
        // No group, or a group with nothing to say: pack against this editor alone, in its own coordinates.
        // `myOriginX` is 0 because demand, room and floor are all local here — the packing does not care
        // which basis it is given, only that it is given one.
        return LinkArt.alignedLaneAxes(
            0, joins, laneDemands(), channelRoom(),
            editor.scrollingModel.visibleArea.x, editor.lineHeight,
        )
    }

    /** True when this editor could hold the whole fan clear of its own left edge. See [LinkAlignGroup]. */
    internal fun linkFanFits(): Boolean {
        val joins = linkLaneLayout() ?: return false
        if (editor.isDisposed) return false
        val visible = editor.scrollingModel.visibleArea
        return visible.width - GUTTER_SLACK >= LinkArt.minRoom(joins, editor.lineHeight)
    }

    /** This editor's right edge in screen coordinates, or null when there is no origin to add. */
    internal fun linkRoomScreenX(): Int? {
        if (linkRows.isEmpty() || editor.isDisposed) return null
        val origin = linkOriginScreenX() ?: return null
        return origin + channelRoom()
    }

    /** Rightmost x a channel may occupy: the viewport's own edge, less the gutter slack. */
    private fun channelRoom(): Int {
        val visible = editor.scrollingModel.visibleArea
        return visible.x + visible.width - GUTTER_SLACK
    }

    /**
     * **The axis one lane's vertical is drawn on** — [base] plus that lane's offset in the step's layout.
     *
     * No clamp of its own, and that is the round-7 change that matters: [linkLaneAxes] already guarantees
     * the *whole* fan fits, so an individual lane can never need saving. Round 6 clamped per lane, which is
     * precisely how five lanes ended up on one column — each of them legally, one at a time.
     *
     * Round 8 moved the arithmetic itself into [LinkArt.alignedLaneAxes], because a lane's x is no longer a
     * fixed offset from a base: it is what the packing made of that lane's own demand. What is left here is
     * the lookup, and the clamp on [LinkHalf.lane] that keeps a half from a previous step's layout from
     * indexing past the current one.
     */
    private fun axisXOf(row: LinkRow, axes: IntArray?): Int {
        if (axes == null || axes.isEmpty()) return -1
        return axes[row.half.lane.coerceIn(0, axes.size - 1)]
    }

    /**
     * **Which rail this end takes off its row** — its position among the ends leaving the *same row on the
     * same side*, in lane order.
     *
     * Per row rather than per step, and for the railed ends per *side* as well, which is what makes the
     * space [reserveLinkSpace] buys proportional to
     * what a row carries: `link-fan.json`'s five links leave one row on fans 0-4 and arrive on five rows at
     * fan 0 each, so one row grows by 13 px and five rows grow by nothing. Round 6 used the step-wide number
     * for this and made every one of those five rows reserve the worst case.
     *
     * **Ordered by the column each end turns up, not by its lane**, which round 8 had to separate: the
     * packing now fills columns in demand order ([LinkArt.laneAxes]), so the innermost lane and the
     * innermost column are no longer the same end. Ordering by column keeps what the order is *for* — the
     * end that turns first is the one that stayed nearest the code, so two rails leaving one row never
     * cross — and the lane is only the tie-break, for two ends that somehow share a column.
     */
    private fun fanOf(row: LinkRow, axes: IntArray?): Int {
        val mine = axisXOf(row, axes)
        var n = 0
        for (other in linkRows) {
            if (other === row || other.half.line != row.half.line) continue
            // **Two fans on one row, counted apart.** A railed end contends only with the railed ends
            // leaving on its own side, because that is what shares a rail. A same-editor line end takes no
            // rail at all and contends with every other one on the row whichever way it goes, because they
            // all run along the row's own middle — round 8's *"horizontal lines from line ending center
            // overlap"*. [LinkArt.shape] reads the same number two ways for exactly that reason.
            if (other.takesRail != row.takesRail) continue
            if (row.takesRail && other.side != row.side) continue
            val theirs = axisXOf(other, axes)
            val before = if (theirs != mine) theirs < mine else other.half.lane < row.half.lane
            if (before) n++
        }
        return n
    }

    /**
     * Geometry for [row] right now, or null when it cannot be drawn.
     *
     * [axes] is a parameter rather than a lookup because a caller that asks about *several* rows should pay
     * for the packing once: since the shared column arrived it costs a `locationOnScreen` and an
     * `offsetToXY` per link row **per member of the group**, and [rowAt] runs on every mouse move. The
     * default keeps the single-row callers honest.
     */
    private fun shapeOf(row: LinkRow, axes: IntArray? = linkLaneAxes()): LinkShape? {
        val doc = editor.document
        if (row.half.line > (doc.lineCount - 1).coerceAtLeast(0)) return null
        if (row.tokenEnd > doc.textLength) return null
        val axisX = axisXOf(row, axes)
        if (axisX < 0) return null
        // Refreshed here, on the paint that draws this row, and read by [fanOf] for its neighbours.
        row.side = targetBelowFor(row)
        // [LinkRow.tokenEnd] is the token's right edge when it resolved and the row's text end when it did
        // not, which is exactly the two anchors — so one `offsetToXY` serves both forms.
        val p = editor.offsetToXY(row.tokenEnd)
        // Every derived number is [LinkArt.shape]'s, not ours — the room check, the rail's side and offset,
        // the portal's position in the row and the leg's travel. The bench calls the same factory, so an end
        // it draws is an end the editor would draw, and a hit test cannot drift from the ink.
        return LinkArt.shape(
            anchor = row.anchor,
            join = row.half.join,
            lane = row.half.lane,
            fan = fanOf(row, axes),
            // **No stub for a token anchor, and that was round 5's reported gap.** The 3 px the in-file arrow
            // used keeps a leg off the glyph it starts beside; a token end starts at the corner of a *tinted
            // token* it is meant to look attached to, so the same 3 px read as the line coming loose. A line
            // end still gets them, and [LinkShape.inkX] is where that split lives.
            startX = p.x,
            textEndX = editor.offsetToXY(trimmedLineEnd(doc, row.half.line)).x,
            rowMid = p.y + editor.lineHeight / 2,
            axisX = axisX,
            lineHeight = editor.lineHeight,
            incoming = !row.half.outgoing,
            mutual = row.half.mutual,
            targetBelow = row.side,
            route = LINK_ROUTE,
            metrics = colors().arrow.metrics,
        )
    }

    /**
     * The rail of [row]'s partner in **this** editor, or null when it has none here.
     *
     * A [LinkJoin.LINE] end's leg runs to that y rather than into a portal, so this is the one number the
     * geometry of a joined link cannot derive from one end. Null for a portal pair, and null for a joined
     * pair whose other row was folded away — which then draws as a lone approach rather than as a leg to
     * nowhere.
     */
    private fun partnerRailY(row: LinkRow, axes: IntArray?): Int? {
        if (row.half.join != LinkJoin.LINE) return null
        val other = linkRows.firstOrNull { it.half.pairId == row.half.pairId && it !== row } ?: return null
        return shapeOf(other, axes)?.railY
    }

    /** Repaint just the rows carrying a link, so a hover does not repaint the whole node. */
    private fun repaintLinks() {
        if (editor.isDisposed) return
        val axes = linkLaneAxes()
        // The fan reaches beyond the row's own bound and a hovered label runs right to the viewport's edge,
        // so the box is padded by the worst-case fan and taken out to that edge.
        val fan = LinkArt.railSpace(LinkArt.slots(), editor.lineHeight, colors().arrow.metrics.hoverStroke) +
            editor.lineHeight
        val right = channelRoom()
        var box: Rectangle? = null
        for (row in linkRows) {
            val shape = shapeOf(row, axes) ?: continue
            val left = minOf(shape.inkX, shape.axisX) - LINK_REPAINT_PAD
            val r = Rectangle(
                left,
                shape.rowMid - editor.lineHeight - fan,
                (right + LINK_REPAINT_PAD - left).coerceAtLeast(1),
                editor.lineHeight * 2 + fan * 2,
            )
            box = box?.union(r) ?: r
        }
        // The union of two rows' boxes is their bounding box, which is what covers a joined pair's leg
        // running between them — the rows in the middle are not in [linkRows] at all.
        editor.contentComponent.repaint(box ?: return)
    }

    /**
     * Make the links hoverable and clickable. One listener pair per editor, installed on the first
     * link ever drawn in it and never removed: **one renderer per editor** is this class's stated
     * invariant, so the listeners' whole lifetime is the editor's own and a released editor takes them
     * with it.
     *
     * Hit-testing is recomputed from [linkRows] rather than remembered from the last paint, so it cannot
     * go stale against a scroll.
     */
    private fun installLinkMouse() {
        if (linkMouseInstalled) return
        linkMouseInstalled = true
        editor.addEditorMouseListener(object : EditorMouseListener {
            // **`mousePressed`, not `mouseClicked`**, and that is not a style choice. The caret is moved by
            // the press, so a consume on the click would arrive too late to stop it — and consuming the
            // press instead leaves it unknown whether the editor still dispatches the click at all. One
            // event that certainly fires, consumed where consuming it still means something.
            override fun mousePressed(e: EditorMouseEvent) {
                if (e.isConsumed || e.mouseEvent.button != MouseEvent.BUTTON1) return
                val row = rowAt(e) ?: return
                e.consume()
                navigate(row.half.target)
            }

            /**
             * **Put the link out when the pointer leaves the editor.** `mouseMoved` is the only other thing
             * that clears a hover, and it cannot fire for a pointer that has left — so a link lit on the way
             * out stayed lit, in every node, until the reader came back or the step changed. Round 7 made
             * that more visible rather than less: the hover now brightens the token tints too.
             *
             * Guarded on [hoveredRow], which only this editor's own `mouseMoved` sets, so a node that was not
             * the one hovering cannot put out a link some other node has just lit.
             */
            override fun mouseExited(e: EditorMouseEvent) {
                if (hoveredRow == null) return
                hoveredRow = null
                editor.contentComponent.toolTipText = null
                setHoveredPair(null)
                onLinkHover?.invoke(null)
            }
        })
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                val row = rowAt(e)
                // The tooltip carries the sentence; the portal itself carries the connection. Set on the
                // content component because a custom renderer has no tooltip of its own, and a node editor
                // is PREVIEW-kind with no line markers, so nothing else here competes for it.
                editor.contentComponent.toolTipText = row?.half?.tooltip
                val pair = row?.half?.pairId
                val movedEnd = row?.half?.line != hoveredRow
                hoveredRow = row?.half?.line
                if (pair == hoveredPair) {
                    // Same link, other end — which happens on one continuous drag along a same-editor link,
                    // and is the only thing [hoveredRow] exists to answer. Without this the label stays
                    // beside the end the pointer left.
                    if (movedEnd && pair != null) repaintLinks()
                    return
                }
                setHoveredPair(pair)
                // Tell the owner, so the half in the *other* editor lights too.
                onLinkHover?.invoke(pair)
            }
        })
    }

    /** The link end under [e]'s pointer, or null — including for anything outside the text area. */
    private fun rowAt(e: EditorMouseEvent): LinkRow? {
        if (linkRows.isEmpty()) return null
        if (e.area != EditorMouseEventArea.EDITING_AREA) return null
        val p = e.mouseEvent.point
        val axes = linkLaneAxes() ?: return null
        return linkRows.firstOrNull { row ->
            val shape = shapeOf(row, axes) ?: return@firstOrNull false
            LinkArt.hit(shape, p, partnerRailY(row, axes) ?: shape.legEndY)
        }
    }

    /**
     * **Blank space, and nothing else** — the block inlay [reserveLinkSpace] buys a row for its fan.
     *
     * No `paint` override, so the platform's default draws nothing at all: what this contributes is the
     * height, which is the whole point. A block inlay is the only mechanism that adds *room* to an editor
     * without adding a line to its document — `setAdditionalLinesCount` would be counted by `offsetToXY` and
     * make every node taller, which `CodeNodeView.setLandingSlack` already records as the wrong tool.
     */
    private class LinkSpacerRenderer(private val px: Int) : EditorCustomElementRenderer {
        override fun calcWidthInPixels(inlay: Inlay<*>): Int = 1

        override fun calcHeightInPixels(inlay: Inlay<*>): Int = px
    }

    /**
     * **The drawn link** — one end of a portal pair, or a whole same-editor pair, in one renderer.
     *
     * [unit] is one row for a portal end and both rows for a [LinkJoin.LINE] pair, which is why the two cases
     * are one class: everything that differs between them is already in [LinkShape], and the only thing this
     * has to decide is whether to call [LinkArt.paintPair] or [LinkArt.paintHalf].
     *
     * `inner` so it can read [hoveredPair] and the shared lane axes off [linkRows]: both are properties of
     * the *editor*, not of one link, and a per-link copy of either is how five links on one step end up with
     * five different columns and five independent hovers.
     */
    private inner class LinkRenderer(
        private val unit: List<LinkRow>,
        private val color: Color,
        private val palette: Palette,
    ) : CustomHighlighterRenderer {

        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            val axes = linkLaneAxes()
            val shapes = unit.mapNotNull { row -> shapeOf(row, axes)?.let { row to it } }
            if (shapes.isEmpty()) return
            val hovered = hoveredPair == unit.first().half.pairId
            val bg = editor.colorsScheme.defaultBackground
            val g2 = g.create() as Graphics2D
            try {
                if (shapes.size == 2 && unit.first().half.join == LinkJoin.LINE) {
                    LinkArt.paintPair(g2, shapes[0].second, shapes[1].second, color, bg, hovered)
                } else {
                    for ((row, shape) in shapes) {
                        LinkArt.paintHalf(
                            g2, shape, color, bg, hovered,
                            partnerRailY(row, axes) ?: shape.legEndY,
                        )
                    }
                }
                if (hovered) {
                    // Beside the end the pointer is on, which for a same-editor link is a real choice: one
                    // renderer draws both ends of it, and putting the words at whichever end came first in
                    // the list would as often as not put them at the other one.
                    val at = shapes.firstOrNull { it.first.half.line == hoveredRow } ?: shapes.first()
                    // The whole fan's right edge **and** the width it would have at minimum pitch:
                    // [LinkArt.paintLabel] weighs one against the other to decide whether the words go
                    // beside this end or past every lane.
                    val lanes = at.first.half.lanes
                    val fanRight = LinkArt.laneRightEdge(lanes, axes ?: IntArray(0), editor.lineHeight)
                    paintHoveredLabel(
                        g2, editor, at.second, at.first, fanRight,
                        LinkArt.laneSpan(lanes, editor.lineHeight),
                    )
                }
            } finally {
                g2.dispose()
            }
        }

        /**
         * The link's own words, beside the hovered end — the reader's *"the label text for the portal links
         * could be visible near the portal"*, on hover for the reason [LinkArt.paintLabel] gives.
         *
         * Gated on the existing `arrowLabels` setting, because it is the same question that setting has
         * always answered: whether a relation's note is drawn on the picture or left to the callout box.
         * The colour is derived from **this link's** colour rather than from the style's one label colour,
         * so a label cannot end up naming a link it does not match.
         */
        private fun paintHoveredLabel(
            g2: Graphics2D,
            editor: Editor,
            shape: LinkShape,
            row: LinkRow,
            fanRight: Int,
            fanSpan: Int,
        ) {
            if (!palette.arrow.labels) return
            val text = row.half.label?.takeIf { it.isNotBlank() } ?: return
            val font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
            LinkArt.paintLabel(
                g2, shape, text,
                SpotlightRenderer.blendToward(color, palette.calloutCodeFg, POINTER_TEXT_ACCENT),
                font, editor.contentComponent.getFontMetrics(font), channelRoom(), fanRight, fanSpan,
                editor.colorsScheme.defaultBackground,
            )
        }
    }
    /**
     * The callout's paragraphs for this step, in reading order: the prose aside, then the worked example
     * directly above the code it illustrates, then one line per off-target relation ([pointers]) —
     * nearest-to-the-code last, so the box reads top-down from "what this is" to "where it goes". All three
     * are optional, and an empty list means no inlay at all — which is what keeps a bare plan looking
     * exactly as it does today.
     *
     * The prose line prefers the sentence-length [StepPaint.note] when the agent wrote one; the terse
     * [StepPaint.label] is the fallback (it reads thin in a box, hence the preference).
     *
     * The POINTER blocks are **still drawn for a relation that became a portal**, and deliberately: the
     * portal says *there is a link and here is its other end*, and the box is where the link's own words
     * live at rest — [LinkArt.paintLabel] draws them on the link itself only while it is hovered. For a link
     * that leaves the file it is also the only place its target is named at all.
     */
    private fun calloutBlocks(paint: StepPaint, pointers: List<PointerSpec>, c: Palette): List<CalloutBlock> {
        val out = mutableListOf<CalloutBlock>()
        (paint.note ?: paint.label)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            out += CalloutBlock(it, BlockStyle.PROSE, MAX_CALLOUT_LINES)
        }
        firstLine(paint.example).takeIf { it.isNotEmpty() }?.let {
            out += CalloutBlock(it, BlockStyle.CODE, MAX_EXAMPLE_LINES)
        }
        for (p in pointers) out += CalloutBlock(p.text, BlockStyle.POINTER, MAX_POINTER_LINES)
        return out
    }

    /**
     * Add the A2 block-inlay callout carrying [blocks] — above [firstLine] of the active range, or below
     * [lastLine] when `calloutBelow` is set. An empty list renders nothing, which is what keeps a bare plan
     * looking exactly as it does today. The [Inlay] is a [Disposable] the markup model does not own, so it
     * goes in [trackedDisposables].
     */
    private fun addCallout(
        firstLine: Int,
        lastLine: Int,
        blocks: List<CalloutBlock>,
        c: Palette,
        reserveRight: Int,
    ) {
        if (blocks.isEmpty()) return
        val below = c.calloutBelow
        val renderer = CalloutRenderer(
            blocks, c.accent, c.calloutBg, c.calloutFg, c.calloutCodeFg, c.calloutPointerFg,
            // Indent to the block's FIRST line in both placements: that is the code's own left edge, whereas
            // the last line of a range is often a closing brace sitting further left.
            indentPx(firstLine), reserveRight, c.calloutStyle,
        )
        val inlay = editor.inlayModel.addBlockElement(
            editor.document.getLineStartOffset(if (below) lastLine else firstLine),
            // The box annotates the code on the other side of itself, so it anchors to that side's text: an
            // edit there should carry the callout along with its code, not strand it against the far line.
            /* relatesToPrecedingText = */ below,
            /* showAbove = */ !below,
            CALLOUT_PRIORITY,
            renderer,
        ) ?: return
        trackedDisposables += inlay
        calloutInlay = inlay
        calloutLine = if (below) lastLine else firstLine
        calloutBelow = below
        calloutRenderer = renderer
        calloutExtra = 0
    }

    /**
     * Horizontal strip the step's arrows need to the right of the callout box.
     *
     * A link routes its leg clear of the box because it cannot paint above it
     * (MoreUI.md §2.9), but a box wrapped to the full viewport leaves nowhere to route *to* — the channel
     * clamps back onto it. So the box gives the arrows room up front, and only when the step actually has
     * arrows: a walkthrough with no relations (most of them) wraps exactly as wide as it did before.
     *
     * Label room is included only if some arrow carries a label, since that is drawn right of the channel
     * again. [CalloutRenderer] floors its text width, so an editor too narrow to honour this loses the
     * reserve rather than the text.
     *
     * **Portals are not reserved for**, and that is a decision: a portal's channel clamps to the viewport
     * rather than being dropped, so it degrades on its own where an arrow would have vanished. Widening the
     * box's reserve for every link would narrow the callout on every step that has one.
     */
    private fun linkReserve(links: List<LinkRequest>, c: Palette): Int {
        if (links.isEmpty()) return 0
        // The hovered weight, not the resting one: the reserve has to hold the widest the ink ever gets, or
        // pointing at a link would push it into the box the reserve exists to keep clear. `ceil`, because a
        // stroke is a fraction of a px and the old `toInt()` truncated a 1.4 px line to 1 — which is how a
        // knob that did nothing still managed to be slightly wrong.
        val ink = kotlin.math.ceil(c.arrow.metrics.hoverStroke).toInt()
        return LinkArt.channelGap() + ink + LinkArt.lineEndStub()
    }

    /**
     * Pixel width of [line]'s leading whitespace, so the callout hangs under the code it describes rather
     * than at the left margin. Uses [Editor.offsetToXY] so tabs and proportional fonts are measured as the
     * editor actually lays them out.
     *
     * Falls back to 0 (flush left) unlogged: this is cosmetic positioning, and a callout at the margin is
     * a fine degradation — not worth a warning that would outnumber real faults.
     */
    private fun indentPx(line: Int): Int = try {
        val doc = editor.document
        val chars = doc.charsSequence
        val start = doc.getLineStartOffset(line)
        val end = doc.getLineEndOffset(line)
        var i = start
        while (i < end && (chars[i] == ' ' || chars[i] == '\t')) i++
        (editor.offsetToXY(i).x - editor.offsetToXY(start).x).coerceAtLeast(0)
    } catch (_: Throwable) {
        0
    }

    // ── B3/A4: relations ──

    /** What a step's relations resolve to — see [resolveRelations]. */
    private class ResolvedRelations(val links: List<LinkRequest>, val pointers: List<PointerSpec>)

    /**
     * A relation the editor can't draw: it hangs off [line] and names [target] in words instead.
     *
     * [origin] is the end that *is* in this step and [note] the relation's own phrase. Both are carried for
     * the portal pair, which has to label the far half and cannot re-derive either from [text] - that
     * string is already a formatted sentence about the *near* end's target.
     */
    private class PointerSpec(
        val line: Int,
        val text: String,
        val target: Endpoint,
        val incoming: Boolean,
        val origin: Endpoint,
        val note: String?,
    )

    /**
     * Turn the relations belonging to this step into links, and prose for the ones that leave the file.
     *
     * Two decisions live here, and both are the plugin's rather than the agent's (MoreUI.md §4.3 B3):
     *
     * - **Which relations belong to this step.** Only those with an endpoint inside [active]. `relations`
     *   is a segment-level list, so drawing all of it at once would put every link in the segment on screen
     *   during step 1; anchoring to the active highlight keeps a relation in step with the narration.
     * - **Line or portal** ([LinkRequest.local]). A drawn line needs two lines of *this* document, on
     *   different rows, close enough to read as a connection. Everything else — another file, a line this
     *   document does not have, endpoints 200 rows apart — is reported as non-local and becomes a portal at
     *   each end if the tree can find the far one, which is [LinkJoin]'s whole content.
     *
     * **What round 6 removed from here.** This used to *decide the picture*: a local relation became an
     * `ArrowSpec` drawn on the spot by a second renderer, everything else a line of prose. So the two forms
     * shared nothing — an in-file arrow had one colour for the whole step, no hover and a channel of its own,
     * while a portal had a palette, a hover relay and a shared column. Both are one report now and the
     * difference between them is one boolean. The prose survives **only** for a link that leaves the file,
     * because that is the one case where the callout is the only place the link's words can live.
     *
     * A wrong `token` no longer condemns an endpoint, and that is a deliberate reversal. The old rule existed
     * because a *wrong arrow* — confidently joining two unrelated lines — is worse than no arrow; but the
     * line number is what decides where a link is drawn, and the token only decides whether this end tints an
     * identifier or hangs off the row's text end ([LinkAnchor]). A token that is not where the agent said now
     * costs the tint and nothing else.
     */
    private fun resolveRelations(
        relations: List<Relation>,
        file: String,
        activeStart: Int,
        activeEnd: Int,
        lastLine: Int,
    ): ResolvedRelations {
        if (relations.isEmpty()) return NO_RELATIONS
        val links = mutableListOf<LinkRequest>()
        val pointers = mutableListOf<PointerSpec>()
        var dropped = 0
        for (r in relations) {
            val fromHere = inActive(activeStart, activeEnd, r.from, file)
            val toHere = inActive(activeStart, activeEnd, r.to, file)
            if (!fromHere && !toHere) continue // belongs to a different step of this frame
            if (links.size >= MAX_RELATIONS_PER_STEP) {
                dropped++
                continue
            }
            val label = r.note?.trim()?.takeIf { it.isNotEmpty() }
            // Trimmed and case-insensitive for the same reason the decoder is tolerant: an unfamiliar kind
            // costs a nuance, not the relation. `PlanLoader` is what says a kind went unrecognised.
            val mutual = r.kind.trim().equals(Relation.KIND_BACKFORTH, ignoreCase = true)
            // Hang the link on whichever end IS in this step, aimed at the other one.
            val here = if (fromHere) r.from else r.to
            val there = if (fromHere) r.to else r.from
            val hereLine = (here.line - 1).coerceIn(0, lastLine)
            val thereLine = place(there, file, lastLine)
            val local = thereLine != null && thereLine != hereLine &&
                abs(thereLine - hereLine) <= MAX_ARROW_SPAN
            links += LinkRequest(
                line = hereLine,
                origin = here,
                target = there,
                note = label,
                outgoing = fromHere,
                local = local,
                mutual = mutual,
            )
            if (log.isDebugEnabled) {
                val where = there.file?.substringAfterLast('/') ?: "here"
                log.debug(
                    "link: line ${hereLine + 1} ${if (fromHere) "->" else "<-"} $where:${there.line} " +
                        "(${r.kind}, ${if (local) "one editor" else "portal"})",
                )
            }
            if (local) continue
            // A link that leaves the file keeps its line of prose: the far half may not exist at all, and
            // then the box is the only thing that names where it went.
            val text = pointerText(there, label, incoming = !fromHere, mutual = mutual) ?: continue
            pointers += PointerSpec(
                line = hereLine,
                text = text,
                target = there,
                incoming = !fromHere,
                origin = here,
                note = label,
            )
        }
        if (dropped > 0 && log.isDebugEnabled) {
            log.debug("$dropped relation(s) past the cap of $MAX_RELATIONS_PER_STEP per step not drawn")
        }
        return ResolvedRelations(links, pointers)
    }

    /**
     * [ep]'s 0-based line **if it names a line of this document**, else null.
     *
     * The [LinkJoin] test, and all of it: a link is drawn as a line when both of its ends come back non-null
     * from here. Unlike the [anchor] it replaced it says nothing about tokens — see [resolveRelations].
     */
    private fun place(ep: Endpoint, segmentFile: String, lastLine: Int): Int? {
        if (ep.line <= 0 || ep.line - 1 > lastLine) return null
        if (ep.file != null && !sameFile(ep.file, segmentFile)) return null
        return ep.line - 1
    }

    /**
     * True when [ep] names a line of the step's active range (1-based, inclusive) — i.e. this relation
     * belongs to this step. Measured against the *primary* active range, which is the one the callout and
     * the arrows are drawn around.
     */
    private fun inActive(activeStart: Int, activeEnd: Int, ep: Endpoint, frameFile: String): Boolean =
        ep.inStepBand(activeStart, activeEnd, frameFile)

    /**
     * One line of prose for a relation the editor can't draw: `→ RateTable.Lookup · RateTable.cs:88 — where
     * the rate comes from`. Null when the endpoint names nothing at all and carries no note, which is an
     * empty relation rather than something to render.
     *
     * [incoming] flips the glyph: `→` for a target this step leads to, `←` for a source it came from. A
     * [mutual] relation ([Relation.KIND_BACKFORTH]) gets `↔` instead of either, so the one line of prose a link
     * leaving the file is allowed says what the two arrowheads say.
     */
    private fun pointerText(ep: Endpoint, note: String?, incoming: Boolean, mutual: Boolean = false): String? {
        val fileBase = ep.file?.trim()?.takeIf { it.isNotEmpty() }?.replace('\\', '/')?.substringAfterLast('/')
        val lineLabel = if (ep.line > 0) "line ${ep.line}" else null
        val name = ep.symbol?.trim()?.takeIf { it.isNotEmpty() }
            ?: ep.token?.trim()?.takeIf { it.isNotEmpty() }
            ?: fileBase
            ?: lineLabel
        val where = when {
            fileBase != null && fileBase != name -> if (ep.line > 0) " · $fileBase:${ep.line}" else " · $fileBase"
            fileBase != null -> if (ep.line > 0) ":${ep.line}" else ""
            lineLabel != null && lineLabel != name -> " · $lineLabel"
            else -> ""
        }
        val glyph = if (mutual) "↔" else if (incoming) "←" else "→"
        val head = if (name == null) null else "$glyph $name$where"
        return when {
            head != null && note != null -> "$head — $note"
            head != null -> head
            note != null -> "$glyph $note"
            else -> null
        }
    }

    // ── A3: token flash ──

    /**
     * Pulse the identifier named by [hl]'s `token` anchor, fading it from the accent colour into the active
     * line's own glow over [AudioExplainerSettings.DEF_TOKEN_FLASH_MS]. No `token`, or one that can't be found in
     * `[lineStart, lineEnd]`, means no flash at all — the validate-and-drop rule, so a wrong anchor costs
     * nothing but a missing pulse.
     *
     * The fade re-adds an `EXACT_RANGE` highlighter per frame rather than mutating one in place: replacing a
     * highlighter is exactly how every other layer here repaints, so it needs no assumptions about when the
     * editor notices a mutated [TextAttributes]. A dozen frames of that is cheap.
     *
     * The [Timer] (not `Alarm`: EDT-native by construction, and no platform-version surface) is wrapped as a
     * [Disposable] into [trackedDisposables], so a segment transition mid-fade both stops the ticks and
     * removes the half-faded highlighter.
     */
    private fun startTokenFlash(
        token: String?,
        occurrence: Int,
        lineStart: Int,
        lineEnd: Int,
        c: Palette,
    ) {
        cancelFlash()
        val range = resolveToken(token, occurrence, lineStart, lineEnd)
        if (range == null) {
            // The whole point of validate-and-drop is that this is NOT an error — but it is invisible, and
            // "my token didn't flash" is unanswerable without knowing whether it resolved. Debug level: an
            // author debugging a plan wants this; a normal user does not.
            if (log.isDebugEnabled && !token.isNullOrBlank()) {
                log.debug(
                    "token anchor dropped: '$token' (occurrence $occurrence) not found in lines " +
                        "${lineStart + 1}-${lineEnd + 1}",
                )
            }
            return
        }
        if (log.isDebugEnabled) {
            val line = editor.document.getLineNumber(range.startOffset) + 1
            log.debug(
                "token flash: '$token' occurrence $occurrence -> line $line " +
                    "offsets ${range.startOffset}-${range.endOffset}, fading over ${AudioExplainerSettings.DEF_TOKEN_FLASH_MS}ms",
            )
        }

        paintFlash(range, c, 1.0)

        val interval = (AudioExplainerSettings.DEF_TOKEN_FLASH_MS / FLASH_STEPS)
            .coerceAtLeast(MIN_FLASH_INTERVAL_MS)
        val timer = Timer(interval, null)
        var step = 0
        timer.addActionListener {
            step++
            when {
                editor.isDisposed -> timer.stop()
                step >= FLASH_STEPS -> {
                    timer.stop()
                    removeFlash()
                }
                // Linear fade of the accent into the active-line glow, so the pulse dissolves into the
                // highlight it belongs to rather than blinking out against a different colour.
                else -> paintFlash(range, c, 1.0 - step.toDouble() / FLASH_STEPS)
            }
        }
        timer.start()
        val cancel = Disposable {
            timer.stop()
            removeFlash()
        }
        flashTimer = cancel
        trackedDisposables += cancel
    }

    /** Stop a fade already in flight (and drop its half-faded highlighter), leaving the rest of the render. */
    private fun cancelFlash() {
        val cancel = flashTimer ?: return
        flashTimer = null
        trackedDisposables -= cancel
        try {
            Disposer.dispose(cancel)
        } catch (_: Throwable) {
            // Editor gone with its timer — unlogged, same reasoning as the teardown catches in clear().
        }
    }

    /** Replace the flash highlighter with one at [intensity] (1.0 = full accent, 0.0 = the active glow). */
    private fun paintFlash(range: TextRange, c: Palette, intensity: Double) {
        removeFlash()
        if (editor.isDisposed) return
        val attrs = TextAttributes().apply { backgroundColor = blend(c.accent, c.activeBg, intensity) }
        flashHighlighter = try {
            editor.markupModel.addRangeHighlighter(
                range.startOffset,
                range.endOffset,
                FLASH_LAYER,
                attrs,
                HighlighterTargetArea.EXACT_RANGE,
            )
        } catch (_: Throwable) {
            null // Document shrank under us mid-fade; the next tick stops the timer.
        }
    }

    private fun removeFlash() {
        val h = flashHighlighter ?: return
        flashHighlighter = null
        try {
            editor.markupModel.removeHighlighter(h)
        } catch (_: Throwable) {
            // Editor already disposed — unlogged for the same reason as the teardown catches below.
        }
    }

    /**
     * Offsets of the [occurrence]-th match of [token] within lines `[lineStart, lineEnd]`, or null if the
     * anchor doesn't resolve. Plain text search over the document — deliberately no PSI, so Rider behaves
     * the same as IntelliJ.
     *
     * Tries **whole-word** matches first and only then falls back to a bare substring: an anchor of `rate`
     * should prefer the standalone variable over the `rate` inside `rateTable`, but a non-identifier anchor
     * (`=>`, `Parse(`) has no word boundaries to honour and must still work.
     */
    private fun resolveToken(
        token: String?,
        occurrence: Int,
        lineStart: Int,
        lineEnd: Int,
    ): TextRange? {
        val needle = token?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        val a = lineStart.coerceIn(0, lastLine)
        val b = lineEnd.coerceIn(a, lastLine)
        val from = doc.getLineStartOffset(a)
        val to = doc.getLineEndOffset(b)
        val text = doc.charsSequence
        val nth = occurrence.coerceAtLeast(1)
        return findNth(text, needle, from, to, nth, wholeWord = true)
            ?: findNth(text, needle, from, to, nth, wholeWord = false)
    }

    /**
     * Remove every decoration this renderer added to [editor] (upstream `clearHighlights`). Called at the
     * head of each render and by the owner when the spotlight leaves this editor; safe to call repeatedly,
     * and safe after the editor has been disposed.
     */
    fun clear() {
        // Unconditional, not merely via the flash timer's disposable: the first frame is painted before
        // that disposable is registered, so this is what makes "no stray flash survives" hold always.
        removeFlash()
        val model = editor.markupModel
        for (h in tracked) {
            try {
                model.removeHighlighter(h)
            } catch (_: Throwable) {
                // Editor/markup already disposed — nothing to remove. Left UNLOGGED on purpose:
                // teardown races are routine and fire per highlighter on every segment transition,
                // so logging here would bury the render failures `runOnEdt` now reports.
            }
        }
        tracked.clear()

        // Inlays (and any future timer/renderer) are NOT owned by the markup model, so removing the
        // highlighters above leaves them on screen. Dispose them explicitly, and unconditionally: a
        // disposed editor has already dropped its own inlays, and disposing twice is a no-op.
        for (d in trackedDisposables) {
            try {
                Disposer.dispose(d)
            } catch (_: Throwable) {
                // Already disposed with its editor — nothing to do. Unlogged, same reasoning as above.
            }
        }
        trackedDisposables.clear()
        // The flash's cancel handle was in that list, so the fade is now stopped; drop the stale reference.
        flashTimer = null
        calloutInlay = null
        calloutRenderer = null
        calloutLine = -1
        // Portals too: "cleared" has to mean nothing of ours is on screen. What lets a pair's far half live
        // in a node the step does not touch is not an exemption from this — it is that the tree paints
        // portals after its whole clear/paint pass. See [paintLinks].
        clearLinks()
        linkReqs = emptyList()
        activeStart = -1
        activeEnd = -1
    }

    /**
     * The docked callout's height in pixels, or 0 when this step has none.
     *
     * Exposed because a node embedded in another node has to **reserve room for it** — the G1c″ round reversed §1a⁵ 4
     * on exactly this point. The number is not derivable from the outside: it is whatever [CalloutRenderer] wrapped
     * to at the current width, which is constraint 9's coupling, and only the inlay knows the answer.
     */
    fun calloutHeight(): Int {
        val inlay = calloutInlay ?: return 0
        return if (inlay.isValid) inlay.heightInPixels.coerceAtLeast(0) else 0
    }

    /**
     * **What this step is about, as one rectangle in the editor's content coordinates**: the active rows *and* the
     * docked callout, unioned — or null when nothing is drawn.
     *
     * The reason it is a union rather than the active line: the G1c″ round found that a step in a nested node
     * scrolled *that* node correctly and left the whole node out of the ancestors' view. Fixing that means the
     * ancestors scroll to a band, and the band has to be the thing the reader is meant to read — a callout docked
     * above the active line is half of it, and it is the half that goes missing first because it is the topmost.
     *
     * `x`/`width` are deliberately degenerate: every caller only scrolls vertically, and the box's own x is the code's
     * indent, which is not a horizontal scroll target.
     */
    fun stepBounds(): Rectangle? {
        if (editor.isDisposed) return null
        var band: Rectangle? = null
        if (activeStart >= 0) {
            val doc = editor.document
            val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
            val a = activeStart.coerceIn(0, lastLine)
            val b = activeEnd.coerceIn(a, lastLine)
            val top = editor.offsetToXY(doc.getLineStartOffset(a)).y
            val bottom = editor.offsetToXY(doc.getLineStartOffset(b)).y + editor.lineHeight
            band = Rectangle(0, top, 1, (bottom - top).coerceAtLeast(editor.lineHeight))
        }
        calloutInlay?.takeIf { it.isValid }?.bounds?.let { box ->
            band = band?.union(box) ?: Rectangle(box)
        }
        return band
    }

    /**
     * **Re-run the current step's reveal**, for a viewport that did not exist when the step was rendered.
     *
     * The G1c‴ round's finding 9: *"stepping into collapsed node expands it, but places callout partially hidden
     * (scrolled out) from top side."* [revealCenter] runs when the step is rendered, and a step can land in a node that
     * is **collapsed** at that moment — a component of height 0, whose editor has a zero-height visible area. Both
     * halves of the reveal degrade silently there: `scrollTo` clamps to a scrollable extent of nothing, and
     * [revealCallout]'s own `view.height <= 0` guard returns. Then the nesting expands, the node acquires a viewport,
     * and **nothing re-issues the scroll** — the callout stays wherever the clamp left it.
     *
     * So the expansion has to ask, and this is what it calls ([CodeNodeView.reRevealStep]). Idempotent by
     * construction: it recomputes from [activeStart]/[activeEnd] and a reveal onto an already-correct viewport
     * scrolls nowhere. Returns false when there is no step to re-reveal, so a caller can report the difference
     * between "re-revealed" and "nothing to do" rather than assuming.
     *
     * This is constraint 20 in the reveal: a scroll issued against geometry that is still moving has to be re-armed
     * by whatever moves it, and an expansion is the most violent mover there is — it takes a viewport from 0 to its
     * full height in one pass.
     */
    fun reRevealStep(): Boolean {
        if (editor.isDisposed || activeStart < 0) return false
        if (editor.scrollingModel.visibleArea.height <= 0) return false
        revealCenter(activeStart, activeEnd.coerceAtLeast(activeStart))
        return true
    }

    /** Add a whole-line highlighter over the 0-based inclusive line range; tracked for later clearing. */
    private fun addLineRange(startLine: Int, endLine: Int, attrs: TextAttributes): RangeHighlighter? {
        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        val a = startLine.coerceIn(0, lastLine)
        val b = endLine.coerceIn(a, lastLine)
        val h = editor.markupModel.addRangeHighlighter(
            doc.getLineStartOffset(a),
            doc.getLineEndOffset(b),
            LAYER,
            attrs,
            HighlighterTargetArea.LINES_IN_RANGE,
        )
        tracked += h
        return h
    }

    private fun revealCenter(zeroStart: Int, zeroEnd: Int) {
        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        val caretLine = zeroStart.coerceIn(0, lastLine)
        editor.caretModel.moveToOffset(doc.getLineStartOffset(caretLine))
        editor.selectionModel.removeSelection()
        // **A node with nothing to scroll gets no scroll, and that is Phase 3 answering constraint 19's own
        // question.** A node sized to its content has no viewport smaller than its content — measured in the
        // 1a⁗ round, where no nested editor had a scrollbar at all — so "reveal the active range in its node"
        // is not expressible here and the container's reveal of the whole node is what carries it. Saying so
        // costs one comparison and stops a `CENTER` scroll being issued against a viewport that cannot honour
        // it, which is the shape of thing that silently degrades and then gets debugged twice.
        if (!scrollable()) {
            if (log.isDebugEnabled) log.debug("render step: node grows to content, reveal is the container's")
            return
        }
        val mid = ((zeroStart + zeroEnd) / 2).coerceIn(0, lastLine)
        editor.scrollingModel.scrollTo(LogicalPosition(mid, 0), ScrollType.CENTER)
        // After the centring has actually happened, not beside it: `scrollTo` animates unless something disabled it,
        // and a correction issued into a running animation is simply overwritten by its last frame. Registered only
        // when there is a callout, so a bare plan's reveal is byte-for-byte what it was.
        if (calloutInlay != null) {
            editor.scrollingModel.runActionOnScrollingFinished { revealCallout(zeroStart) }
        }
    }

    /**
     * Whether this editor has a viewport smaller than what it draws — i.e. whether scrolling it means
     * anything at all.
     *
     * Read off the live scroll geometry rather than off [com.audioexplainer.tree.CodeNodeStyle.grow], because
     * a renderer owns an editor and not the node policy above it, and because the answer is the same for a
     * grown node, a node whose excerpt happens to fit, and an editor laid out at height 0 before its first
     * validate. All three want the same treatment: leave the scroll alone.
     */
    private fun scrollable(): Boolean {
        val view = editor.scrollingModel.visibleArea
        if (view.height <= 0) return false
        return editor.contentComponent.height > view.height
    }

    /**
     * Bring the step's docked callout back into the viewport when centring the active line pushed it out.
     *
     * **The correction the G1c′ round asked for, and the alternative it is instead of.** §1a⁵ 4 chose to let the
     * reveal handle the callout rather than have a node reserve room for it — reserving would make a node change
     * height on every step advance, which is constraint 9's coupling deliberately amplified. The round then found
     * that the reveal was *not* handling it: `ScrollType.CENTER` knows about the line and not about the inlay above
     * it. This is that half arriving.
     *
     * The box's own edge is what has to be visible, so the measurement is [Inlay.getBounds] rather than a line
     * arithmetic — the inlay's height is whatever [CalloutRenderer] wrapped to at this width, which nothing else
     * knows. A scroll *up* to it never hides the active line, because the callout is adjacent to that line: at worst
     * the far end of a taller-than-viewport box wins, which is the same trade a reader scrolling by hand would make.
     * The below-placement case is the mirror, clamped so the active line's own top stays in view.
     */
    private fun revealCallout(zeroStart: Int) {
        val inlay = calloutInlay ?: return
        if (!inlay.isValid) return
        val box = inlay.bounds ?: return
        val view = editor.scrollingModel.visibleArea
        if (view.height <= 0) return
        val sm = editor.scrollingModel
        // **Never flush with the edge**, which is the G1c‴ round's other half of finding 9 and constraint 20's second
        // rule: a target placed exactly at the viewport's top crosses it on any later change — and in this surface
        // there is always a later change (the allowance resize, the box's own re-wrap). The slack is the same margin
        // [CodeNodeView.calloutAllowance] already reserves, so the room being scrolled to is room that exists.
        if (box.y - CALLOUT_REVEAL_PAD < view.y) {
            sm.scrollVertically((box.y - CALLOUT_REVEAL_PAD).coerceAtLeast(0))
        } else if (box.y + box.height + CALLOUT_REVEAL_PAD > view.y + view.height) {
            val activeTop = editor.offsetToXY(editor.document.getLineStartOffset(zeroStart)).y
            sm.scrollVertically(
                minOf(box.y + box.height + CALLOUT_REVEAL_PAD - view.height, activeTop).coerceAtLeast(0),
            )
        }
    }

    /**
     * A render's resolved styling. Foreground dims are theme-derived (recomputed per render, so a
     * mid-session light/dark switch is honoured); accent/glow are the user's absolute colours from
     * [AudioExplainerSettings]. Recomputed on every render, so a settings change is picked up.
     */
    private class Palette(
        val dim: TextAttributes,
        val segmentDim: TextAttributes,
        val gap: TextAttributes,
        val active: TextAttributes,
        val accent: Color,
        val gapGutterEnabled: Boolean,
        val gapGutterWidth: Int,
        val calloutBg: Color,
        val calloutFg: Color,
        /** Body colour for a [BlockStyle.CODE] block — full scheme foreground, so an example reads crisper. */
        val calloutCodeFg: Color,
        /**
         * Body colour for a [BlockStyle.POINTER] block and an arrow's label — the accent mixed *into* the
         * scheme foreground rather than the accent itself, which on a light theme would be gold-on-gold.
         */
        val calloutPointerFg: Color,
        val calloutStyle: CalloutStyle,
        val calloutBelow: Boolean,
        val arrow: ArrowStyle,
        /** The active line's resolved glow, held separately so the token flash can fade *into* it. */
        val activeBg: Color,
    )

    private fun colors(role: FrameRole = FrameRole.PRIMARY): Palette {
        // Read the scheme the editor ACTUALLY renders with, not the global one. The two can disagree
        // (per-editor scheme, presentation mode, a light editor scheme under a dark UI theme, timing),
        // and blending our glow/tint against the wrong background is what makes a dark editor wash out
        // to white. `editor.colorsScheme` is always the colours this editor paints, and both accessors are
        // `@NotNull` — there was a global-scheme fallback here, which the compiler pointed out could never
        // run.
        val scheme = editor.colorsScheme
        val fg = scheme.defaultForeground
        val bg = scheme.defaultBackground
        val st = AudioExplainerSettings.getInstance().state

        val dimFg = blend(fg, bg, st.outsideDimPercent / 100.0)
        val segDimFg = blend(fg, bg, st.withinDimPercent / 100.0)
        val accent = parseColor(st.accentColor)
        // **The helper tier.** A helper frame's glow is the primary's pulled most of the way back to the
        // editor's own background: still visibly lit, unmistakably not the subject. Applied to the *glow
        // percentage* rather than to the finished colour so it composes with the user's own setting instead
        // of overriding it — a reader who turns the glow down keeps the ratio between the two tiers.
        val glowPercent = when (role) {
            FrameRole.PRIMARY -> st.activeGlowPercent / 100.0
            FrameRole.HELPER -> st.activeGlowPercent / 100.0 * HELPER_GLOW_MIX
        }
        val activeBg = blend(parseColor(st.activeGlowColor), bg, glowPercent)

        val dim = TextAttributes().apply { foregroundColor = dimFg }
        // Non-active highlight lines: dimmed foreground PLUS an own faint accent-tinted background,
        // so they no longer read identically to gap lines (which carry only the gutter bar).
        val segmentDim = TextAttributes().apply {
            foregroundColor = segDimFg
            if (st.nonActiveTint) backgroundColor = blend(accent, bg, st.nonActiveTintPercent / 100.0)
        }
        val gap = TextAttributes().apply { foregroundColor = segDimFg }
        val active = TextAttributes().apply {
            backgroundColor = activeBg
            if (st.overviewRulerMark) errorStripeColor = accent
        }
        // Callout box: accent-tinted panel with softened body text, both derived from THIS editor's scheme
        // so the box tracks a light/dark switch like every other layer here.
        // Opacity is applied on top of the tint, not instead of it: the tint decides the box's *hue*, the
        // alpha decides how much of what it covers (a clamped arrow leg, a long line of code) still reads
        // through it. At 100 the colour is opaque, i.e. exactly the box as it was before the setting existed.
        // A Helper callout is tinted in the same softened key as its glow, for the same reason: one box per
        // step is what read well, so the second box must be legible without competing.
        val calloutTint = when (role) {
            FrameRole.PRIMARY -> st.calloutTintPercent / 100.0
            FrameRole.HELPER -> st.calloutTintPercent / 100.0 * HELPER_GLOW_MIX
        }
        val calloutBg = blend(accent, bg, calloutTint).let {
            if (st.calloutOpacityPercent >= 100) it else Color(it.red, it.green, it.blue, alpha255(st.calloutOpacityPercent))
        }
        val calloutFg = blend(fg, bg, CALLOUT_TEXT_BRIGHTNESS)
        val calloutStyle = CalloutStyle(
            FrameStyle.of(st.calloutFrame),
            st.calloutCornerRadius.coerceAtLeast(0),
            st.calloutBarWidth.coerceAtLeast(0),
            st.calloutTextScalePercent.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
            st.calloutProseFont.trim(),
            st.calloutLeader,
            st.calloutBelow,
            commentFg(scheme, calloutFg),
        )
        // **A label follows slot 0**, not the slot it belongs to: only one label is on screen at a time, it
        // is the hovered link's, and the hover already brightens that link's own ink to say which. Tying the
        // words to five different colours would repaint the same sentence differently on each hover for no
        // extra fact. A POINTER block belongs to the callout box and stays on the accent regardless.
        val arrowStyle = ArrowStyle(
            accent,
            slotColors(st.arrowSlotColors),
            blend(LinkArt.color(accent, 0), fg, POINTER_TEXT_ACCENT),
            LinkArt.metrics(st.arrowThicknessTenths, st.arrowHeadSize, st.arrowSquareElbow),
            st.arrowLabels,
        )
        return Palette(
            dim, segmentDim, gap, active, accent, st.gapGutter, st.gapGutterWidth,
            calloutBg, calloutFg, fg, blend(accent, fg, POINTER_TEXT_ACCENT),
            calloutStyle, st.calloutBelow, arrowStyle, activeBg,
        )
    }

    // ── Decoration renderers ──

    /**
     * What a callout paragraph *is*, which is the whole visual vocabulary of the box: an aside about the code
     * ([PROSE], italic, softened), the code itself having been run ([CODE], upright, full foreground), or a
     * place this step points at ([POINTER], upright, accent-tinted).
     *
     * All three come from `editor.colorsScheme`, i.e. the editor's own **monospace** family, so they share one
     * metrics contract and one measure/paint path — see MoreUI.md §4.3 B4, where a second renderer was wrongly
     * expected to be needed for anything but prose.
     */
    private enum class BlockStyle { PROSE, CODE, POINTER }

    /**
     * How the callout box is decorated around its text. Anything unrecognised in the persisted setting reads
     * as [BAR] — a stale or hand-edited value should cost the *decoration*, not the callout.
     */
    private enum class FrameStyle {
        BAR, OUTLINE, SOLID, NONE, COMMENT;

        companion object {
            fun of(id: String): FrameStyle = when (id) {
                AudioExplainerSettings.CALLOUT_FRAME_OUTLINE -> OUTLINE
                AudioExplainerSettings.CALLOUT_FRAME_SOLID -> SOLID
                AudioExplainerSettings.CALLOUT_FRAME_NONE -> NONE
                AudioExplainerSettings.CALLOUT_FRAME_COMMENT -> COMMENT
                else -> BAR
            }
        }
    }

    /** The callout box's look, resolved from settings once per render. */
    private class CalloutStyle(
        val frame: FrameStyle,
        val arc: Int,
        val barWidth: Int,
        val textScalePercent: Int,
        /** Family for the description text; blank = the editor's own font. Never applies to the example. */
        val proseFont: String,
        val leader: Boolean,
        /** Which way the leader points — the box is below the code it annotates, so the leader goes up. */
        val below: Boolean,
        /**
         * The scheme's own comment colour, used by [FrameStyle.COMMENT] for every block. A colour on a
         * "style" object because it is this frame's whole point: matching a real comment is not something
         * the callout's own palette can express.
         */
        val commentFg: Color,
    )

    /**
     * The relation arrow's look. Held as one object rather than six [Palette] fields because both the
     * renderer and the right-hand reserve need the whole set — the reserve has to know the stroke it is making room
     * for, or the leg it clears is not the leg that gets drawn.
     */
    /**
     * How a step's links are drawn — the settings half of `LinkArt`, resolved once per render.
     *
     * Before the K1 round three of these fields were built and never read: the unified link drawing that
     * replaced the elbow arrow carried its own constants, so the dialog's thickness, arrowhead and square-
     * elbow rows moved a number into `audioExplainer.xml` and stopped there. [metrics] is that wiring — see
     * [LinkMetrics], which explains why the numbers are handed to `LinkArt` rather than read by it.
     */
    private class ArrowStyle(
        /** Base for the derived hue rotation: the accent, exactly as `LinkArt.color` has always taken it. */
        val accent: Color,
        /** Per-slot overrides in slot order; a null entry (or a missing one) means *derive from [accent]*. */
        val slotColors: List<Color?>,
        val labelColor: Color,
        val metrics: LinkMetrics,
        val labels: Boolean,
    ) {
        /** [slot]'s colour: the reader's, if they set one, else the shipped rotation. */
        fun colorFor(slot: Int): Color =
            slotColors.getOrNull(((slot % LinkArt.slots()) + LinkArt.slots()) % LinkArt.slots())
                ?: LinkArt.color(accent, slot)
    }

    /**
     * One independently-wrapped paragraph of a callout box. [maxLines] is per-block, so a long note can't eat
     * the example's room, nor an example the relation pointers'.
     */
    private class CalloutBlock(val text: String, val style: BlockStyle, val maxLines: Int)

    /**
     * A block-inlay callout: an accent-barred box of wrapped text docked above the active highlight.
     *
     * Each of [blocks] is word-wrapped to the viewport at its own line cap and ellipsised past that, so this
     * renderer already handles sentence-length prose — a longer per-highlight note becomes a data change
     * with no rendering work. The wrap is cached per measured width because [calcHeightInPixels] and [paint]
     * must agree on the line count or the box clips.
     */
    private class CalloutRenderer(
        private val blocks: List<CalloutBlock>,
        private val accent: Color,
        private val bg: Color,
        private val fg: Color,
        private val codeFg: Color,
        private val pointerFg: Color,
        private val indent: Int,
        /** Width kept free on the right for this step's arrow legs and labels. */
        private val reserveRight: Int,
        private val style: CalloutStyle,
    ) : EditorCustomElementRenderer {

        /** The accent bar's width where one is drawn, and 0 where it isn't — it also offsets the text. */
        private val barW: Int = if (style.frame == FrameStyle.BAR) style.barWidth else 0

        /** Frames that paint a panel behind the text, i.e. the ones with a background for the leader to share. */
        private val panelFrame: Boolean =
            style.frame != FrameStyle.NONE && style.frame != FrameStyle.COMMENT

        /**
         * Frames whose leader draws its own border. Bar is the exception: its box already carries an accent
         * edge, and a stroked tail hanging off it would read as a second, competing one. Everywhere else the
         * tail has to define itself — either because the box is stroked too (Outline) and an unstroked tail
         * looks unfinished beside it, or because there is no box at all (None, Comment) and an unstroked tail
         * is a shape with nothing to say where it came from.
         */
        private val leaderBorder: Boolean = style.frame != FrameStyle.BAR

        private var cachedWidth = -1

        /** Wrapped lines per [blocks] entry, index-parallel to it. */
        private var cachedLines: List<List<String>> = emptyList()

        /**
         * The wrap [calcHeightInPixels] last reported a height for. The platform measures an inlay once, so
         * if the viewport is resized afterwards the re-wrapped text would no longer fit the reserved height
         * — [paint] detects the drift and asks for a re-measure instead of overflowing the box.
         */
        private var measuredForWidth = -1
        private var measuredLineCounts: List<Int> = emptyList()

        /** One re-measure in flight at a time, so repaints before it lands don't queue a pile of updates. */
        private var updateScheduled = false

        /**
         * The block's font, scaled by `calloutTextScalePercent`: the editor's own family — italic for prose,
         * upright for code and pointers — unless `calloutProseFont` names one, which applies to the
         * *description* text only. [BlockStyle.CODE] never leaves the editor font, because an example is code
         * and code in a proportional face reads as prose about code.
         *
         * Everything measures through [metrics], which derives from this, so a family or scale change moves
         * the wrap, the reserved height and the painted text together.
         */
        private fun font(editor: Editor, block: CalloutBlock): Font {
            val base = editor.colorsScheme.getFont(
                if (block.style == BlockStyle.PROSE) EditorFontType.ITALIC else EditorFontType.PLAIN,
            )
            // An unknown family falls back to a platform default inside `Font` — the right cost for a stale
            // or hand-edited setting: the family, not the callout.
            val named = style.proseFont
                .takeIf { it.isNotEmpty() && block.style != BlockStyle.CODE }
                ?.let { Font(it, base.style, base.size) }
                ?: base
            val scale = style.textScalePercent
            return if (scale == 100) named else named.deriveFont(named.size2D * scale / 100f)
        }

        private fun metrics(editor: Editor, block: CalloutBlock): FontMetrics =
            editor.contentComponent.getFontMetrics(font(editor, block))

        /** In [FrameStyle.COMMENT] every block takes the scheme's comment colour — that is the whole point. */
        private fun color(block: CalloutBlock): Color =
            if (style.frame == FrameStyle.COMMENT) style.commentFg else when (block.style) {
                BlockStyle.PROSE -> fg
                BlockStyle.CODE -> codeFg
                BlockStyle.POINTER -> pointerFg
            }

        /**
         * Per-line prefix, non-empty only in [FrameStyle.COMMENT]. It is real width, so it comes off the wrap
         * budget in [lines] and goes back on in [textWidth]: a prefix charged on one side only is how a box
         * ends up clipping its own last word.
         */
        private val prefix: String = if (style.frame == FrameStyle.COMMENT) "// " else ""

        private fun prefixW(fm: FontMetrics): Int = if (prefix.isEmpty()) 0 else fm.stringWidth(prefix)

        /** Wrapped text for the current viewport width, one list per block. */
        private fun lines(editor: Editor): List<List<String>> {
            val viewport = editor.scrollingModel.visibleArea.width.takeIf { it > 0 } ?: FALLBACK_VIEWPORT
            val avail = (viewport - indent - barW - 2 * PAD_X - GUTTER_SLACK - reserveRight)
                .coerceAtLeast(MIN_TEXT_W)
            if (avail == cachedWidth) return cachedLines
            cachedWidth = avail
            cachedLines = blocks.map {
                val fm = metrics(editor, it)
                wrap(it.text, fm, (avail - prefixW(fm)).coerceAtLeast(MIN_TEXT_W), it.maxLines)
            }
            return cachedLines
        }

        /** Widest line across every block, each measured in its own font and carrying its own prefix. */
        private fun textWidth(editor: Editor, wrapped: List<List<String>>): Int {
            var w = 0
            for ((i, block) in blocks.withIndex()) {
                val fm = metrics(editor, block)
                val pw = prefixW(fm)
                for (line in wrapped.getOrElse(i) { emptyList() }) w = maxOf(w, pw + fm.stringWidth(line))
            }
            return w
        }

        /**
         * Room above the block at position [drawn] in the drawn sequence: nothing for the first, [BLOCK_GAP]
         * between paragraphs, and extra above a worked example so it reads as a separate exhibit rather than
         * one more sentence. Shared by [textHeight] and [paint] — drift between those two is what clips a box.
         */
        private fun gapBefore(block: CalloutBlock, drawn: Int): Int = when {
            drawn == 0 -> 0
            block.style == BlockStyle.CODE -> BLOCK_GAP + EXAMPLE_GAP
            else -> BLOCK_GAP
        }

        /**
         * Height of the wrapped text alone (no padding), including the inter-block gaps. Both
         * [calcHeightInPixels] and [paint] walk the blocks through this, so the reserved height and the
         * painted height cannot drift apart.
         */
        private fun textHeight(editor: Editor, wrapped: List<List<String>>): Int {
            var h = 0
            var drawn = 0
            for ((i, block) in blocks.withIndex()) {
                val n = wrapped.getOrElse(i) { emptyList() }.size
                if (n == 0) continue
                h += gapBefore(block, drawn)
                h += n * metrics(editor, block).height
                drawn++
            }
            return h
        }

        /** Height reserved beyond the box for the leader, and 0 where it is switched off. */
        private fun leaderLen(): Int = if (style.leader) LEADER_LEN else 0

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            return indent + barW + 2 * PAD_X + textWidth(editor, lines(editor))
        }

        /**
         * **Blank room between the box and the code**, for a link fan on the row the box is docked against.
         *
         * Set by [reserveLinkSpace], which then calls `update()`. The box is drawn against the *far* edge of
         * its own region — the top when docked above, below the leader when docked below — so height added
         * here appears on the code's side of it, which is exactly where the rails are. That is what lets a
         * fanned row keep its callout instead of the two being drawn through each other.
         */
        var extraSpace: Int = 0

        override fun calcHeightInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            val wrapped = lines(editor)
            measuredForWidth = cachedWidth
            measuredLineCounts = wrapped.map { it.size }
            updateScheduled = false
            val h = textHeight(editor, wrapped)
            if (h == 0) return 0
            return h + 2 * PAD_Y + 2 * MARGIN_Y + leaderLen() + extraSpace
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val editor = inlay.editor

            // The editor was resized since we were measured: re-wrapping now could need more lines than the
            // reserved height holds. Draw only what fits and ask for a re-measure (off the paint pass —
            // `update()` re-enters layout), which repaints at the right height.
            val wrapped = lines(editor)
            if (cachedWidth != measuredForWidth && !updateScheduled) {
                updateScheduled = true
                ApplicationManager.getApplication().invokeLater {
                    if (inlay.isValid) runCatching { inlay.update() }
                }
            }
            // Clamp each block to the lines the measure pass reserved room for. If we somehow paint before
            // being measured, honour the full wrap rather than drawing nothing.
            val caps = measuredLineCounts.takeIf { it.size == blocks.size } ?: wrapped.map { it.size }
            val shown = wrapped.mapIndexed { i, ls -> ls.take(caps[i]) }

            val textW = textWidth(editor, shown)
            if (textW == 0) return

            val x = targetRegion.x + indent
            // Docked below, the leader points *up* at the code, so it takes the top of the reserved band and
            // the box moves down past it. Docked above (the default) the box keeps the top.
            // Docked below, the extra room bought for a fan goes *above* the box, between it and the code;
            // docked above, the box keeps the top and the room falls out underneath it for free.
            val y = targetRegion.y + MARGIN_Y + if (style.below) leaderLen() + extraSpace else 0
            val w = barW + 2 * PAD_X + textW
            val h = textHeight(editor, shown) + 2 * PAD_Y
            val arc = style.arc

            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                // Decoration only — every style below wraps and paints the same text in the same place.
                when (style.frame) {
                    FrameStyle.BAR -> {
                        g2.color = bg
                        g2.fillRoundRect(x, y, w, h, arc, arc)
                        g2.color = accent
                        g2.fillRect(x, y, barW, h) // left accent bar, echoing the gap gutter cue
                    }
                    FrameStyle.OUTLINE -> {
                        g2.color = bg
                        g2.fillRoundRect(x, y, w, h, arc, arc)
                        g2.color = accent
                        // -1: drawRoundRect's stroke is inclusive of the far edge, so the full width would
                        // land one pixel outside the fill it is supposed to trace.
                        g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc)
                    }
                    FrameStyle.SOLID -> {
                        g2.color = bg
                        g2.fillRoundRect(x, y, w, h, arc, arc)
                    }
                    // Text straight onto the code background. The padding still applies, so switching styles
                    // moves the decoration and never the text. COMMENT is NONE plus the `//` prefix that
                    // [prefix] has already charged to the wrap, and the scheme's comment colour.
                    FrameStyle.NONE, FrameStyle.COMMENT -> {}
                }

                // After the frame, never before: the leader's mouth has to be able to erase the box's own
                // edge across it.
                if (style.leader) drawLeader(g2, x, y, w, h)

                var top = y + PAD_Y
                var drawn = 0
                for ((i, block) in blocks.withIndex()) {
                    val ls = shown[i]
                    if (ls.isEmpty()) continue
                    val gap = gapBefore(block, drawn)
                    // The example's separator, drawn in the middle of the extra room [gapBefore] just gave
                    // it. Suppressed in COMMENT, where a rule through a run of `//` lines would break the one
                    // thing that style sells.
                    if (gap > BLOCK_GAP && style.frame != FrameStyle.COMMENT) {
                        g2.color = blend(fg, bg, EXAMPLE_RULE_BRIGHTNESS)
                        g2.fillRect(x + barW + PAD_X, top + gap / 2, textW, 1)
                    }
                    top += gap
                    val fm = metrics(editor, block)
                    g2.font = font(editor, block)
                    g2.color = color(block)
                    var baseline = top + fm.ascent
                    for (line in ls) {
                        g2.drawString(prefix + line, x + barW + PAD_X, baseline)
                        baseline += fm.height
                    }
                    top += ls.size * fm.height
                    drawn++
                }
            } finally {
                g2.dispose()
            }
        }

        /**
         * The callout's leader: a speech-bubble tail grown out of the box's near edge toward the line it
         * annotates. It lives in the [leaderLen] the measure pass reserved, so it is never drawn over code.
         *
         * Skewed rather than isosceles — the tip drops straight down from the base's leading corner and the
         * far edge slants back up to it — so it reads as leaning out of the box toward the code instead of as
         * a pin holding the box up. It grows out of the box at [LEADER_POS_PERCENT], not at the corner, for
         * the same reason.
         *
         * Two frame-dependent parts, because a tail can only inherit what its box actually paints:
         * - **Fill** ([panelFrame]): the box's own tinted background, so the tail is one silhouette with it.
         *   None and Comment paint no panel, so there is no fill to continue and the tail stays open.
         * - **Border** (every frame but Bar): the two free edges stroked, and the shared edge then redrawn in
         *   the tint. That shared edge is the whole trick — it erases the box's own outline across the mouth
         *   of the tail, which is what turns "outlined box with a triangle stuck under it" into one bubble.
         *   Below full opacity the erase is translucent too, so it thins that edge rather than removing it —
         *   the same bargain the box's own fill makes with the code it covers.
         */
        private fun drawLeader(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
            val len = leaderLen()
            // Never wider than the box's text area, and never hanging past either end of it: a one-word
            // callout would otherwise sprout its tail outside the box it belongs to.
            val wide = minOf(LEADER_W, w - barW)
            val left = (x + w * LEADER_POS_PERCENT / 100).coerceIn(x + barW, x + w - wide)
            val right = left + wide
            // The base sits *on* the frame's own edge (hence h - 1, matching the outline's inclusive stroke)
            // so the two fills meet with no antialiasing seam between them.
            val baseY = if (style.below) y else y + h - 1
            val apexY = if (style.below) baseY - len else baseY + len

            if (panelFrame) {
                g2.color = bg
                val tri = GeneralPath()
                tri.moveTo(left.toFloat(), baseY.toFloat())
                tri.lineTo(left.toFloat(), apexY.toFloat())
                tri.lineTo(right.toFloat(), baseY.toFloat())
                tri.closePath()
                g2.fill(tri)
            }
            if (!leaderBorder) return
            g2.stroke = BasicStroke(LEADER_STROKE)
            g2.color = if (style.frame == FrameStyle.COMMENT) style.commentFg else accent
            // Open path: the free edges only. Closing it here is what would draw the mouth shut.
            val edges = GeneralPath()
            edges.moveTo(left.toFloat(), baseY.toFloat())
            edges.lineTo(left.toFloat(), apexY.toFloat())
            edges.lineTo(right.toFloat(), baseY.toFloat())
            g2.draw(edges)
            if (panelFrame) {
                // One row — [baseY] *is* the frame's own edge row in both dock directions, which is why it was
                // chosen — inset a pixel at each end so the two strokes above keep their own corners, and a
                // fillRect rather than a drawLine because drawLine's endpoint is inclusive and would take the
                // pixel where the slanted edge meets the base.
                //
                // Docked above this is nearly redundant: the tail's own fill starts on that same row and has
                // already covered it. Docked below it is the whole job — the fill stops one row short of the
                // base, so without this the frame's edge runs straight across the mouth.
                g2.color = bg
                g2.fillRect(left + 1, baseY, wide - 2, 1)
            }
        }
    }

    companion object {
        /** Above syntax/semantic highlighting (so the dim foreground wins) but below the selection layer. */
        private val LAYER = HighlighterLayer.SELECTION - 1

        /** Topmost, so the flash reads over the active line's glow instead of being blended under it. */
        private val FLASH_LAYER = HighlighterLayer.LAST

        /** Same reasoning as [FLASH_LAYER]: an arrow drawn under the dim/glow layers would be washed out. */
        private val ARROW_LAYER = HighlighterLayer.LAST

        /**
         * True when nothing will ever signal "audio started", so a step render is the only cue the token
         * flash can have: the user has narration switched off and is stepping through the walkthrough
         * visually.
         *
         * Read from settings rather than tracked as narration state on purpose — it is a user setting, so
         * there is no staleness or cross-thread handoff to get wrong. The one gap it leaves is *engine
         * selected but not yet installed*: narration is silently disabled, so no flash. That state also
         * produces no audio at all and prompts the user to finish setup, so a missing pulse is not the
         * complaint they'd have, and it resolves itself once TTS works.
         *
         * `private` since round 3: it was `internal` because the in-place listener's `refresh()` needed the
         * same rule — flash only when the render did not, and two copies of that condition would have been
         * two places to get it wrong. That listener is gone, and [paintStep] is the only caller left.
         */
        private fun flashOnRender(): Boolean =
            AudioExplainerSettings.getInstance().state.ttsEngine == AudioExplainerSettings.ENGINE_OFF

        /**
         * The user's accent colour, for decoration a **node view** owns rather than this renderer:
         * `CodeNodeView`'s active/non-active node cue and its elision rule.
         *
         * `internal` — unlike [flashOnRender] this one does have callers outside the file (`CodeNodeView`
         * and `LeaderArt` both tint with it), and it is one rule in one place. `TreeOfCode.rules.md`
         * constraint 4 is what makes it a rule rather than a preference: `JBColor.namedColor("Focus.color",
         * …)` rendered dark red in Rider's default scheme and a node tinted with it read as an error band, so
         * every accent in this plugin comes from settings. A second hex parse with its own fallback in the
         * tree package would be a second place for that to drift.
         */
        internal fun accentColor(): Color = parseColor(AudioExplainerSettings.getInstance().state.accentColor)

        /** [blend], exposed for the same callers as [accentColor]: a node-level tint must mix toward the bg. */
        internal fun blendToward(a: Color, b: Color, t: Double): Color = blend(a, b, t)

        // ── Token flash (A3) ──
        /** Fade frames. 12 over the default 700 ms is ~58 ms/frame — smooth without being a repaint storm. */
        private const val FLASH_STEPS = 12

        /** Floor on the frame interval, so a short flash can't ask for a 1 ms repaint loop. */
        private const val MIN_FLASH_INTERVAL_MS = 16

        // ── Callout geometry (unscaled px; the editor font already scales with the IDE) ──
        private const val CALLOUT_PRIORITY = 0

        /**
         * The fan spacer's priority — above the callout's, though nothing should ever depend on it.
         *
         * [reserveLinkSpace] only adds a spacer where the callout is *not* docked, precisely so the platform's
         * ordering of two block inlays at one position never has to be reasoned about. This is here so that if
         * a third party ever does put an inlay there, ours is the one nearer the code it belongs to.
         */
        private const val SPACER_PRIORITY = 100
        private const val MAX_CALLOUT_LINES = 3

        /**
         * Slack left between the callout's edge and the viewport's when [revealCallout] scrolls to it.
         *
         * Constraint 20: a scroll target flush with a viewport edge crosses that edge on any later change, and this
         * surface always has a later change. Deliberately the same number as `CodeNodeView.CALLOUT_MARGIN`, because
         * these are the two halves of one arrangement — the node reserves that margin, and the reveal scrolls to the
         * room the node reserved. Two constants with one meaning is how they drift; if this needs to move, move both.
         */
        private const val CALLOUT_REVEAL_PAD = 4

        /**
         * Cap on the B4 `example` block. It is authored as **one** line; 2 is headroom for a long one-liner
         * wrapping in a narrow editor, not licence to write two examples. The matching authoring rule lives
         * in `plan-schema.md` (see MoreUI.md §2.4 — renderer caps ship as authoring rules).
         */
        private const val MAX_EXAMPLE_LINES = 2

        /** Vertical breathing room between two callout blocks, so note and example read as separate lines. */
        private const val BLOCK_GAP = 3
        private const val PAD_X = 8
        private const val PAD_Y = 4
        private const val MARGIN_Y = 2

        /**
         * Extra room *above* a worked example, on top of [BLOCK_GAP], with a faint rule drawn in the middle of
         * it. An example is a different kind of thing from the prose above it — a demonstration, not another
         * sentence — and at one paragraph gap the two read as one run-on block.
         */
        private const val EXAMPLE_GAP = 6

        /** Blend fraction of the foreground kept for that rule: present, not a second border. */
        private const val EXAMPLE_RULE_BRIGHTNESS = 0.3

        // ── Callout leader ──
        /**
         * How far the callout's leader reaches past the box, reserved beyond it by
         * [CalloutRenderer.calcHeightInPixels]. Long enough to read as a connection, short enough that the
         * box still lands next to its code.
         */
        private const val LEADER_LEN = 9

        /** The leader's base along the box edge. Wider than [LEADER_LEN], so the tail reads as a tail. */
        private const val LEADER_W = 14

        /**
         * Where the leader's tip sits along the box, left to right. A tail under the *start* of the box points
         * back at the code's own indent; centred it reads as a pin holding the box up.
         */
        private const val LEADER_POS_PERCENT = 25

        private const val LEADER_STROKE = 1f

        /**
         * Bounds on `calloutTextScalePercent`. Below the floor the box is unreadable, above the ceiling it
         * stops looking like an annotation of the code and starts competing with it.
         */
        private const val MIN_TEXT_SCALE = 60
        private const val MAX_TEXT_SCALE = 160

        /** Cap on relation pointer lines in the callout; the box already carries a note and an example. */
        private const val MAX_POINTER_LINES = 2

        /** Right-edge breathing room so a full-width callout never collides with the scrollbar. */
        private const val GUTTER_SLACK = 40

        // ── Relations (B3) / links (A4) ───────────────────────────────────────
        /**
         * **Links drawn for one step**, which is the reader's *"support at most 5 simultaneous connections
         * per step"* and is read off [LinkArt.slots] rather than restated — the cap and the palette have to
         * be the same number or a step can run out of colours.
         *
         * It was 3, on the argument that three relations over a four-line highlight is already busy. Round 6
         * raised it because the routing changed underneath the argument: three links used to mean three
         * drawings competing for one channel, and now every link has a rail and a channel of its own. Ships
         * as an authoring rule in `plan-schema.md` (MoreUI.md §2.4); anything past it is logged at debug and
         * not drawn.
         */
        private val MAX_RELATIONS_PER_STEP = LinkArt.slots()

        /**
         * Line distance beyond which a same-document link is drawn as a **portal at each end** rather than as
         * one line. A leg only reads as a connection when both ends can be seen at once; past a screenful it
         * becomes a line disappearing off the top of the viewport, which says less than a portal does.
         *
         * The name is inherited and kept: it is the same number, doing the same job, for the drawing that
         * replaced the arrow.
         */
        private const val MAX_ARROW_SPAN = 40

        /** Elbow corner radius when the corners aren't squared off by `arrowSquareElbow`. */

        /** Fraction of the accent mixed into the scheme foreground for pointer text and link labels. */
        private const val POINTER_TEXT_ACCENT = 0.45

        // ── The drawn link (A4) ──
        /**
         * Where a half's connector leaves its token, **chosen off the bench's PNGs** rather than argued.
         *
         * The codebase already carried a finding against a token anchor: the arrow this replaced used line
         * ends precisely so a leg never crosses code. `probes/LinkBench.java` rendered all three of
         * [LinkRoute] side by side at both measured node widths, and the finding held — [LinkRoute.STRAIGHT]
         * draws a solid line through the rest of the row, which reads as strikethrough on the code it is
         * annotating.
         *
         * A `LINE_END` variant fixed that and cost more than it saved: with the connector starting past the
         * text, a row whose code is wider than the channel had nowhere to start, so the link was **dropped** —
         * eight halves in the bench, including every one on the 90-character fixture line. A `DASHED` one
         * shipped next and was judged *"more or less ok"* — followed by the right question, which was
         * whether the crossing has to happen at all. It does not. The bench counted the ink each variant put
         * on top of a glyph:
         *
         * | route | ink on glyphs |
         * |---|---|
         * | straight | 234 px |
         * | dashed | 160 px |
         * | **through the leading** | **4 px** |
         * | from the line's end | 0 px, and drops 8 halves |
         *
         * [LinkRoute.DIRECTIONAL] is that winning route with the side no longer a matter of taste: it is
         * the **top** leading for a target below and the **bottom** leading for one above, so the drawing
         * carries the link's direction instead of throwing it away. Round 3 chose *above* for every link on
         * the argument that an IDE has already spent the underline — a rule under code means error, warning
         * or hyperlink — which held right up until the line had something better to say with its side.
         *
         * [LinkRoute.STRAIGHT] stays only as the bench's control. See [LinkRoute].
         */
        private val LINK_ROUTE = LinkRoute.DIRECTIONAL

        /** Slack left of a link's own ink when a hover repaints it, so an antialiased edge is included. */
        private const val LINK_REPAINT_PAD = 6

        /** Shared no-op result, so the common "no relations at all" path allocates nothing. */
        private val NO_RELATIONS = ResolvedRelations(emptyList(), emptyList())

        /** Used before the editor has a laid-out viewport (visibleArea is 0 on the first paint). */
        private const val FALLBACK_VIEWPORT = 700
        private const val MIN_TEXT_W = 120

        /** Blend fraction of the editor foreground kept for callout body text (softer than code). */
        private const val CALLOUT_TEXT_BRIGHTNESS = 0.85

        /**
         * How much of the primary tier a [FrameRole.HELPER] frame keeps — its glow and its callout tint are
         * scaled by this.
         *
         * 0.45 rather than something fainter for the reason the link dimming was raised from 0.45 to 0.82 in
         * round 6: a decoration pulled too far back stops reading as *lit* and starts reading as *broken*,
         * and a helper frame genuinely is part of the step. It is a ratio on the user's own glow percentage
         * rather than an absolute colour, so turning the glow down keeps both tiers in proportion.
         */
        const val HELPER_GLOW_MIX = 0.45

        /**
         * Greedy word wrap to [maxWidth], capped at [maxLines] with an ellipsis on the last line. A single
         * word longer than the line is left overlong rather than broken — truncating an identifier mid-way
         * reads worse than letting it run.
         */
        private fun wrap(text: String, fm: FontMetrics, maxWidth: Int, maxLines: Int): List<String> {
            val words = text.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }
            if (words.isEmpty()) return emptyList()
            val out = mutableListOf<String>()
            var cur = ""
            for (word in words) {
                val candidate = if (cur.isEmpty()) word else "$cur $word"
                when {
                    cur.isEmpty() || fm.stringWidth(candidate) <= maxWidth -> cur = candidate
                    out.size + 1 < maxLines -> {
                        out += cur
                        cur = word
                    }
                    // Out of lines: drop the rest and mark the truncation.
                    else -> return out + ellipsize(cur, fm, maxWidth)
                }
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }

        /**
         * The first non-blank line of [s], marked with an ellipsis when more followed; `""` for null/blank.
         *
         * [wrap] splits on `'\n'` along with spaces, so handing it a multi-line snippet would reflow the
         * whole thing into one run-on paragraph — destroying the line structure that made it a snippet.
         * Keeping line 1 and *showing* that something was cut is the honest degradation, and it matches the
         * one-line rule `plan-schema.md` states for `example`. Real multi-line blocks need their own
         * renderer (MoreUI.md §4.3 B4).
         */
        private fun firstLine(s: String?): String {
            val lines = s?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return ""
            val head = lines.firstOrNull() ?: return ""
            return if (lines.size > 1) "$head …" else head
        }

        /**
         * Offset just past the last non-blank character of [line] — where a relation arrow leaves the code
         * without crossing it. Anchoring at the endpoint *token* instead would run the horizontal leg back
         * over the rest of that line's text, so the token validates and names the endpoint ([Endpoint]) while
         * the arrow always departs from the end of the line.
         */
        private fun trimmedLineEnd(doc: Document, line: Int): Int {
            val start = doc.getLineStartOffset(line)
            var end = doc.getLineEndOffset(line)
            val chars = doc.charsSequence
            while (end > start && chars[end - 1].isWhitespace()) end--
            return end
        }

        /**
         * True when [file] names the same file as [segmentFile], tolerating the relative form an agent is
         * likely to write for a sibling file. Case-insensitive, which is right on Windows and harmless
         * elsewhere: the worst a false negative costs is an arrow degrading to a pointer.
         *
         * `internal` because [com.audioexplainer.tree.TreePlanView] answers the same question about the same
         * strings — is this endpoint's file the one that node is showing? — and two copies of a path-matching
         * rule agree only until one of them is edited.
         */
        internal fun sameFile(file: String, segmentFile: String): Boolean = sameFilePath(file, segmentFile)

        private fun ellipsize(s: String, fm: FontMetrics, maxWidth: Int): String {
            var t = s
            while (t.isNotEmpty() && fm.stringWidth("$t…") > maxWidth) t = t.dropLast(1)
            return "$t…"
        }

        /**
         * Offsets of the [n]-th occurrence of [needle] in `text[from, to]`, or null. When [wholeWord], a hit
         * must not be flanked by identifier characters, so `rate` doesn't match inside `rateTable`.
         *
         * Occurrences are counted **among qualifying matches only** — with `wholeWord`, `occurrence: 2` means
         * the second standalone `rate`, not the second textual one. That is the reading an author intends.
         */
        private fun findNth(
            text: CharSequence,
            needle: String,
            from: Int,
            to: Int,
            n: Int,
            wholeWord: Boolean,
        ): TextRange? {
            var hits = 0
            var i = from.coerceAtLeast(0)
            while (i <= to) {
                val idx = text.indexOf(needle, i)
                if (idx < 0 || idx + needle.length > to) return null
                if (!wholeWord || isWholeWord(text, idx, needle.length)) {
                    hits++
                    if (hits == n) return TextRange(idx, idx + needle.length)
                }
                i = idx + 1
            }
            return null
        }

        private fun isWholeWord(text: CharSequence, start: Int, len: Int): Boolean {
            val before = if (start > 0) text[start - 1] else ' '
            val after = if (start + len < text.length) text[start + len] else ' '
            return !isIdentifierPart(before) && !isIdentifierPart(after)
        }

        /** Identifier characters across the languages we care about (`_` and `$` included, `-` not). */
        private fun isIdentifierPart(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '$'

        /** Gutter analogue of the gap layer's dotted gold left border; colour/width come from settings. */
        private fun gutterRenderer(color: Color, width: Int): LineMarkerRenderer =
            object : LineMarkerRenderer {
                override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
                    g.color = color
                    g.fillRect(r.x, r.y, width.coerceAtLeast(1), r.height)
                }
            }

        /**
         * The colour this editor's scheme paints comments in, for [FrameStyle.COMMENT]. Line comments first,
         * then block comments (a scheme may colour only one), then [fallback] — the callout's own body
         * colour, so an exotic scheme costs the disguise rather than the text.
         */
        private fun commentFg(scheme: EditorColorsScheme, fallback: Color): Color =
            scheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT)?.foregroundColor
                ?: scheme.getAttributes(DefaultLanguageHighlighterColors.BLOCK_COMMENT)?.foregroundColor
                ?: fallback

        /** Parse a `RRGGBB` (or `#RRGGBB`) hex string; falls back to the default accent on garbage. */
        private fun parseColor(hex: String): Color = try {
            Color(hex.trim().removePrefix("#").toInt(16))
        } catch (_: Exception) {
            Color(AudioExplainerSettings.DEF_ACCENT_COLOR.toInt(16))
        }

        /**
         * The per-slot link colours, as `LinkArt.SLOTS` entries where **null means derive**.
         *
         * Deliberately not [parseColor]: that falls back to the accent on anything it cannot read, and here
         * "unreadable" and "blank" must both come out as *no override* rather than as a real colour that
         * happens to be the accent — otherwise a slot the reader never touched would pin itself to slot 0's
         * hue and the rotation would collapse. Padded and truncated to [LinkArt.slots] so a settings file
         * written before this existed, or edited by hand, is a shorter list rather than an exception.
         */
        private fun slotColors(stored: List<String>): List<Color?> = List(LinkArt.slots()) { i ->
            stored.getOrNull(i)?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }?.let {
                try {
                    Color(it.toInt(16))
                } catch (_: Exception) {
                    null
                }
            }
        }

        /**
         * A 0-100 opacity setting as an alpha byte. 0 is allowed and is not the same as hiding the callout:
         * only the box's *fill* carries this alpha, so at 0 the text, the accent bar and the outline all
         * still draw — which is the one look no frame style offers on its own.
         */
        private fun alpha255(percent: Int): Int = (percent.coerceIn(0, 100) * 255 / 100)

        /** result = a·t + b·(1-t), component-wise, clamped to [0,255]. */
        private fun blend(a: Color, b: Color, t: Double): Color {
            fun mix(x: Int, y: Int) = (x * t + y * (1 - t)).toInt().coerceIn(0, 255)
            return Color(mix(a.red, b.red), mix(a.green, b.green), mix(a.blue, b.blue))
        }
    }
}
