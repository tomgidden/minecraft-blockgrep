package cx.gid.minecraft.blockgrep.pattern;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Parsing of the block-matching predicates that make up a pattern cell.
 *
 * A predicate is written as a comma-separated alternation, matching if any one
 * alternative matches. Each alternative is one of:
 *
 *   minecraft:mud      an exact block id (the namespace may be omitted)
 *   #minecraft:dirt    a block tag, matching every block carrying it
 *   ?                  any block
 *   !minecraft:air     a negation, matching anything the rest does not
 *
 * Ids are resolved eagerly at parse time so a typo is reported when the pattern
 * is set rather than silently matching nothing on every scan afterwards. Tags,
 * by contrast, are resolved per-test: tag contents come from the server and can
 * change on reload, so a TagKey is held rather than its members.
 */
public final class BlockPredicates {

    private BlockPredicates() {}

    /** Thrown when a predicate string cannot be parsed; the message is player-facing. */
    public static class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }

    /**
     * Parses one cell's predicate: a comma-separated alternation.
     *
     * An empty alternative (from a doubled or trailing comma) is rejected rather
     * than ignored, because silently dropping it would usually mean the player
     * mistyped something and got a pattern subtly different from the one they
     * wrote.
     */
    public static Predicate<BlockState> parse(String spec) throws ParseException {
        String trimmed = spec.trim();
        if (trimmed.isEmpty()) {
            throw new ParseException("empty block predicate");
        }

        List<Predicate<BlockState>> alternatives = new ArrayList<>();
        for (String rawPart : trimmed.split(",", -1)) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                throw new ParseException("empty alternative in '" + trimmed + "'");
            }
            alternatives.add(parseSingle(part));
        }

        // A single alternative is by far the common case; returning it unwrapped
        // keeps the hot scan path free of a pointless list iteration.
        if (alternatives.size() == 1) {
            return alternatives.getFirst();
        }

        // Copy to an array once: this predicate is invoked millions of times per
        // scan and array iteration avoids the list's iterator allocation.
        @SuppressWarnings("unchecked")
        Predicate<BlockState>[] arr = alternatives.toArray(new Predicate[0]);
        return state -> {
            for (Predicate<BlockState> p : arr) {
                if (p.test(state)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static Predicate<BlockState> parseSingle(String part) throws ParseException {
        if (part.startsWith("!")) {
            String rest = part.substring(1).trim();
            if (rest.isEmpty()) {
                throw new ParseException("'!' must be followed by a block or tag");
            }
            Predicate<BlockState> inner = parseSingle(rest);
            return state -> !inner.test(state);
        }

        if (part.startsWith("#")) {
            return parseTag(part.substring(1).trim());
        }

        return parseBlock(part);
    }

    private static Predicate<BlockState> parseTag(String id) throws ParseException {
        Identifier loc = tryParseId(id);
        if (loc == null) {
            throw new ParseException("'" + id + "' is not a valid tag id");
        }
        // Not validated against the loaded tag set: a client may parse a pattern
        // before tags arrive from the server, and an unknown tag is simply empty
        // rather than an error.
        TagKey<Block> key = TagKey.create(BuiltInRegistries.BLOCK.key(), loc);
        return state -> state.is(key);
    }

    private static Predicate<BlockState> parseBlock(String id) throws ParseException {
        Identifier loc = tryParseId(id);
        if (loc == null) {
            throw new ParseException("'" + id + "' is not a valid block id");
        }

        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(loc);
        if (block.isEmpty()) {
            throw new ParseException("unknown block '" + loc + "'");
        }

        Block b = block.get();
        // Compared by block identity, so any state of the block matches: a
        // waterlogged or rotated variant is still that block.
        return state -> state.is(b);
    }

    /**
     * Parses an id, defaulting a bare name to the minecraft namespace so a
     * player can write "mud" instead of "minecraft:mud".
     */
    private static Identifier tryParseId(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.indexOf(':') >= 0
            ? Identifier.tryParse(lower)
            : Identifier.tryBuild("minecraft", lower);
    }

    /**
     * Best-effort set of blocks a predicate can match, used to skip whole chunk
     * sections whose palette contains none of them.
     *
     * Returns empty when the answer cannot be known statically — a negation can
     * match blocks not enumerable in advance — which the caller must treat as
     * "cannot skip" rather than "matches nothing".
     */
    public static Optional<Set<Block>> possibleBlocks(String spec) {
        Set<Block> out = new HashSet<>();
        for (String rawPart : spec.trim().split(",", -1)) {
            String part = rawPart.trim();
            if (part.isEmpty() || part.startsWith("!")) {
                return Optional.empty();
            }
            if (part.startsWith("#")) {
                Identifier loc = tryParseId(part.substring(1).trim());
                if (loc == null) {
                    return Optional.empty();
                }
                TagKey<Block> key = TagKey.create(BuiltInRegistries.BLOCK.key(), loc);
                try {
                    BuiltInRegistries.BLOCK.getTagOrEmpty(key)
                        .forEach(holder -> out.add(holder.value()));
                } catch (IllegalStateException e) {
                    // Tags are bound when a world loads, so a pattern compiled
                    // before then cannot enumerate its members. Returning empty
                    // reports "unknowable" rather than "matches nothing": the
                    // caller must then scan without the palette early-out, which
                    // is slower but correct. Treating it as an empty tag would
                    // instead make the pattern silently match nothing at all.
                    return Optional.empty();
                }
            } else {
                Identifier loc = tryParseId(part);
                if (loc == null) {
                    return Optional.empty();
                }
                BuiltInRegistries.BLOCK.getOptional(loc).ifPresent(out::add);
            }
        }
        return Optional.of(out);
    }

    /** Convenience for the common "any of these blocks" case, used by saved patterns. */
    public static Predicate<BlockState> anyOf(Block... blocks) {
        Set<Block> set = Set.of(blocks);
        return state -> set.contains(state.getBlock());
    }
}
