package cx.gid.minecraft.blockgrep.pattern;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which orientations of a pattern count as a match.
 *
 * Written as up to three axis letters, optionally followed by {@code +} and a
 * further set of axis letters. Before the {@code +}, a lowercase letter means
 * the pattern may be rotated about that axis and an uppercase or absent letter
 * pins it. After it, each letter names a mirror plane by its normal: {@code +x}
 * is the east-west flip, {@code +y} the top-to-bottom flip, {@code +z} the
 * north-south one.
 *
 * So {@code y} — equivalently {@code XyZ} — is the historical behaviour of
 * matching all four compass orientations, {@code XYZ} or the empty spec matches
 * only as written, {@code xyz} matches at all 24 rotations of the cube, and
 * {@code xyz+xyz} matches all 48 orientations including reflections.
 *
 * Mirrors are not reachable from rotations — an L-shape and its mirror image are
 * different shapes no matter how you turn them — so they must be asked for
 * separately rather than falling out of a free axis. Conversely, mirrors are
 * only interchangeable *because* of rotation: with the four turns of {@code y}
 * in hand, {@code +x} and {@code +z} denote the same eight orientations, but
 * with no rotation at all they are genuinely different flips.
 *
 * Two shorthands are accepted for the common cases: {@code h} for both
 * horizontal mirrors ({@code +xz}) and {@code v} for the vertical one
 * ({@code +y}), so {@code yh} reads as "four turns, and it may be flipped
 * horizontally".
 *
 * An earlier syntax spelled the mirror flag {@code +m}. It is deliberately no
 * longer accepted: it meant "every mirror", which under that grammar could only
 * reach the horizontal flips, so silently reinterpreting it now would widen an
 * existing pattern to match more than it used to. A spec that still says
 * {@code +m} therefore fails loudly and is reported to the player.
 *
 * Nothing above is special-cased. Axes are not independent under composition —
 * rotations about two different axes generate rotations about the third — so the
 * group is built by closing the chosen generators, and the spec therefore cannot
 * disagree with the orientations actually searched.
 */
public final class Symmetry {

    /** Matches only in the orientation the pattern was written. */
    public static final Symmetry NONE =
        new Symmetry(false, false, false, false, false, false);

    /** The four compass orientations: the default, and what {@code rows} did. */
    public static final Symmetry YAW =
        new Symmetry(false, true, false, false, false, false);

    /** The four compass orientations plus horizontal flips. */
    /** Every rotation and mirror: the largest group. */
    public static final Symmetry YAW_MIRROR =
        new Symmetry(false, true, false, true, false, true);

    /** Every rotation of the cube. */
    public static final Symmetry ALL_ROTATIONS =
        new Symmetry(true, true, true, false, false, false);

    /** Every orientation of the cube, reflections included. */
    public static final Symmetry ALL = new Symmetry(true, true, true, true, true, true);

    private final boolean freeX;
    private final boolean freeY;
    private final boolean freeZ;

    /** Mirror planes, named by the axis normal to each. */
    private final boolean mirrorX;
    private final boolean mirrorY;
    private final boolean mirrorZ;

    /**
     * The group this spec denotes, computed once at construction.
     *
     * Held here rather than recomputed per scan because a scan asks for it on
     * every candidate position; there are at most 48 elements, so the closure is
     * trivial to compute and well worth caching.
     */
    private final List<Transform> transforms;

    public Symmetry(boolean freeX, boolean freeY, boolean freeZ,
                    boolean mirrorX, boolean mirrorY, boolean mirrorZ) {
        this.freeX = freeX;
        this.freeY = freeY;
        this.freeZ = freeZ;
        this.mirrorX = mirrorX;
        this.mirrorY = mirrorY;
        this.mirrorZ = mirrorZ;
        this.transforms = List.copyOf(generate());
    }

    public boolean freeX() { return freeX; }
    public boolean freeY() { return freeY; }
    public boolean freeZ() { return freeZ; }
    public boolean mirrorX() { return mirrorX; }
    public boolean mirrorY() { return mirrorY; }
    public boolean mirrorZ() { return mirrorZ; }

    /** Whether any reflection at all is permitted. */
    public boolean hasMirror() {
        return mirrorX || mirrorY || mirrorZ;
    }

    /** Whether only the written orientation matches. */
    public boolean isIdentityOnly() {
        return transforms.size() == 1;
    }

    /**
     * The orientations to search, always including the identity first.
     *
     * Callers apply these to a pattern and deduplicate the results; a symmetric
     * shape collapses to fewer distinct patterns than there are transforms here.
     */
    public List<Transform> transforms() {
        return transforms;
    }

    /**
     * Closes the chosen generators into a full group.
     *
     * Starting from the identity, each generator is applied to every element
     * found so far until nothing new appears. This is why {@code xy} and
     * {@code xyz} produce identical results without either being special-cased.
     */
    private List<Transform> generate() {
        List<Transform> generators = new ArrayList<>(4);
        if (freeX) {
            generators.add(Transform.ROT_X);
        }
        if (freeY) {
            generators.add(Transform.ROT_Y);
        }
        if (freeZ) {
            generators.add(Transform.ROT_Z);
        }
        // Each requested mirror is its own generator. Where rotations make two
        // of them equivalent the closure discovers that by itself, and where
        // there is no rotation to do so they stay distinct — which is the whole
        // reason mirrors are named per axis rather than lumped together.
        if (mirrorX) {
            generators.add(Transform.MIRROR_X);
        }
        if (mirrorY) {
            generators.add(Transform.MIRROR_Y);
        }
        if (mirrorZ) {
            generators.add(Transform.MIRROR_Z);
        }

        // LinkedHashSet keeps the identity first and the enumeration order
        // stable, so match reporting does not shuffle between runs.
        Set<Transform> group = new LinkedHashSet<>();
        group.add(Transform.IDENTITY);

        List<Transform> frontier = new ArrayList<>(group);
        while (!frontier.isEmpty()) {
            List<Transform> next = new ArrayList<>();
            for (Transform element : frontier) {
                for (Transform generator : generators) {
                    Transform composed = element.then(generator);
                    if (group.add(composed)) {
                        next.add(composed);
                    }
                }
            }
            frontier = next;
        }

        return new ArrayList<>(group);
    }

    /**
     * Parses a spec such as {@code "y"}, {@code "XyZ"}, {@code "yh"} or
     * {@code "xyz+xy"}.
     *
     * Each rotation axis may appear at most once, in either case and in any
     * order. An empty spec is {@link #NONE}, since every absent letter is
     * pinned.
     */
    public static Symmetry parse(String spec) throws BlockPredicates.ParseException {
        String rotationPart = spec;
        String mirrorPart = "";

        int plus = spec.indexOf('+');
        if (plus >= 0) {
            rotationPart = spec.substring(0, plus);
            mirrorPart = spec.substring(plus + 1);
            if (mirrorPart.isEmpty()) {
                throw new BlockPredicates.ParseException(
                    "'+' must be followed by the mirror planes to allow, such as "
                        + "'+y' for a top-to-bottom flip or '+xz' for horizontal ones");
            }
        }

        boolean[] rotations = new boolean[3];
        boolean[] mirrors = new boolean[3];
        boolean[] seenRotation = new boolean[3];

        // The rotation section, where v and h are shorthands that also imply
        // mirrors. Taking them here rather than after the '+' is what lets a
        // spec read as the single word "yh".
        for (int i = 0; i < rotationPart.length(); i++) {
            char c = rotationPart.charAt(i);
            int axis = axisIndex(c);
            if (axis >= 0) {
                if (seenRotation[axis]) {
                    throw duplicate(Character.toLowerCase(c));
                }
                seenRotation[axis] = true;
                rotations[axis] = Character.isLowerCase(c);
            } else if (c == 'h' || c == 'H') {
                // Both horizontal mirrors: with no rotation they are distinct
                // flips, and with a free vertical axis they coincide anyway.
                mirrors[0] = true;
                mirrors[2] = true;
            } else if (c == 'v' || c == 'V') {
                mirrors[1] = true;
            } else {
                throw new BlockPredicates.ParseException(
                    "'" + c + "' is not understood in a symmetry spec — use x, y and z "
                        + "for rotation axes (lowercase to allow), 'h' or 'v' for "
                        + "horizontal or vertical mirroring, or '+' followed by the "
                        + "mirror planes to name them individually");
            }
        }

        // The mirror section: plain axis letters naming planes by their normal,
        // case-insensitively, since an "uppercase mirror" would mean nothing.
        for (int i = 0; i < mirrorPart.length(); i++) {
            char c = mirrorPart.charAt(i);
            int axis = axisIndex(c);
            if (axis >= 0) {
                mirrors[axis] = true;
            } else if (c == 'h' || c == 'H') {
                mirrors[0] = true;
                mirrors[2] = true;
            } else if (c == 'v' || c == 'V') {
                mirrors[1] = true;
            } else {
                throw new BlockPredicates.ParseException(
                    "'" + c + "' is not a mirror plane — name planes by their normal "
                        + "axis (x, y or z), or use 'h' for both horizontal planes "
                        + "and 'v' for the vertical one");
            }
        }

        return new Symmetry(rotations[0], rotations[1], rotations[2],
            mirrors[0], mirrors[1], mirrors[2]);
    }

    /** The axis index for a letter, or -1 when it is not an axis letter. */
    private static int axisIndex(char c) {
        return switch (c) {
            case 'x', 'X' -> 0;
            case 'y', 'Y' -> 1;
            case 'z', 'Z' -> 2;
            default -> -1;
        };
    }

    /**
     * Whether a token looks like a symmetry spec rather than a cell predicate.
     *
     * Lets the spec stay optional as the first word of a pattern command. Block
     * ids are longer than three characters and contain characters outside
     * {@code xyz}, so the only real collision would be a block named literally
     * "x" — which does not exist, and which a player could still write as
     * {@code minecraft:x} to disambiguate.
     */
    public static boolean looksLikeSpec(String token) {
        if (!isSymmetryShaped(token)) {
            return false;
        }
        try {
            parse(token);
            return true;
        } catch (BlockPredicates.ParseException e) {
            return false;
        }
    }

    /**
     * Whether a token is in the shape of a symmetry spec, valid or not.
     *
     * Deliberately broader than {@link #looksLikeSpec}: it accepts specs that
     * will fail to parse, so a caller can commit to reading a token as symmetry
     * and then report why it is wrong. Without that distinction a typo such as
     * {@code y+q} would be quietly reinterpreted as a block name.
     *
     * The test is that the token is short, contains no character that could not
     * appear in a spec, and — when it is a bare word — is not a plausible block
     * name. Block ids contain letters outside {@code xyzhv} or a separator, so
     * the only collisions are very short words, which the length cap excludes.
     */
    public static boolean isSymmetryShaped(String token) {
        if (token.isEmpty() || token.length() > 9) {
            return false;
        }
        // A '+' cannot appear in a block id, tag or predicate, so its presence
        // alone settles the question — including for a spec that will not parse,
        // which is exactly the case this method exists to catch.
        if (token.indexOf('+') >= 0) {
            return true;
        }

        // Otherwise every character must be one a spec can contain. That leaves
        // short words such as "yh" or "XYZ", which no block is named.
        for (int i = 0; i < token.length(); i++) {
            if ("xXyYzZhHvV".indexOf(token.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static BlockPredicates.ParseException duplicate(char axis) {
        return new BlockPredicates.ParseException(
            "axis '" + axis + "' appears twice in the symmetry spec");
    }

    /**
     * The spec as the player would type it, in the canonical per-axis form.
     *
     * Always writes all three rotation letters so the pinned axes are visible
     * rather than merely absent, and names mirror planes individually rather
     * than using the h/v shorthands, so that round-tripping a stored spec cannot
     * quietly widen it.
     */
    public String spec() {
        StringBuilder sb = new StringBuilder();
        sb.append(freeX ? 'x' : 'X').append(freeY ? 'y' : 'Y').append(freeZ ? 'z' : 'Z');
        if (hasMirror()) {
            sb.append('+');
            if (mirrorX) {
                sb.append('x');
            }
            if (mirrorY) {
                sb.append('y');
            }
            if (mirrorZ) {
                sb.append('z');
            }
        }
        return sb.toString();
    }

    /**
     * A phrase for command feedback.
     *
     * Reports the size of the generated group rather than restating the spec,
     * since a two-axis spec silently denotes more than it appears to.
     */
    public String describe() {
        if (isIdentityOnly()) {
            return "fixed orientation";
        }
        return transforms.size() + " orientations (" + spec() + ")";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Symmetry other
            && freeX == other.freeX && freeY == other.freeY && freeZ == other.freeZ
            && mirrorX == other.mirrorX && mirrorY == other.mirrorY
            && mirrorZ == other.mirrorZ;
    }

    @Override
    public int hashCode() {
        return (freeX ? 1 : 0) | (freeY ? 2 : 0) | (freeZ ? 4 : 0)
            | (mirrorX ? 8 : 0) | (mirrorY ? 16 : 0) | (mirrorZ ? 32 : 0);
    }

    @Override
    public String toString() {
        return spec();
    }

    /**
     * A signed axis permutation: one of the 48 symmetries of the cube.
     *
     * Each transform says where the source x, y and z axes end up, as a
     * destination axis index and a sign. Composition and inversion are then
     * plain index arithmetic, which keeps {@link Symmetry#generate} cheap and
     * makes equality exact — important, since group closure relies on
     * recognising an element it has already seen.
     */
    public record Transform(int mapX, int signX, int mapY, int signY, int mapZ, int signZ) {

        public static final Transform IDENTITY = new Transform(0, 1, 1, 1, 2, 1);

        /** Quarter turn about X: y to z, z to -y. */
        public static final Transform ROT_X = new Transform(0, 1, 2, 1, 1, -1);

        /** Quarter turn about Y: z to x, x to -z. */
        public static final Transform ROT_Y = new Transform(2, 1, 1, 1, 0, -1);

        /** Quarter turn about Z: x to y, y to -x. */
        public static final Transform ROT_Z = new Transform(1, 1, 0, -1, 2, 1);

        /** Reflection in the plane normal to X. */
        public static final Transform MIRROR_X = new Transform(0, -1, 1, 1, 2, 1);

        /** Reflection in the plane normal to Y: the top-to-bottom flip. */
        public static final Transform MIRROR_Y = new Transform(0, 1, 1, -1, 2, 1);

        /** Reflection in the plane normal to Z. */
        public static final Transform MIRROR_Z = new Transform(0, 1, 1, 1, 2, -1);

        /** Whether this transform reverses handedness. */
        public boolean isReflection() {
            return determinant() < 0;
        }

        private int determinant() {
            // A signed permutation's determinant is the permutation's parity
            // times the product of the signs.
            int parity = (mapX == 0 && mapY == 1 && mapZ == 2)
                || (mapX == 1 && mapY == 2 && mapZ == 0)
                || (mapX == 2 && mapY == 0 && mapZ == 1) ? 1 : -1;
            return parity * signX * signY * signZ;
        }

        /**
         * This transform followed by {@code next}.
         *
         * Source axis i lands on axis {@code map(i)} with sign {@code sign(i)};
         * applying {@code next} sends that on to {@code next.map(map(i))} with
         * the signs multiplied.
         */
        public Transform then(Transform next) {
            return new Transform(
                next.map(mapX), signX * next.sign(mapX),
                next.map(mapY), signY * next.sign(mapY),
                next.map(mapZ), signZ * next.sign(mapZ));
        }

        private int map(int axis) {
            return switch (axis) {
                case 0 -> mapX;
                case 1 -> mapY;
                default -> mapZ;
            };
        }

        private int sign(int axis) {
            return switch (axis) {
                case 0 -> signX;
                case 1 -> signY;
                default -> signZ;
            };
        }

        /** The destination axis and sign for a given source axis. */
        public int destinationAxis(int sourceAxis) {
            return map(sourceAxis);
        }

        public int destinationSign(int sourceAxis) {
            return sign(sourceAxis);
        }
    }
}
