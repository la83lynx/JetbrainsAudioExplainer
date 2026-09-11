import com.audioexplainer.SpotlightRenderer;
import com.intellij.openapi.util.TextRange;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The token anchor's boundary behaviour (B1a) — the last thing in `MoreUI.md` §2.7 that had been traced by
 * hand and never asserted.
 *
 * It calls the REAL `SpotlightRenderer.findNth`, the same one `resolveToken` runs twice (whole-word, then
 * bare substring), so like every probe here it cannot drift from the code it measures: a rename breaks it
 * loudly rather than letting it agree with a copy.
 *
 * **Reflection, deliberately.** `findNth` is private and should stay private — nothing outside the renderer
 * has any business calling it, and widening it to be measurable would be the measurement changing the thing
 * measured. What is reached is the production method itself, not a transcription of it.
 *
 * **What this cannot reach**: `resolveToken` needs an `Editor` for its `Document`, so the empty-needle guard
 * (`token?.trim()?.takeIf { it.isNotEmpty() }`) and the whole-word-then-substring composition are read, not
 * asserted. Each *pass* is asserted here; the order they run in is one line of `resolveToken`.
 *
 * Every absence assertion sits beside a case where the thing is present, so a null cannot come from an
 * instrument that is simply blind.
 */
public final class TokenProbe {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (ok) pass++; else fail++;
    }

    // ── the real findNth, reached through the companion it is private to ──
    private static final Object COMPANION;
    private static final Method FIND_NTH;

    static {
        try {
            Field f = SpotlightRenderer.class.getDeclaredField("Companion");
            f.setAccessible(true);
            COMPANION = f.get(null);
            FIND_NTH = COMPANION.getClass().getDeclaredMethod("findNth",
                    CharSequence.class, String.class, int.class, int.class, int.class, boolean.class);
            FIND_NTH.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static TextRange find(String text, String needle, int from, int to, int n, boolean wholeWord) {
        try {
            return (TextRange) FIND_NTH.invoke(COMPANION, text, needle, from, to, n, wholeWord);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("findNth moved — this probe measures a method that no longer exists", e);
        }
    }

    /** The whole of [text] as the search window, which is what `resolveToken` passes for a one-line step. */
    static TextRange whole(String text, String needle, int n, boolean wholeWord) {
        return find(text, needle, 0, text.length(), n, wholeWord);
    }

    static boolean at(TextRange r, int start, int end) {
        return r != null && r.getStartOffset() == start && r.getEndOffset() == end;
    }

    public static void main(String[] args) {
        System.out.println("TokenProbe - SpotlightRenderer.findNth, the B1a anchor's boundaries");
        System.out.println();

        // ── 1. the window's far edge ─────────────────────────────────────────
        // `to` is the line's end offset, so a token that *finishes* on the last character of the last line
        // has to resolve. The guard is `idx + needle.length > to`, i.e. exclusive-end arithmetic against an
        // exclusive-end offset — the off-by-one that would silently drop every token at a line end.
        String abcdef = "abc def";                                    // len 7, "def" at [4,7)
        check("a token ending exactly at `to` resolves",
                at(find(abcdef, "def", 0, 7, 1, false), 4, 7));
        check("  control: one character short of it does not",
                find(abcdef, "def", 0, 6, 1, false) == null);
        check("a token starting at `to` is rejected",
                find(abcdef, "d", 0, 4, 1, false) == null);
        check("  control: one character past it resolves",
                at(find(abcdef, "d", 0, 5, 1, false), 4, 5));

        // ── 2. progress ──────────────────────────────────────────────────────
        // The scan advances by `i = idx + 1`, not by the needle's length, so overlapping hits are all
        // counted AND the loop cannot stand still. Both halves matter: length-stepping would miss the
        // second `aa`, and a zero-length step would spin.
        check("overlapping matches are each counted (3rd `aa` in `aaaa`)",
                at(whole("aaaa", "aa", 3, false), 2, 4));
        check("  control: there is no 4th",
                whole("aaaa", "aa", 4, false) == null);

        // The empty needle: `indexOf("", i)` answers `i` forever, so a length-step would never terminate.
        // `i = idx + 1` is why it does. It is blocked upstream anyway (`resolveToken` drops a blank token
        // before it gets here) — but the loop is safe on its own, which is what this asserts.
        check("an empty needle terminates rather than spinning - zero-width hit at `from`",
                at(whole("abc", "", 1, false), 0, 0));
        check("  and whole-word rejects it, because both flanks are identifier characters",
                whole("abc", "", 1, true) == null);

        // ── 3. occurrence counts QUALIFYING matches ──────────────────────────
        // The reading an author intends: `occurrence: 2` under whole-word is the second *standalone* hit,
        // not the second textual one. This is the pair that shows the counter lives inside the filter.
        String rates = "rate rateTable rate";                          // [0,4) [5,14) [15,19)
        check("occurrence counts qualifying matches only - whole-word #2 is the standalone one",
                at(whole(rates, "rate", 2, true), 15, 19));
        check("  control: the same #2 without whole-word is the one inside `rateTable`",
                at(whole(rates, "rate", 2, false), 5, 9));
        check("  control: there is no qualifying #3",
                whole(rates, "rate", 3, true) == null);

        // ── 4. what counts as a word boundary ────────────────────────────────
        // `_` and `$` are identifier characters across the languages this plugin reads; `-` is not.
        check("`_` flanks an identifier, so `_rate` is not a whole-word `rate`",
                whole("_rate", "rate", 1, true) == null);
        check("`$` does too, on the trailing side",
                whole("rate$", "rate", 1, true) == null);
        check("  control: `-` does not, so `a-rate-b` is",
                at(whole("a-rate-b", "rate", 1, true), 2, 6));

        // ── 5. why resolveToken needs two passes ─────────────────────────────
        // A non-identifier anchor is the case the whole-word pass cannot serve: `Parse(` is flanked by `.`
        // on the left (fine) and `x` on the right (an identifier character), so it qualifies only as a bare
        // substring. This pair is the whole argument for the fallback, measured.
        String call = "DateOnly.Parse(x)";
        check("a non-identifier anchor can fail whole-word (`Parse(` before an identifier)",
                whole(call, "Parse(", 1, true) == null);
        check("  and the substring pass is what resolves it",
                at(whole(call, "Parse(", 1, false), 9, 15));

        // ── 6. the cheap guards ──────────────────────────────────────────────
        check("`from` below zero is clamped rather than throwing",
                at(find(abcdef, "abc", -5, 7, 1, false), 0, 3));
        check("  control: a needle that is not there is null, not a throw",
                whole(abcdef, "xyz", 1, false) == null);

        System.out.println();
        System.out.println(pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
