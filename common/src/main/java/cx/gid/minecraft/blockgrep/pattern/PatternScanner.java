package cx.gid.minecraft.blockgrep.pattern;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds every occurrence of a pattern within a radius of a point.
 *
 * The scan walks chunk sections rather than raw block positions so that a
 * section whose palette contains none of the pattern's candidate blocks can be
 * rejected in one check instead of 4096. In a typical world this skips the
 * overwhelming majority of sections and is what makes a large radius affordable.
 *
 * The result is a list of match origins — the minimum corner of each occurrence
 * — in world coordinates.
 */
public final class PatternScanner {

    private PatternScanner() {}

    /** A single occurrence: where it starts, and the extent it covers. */
    public record Match(BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        /** The exclusive maximum corner, convenient for building a render box. */
        public BlockPos max() {
            return origin.offset(sizeX, sizeY, sizeZ);
        }
    }

    /**
     * Scans a cube of the given radius around {@code centre}.
     *
     * {@code limit} caps the number of matches collected; scanning stops once it
     * is reached, so a pathological pattern (say, a 1x1 of stone) degrades into
     * a truncated result rather than an unbounded list and a stalled frame.
     */
    public static List<Match> scan(ChunkSource chunks,
                                   Pattern pattern,
                                   BlockPos centre,
                                   int radius,
                                   int limit) {
        return scan(chunks, pattern, centre, radius, limit, Symmetry.YAW);
    }

    /**
     * As above, but with control over which orientations count as a match.
     *
     * {@link Symmetry#NONE} matches only the orientation the pattern was written
     * in, which is what a player wants for a shape whose facing is the point — a
     * staircase, or a doorway they intend to align to.
     */
    public static List<Match> scan(ChunkSource chunks,
                                   Pattern pattern,
                                   BlockPos centre,
                                   int radius,
                                   int limit,
                                   Symmetry symmetry) {
        List<Pattern> rotations = pattern.orientations(symmetry);
        Optional<Set<Block>> candidates = pattern.candidateBlocks();

        // A pattern that can only match a known set of blocks lets whole sections
        // be skipped; one containing a negation cannot, and must be tested
        // position by position.
        Set<Block> candidateSet = candidates.orElse(null);

        int minX = centre.getX() - radius;
        int minY = centre.getY() - radius;
        int minZ = centre.getZ() - radius;
        int maxX = centre.getX() + radius;
        int maxY = centre.getY() + radius;
        int maxZ = centre.getZ() + radius;

        // Clamp to the world's build height; sections outside it do not exist and
        // requesting them would either return null or throw depending on version.
        minY = Math.max(minY, chunks.minY());
        maxY = Math.min(maxY, chunks.maxY() - 1);

        List<Match> matches = new ArrayList<>();

        int minChunkX = SectionPos.blockToSectionCoord(minX);
        int maxChunkX = SectionPos.blockToSectionCoord(maxX);
        int minChunkZ = SectionPos.blockToSectionCoord(minZ);
        int maxChunkZ = SectionPos.blockToSectionCoord(maxZ);
        int minSection = SectionPos.blockToSectionCoord(minY);
        int maxSection = SectionPos.blockToSectionCoord(maxY);

        // Sections are gathered before any of them is walked so they can be
        // visited nearest-first. Scanning in raster order instead would mean a
        // scan that hits the limit reports only what lies in the lowest-numbered
        // corner of the radius — every highlight far away and in one direction —
        // which is useless for the "what is near me" question a search is
        // usually asking. Ordering by section rather than by block keeps the
        // sort cheap: there are a few hundred sections against millions of
        // positions, and 16 blocks is fine granularity for this purpose.
        List<PendingSection> pending = new ArrayList<>();

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                ChunkAccess chunk = chunks.getChunk(cx, cz);
                if (chunk == null) {
                    // Not loaded on the client: genuinely invisible, not empty.
                    continue;
                }

                for (int sy = minSection; sy <= maxSection; sy++) {
                    int sectionIndex = chunk.getSectionIndexFromSectionY(sy);
                    if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                        continue;
                    }
                    LevelChunkSection section = chunk.getSection(sectionIndex);
                    if (section == null || section.hasOnlyAir()) {
                        // hasOnlyAir is a cheap precomputed flag. A pattern that
                        // deliberately matches air is rare enough that treating an
                        // all-air section as uninteresting is the right trade;
                        // callers wanting air matches should include a solid cell.
                        continue;
                    }

                    if (candidateSet != null && !sectionMayContain(section, candidateSet)) {
                        continue;
                    }

                    pending.add(new PendingSection(cx, sy, cz,
                        sectionDistanceSq(centre, cx, sy, cz)));
                }
            }
        }

        pending.sort(Comparator.comparingLong(PendingSection::distanceSq));

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (PendingSection candidate : pending) {
            // Walk the part of this section that lies inside the requested radius.
            int baseY = SectionPos.sectionToBlockCoord(candidate.sectionY());
            int y0 = Math.max(minY, baseY);
            int y1 = Math.min(maxY, baseY + 15);
            int x0 = Math.max(minX, SectionPos.sectionToBlockCoord(candidate.chunkX()));
            int x1 = Math.min(maxX, SectionPos.sectionToBlockCoord(candidate.chunkX()) + 15);
            int z0 = Math.max(minZ, SectionPos.sectionToBlockCoord(candidate.chunkZ()));
            int z1 = Math.min(maxZ, SectionPos.sectionToBlockCoord(candidate.chunkZ()) + 15);

            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        for (Pattern rot : rotations) {
                            cursor.set(x, y, z);
                            if (matchesAt(chunks, rot, cursor)) {
                                matches.add(new Match(
                                    new BlockPos(x, y, z),
                                    rot.sizeX(), rot.sizeY(), rot.sizeZ()));
                                if (matches.size() >= limit) {
                                    return sortedByDistance(matches, centre);
                                }
                                // One origin reports at most one match, so
                                // rotations of the same spot do not stack.
                                break;
                            }
                        }
                    }
                }
            }
        }

        return sortedByDistance(matches, centre);
    }

    /**
     * A section that survived the palette check, with its distance from centre.
     *
     * Recorded rather than scanned immediately so the whole set can be ordered
     * before any of it is walked.
     */
    private record PendingSection(int chunkX, int sectionY, int chunkZ, long distanceSq) {}

    /**
     * Squared distance from a point to the nearest part of a section.
     *
     * Measured to the section's closest corner rather than its centre, so a
     * section the player is standing at the edge of sorts ahead of one further
     * away — with 16-block sections, centre distance would misorder neighbours.
     */
    private static long sectionDistanceSq(BlockPos centre, int chunkX, int sectionY, int chunkZ) {
        long dx = axisDistance(centre.getX(), SectionPos.sectionToBlockCoord(chunkX));
        long dy = axisDistance(centre.getY(), SectionPos.sectionToBlockCoord(sectionY));
        long dz = axisDistance(centre.getZ(), SectionPos.sectionToBlockCoord(chunkZ));
        return dx * dx + dy * dy + dz * dz;
    }

    /** Distance from a coordinate to a 16-wide span starting at {@code base}. */
    private static long axisDistance(int value, int base) {
        if (value < base) {
            return base - value;
        }
        if (value > base + 15) {
            return value - (base + 15);
        }
        return 0;
    }

    /**
     * Matches ordered by distance from the scan centre, nearest first.
     *
     * Sections are already visited nearest-first, but a section is 16 blocks
     * across and each is walked in raster order internally, so the raw list is
     * only approximately ordered. Sorting the result — at most {@code limit}
     * entries — makes "the nearest match" meaningful to anything reading this
     * list, and costs nothing next to the scan itself.
     */
    private static List<Match> sortedByDistance(List<Match> matches, BlockPos centre) {
        matches.sort(Comparator.comparingDouble(m -> m.origin().distSqr(centre)));
        return matches;
    }

    /**
     * Whether a section's palette holds any candidate block.
     *
     * This is the early-out that makes the whole scan viable. It reads the
     * palette rather than the block data, so the cost is proportional to the
     * number of distinct blocks in the section, not to its 4096 positions.
     */
    private static boolean sectionMayContain(LevelChunkSection section, Set<Block> candidates) {
        PalettedContainer<BlockState> states = section.getStates();
        return states.maybeHas(state -> candidates.contains(state.getBlock()));
    }

    /**
     * Whether the pattern matches with its minimum corner at {@code origin}.
     *
     * Cells are tested in storage order, and a don't-care cell costs only the
     * null check — no block lookup — which is why sparse patterns stay cheap.
     */
    private static boolean matchesAt(ChunkSource chunks, Pattern pattern, BlockPos origin) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

        for (int y = 0; y < pattern.sizeY(); y++) {
            for (int z = 0; z < pattern.sizeZ(); z++) {
                for (int x = 0; x < pattern.sizeX(); x++) {
                    Predicate<BlockState> cell = pattern.cell(x, y, z);
                    if (cell == null) {
                        continue;
                    }
                    probe.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = chunks.getBlockState(probe);
                    if (state == null || !cell.test(state)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * The world access the scanner needs, narrowed to three operations.
     *
     * Keeping this an interface rather than taking a ClientLevel directly means
     * the matching logic can be exercised in a plain unit test with a stub, with
     * no Minecraft bootstrap involved.
     */
    public interface ChunkSource {
        /** The chunk at these chunk coordinates, or null when not loaded. */
        ChunkAccess getChunk(int chunkX, int chunkZ);

        /** The state at a position, or null when outside a loaded chunk. */
        BlockState getBlockState(BlockPos pos);

        /** Inclusive minimum build height. */
        int minY();

        /** Exclusive maximum build height. */
        int maxY();
    }

    /** Chunk coordinates covered by a radius, exposed for cache invalidation. */
    public static List<ChunkPos> chunksInRange(BlockPos centre, int radius) {
        List<ChunkPos> out = new ArrayList<>();
        int minChunkX = SectionPos.blockToSectionCoord(centre.getX() - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(centre.getX() + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(centre.getZ() - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(centre.getZ() + radius);
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                out.add(new ChunkPos(cx, cz));
            }
        }
        return out;
    }
}
