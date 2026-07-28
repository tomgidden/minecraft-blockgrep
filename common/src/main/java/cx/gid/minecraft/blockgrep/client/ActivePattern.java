package cx.gid.minecraft.blockgrep.client;

import cx.gid.minecraft.blockgrep.pattern.Pattern;
import cx.gid.minecraft.blockgrep.pattern.Symmetry;

/**
 * One pattern being searched for, together with how it should be drawn.
 *
 * Several of these are live at once, which is why the whole appearance travels
 * with the pattern rather than sitting in global settings: with more than one
 * search running, shared colors would make the results indistinguishable, and a
 * shared x-ray setting would force the same answer on searches asking
 * quite different questions.
 *
 * This is the compiled, ready-to-scan form. The saved form is a text spec that
 * is re-parsed against the live block registry each time it is loaded; see the
 * config package. Instances are immutable so the scan can hold one while the
 * player edits the underlying entry.
 */
public record ActivePattern(String name,
                            Pattern pattern,
                            Symmetry symmetry,
                            int strokeColor,
                            int fillColor,
                            float strokeWidth,
                            boolean xray) {
}
