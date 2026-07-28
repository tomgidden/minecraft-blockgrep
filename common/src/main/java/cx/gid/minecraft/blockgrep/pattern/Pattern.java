package cx.gid.minecraft.blockgrep.pattern;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A shape to search for: a box of cells, each holding a block predicate.
 *
 * Cells are stored flat in x-fastest, then z, then y order, indexed by
 * {@link #index}. A null predicate marks a "don't care" cell, which lets a
 * non-rectangular shape be expressed inside a rectangular box.
 *
 * A pattern is immutable once built, so the scan thread and the render thread
 * can share one instance without synchronisation.
 */
public final class Pattern {

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final Predicate<BlockState>[] cells;

    /** Source text, kept so the pattern can be echoed back to the player. */
    private final String description;

    /**
     * Blocks that could satisfy at least one cell, or empty when that cannot be
     * determined. Drives the section-palette early-out in the scanner.
     */
    private final Optional<Set<Block>> candidateBlocks;

    private Pattern(int sizeX, int sizeY, int sizeZ,
                    Predicate<BlockState>[] cells,
                    String description,
                    Optional<Set<Block>> candidateBlocks) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = cells;
        this.description = description;
        this.candidateBlocks = candidateBlocks;
    }

    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public String description() { return description; }
    public Optional<Set<Block>> candidateBlocks() { return candidateBlocks; }

    private int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    /** The predicate at a cell, or null for "don't care". */
    public Predicate<BlockState> cell(int x, int y, int z) {
        return cells[index(x, y, z)];
    }

    /** Number of cells that actually have to match; used to reject empty patterns. */
    public int significantCells() {
        int n = 0;
        for (Predicate<BlockState> c : cells) {
            if (c != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * Builds a uniform box where every cell carries the same predicate — the
     * "2x2 of soft ground" case, and the one a player asks for most often.
     */
    public static Pattern uniformBox(int sizeX, int sizeY, int sizeZ, String spec)
            throws BlockPredicates.ParseException {
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
            throw new BlockPredicates.ParseException("pattern dimensions must be at least 1");
        }

        Predicate<BlockState> p = BlockPredicates.parse(spec);

        @SuppressWarnings("unchecked")
        Predicate<BlockState>[] cells = new Predicate[sizeX * sizeY * sizeZ];
        java.util.Arrays.fill(cells, p);

        String desc = sizeX + "x" + sizeY + "x" + sizeZ + " of [" + spec + "]";
        return new Pattern(sizeX, sizeY, sizeZ, cells, desc,
            BlockPredicates.possibleBlocks(spec));
    }

    /**
     * Builds a single-layer pattern from rows of cell specs, where each row is a
     * line of z and each entry within it a step in x.
     *
     * A cell spec of "?" means don't care. This is the general form behind
     * multi-cell patterns like a doorway or a specific ore arrangement.
     */
    public static Pattern fromRows(List<List<String>> rows)
            throws BlockPredicates.ParseException {
        return fromLayers(List.of(rows));
    }

    /**
     * Builds a pattern from layers of rows: the general case.
     *
     * The outer list is y, bottom layer first; within a layer each row is a step
     * in z and each entry a step in x. Every layer must be the same size, since
     * a pattern is a box; a ragged shape is expressed by filling the gaps with
     * don't-care cells rather than by varying the extents.
     *
     * A cell spec of "?" means don't care.
     */
    public static Pattern fromLayers(List<List<List<String>>> layers)
            throws BlockPredicates.ParseException {
        if (layers.isEmpty()) {
            throw new BlockPredicates.ParseException("pattern has no layers");
        }

        int sizeY = layers.size();
        int sizeZ = layers.getFirst().size();
        if (sizeZ == 0) {
            throw new BlockPredicates.ParseException("pattern layer has no rows");
        }
        int sizeX = layers.getFirst().getFirst().size();
        if (sizeX == 0) {
            throw new BlockPredicates.ParseException("pattern row is empty");
        }

        for (List<List<String>> layer : layers) {
            if (layer.size() != sizeZ) {
                throw new BlockPredicates.ParseException(
                    "all layers must have the same number of rows (expected " + sizeZ + ")");
            }
            for (List<String> row : layer) {
                if (row.size() != sizeX) {
                    throw new BlockPredicates.ParseException(
                        "all pattern rows must be the same length (expected " + sizeX + ")");
                }
            }
        }

        @SuppressWarnings("unchecked")
        Predicate<BlockState>[] cells = new Predicate[sizeX * sizeY * sizeZ];

        // Accumulated across cells; a single unknowable cell makes the whole
        // pattern unskippable, matching possibleBlocks' contract.
        Set<Block> candidates = new HashSet<>();
        boolean candidatesKnown = true;

        List<String> layerDescriptions = new ArrayList<>();
        for (int y = 0; y < sizeY; y++) {
            List<List<String>> layer = layers.get(y);
            List<String> rowDescriptions = new ArrayList<>();
            for (int z = 0; z < sizeZ; z++) {
                List<String> row = layer.get(z);
                for (int x = 0; x < sizeX; x++) {
                    String spec = row.get(x).trim();
                    int at = (y * sizeZ + z) * sizeX + x;
                    if (spec.equals("?")) {
                        cells[at] = null;
                        continue;
                    }
                    cells[at] = BlockPredicates.parse(spec);
                    if (candidatesKnown) {
                        Optional<Set<Block>> possible = BlockPredicates.possibleBlocks(spec);
                        if (possible.isPresent()) {
                            candidates.addAll(possible.get());
                        } else {
                            candidatesKnown = false;
                        }
                    }
                }
                rowDescriptions.add(String.join(" ", row));
            }
            layerDescriptions.add(String.join(" | ", rowDescriptions));
        }

        Pattern pattern = new Pattern(sizeX, sizeY, sizeZ, cells,
            String.join(" || ", layerDescriptions),
            candidatesKnown ? Optional.of(candidates) : Optional.empty());

        if (pattern.significantCells() == 0) {
            throw new BlockPredicates.ParseException("pattern is entirely '?'");
        }
        return pattern;
    }

    /**
     * Builds a pattern from explicit dimensions and a flat list of cell specs,
     * given in x-fastest, then z, then y order.
     *
     * The alternative to the layered form, for when a shape is easier to state
     * as its extents plus a run of cells than as a drawing.
     */
    public static Pattern fromDimensions(int sizeX, int sizeY, int sizeZ, List<String> specs)
            throws BlockPredicates.ParseException {
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
            throw new BlockPredicates.ParseException("pattern dimensions must be at least 1");
        }
        int expected = sizeX * sizeY * sizeZ;
        if (specs.size() != expected) {
            throw new BlockPredicates.ParseException(
                "expected " + expected + " cells for " + sizeX + "x" + sizeY + "x" + sizeZ
                    + ", found " + specs.size());
        }

        List<List<List<String>>> layers = new ArrayList<>(sizeY);
        for (int y = 0; y < sizeY; y++) {
            List<List<String>> layer = new ArrayList<>(sizeZ);
            for (int z = 0; z < sizeZ; z++) {
                int from = (y * sizeZ + z) * sizeX;
                layer.add(new ArrayList<>(specs.subList(from, from + sizeX)));
            }
            layers.add(layer);
        }
        return fromLayers(layers);
    }

    /**
     * This pattern under one of the cube's symmetries.
     *
     * The transform says where each source axis goes and whether it flips. A
     * cell's coordinate along a source axis therefore becomes a coordinate along
     * the destination axis, reversed within the new extent when the sign is
     * negative — reversing rather than negating is what keeps the result a box
     * anchored at the origin, so match origins stay comparable across
     * orientations.
     */
    public Pattern transformed(Symmetry.Transform transform) {
        int[] sourceSizes = {sizeX, sizeY, sizeZ};
        int[] newSizes = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            newSizes[transform.destinationAxis(axis)] = sourceSizes[axis];
        }

        int newSizeX = newSizes[0];
        int newSizeY = newSizes[1];
        int newSizeZ = newSizes[2];

        @SuppressWarnings("unchecked")
        Predicate<BlockState>[] moved = new Predicate[cells.length];

        int[] source = new int[3];
        int[] destination = new int[3];

        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    source[0] = x;
                    source[1] = y;
                    source[2] = z;

                    for (int axis = 0; axis < 3; axis++) {
                        int target = transform.destinationAxis(axis);
                        int value = source[axis];
                        destination[target] = transform.destinationSign(axis) > 0
                            ? value
                            : sourceSizes[axis] - 1 - value;
                    }

                    int index = (destination[1] * newSizeZ + destination[2]) * newSizeX
                        + destination[0];
                    moved[index] = cell(x, y, z);
                }
            }
        }

        return new Pattern(newSizeX, newSizeY, newSizeZ, moved,
            description, candidateBlocks);
    }

    /**
     * The distinct orientations of this pattern under a symmetry.
     *
     * Symmetric shapes collapse to fewer than the group's size — a uniform 2x2
     * yields one however it is turned — so the scanner does not test the same
     * arrangement repeatedly or report an occurrence more than once. Equality is
     * structural over cell identity, which works because transforming copies
     * predicate references rather than rebuilding them.
     */
    public List<Pattern> orientations(Symmetry symmetry) {
        List<Pattern> out = new ArrayList<>();
        for (Symmetry.Transform transform : symmetry.transforms()) {
            Pattern candidate = transformed(transform);
            boolean duplicate = false;
            for (Pattern seen : out) {
                if (seen.sameCells(candidate)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                out.add(candidate);
            }
        }
        return out;
    }

    private boolean sameCells(Pattern other) {
        if (sizeX != other.sizeX || sizeY != other.sizeY || sizeZ != other.sizeZ) {
            return false;
        }
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != other.cells[i]) {
                return false;
            }
        }
        return true;
    }
}
