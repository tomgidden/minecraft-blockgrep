package cx.gid.minecraft.blockgrep.pattern;

import java.util.ArrayList;
import java.util.List;

/**
 * The textual pattern syntax, shared by the command line and saved patterns.
 *
 * Kept in one place deliberately: a saved pattern is nothing but a line the
 * player could have typed, so if the two ever parsed differently a pattern would
 * stop meaning what it said when saved.
 *
 * The syntax has two forms. The drawing form separates cells by whitespace, rows
 * by {@code |} and layers by {@code ||}, bottom layer first:
 *
 * <pre>
 *   mud mud | mud mud || #logs ? | ? #logs
 * </pre>
 *
 * A spaced {@code /} and {@code //} are accepted as synonyms, but only standing
 * alone as their own token: a {@code /} within a cell belongs to an id, as in
 * {@code #minecraft:mineable/pickaxe}.
 *
 * The dimensioned form gives extents up front, followed by exactly x*y*z cells
 * in x-fastest, then z, then y order:
 *
 * <pre>
 *   2 2 2 mud mud mud mud #logs ? ? #logs
 * </pre>
 *
 * A cell of {@code ?} is don't-care; anything else is a block predicate.
 */
public final class PatternSpec {

    private PatternSpec() {}

    /** A spec with its optional leading symmetry, as typed. */
    public record Parsed(Pattern pattern, Symmetry symmetry, String patternText) {}

    /**
     * Parses a full command argument: an optional symmetry spec, then a pattern.
     *
     * The symmetry is recognised only as the very first token and only when it
     * cannot be a cell — see {@link Symmetry#looksLikeSpec} — so a pattern that
     * happens to start with a short block name is still read as a pattern.
     */
    public static Parsed parseWithSymmetry(String text) throws BlockPredicates.ParseException {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new BlockPredicates.ParseException("pattern is empty");
        }

        Symmetry symmetry = Symmetry.YAW;
        String remainder = trimmed;

        int split = indexOfWhitespace(trimmed);
        String first = split < 0 ? trimmed : trimmed.substring(0, split);

        // A token is treated as symmetry when it is shaped like one, which is a
        // wider test than "parses as one". Requiring it to parse would mean a
        // misspelled symmetry — 'y+q', or the withdrawn '+m' — fell through and
        // was read as a block name, producing a pattern that silently matches
        // nothing instead of an error naming the real mistake.
        if (Symmetry.isSymmetryShaped(first)) {
            symmetry = Symmetry.parse(first);
            remainder = split < 0 ? "" : trimmed.substring(split).trim();
            if (remainder.isEmpty()) {
                throw new BlockPredicates.ParseException(
                    "symmetry '" + first + "' given with no pattern after it");
            }
        }

        return new Parsed(parse(remainder), symmetry, remainder);
    }

    /**
     * Parses the pattern body, in either the drawing or the dimensioned form.
     *
     * Which form is in use is decided by the opening tokens: three integers
     * followed by at least one more token can only be the dimensioned form,
     * since a bare number is not a valid block predicate.
     */
    public static Pattern parse(String text) throws BlockPredicates.ParseException {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new BlockPredicates.ParseException("pattern is empty");
        }

        List<String> tokens = new ArrayList<>();
        for (String token : trimmed.split("\\s+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }

        if (tokens.size() > 3 && isDimension(tokens.get(0))
                && isDimension(tokens.get(1)) && isDimension(tokens.get(2))) {
            return Pattern.fromDimensions(
                Integer.parseInt(tokens.get(0)),
                Integer.parseInt(tokens.get(1)),
                Integer.parseInt(tokens.get(2)),
                tokens.subList(3, tokens.size()));
        }

        return parseLayers(trimmed);
    }

    /**
     * Parses the drawing form.
     *
     * Separators are recognised as whole tokens rather than by splitting the
     * raw text, because a {@code /} also occurs inside ids — {@code
     * #minecraft:mineable/pickaxe} is one cell, not two rows. Splitting on the
     * character would tear such a tag in half; requiring the separator to stand
     * alone between spaces cannot, since a cell never contains whitespace.
     *
     * {@code |} and {@code ||} are the canonical separators and need no spaces.
     * The spaced forms {@code /} and {@code //} are accepted as well, since a
     * pattern reads naturally that way and the ambiguity disappears once the
     * separator must be its own token.
     */
    private static Pattern parseLayers(String text) throws BlockPredicates.ParseException {
        List<List<List<String>>> layers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();

        for (String token : splitOnBars(text)) {
            switch (token) {
                case "||" -> {
                    // Ending a layer ends the row it was in the middle of.
                    rows.add(endRow(cells));
                    cells = new ArrayList<>();
                    layers.add(endLayer(rows));
                    rows = new ArrayList<>();
                }
                case "|" -> {
                    rows.add(endRow(cells));
                    cells = new ArrayList<>();
                }
                default -> {
                    // A longer run of separator characters is a typo, and would
                    // otherwise reach the block parser and be reported as an
                    // unknown block — naming the character is far more useful.
                    if (isSeparatorRun(token)) {
                        throw new BlockPredicates.ParseException(
                            "'" + token + "' is not a separator; use '|' between rows"
                                + " and '||' between layers");
                    }
                    cells.add(token);
                }
            }
        }

        rows.add(endRow(cells));
        layers.add(endLayer(rows));

        return Pattern.fromLayers(layers);
    }

    /**
     * Whether a token is nothing but separator characters.
     *
     * Only the over-long runs reach this: {@code |}, {@code ||} and the spaced
     * slashes are matched before it, so anything still made purely of these
     * characters is a miscount rather than a cell.
     */
    private static boolean isSeparatorRun(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != '|' && c != '/') {
                return false;
            }
        }
        return !token.isEmpty();
    }

    /** A row's cells, rejecting the empty row a doubled separator would make. */
    private static List<String> endRow(List<String> cells) throws BlockPredicates.ParseException {
        if (cells.isEmpty()) {
            throw new BlockPredicates.ParseException("empty row in pattern");
        }
        return cells;
    }

    /** A layer's rows, rejecting the empty layer a doubled separator would make. */
    private static List<List<String>> endLayer(List<List<String>> rows)
            throws BlockPredicates.ParseException {
        if (rows.isEmpty()) {
            throw new BlockPredicates.ParseException("empty layer in pattern");
        }
        return rows;
    }

    /**
     * Splits the spec into cell and separator tokens.
     *
     * Whitespace separates tokens, and a bare {@code /} or {@code //} token is
     * rewritten to its canonical bar form so the caller sees one spelling. Bars
     * are additionally split out of adjoining text, which is what lets {@code
     * mud|mud} be written without spaces; slashes deliberately are not, so that
     * an id containing one survives intact.
     */
    private static List<String> splitOnBars(String text) {
        List<String> out = new ArrayList<>();

        for (String token : text.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.equals("/")) {
                out.add("|");
                continue;
            }
            if (token.equals("//")) {
                out.add("||");
                continue;
            }

            int i = 0;
            while (i < token.length()) {
                int bar = token.indexOf('|', i);
                if (bar < 0) {
                    out.add(token.substring(i));
                    break;
                }
                if (bar > i) {
                    out.add(token.substring(i, bar));
                }
                // A run of three or more bars is left as one token, which no
                // case matches, so it reports as an unknown block rather than
                // being silently read as a layer break plus a row break.
                int end = bar;
                while (end < token.length() && token.charAt(end) == '|') {
                    end++;
                }
                out.add(token.substring(bar, end));
                i = end;
            }
        }

        return out;
    }

    /** Whether a token is a plain non-negative integer usable as an extent. */
    private static boolean isDimension(String token) {
        if (token.isEmpty() || token.length() > 2) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Renders a uniform box as an equivalent pattern spec.
     *
     * Lets a {@code box} command be stored like any other pattern, since the
     * config holds text rather than compiled patterns.
     */
    public static String uniformBoxSpec(int sizeX, int sizeY, int sizeZ, String blocks) {
        String cell = blocks.trim();

        StringBuilder row = new StringBuilder();
        for (int x = 0; x < sizeX; x++) {
            if (x > 0) {
                row.append(' ');
            }
            row.append(cell);
        }

        StringBuilder layer = new StringBuilder();
        for (int z = 0; z < sizeZ; z++) {
            if (z > 0) {
                layer.append(" | ");
            }
            layer.append(row);
        }

        StringBuilder spec = new StringBuilder();
        for (int y = 0; y < sizeY; y++) {
            if (y > 0) {
                spec.append(" || ");
            }
            spec.append(layer);
        }
        return spec.toString();
    }
}
