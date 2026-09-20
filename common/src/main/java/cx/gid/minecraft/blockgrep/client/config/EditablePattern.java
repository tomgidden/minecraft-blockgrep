package cx.gid.minecraft.blockgrep.client.config;

import cx.gid.minecraft.blockgrep.pattern.BlockPredicates;
import cx.gid.minecraft.blockgrep.pattern.PatternSpec;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Mutable, source-preserving model used by the visual pattern editor.
 *
 * Runtime {@link cx.gid.minecraft.blockgrep.pattern.Pattern Pattern}s deliberately
 * discard their source cells after compiling them to predicates.  That is ideal
 * for the scanner but makes them impossible to edit.  This class is the inverse:
 * it knows nothing about predicates and keeps every cell as a small structured
 * rule which can be painted, copied and serialised back to Block Grep syntax.
 */
public final class EditablePattern {

    public static final int MAX_SIZE = 16;

    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private CellRule[] cells;

    public EditablePattern(int sizeX, int sizeY, int sizeZ) {
        checkSize(sizeX, sizeY, sizeZ);
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = new CellRule[sizeX * sizeY * sizeZ];
        Arrays.fill(cells, CellRule.any());
    }

    private EditablePattern(int sizeX, int sizeY, int sizeZ, CellRule[] cells) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = cells;
    }

    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }

    private int index(int x, int y, int z) {
        if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
            throw new IndexOutOfBoundsException(x + "," + y + "," + z);
        }
        return (y * sizeZ + z) * sizeX + x;
    }

    public CellRule cell(int x, int y, int z) {
        return cells[index(x, y, z)];
    }

    public void set(int x, int y, int z, CellRule rule) {
        cells[index(x, y, z)] = rule == null ? CellRule.any() : rule;
    }

    public void clear() {
        Arrays.fill(cells, CellRule.any());
    }

    public int significantCells() {
        int count = 0;
        for (CellRule rule : cells) {
            if (!rule.isAny()) {
                count++;
            }
        }
        return count;
    }

    /** Deep-enough copy: CellRule and Term are immutable. */
    public EditablePattern copy() {
        return new EditablePattern(sizeX, sizeY, sizeZ, cells.clone());
    }

    /**
     * Changes one or more extents, preserving the low-coordinate corner and
     * filling newly exposed cells with don't-care rules.
     */
    public void resize(int newX, int newY, int newZ) {
        checkSize(newX, newY, newZ);
        CellRule[] replacement = new CellRule[newX * newY * newZ];
        Arrays.fill(replacement, CellRule.any());
        for (int y = 0; y < Math.min(sizeY, newY); y++) {
            for (int z = 0; z < Math.min(sizeZ, newZ); z++) {
                for (int x = 0; x < Math.min(sizeX, newX); x++) {
                    replacement[(y * newZ + z) * newX + x] = cell(x, y, z);
                }
            }
        }
        sizeX = newX;
        sizeY = newY;
        sizeZ = newZ;
        cells = replacement;
    }

    /** Canonical layered syntax, bottom layer first and x-fastest within a row. */
    public String toSpec() {
        List<String> layers = new ArrayList<>(sizeY);
        for (int y = 0; y < sizeY; y++) {
            List<String> rows = new ArrayList<>(sizeZ);
            for (int z = 0; z < sizeZ; z++) {
                List<String> row = new ArrayList<>(sizeX);
                for (int x = 0; x < sizeX; x++) {
                    row.add(cell(x, y, z).toSpec());
                }
                rows.add(String.join(" ", row));
            }
            layers.add(String.join(" | ", rows));
        }
        return String.join(" || ", layers);
    }

    /** True only when the pattern has matching cells and all block ids are known. */
    public boolean isValid() {
        if (significantCells() == 0) {
            return false;
        }
        for (CellRule rule : cells) {
            if (rule.isAny()) {
                continue;
            }
            for (Term term : rule.terms()) {
                Identifier id = Identifier.tryParse(term.id());
                if (id == null) {
                    return false;
                }
                if (!term.tag() && net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(id).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Reads either supported Block Grep form without compiling its predicates.
     * This allows an unknown block from a removed mod to remain visible as a
     * missing tile so that the player can replace it visually.
     */
    public static EditablePattern parse(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("pattern is empty");
        }
        String trimmed = source.trim();
        List<String> whitespace = List.of(trimmed.split("\\s+"));
        if (whitespace.size() > 3 && PatternSpec.isDimension(whitespace.get(0))
                && PatternSpec.isDimension(whitespace.get(1)) && PatternSpec.isDimension(whitespace.get(2))) {
            int x = Integer.parseInt(whitespace.get(0));
            int y = Integer.parseInt(whitespace.get(1));
            int z = Integer.parseInt(whitespace.get(2));
            checkSize(x, y, z);
            int expected = x * y * z;
            if (whitespace.size() - 3 != expected) {
                throw new IllegalArgumentException("wrong number of cells");
            }
            EditablePattern out = new EditablePattern(x, y, z);
            for (int i = 0; i < expected; i++) {
                out.cells[i] = CellRule.parse(whitespace.get(i + 3));
            }
            return out;
        }

        List<List<List<CellRule>>> layers = new ArrayList<>();
        List<List<CellRule>> rows = new ArrayList<>();
        List<CellRule> cells = new ArrayList<>();
        for (String token : PatternSpec.splitOnBars(trimmed)) {
            if (token.equals("|")) {
                finishRow(rows, cells);
                cells = new ArrayList<>();
            } else if (token.equals("||")) {
                finishRow(rows, cells);
                cells = new ArrayList<>();
                finishLayer(layers, rows);
                rows = new ArrayList<>();
            } else {
                cells.add(CellRule.parse(token));
            }
        }
        finishRow(rows, cells);
        finishLayer(layers, rows);

        int ySize = layers.size();
        int zSize = layers.getFirst().size();
        int xSize = layers.getFirst().getFirst().size();
        checkSize(xSize, ySize, zSize);
        for (List<List<CellRule>> layer : layers) {
            if (layer.size() != zSize) {
                throw new IllegalArgumentException("ragged layers");
            }
            for (List<CellRule> row : layer) {
                if (row.size() != xSize) {
                    throw new IllegalArgumentException("ragged rows");
                }
            }
        }

        EditablePattern out = new EditablePattern(xSize, ySize, zSize);
        for (int y = 0; y < ySize; y++) {
            for (int z = 0; z < zSize; z++) {
                for (int x = 0; x < xSize; x++) {
                    out.set(x, y, z, layers.get(y).get(z).get(x));
                }
            }
        }
        return out;
    }

    private static void finishRow(List<List<CellRule>> rows, List<CellRule> cells) {
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("empty row");
        }
        rows.add(List.copyOf(cells));
    }

    private static void finishLayer(List<List<List<CellRule>>> layers,
                                    List<List<CellRule>> rows) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("empty layer");
        }
        layers.add(List.copyOf(rows));
    }

    private static void checkSize(int x, int y, int z) {
        if (x < 1 || y < 1 || z < 1 || x > MAX_SIZE || y > MAX_SIZE || z > MAX_SIZE) {
            throw new IllegalArgumentException("pattern dimensions must be 1–" + MAX_SIZE);
        }
    }

    /** One comma-separated alternative within a cell. */
    public record Term(String id, boolean tag, boolean negated) {
        public Term {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("empty id");
            }
        }

        public Term toggled() {
            return new Term(id, tag, !negated);
        }

        public String toSpec() {
            return (negated ? "!" : "") + (tag ? "#" : "") + id;
        }
    }

    /**
     * A cell predicate.  The wildcard is represented separately because '?' is
     * a shape hole in Block Grep rather than an alternative accepted by the
     * ordinary block-predicate parser.
     */
    public static final class CellRule {
        private static final CellRule ANY = new CellRule(true, List.of());

        private final boolean any;
        private final List<Term> terms;

        private CellRule(boolean any, List<Term> terms) {
            this.any = any;
            this.terms = Collections.unmodifiableList(new ArrayList<>(terms));
            if (!any && terms.isEmpty()) {
                throw new IllegalArgumentException("a rule needs an alternative");
            }
        }

        public static CellRule any() {
            return ANY;
        }

        public static CellRule of(Term term) {
            return new CellRule(false, List.of(term));
        }

        public static CellRule of(List<Term> terms) {
            return terms.isEmpty() ? any() : new CellRule(false, terms);
        }

        public boolean isAny() {
            return any;
        }

        public List<Term> terms() {
            return terms;
        }

        public CellRule add(Term term) {
            if (any) {
                return of(term);
            }
            List<Term> changed = new ArrayList<>(terms);
            if (!changed.contains(term)) {
                changed.add(term);
            }
            return of(changed);
        }

        public CellRule remove(int index) {
            if (any || index < 0 || index >= terms.size()) {
                return this;
            }
            List<Term> changed = new ArrayList<>(terms);
            changed.remove(index);
            return of(changed);
        }

        public CellRule toggle(int index) {
            if (any || index < 0 || index >= terms.size()) {
                return this;
            }
            List<Term> changed = new ArrayList<>(terms);
            changed.set(index, changed.get(index).toggled());
            return of(changed);
        }

        public String toSpec() {
            if (any) {
                return "?";
            }
            List<String> source = new ArrayList<>(terms.size());
            for (Term term : terms) {
                source.add(term.toSpec());
            }
            return String.join(",", source);
        }

        public static CellRule parse(String source) {
            String trimmed = source.trim();
            if (trimmed.equals("?")) {
                return any();
            }
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("empty cell");
            }
            List<Term> alternatives = new ArrayList<>();
            for (String raw : trimmed.split(",", -1)) {
                String part = raw.trim();
                if (part.isEmpty()) {
                    throw new IllegalArgumentException("empty alternative");
                }
                boolean negated = false;
                while (part.startsWith("!")) {
                    negated = !negated;
                    part = part.substring(1).trim();
                }
                boolean tag = part.startsWith("#");
                if (tag) {
                    part = part.substring(1).trim();
                }
                if (part.isEmpty()) {
                    throw new IllegalArgumentException("empty id after prefix");
                }
                Identifier id = part.indexOf(':') >= 0
                    ? Identifier.tryParse(part.toLowerCase(java.util.Locale.ROOT))
                    : Identifier.tryBuild("minecraft", part.toLowerCase(java.util.Locale.ROOT));
                if (id == null) {
                    throw new IllegalArgumentException("invalid id");
                }
                alternatives.add(new Term(id.toString(), tag, negated));
            }
            return of(alternatives);
        }
    }
}
