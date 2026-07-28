package cx.gid.minecraft.blockgrep.client;

import cx.gid.minecraft.blockgrep.Constants;
import cx.gid.minecraft.blockgrep.pattern.PatternScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * The mod's client-side state: which patterns are active and where they
 * currently match.
 *
 * Scanning is deliberately not done every frame. A full scan of a large radius
 * costs far more than a frame's budget, so results are cached and refreshed only
 * when the player has moved far enough for the answer to plausibly differ, or
 * after a dwell time to pick up world edits.
 *
 * Even throttled, a scan at a large radius is far too slow to sit on the client
 * thread: at radius 128 it costs tens of milliseconds, which lands as a visible
 * hitch every time the throttle lets one through. So the scan runs on a
 * background thread and publishes its results when it finishes; the client
 * thread only ever reads the most recently published set. That means the
 * highlights can lag the player by a scan, which is imperceptible next to the
 * stutter it replaces.
 *
 * Reading block states off-thread is safe here in a way it would not be on the
 * server: the client's chunks are replaced wholesale by the network thread
 * rather than mutated in place, so a scan racing a chunk update reads either the
 * old section or the new one, never a torn one. The worst case is a highlight
 * that is one update stale, which the next scan corrects.
 *
 * Several patterns can be active at once. They share the radius and match limit
 * — those bound the work the client is willing to do per scan, which is a
 * property of the machine rather than of any one pattern — but each carries its
 * own color so the results stay tellable apart.
 */
public final class GrepState {

    private GrepState() {}

    /** Currently active patterns, in no particular order. Never null. */
    private static List<ActivePattern> active = List.of();

    /** Most recent scan results, rendered until replaced. */
    private static List<Hit> hits = List.of();

    /** Where the player was when {@link #hits} was computed. */
    private static BlockPos lastScanCentre;

    /** Game time of the last scan, used for the dwell-based refresh. */
    private static long lastScanTick;

    /**
     * True while a background scan is in flight.
     *
     * Guards against queueing a second scan behind the first: with the scan
     * taking longer than the throttle interval at a large radius, every tick
     * would otherwise start another, and the pool would fall further behind the
     * player the faster they moved.
     */
    private static boolean scanning;

    /**
     * The thread scans run on.
     *
     * A single daemon thread rather than a pool: scans are serialised anyway by
     * the {@link #scanning} flag, and one background thread that never blocks
     * shutdown is the whole requirement.
     */
    private static final java.util.concurrent.ExecutorService SCANNER =
        java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "blockgrep-scan");
            thread.setDaemon(true);
            // Below the client thread: a scan finishing a tick later is
            // invisible, whereas stealing time from rendering is not.
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });

    /** Search radius in blocks, clamped to something a client can afford. */
    private static int radius = 32;

    /** Upper bound on reported matches, protecting against runaway patterns. */
    private static int limit = 512;

    public static final int MIN_RADIUS = 4;
    public static final int MAX_RADIUS = 128;

    /**
     * Blocks the player may move before results are considered stale. Half a
     * chunk keeps the highlighted set roughly centred without rescanning on
     * every step.
     */
    private static final int RESCAN_DISTANCE = 8;

    /** Ticks before a stationary player's results are refreshed anyway. */
    private static final int RESCAN_INTERVAL_TICKS = 40;

    /**
     * One occurrence, paired with the pattern that found it.
     *
     * The pattern travels with the match because the renderer needs its color,
     * and because with several searches running "which pattern is this?" is a
     * question the result has to be able to answer.
     */
    public record Hit(ActivePattern source, PatternScanner.Match match) {}

    /**
     * Replaces the set of active patterns.
     *
     * Called on every edit in the settings screen, which is why the previous
     * hits are kept rather than cleared. Clearing looks correct in isolation —
     * the old hits do describe the old patterns — but the replacement set only
     * arrives a scan later, so blanking here makes the highlights flicker off
     * and back on at every keystroke. Showing results that are one scan stale
     * for a fraction of a second is the lesser distraction, and the scan that
     * follows corrects them.
     *
     * {@link #clear()} is the one that really does mean "show nothing".
     */
    public static synchronized void setPatterns(List<ActivePattern> patterns) {
        active = List.copyOf(patterns);
        // Force the next tick to rescan rather than waiting out the dwell
        // interval: the patterns have changed, so the cached answer is wrong
        // even standing still.
        lastScanCentre = null;
    }

    public static synchronized void clear() {
        active = List.of();
        hits = List.of();
        lastScanCentre = null;
    }

    public static synchronized List<ActivePattern> activePatterns() {
        return active;
    }

    /** Whether anything is currently being searched for. */
    public static synchronized boolean isActive() {
        return !active.isEmpty();
    }

    public static synchronized List<Hit> currentHits() {
        return hits;
    }

    public static synchronized int radius() {
        return radius;
    }

    public static synchronized void setRadius(int value) {
        radius = Math.clamp(value, MIN_RADIUS, MAX_RADIUS);
        // Force a rescan: a changed radius changes the answer even standing still.
        lastScanCentre = null;
    }

    public static synchronized int limit() {
        return limit;
    }

    public static synchronized void setLimit(int value) {
        limit = Math.max(1, value);
        lastScanCentre = null;
    }

    /**
     * Rescans if the cached result has gone stale.
     *
     * Called once per client tick. Returns quietly when there is nothing to do,
     * which is the overwhelmingly common case.
     */
    public static void tick(Minecraft client) {
        List<ActivePattern> patterns;
        int currentRadius;
        int currentLimit;
        BlockPos centreOfLastScan;
        long tickOfLastScan;

        synchronized (GrepState.class) {
            if (scanning) {
                // A scan is already running. Starting another would not produce
                // an answer any sooner and would leave the queue growing.
                return;
            }
            patterns = active;
            currentRadius = radius;
            currentLimit = limit;
            centreOfLastScan = lastScanCentre;
            tickOfLastScan = lastScanTick;
        }

        if (patterns.isEmpty() || client.player == null || client.level == null) {
            return;
        }

        BlockPos centre = client.player.blockPosition();
        long now = client.level.getGameTime();

        boolean moved = centreOfLastScan == null
            || centreOfLastScan.distSqr(centre) > (double) RESCAN_DISTANCE * RESCAN_DISTANCE;
        boolean stale = now - tickOfLastScan >= RESCAN_INTERVAL_TICKS;

        if (!moved && !stale) {
            return;
        }

        // The level is captured now, on the client thread, rather than being
        // reached for from the worker: by the time the scan runs the player may
        // have changed dimension, and reading client.level then could hand the
        // scan a world the results would be meaningless in.
        ClientLevel level = client.level;

        synchronized (GrepState.class) {
            scanning = true;
        }

        // Marked before the scan rather than after, so the throttle measures the
        // interval between scans starting. Recording it on completion would let
        // a scan that takes longer than the interval trigger the next one the
        // instant it published, leaving the worker permanently busy.
        synchronized (GrepState.class) {
            lastScanCentre = centre;
            lastScanTick = now;
        }

        SCANNER.execute(() -> {
            List<Hit> found = new ArrayList<>();
            try {
                PatternScanner.ChunkSource source = new LevelChunkSource(level);

                // The limit is shared out rather than applied per pattern, so
                // that the total drawn stays bounded however many patterns are
                // active. Dividing it evenly means one very common pattern
                // cannot crowd out the rest, which matters because the scan is
                // nearest-first: each pattern gets its own share of nearby
                // results instead of one pattern consuming the lot.
                int share = Math.max(1, currentLimit / patterns.size());

                for (ActivePattern entry : patterns) {
                    List<PatternScanner.Match> matches = PatternScanner.scan(
                        source, entry.pattern(), centre, currentRadius, share,
                        entry.symmetry());
                    for (PatternScanner.Match match : matches) {
                        found.add(new Hit(entry, match));
                    }
                }
            } catch (RuntimeException e) {
                // A chunk going away underneath the scan is the expected case.
                // Dropping this scan's results costs one refresh; letting the
                // exception escape would kill the worker for the session.
                Constants.LOGGER.debug("Scan abandoned", e);
                synchronized (GrepState.class) {
                    scanning = false;
                    // Force the next tick to retry rather than waiting out the
                    // dwell interval on results that were never produced.
                    lastScanCentre = null;
                }
                return;
            }

            synchronized (GrepState.class) {
                scanning = false;
                // Only publish if the patterns have not been swapped mid-scan;
                // otherwise these results describe a search nobody asked for
                // any more.
                if (active == patterns) {
                    hits = List.copyOf(found);
                }
            }
        });
    }

    /**
     * Adapts the client's level to the scanner's narrow view of the world.
     *
     * Only fully-loaded chunks are consulted: a partially-populated chunk can
     * report air for blocks that do exist, which would produce matches that
     * vanish a moment later.
     */
    private record LevelChunkSource(ClientLevel level) implements PatternScanner.ChunkSource {

        @Override
        public ChunkAccess getChunk(int chunkX, int chunkZ) {
            return level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return level.getBlockState(pos);
        }

        @Override
        public int minY() {
            return level.getMinY();
        }

        @Override
        public int maxY() {
            return level.getMaxY();
        }
    }
}
