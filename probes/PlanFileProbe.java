import com.audioexplainer.*;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonKt;
import kotlin.Unit;
import java.nio.file.*;

/**
 * Runs the real loader over the two hand-written acceptance plans, so a mis-numbered keep range is caught
 * in three seconds rather than in a four-minute sandbox launch. Structure only — whether line 86 really
 * holds that call is a question for the VFS, and the tree view answers it.
 */
public final class PlanFileProbe {
    public static void main(String[] args) throws Exception {
        // Mirrors ExplainerServer's own Json: `type` is a message field, not a Plan field, so a strict
        // decoder would reject every real payload.
        Json json = JsonKt.Json(Json.Default, b -> { b.setIgnoreUnknownKeys(true); return Unit.INSTANCE; });
        int bad = 0;
        for (String path : args) {
            String text = Files.readString(Path.of(path));
            Plan plan = json.decodeFromString(Plan.Companion.serializer(), text);
            ResolvedPlan r = PlanLoader.INSTANCE.resolve(plan);
            Outline o = PlanOutline.INSTANCE.of(r);
            int groups = 0, rows = 0;
            for (OutlineScene sc : o.getScenes()) {
                groups += sc.getGroups().size();
                for (OutlineGroup g : sc.getGroups()) rows += g.getSteps().size();
            }
            System.out.println("── " + path);
            System.out.println("   frames=" + r.getFrames().size() + "/" + plan.getFrames().size()
                    + " steps=" + r.getSteps().size() + "/" + plan.getSteps().size()
                    + " scenes=" + r.getScenes().size()
                    + " rebuilds=" + r.sceneTransitions());
            // The outline the sidebar will draw: scene rows (only when there is more than one), frame
            // groups, and one row per step. `rows != steps` would mean the reader cannot reach a step.
            System.out.println("   outline: sceneRows=" + o.getScenes().size()
                    + " scened=" + o.getScened()
                    + " groups=" + groups
                    + " stepRows=" + rows + "/" + r.getSteps().size()
                    + (rows == r.getSteps().size() ? "" : "  <-- UNREACHABLE STEP(S)"));
            if (rows != r.getSteps().size()) bad++;
            for (Scene sc : r.getScenes()) {
                System.out.println("   scene " + sc.getId()
                        + ": frames=" + sc.getFrames().size()
                        + " steps=" + sc.getStepIndices().size()
                        + " keptLines=" + sc.keptLines()
                        + (sc.keptLines() > PlanLoader.LINE_BUDGET ? "  <-- OVER BUDGET" : ""));
            }
            for (ResolvedFrame f : r.getFrames()) {
                System.out.println("   frame " + f.getId() + " s" + f.getScene() + " d" + f.getDepth()
                        + " parent=" + f.getParentId() + " nests=" + f.nests()
                        + " keptLines=" + f.keptLines()
                        + " focus=" + (f.getFocus() == null ? "-" : f.getFocus().getStart() + "-" + f.getFocus().getEnd()));
            }
            // **Every step must resolve to a scene**, and a step whose scene is unknown would mount nothing
            // and paint into an empty tree — the one new failure mode the scene model introduces, so it is
            // asserted here rather than left to the eye.
            for (int i = 0; i < r.getSteps().size(); i++) {
                if (r.sceneOfStep(i) == null) {
                    System.out.println("   step " + (i + 1) + " resolves to NO scene");
                    bad++;
                }
            }
            for (PlanIssue i : r.getIssues()) {
                System.out.println("   " + i);
                if (i.getLevel() != IssueLevel.INFO) bad++;
            }
            if (r.getIssues().isEmpty()) System.out.println("   no issues");
        }
        System.out.println();
        System.out.println(bad == 0 ? "PASS — no error/warn issues in any plan" : "FAIL — " + bad + " error/warn issue(s)");
        if (bad > 0) System.exit(1);
    }
}
