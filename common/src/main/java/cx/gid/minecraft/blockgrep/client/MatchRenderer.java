package cx.gid.minecraft.blockgrep.client;

import cx.gid.minecraft.blockgrep.pattern.PatternScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a box around every match, in the color of the pattern that found it.
 *
 * Emission happens during the client tick, which the game already wraps in a
 * gizmo collection scope; the collected gizmos are drained and drawn by the
 * renderer for the following frame. That means this class needs no render hook
 * and no mixin — it just asks for boxes once per tick.
 *
 * Because gizmos live only for the tick that emitted them, every visible match
 * must be re-emitted on each tick. That is cheap: it is a few hundred small
 * objects, against a scan that is deliberately much rarer.
 */
public final class MatchRenderer {

    private MatchRenderer() {}

    /**
     * Slight outward inflation so the box sits just outside the block surface.
     * Without it the outline z-fights with the block faces it traces.
     */
    private static final double INFLATE = 0.002;

    /**
     * Emits one box per match, styled by the pattern that found it.
     *
     * Every aspect of the appearance — both colors, thickness, and whether the
     * box x-rays through what is in front of it — comes from the match's own
     * pattern. That is what keeps several simultaneous searches tellable apart,
     * and lets one search see through obstructions while another does not.
     */
    public static void emit(List<GrepState.Hit> hits) {
        if (hits.isEmpty()) {
            return;
        }

        // Styles are cached per pattern rather than rebuilt per match: a scan can
        // return hundreds of hits but only a handful of distinct patterns, and
        // the style is immutable so one instance serves every box that shares it.
        Map<ActivePattern, GizmoStyle> styles = new HashMap<>();

        for (GrepState.Hit hit : hits) {
            ActivePattern source = hit.source();
            GizmoStyle style = styles.computeIfAbsent(source, pattern ->
                GizmoStyle.strokeAndFill(
                    pattern.strokeColor(),
                    pattern.strokeWidth(),
                    pattern.fillColor()));

            PatternScanner.Match match = hit.match();
            BlockPos origin = match.origin();
            AABB box = new AABB(
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                origin.getX() + match.sizeX(),
                origin.getY() + match.sizeY(),
                origin.getZ() + match.sizeZ()
            ).inflate(INFLATE);

            var properties = Gizmos.cuboid(box, style);
            if (source.xray()) {
                properties.setAlwaysOnTop();
            }
        }
    }
}
