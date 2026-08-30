/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.elytra;

import baritone.api.event.events.BlockChangeEvent;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A pure-Java {@link ElytraTerrainProvider} backed by real loaded chunk data, for use in dimensions other
 * than the Nether (where the native, terrain-predicting {@link NetherPathfinderContext} isn't available -
 * see its javadoc for why). Reactive only: no long-range terrain prediction, so it plans only as far as the
 * client has actually loaded and reports the resulting segment as unfinished, leaving {@link ElytraBehavior}
 * to extend it as more world streams in. Works at any Y, in any dimension.
 * <p>
 * <b>Unknown terrain counts as solid.</b> This mirrors {@code NetherPathfinder.CACHE_MISS_SOLID}, which the
 * native backend uses and which {@link ElytraBehavior} assumes throughout. Vanilla's {@code Level.clip}
 * has the opposite convention - an unloaded chunk reads back as void air, so a ray fired across one reports
 * "clear" - and taking that at face value produces straight-line paths through mountains that simply hadn't
 * rendered yet. Every terrain read here is therefore gated on the chunk actually being loaded.
 *
 * @author Brady
 */
public final class VanillaElytraContext implements ElytraTerrainProvider {

    /** Spacing of the search lattice, in blocks. */
    private static final int STEP = 8;
    /** A node this close to the goal is a candidate for terminating the search. */
    private static final int GOAL_RADIUS = STEP;
    /** Hard cap on expansions. Overrunning it is benign - the best node found so far is returned. */
    private static final int MAX_NODES_EXPLORED = 20_000;
    /** Wall-clock cap. The search runs off-thread but its result is consumed at most once per tick. */
    private static final long MAX_SEARCH_NANOS = 30_000_000L;
    /** Weight on the heuristic. Above 1 trades optimality for a much smaller explored set in open air. */
    private static final double HEURISTIC_WEIGHT = 1.5;
    /** Gaining altitude burns fireworks; losing it is free. Bias the search accordingly. */
    private static final double CLIMB_COST_MULT = 1.6;
    private static final double DESCEND_COST_MULT = 0.85;
    /** Vertical half-width of the band the search may wander into, relative to the src/dst extremes. */
    private static final int Y_BAND = 96;
    /** Chunk-presence sampling interval along a ray. Must stay well under a chunk's 16-block width. */
    private static final double CHUNK_PROBE_INTERVAL = 4.0;

    private static final int CORNER_ROUNDING_ITERATIONS = 3;
    private static final double CORNER_CUT_RATIO = 0.3;
    private static final double SHARP_TURN_ANGLE_DEGREES = 25;
    private static final double MIN_EDGE_LENGTH_TO_CUT = 3.0;
    /** Cap on how far ahead string pulling looks, to keep it from being O(n^2) in long raytraces. */
    private static final int SMOOTH_LOOKAHEAD = 32;
    /**
     * Longest edge handed to {@link ElytraBehavior}. Its progress tracking, stall detection and solver
     * lookahead are all measured in path <i>indices</i>, calibrated against nether-pathfinder output that
     * is dense by construction, so string-pulled routes have to be re-subdivided before they leave here.
     */
    private static final int MAX_EDGE_LENGTH = 16;

    private final IPlayerContext ctx;
    private final ExecutorService executor;
    private final Object cullingLock = new Object();
    private volatile boolean destroyed;

    public VanillaElytraContext(final IPlayerContext ctx) {
        this.ctx = ctx;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            final Thread thread = new Thread(r, "Baritone Vanilla Elytra Pathfinder");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public boolean hasChunk(final ChunkPos pos) {
        final Level world = this.ctx.world();
        return world != null && world.hasChunk(pos.x(), pos.z());
    }

    @Override
    public void queueForPacking(final LevelChunk chunk) {
        // no-op - loaded chunk data is read directly, there's no separate native representation to pack
    }

    @Override
    public void queueBlockUpdate(final BlockChangeEvent event) {
        // no-op, same reason as queueForPacking
    }

    @Override
    public void queueCacheCulling(final int chunkX, final int chunkZ, final int maxDistanceBlocks) {
        // no-op, there's no cache to cull
    }

    /**
     * {@inheritDoc}
     * <p>
     * This backend holds no pointers into cached chunk data, so there is nothing for this lock to guard.
     * It exists only to satisfy the interface; callers synchronizing on it are not gaining any exclusion.
     */
    @Override
    public Object cullingLock() {
        return this.cullingLock;
    }

    /**
     * @return the chunk containing the given block coordinates, or {@code null} if it isn't loaded. Never
     * triggers a load, and never substitutes the empty chunk, so an unloaded column is reported honestly
     * rather than as air.
     */
    private LevelChunk chunkAt(final Level world, final int x, final int z) {
        return world.getChunkSource().getChunk(x >> 4, z >> 4, false);
    }

    @Override
    public boolean isPassable(final int x, final int y, final int z) {
        final Level world = this.ctx.world();
        if (world == null) {
            return false;
        }
        if (y < world.getMinY() || y >= world.getMaxY()) {
            return false;
        }
        final LevelChunk chunk = this.chunkAt(world, x, z);
        if (chunk == null) {
            return false; // unknown terrain is solid
        }
        // Deliberately air-only, matching NetherPathfinderContext's `state != AIR` packing rule. Water and
        // lava are not flyable, and treating foliage as an obstacle is merely conservative.
        final BlockState state = chunk.getBlockState(new BlockPos(x, y, z));
        return state.isAir();
    }

    @Override
    public boolean raytrace(final Vec3 start, final Vec3 end) {
        if (start.equals(end)) {
            return true;
        }
        final Level world = this.ctx.world();
        if (world == null) {
            return false;
        }
        if (!this.corridorLoaded(world, start, end)) {
            return false; // unknown terrain is solid
        }
        final HitResult result = world.clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()
        ));
        return result.getType() == HitResult.Type.MISS;
    }

    /**
     * @return {@code true} if every chunk column the segment passes through is loaded. Sampled rather than
     * walked exactly: at a {@value #CHUNK_PROBE_INTERVAL}-block interval a ray cannot cross a whole 16-wide
     * column undetected, only clip a corner of one, which is cheap insurance against a much more expensive
     * exact traversal on a path that is already gated by {@link #hasChunk}.
     */
    private boolean corridorLoaded(final Level world, final Vec3 start, final Vec3 end) {
        final double distance = start.distanceTo(end);
        final int probes = Math.max(1, Mth.ceil(distance / CHUNK_PROBE_INTERVAL));
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;
        for (int i = 0; i <= probes; i++) {
            final double t = (double) i / probes;
            final int x = Mth.floor(Mth.lerp(t, start.x, end.x));
            final int z = Mth.floor(Mth.lerp(t, start.z, end.z));
            final int chunkX = x >> 4;
            final int chunkZ = z >> 4;
            if (chunkX == lastChunkX && chunkZ == lastChunkZ) {
                continue;
            }
            lastChunkX = chunkX;
            lastChunkZ = chunkZ;
            if (world.getChunkSource().getChunk(chunkX, chunkZ, false) == null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public CompletableFuture<UnpackedSegment> pathFindAsync(final BlockPos src, final BlockPos dst) {
        return CompletableFuture.supplyAsync(() -> this.findPath(src, dst), this.executor);
    }

    /**
     * The outcome of a search: the waypoints found, and whether they actually arrive at the goal. A route
     * that stops short is not a failure - it's the honest answer for a backend that can only see as far as
     * the client has loaded, and {@link ElytraBehavior.PathManager} extends it once more world arrives.
     */
    private record SearchResult(List<BetterBlockPos> route, boolean reachedGoal) {}

    private UnpackedSegment findPath(final BlockPos src, final BlockPos dst) {
        final Vec3 start = Vec3.atCenterOf(src);
        final Vec3 end = Vec3.atCenterOf(dst);

        if (this.raytrace(start, end)) {
            // Straight shot, and `raytrace` has already confirmed the whole corridor is loaded.
            return UnpackedSegment.of(this.subdivide(List.of(new BetterBlockPos(src), new BetterBlockPos(dst))), true);
        }

        final SearchResult result = this.findRoute(src, dst);
        if (result == null) {
            // Nothing at all was reachable from the start position - a genuine dead end, not a horizon.
            throw new PathCalculationException("No route out of " + src);
        }
        final List<BetterBlockPos> route = this.subdivide(this.roundCorners(this.smoothRoute(result.route())));
        if (route.size() < 2) {
            throw new PathCalculationException("Route from " + src + " collapsed to a single point");
        }
        return UnpackedSegment.of(route, result.reachedGoal());
    }

    /**
     * The grid search only ever steps between lattice points, so raw routes come out as a staircase of
     * turns - too sharp for the flight solver to actually fly without stalling or circling to reorient.
     * This is a classic "string pulling" pass: from each waypoint, greedily jump ahead to the farthest
     * later waypoint that's still a clear line of sight away, skipping everything in between. That
     * collapses zig-zags around a corner into far fewer, straighter segments.
     */
    private List<BetterBlockPos> smoothRoute(final List<BetterBlockPos> route) {
        if (route.size() < 2) {
            return new ArrayList<>(route);
        }
        final List<BetterBlockPos> smoothed = new ArrayList<>();
        smoothed.add(route.get(0));

        int i = 0;
        while (i < route.size() - 1) {
            final Vec3 fromVec = Vec3.atCenterOf(route.get(i));
            int farthest = i + 1;
            final int limit = Math.min(route.size() - 1, i + SMOOTH_LOOKAHEAD);
            for (int j = limit; j > i + 1; j--) {
                if (this.raytrace(fromVec, Vec3.atCenterOf(route.get(j)))) {
                    farthest = j;
                    break;
                }
            }
            smoothed.add(route.get(farthest));
            i = farthest;
        }
        return smoothed;
    }

    /**
     * String pulling only guarantees each consecutive waypoint pair is visibility-clear - it says nothing about
     * the angle between segments. A single unavoidable sharp turn (e.g. rounding one building corner) survives
     * it untouched, and since elytra flight has heavy momentum, the flight solver can't snap its heading to
     * match, so it overshoots, corrects, overshoots again, and ends up circling around the corner instead of
     * just flying through it.
     * <p>
     * This rounds sharp corners via Chaikin corner-cutting: a vertex whose turn angle exceeds
     * {@link #SHARP_TURN_ANGLE_DEGREES} is replaced with two points pulled in from its neighboring edges,
     * turning the sharp bend into a short, gentle chord. Repeating this a few times converges towards a smooth
     * arc through the corner. A cut is only kept if the new chord is still visibility-clear, so this can never
     * route through terrain that the original waypoints were carefully placed to avoid - it just softens turns
     * that happen in open air.
     */
    private List<BetterBlockPos> roundCorners(final List<BetterBlockPos> route) {
        List<BetterBlockPos> current = route;
        for (int iter = 0; iter < CORNER_ROUNDING_ITERATIONS && current.size() > 2; iter++) {
            current = dedupe(roundCornersPass(current));
        }
        return current;
    }

    private List<BetterBlockPos> roundCornersPass(final List<BetterBlockPos> route) {
        if (route.size() < 3) {
            return new ArrayList<>(route);
        }
        final List<BetterBlockPos> result = new ArrayList<>();
        result.add(route.get(0));

        for (int i = 1; i < route.size() - 1; i++) {
            final BetterBlockPos prev = route.get(i - 1);
            final BetterBlockPos cur = route.get(i);
            final BetterBlockPos next = route.get(i + 1);

            if (isTurnGentle(prev, cur, next) || edgeTooShortToCut(prev, cur) || edgeTooShortToCut(cur, next)) {
                result.add(cur);
                continue;
            }

            final BetterBlockPos cut1 = lerp(cur, prev, CORNER_CUT_RATIO);
            final BetterBlockPos cut2 = lerp(cur, next, CORNER_CUT_RATIO);

            if (cut1.equals(cur) || cut2.equals(cur) || cut1.equals(cut2)
                    || !this.raytrace(Vec3.atCenterOf(cut1), Vec3.atCenterOf(cut2))) {
                // Either the cut collapsed to nothing, or the shortcut across the corner clips something -
                // keep the original vertex rather than risk flying through terrain.
                result.add(cur);
                continue;
            }

            result.add(cut1);
            result.add(cut2);
        }

        result.add(route.get(route.size() - 1));
        return result;
    }

    /**
     * Splits any edge longer than {@link #MAX_EDGE_LENGTH} into evenly spaced pieces. String pulling is
     * free to return three waypoints spanning hundreds of blocks, but {@link ElytraBehavior} measures
     * progress by path index: {@code updatePlayerNear} strides over indices, the stall detector recalculates
     * when that index hasn't advanced in 100 ticks, and the solver's lookahead is expressed in nodes. On a
     * 200-block edge none of those units mean anything, and the stall detector fires spuriously - a second,
     * independent source of the recalculation loop this class used to produce.
     */
    private List<BetterBlockPos> subdivide(final List<BetterBlockPos> route) {
        if (route.size() < 2) {
            return new ArrayList<>(route);
        }
        final List<BetterBlockPos> result = new ArrayList<>();
        result.add(route.get(0));
        for (int i = 1; i < route.size(); i++) {
            final BetterBlockPos from = route.get(i - 1);
            final BetterBlockPos to = route.get(i);
            final double distance = Math.sqrt(from.distSqr(to));
            final int pieces = Math.max(1, Mth.ceil(distance / MAX_EDGE_LENGTH));
            for (int piece = 1; piece <= pieces; piece++) {
                final BetterBlockPos point = lerp(from, to, (double) piece / pieces);
                if (!point.equals(result.get(result.size() - 1))) {
                    result.add(point);
                }
            }
        }
        return result;
    }

    /**
     * Removes consecutive duplicates. Corner cutting rounds to block coordinates, so the outgoing cut of one
     * vertex and the incoming cut of the next routinely land on the same block. Duplicates aren't cosmetic
     * here: a zero-length edge normalizes to the zero vector, whose dot product is 0, which
     * {@link #isTurnGentle} reads as a 90-degree turn and cuts again.
     */
    private static List<BetterBlockPos> dedupe(final List<BetterBlockPos> route) {
        final List<BetterBlockPos> result = new ArrayList<>(route.size());
        for (final BetterBlockPos pos : route) {
            if (result.isEmpty() || !result.get(result.size() - 1).equals(pos)) {
                result.add(pos);
            }
        }
        return result;
    }

    private static boolean isTurnGentle(final BetterBlockPos prev, final BetterBlockPos cur, final BetterBlockPos next) {
        final Vec3 incoming = Vec3.atLowerCornerOf(cur).subtract(Vec3.atLowerCornerOf(prev)).normalize();
        final Vec3 outgoing = Vec3.atLowerCornerOf(next).subtract(Vec3.atLowerCornerOf(cur)).normalize();
        final double cosAngle = Mth.clamp(incoming.dot(outgoing), -1.0, 1.0);
        final double angleDegrees = Math.toDegrees(Math.acos(cosAngle));
        return angleDegrees < SHARP_TURN_ANGLE_DEGREES;
    }

    private static boolean edgeTooShortToCut(final BetterBlockPos a, final BetterBlockPos b) {
        return a.distSqr(b) < MIN_EDGE_LENGTH_TO_CUT * MIN_EDGE_LENGTH_TO_CUT;
    }

    /**
     * @return the point {@code ratio} of the way from {@code from} towards {@code to}, rounded to the nearest block
     */
    private static BetterBlockPos lerp(final BetterBlockPos from, final BetterBlockPos to, final double ratio) {
        return new BetterBlockPos(
                from.x + (int) Math.round((to.x - from.x) * ratio),
                from.y + (int) Math.round((to.y - from.y) * ratio),
                from.z + (int) Math.round((to.z - from.z) * ratio)
        );
    }

    private static final class Node implements Comparable<Node> {

        private final BetterBlockPos pos;
        private final double priority;

        private Node(final BetterBlockPos pos, final double priority) {
            this.pos = pos;
            this.priority = priority;
        }

        @Override
        public int compareTo(final Node other) {
            return Double.compare(this.priority, other.priority);
        }
    }

    /**
     * Weighted A* over a coarse lattice, linking only neighbors that are visible (raytrace-clear) from the
     * node being expanded - so every consecutive pair of waypoints in the returned route is guaranteed to
     * have a clear line of sight, which is what {@link ElytraBehavior}'s flight solver assumes between path
     * points. Because unknown terrain reads as solid, the frontier stops at the edge of loaded chunks on its
     * own; no artificial search radius is needed, and the stopping point is exactly the client's horizon.
     * <p>
     * The search never reports outright failure while it made any progress at all. When the budget runs out,
     * or the frontier is exhausted, or the goal is simply beyond the horizon, it returns the route to the
     * expanded node closest to the goal, flagged as not having reached it. That is what makes the node and
     * time budgets tuning parameters rather than failure modes - the previous greedy version had a single
     * success condition (line of sight to the goal itself) and returned {@code null} for everything else,
     * which for a goal behind terrain meant a thrown exception on every attempt, forever.
     *
     * @return the best route found, or {@code null} only if nothing at all was reachable from {@code src}
     */
    private SearchResult findRoute(final BlockPos src, final BlockPos dst) {
        final Level world = this.ctx.world();
        if (world == null) {
            return null;
        }
        final BetterBlockPos start = new BetterBlockPos(src);
        final BetterBlockPos goal = new BetterBlockPos(dst);
        final Vec3 goalVec = Vec3.atCenterOf(goal);

        final int minY = Math.max(world.getMinY() + 1, Math.min(start.y, goal.y) - Y_BAND);
        final int maxY = Math.min(world.getMaxY() - 1, Math.max(start.y, goal.y) + Y_BAND);

        final Map<BetterBlockPos, BetterBlockPos> cameFrom = new HashMap<>();
        final Map<BetterBlockPos, Double> gScore = new HashMap<>();
        final Set<BetterBlockPos> closed = new HashSet<>();
        final PriorityQueue<Node> frontier = new PriorityQueue<>();

        gScore.put(start, 0.0);
        frontier.add(new Node(start, 0.0));

        BetterBlockPos best = null;
        double bestHeuristic = Double.MAX_VALUE;

        final long deadline = System.nanoTime() + MAX_SEARCH_NANOS;
        int explored = 0;

        while (!frontier.isEmpty()) {
            if (this.destroyed || Thread.currentThread().isInterrupted()) {
                break;
            }
            if (explored >= MAX_NODES_EXPLORED || System.nanoTime() > deadline) {
                break;
            }
            final Node node = frontier.poll();
            final BetterBlockPos current = node.pos;
            if (!closed.add(current)) {
                continue; // stale duplicate left over from a decrease-key
            }
            explored++;

            final double heuristic = Math.sqrt(current.distSqr(goal));
            if (heuristic < bestHeuristic) {
                bestHeuristic = heuristic;
                best = current;
            }

            // Proximity goal test, not line-of-sight-to-goal. The old visibility test was unsatisfiable
            // whenever the goal block was solid or buried, because a ray ending inside a collider is a hit.
            if (heuristic <= GOAL_RADIUS && !current.equals(goal)) {
                if (this.raytrace(Vec3.atCenterOf(current), goalVec)) {
                    return new SearchResult(reconstructRoute(cameFrom, current, goal), true);
                }
                // Close but the final hop is blocked - keep searching for an approach that isn't.
            }

            final double currentG = gScore.getOrDefault(current, Double.MAX_VALUE);
            final Vec3 currentVec = Vec3.atCenterOf(current);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        final int ny = current.y + dy * STEP;
                        if (ny < minY || ny > maxY) {
                            continue;
                        }
                        final BetterBlockPos next = new BetterBlockPos(
                                current.x + dx * STEP, ny, current.z + dz * STEP
                        );
                        if (closed.contains(next)) {
                            continue;
                        }
                        final double stepCost = Math.sqrt(current.distSqr(next))
                                * (dy > 0 ? CLIMB_COST_MULT : dy < 0 ? DESCEND_COST_MULT : 1.0);
                        final double tentativeG = currentG + stepCost;
                        if (tentativeG >= gScore.getOrDefault(next, Double.MAX_VALUE)) {
                            continue;
                        }
                        // Passability is far cheaper than a raytrace, and a node inside terrain would fail
                        // every one of its own outgoing rays anyway.
                        if (!this.isPassable(next.x, next.y, next.z)
                                || !this.raytrace(currentVec, Vec3.atCenterOf(next))) {
                            continue;
                        }
                        gScore.put(next, tentativeG);
                        cameFrom.put(next, current);
                        frontier.add(new Node(next, tentativeG + HEURISTIC_WEIGHT * Math.sqrt(next.distSqr(goal))));
                    }
                }
            }
        }

        if (best == null || best.equals(start)) {
            return null;
        }
        return new SearchResult(reconstructRoute(cameFrom, best, null), false);
    }

    /**
     * @param goal the goal to append, or {@code null} for a route that stops short of it. Appending a goal
     *             the caller hasn't just raytraced to would put a terrain-clipping final edge in the path,
     *             which {@code pathfindAroundObstacles} would then try, and fail, to route around forever.
     */
    private static List<BetterBlockPos> reconstructRoute(final Map<BetterBlockPos, BetterBlockPos> cameFrom,
                                                         final BetterBlockPos last,
                                                         final BetterBlockPos goal) {
        final List<BetterBlockPos> route = new ArrayList<>();
        if (goal != null && !goal.equals(last)) {
            route.add(goal);
        }
        BetterBlockPos cur = last;
        while (cur != null) {
            route.add(cur);
            cur = cameFrom.get(cur);
        }
        Collections.reverse(route);
        return dedupe(route);
    }

    @Override
    public void destroy() {
        this.destroyed = true;
        this.executor.shutdownNow();
        try {
            // Level.clip isn't interruptible, so wait for the worker to notice `destroyed` and stop reading
            // a world the client may be in the middle of tearing down.
            if (!this.executor.awaitTermination(1, TimeUnit.SECONDS)) {
                System.out.println("Vanilla elytra pathfinder did not shut down in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
