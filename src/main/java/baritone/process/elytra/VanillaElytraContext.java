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
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A pure-Java {@link ElytraTerrainProvider} backed by real loaded chunk data, for use in dimensions other
 * than the Nether (where the native, terrain-predicting {@link NetherPathfinderContext} isn't available -
 * see its javadoc for why). Reactive only: no long-range terrain prediction, just a straight line to the
 * destination with BFS-found detour waypoints around anything in the way. Works at any Y, in any dimension.
 *
 * @author Brady
 */
public final class VanillaElytraContext implements ElytraTerrainProvider {

    private static final int MAX_NODES_EXPLORED = 5000;
    private static final int STEP = 8;

    private final IPlayerContext ctx;
    private final ExecutorService executor;
    private final Object cullingLock = new Object();

    public VanillaElytraContext(final IPlayerContext ctx) {
        this.ctx = ctx;
        this.executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public boolean hasChunk(final ChunkPos pos) {
        return this.ctx.world() != null && this.ctx.world().hasChunk(pos.x(), pos.z());
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

    @Override
    public Object cullingLock() {
        return this.cullingLock;
    }

    @Override
    public boolean isPassable(final int x, final int y, final int z) {
        final BlockPos pos = new BlockPos(x, y, z);
        final BlockState state = this.ctx.world().getBlockState(pos);
        return state.getCollisionShape(this.ctx.world(), pos).isEmpty();
    }

    @Override
    public boolean raytrace(final Vec3 start, final Vec3 end) {
        if (start.equals(end)) {
            return true;
        }
        final HitResult result = this.ctx.world().clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.ctx.player()
        ));
        return result.getType() == HitResult.Type.MISS;
    }

    @Override
    public CompletableFuture<UnpackedSegment> pathFindAsync(final BlockPos src, final BlockPos dst) {
        return CompletableFuture.supplyAsync(() -> this.findPath(src, dst), this.executor);
    }

    private UnpackedSegment findPath(final BlockPos src, final BlockPos dst) {
        final Vec3 start = Vec3.atCenterOf(src);
        final Vec3 end = Vec3.atCenterOf(dst);

        if (this.raytrace(start, end)) {
            return UnpackedSegment.of(List.of(new BetterBlockPos(src), new BetterBlockPos(dst)), true);
        }

        final List<BetterBlockPos> route = this.findRoute(src, dst);
        if (route == null) {
            throw new PathCalculationException("Could not find a way around the obstacle between " + src + " and " + dst);
        }
        return UnpackedSegment.of(this.roundCorners(this.smoothRoute(route)), true);
    }

    /**
     * The grid search only ever steps in the 6 axis-aligned directions, so raw routes come out as a
     * staircase of 90-degree turns - too sharp for the flight solver to actually fly without stalling or
     * circling to reorient. This is a classic "string pulling" pass: from each waypoint, greedily jump ahead
     * to the farthest later waypoint that's still a clear line of sight away, skipping everything in between.
     * That collapses zig-zags around a corner into far fewer, straighter segments.
     */
    private List<BetterBlockPos> smoothRoute(final List<BetterBlockPos> route) {
        final List<BetterBlockPos> smoothed = new ArrayList<>();
        smoothed.add(route.get(0));

        int i = 0;
        while (i < route.size() - 1) {
            final Vec3 fromVec = Vec3.atCenterOf(route.get(i));
            int farthest = i + 1;
            for (int j = route.size() - 1; j > i + 1; j--) {
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
            current = roundCornersPass(current);
        }
        return current;
    }

    private List<BetterBlockPos> roundCornersPass(final List<BetterBlockPos> route) {
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

    private static final int CORNER_ROUNDING_ITERATIONS = 3;
    private static final double CORNER_CUT_RATIO = 0.3;
    private static final double SHARP_TURN_ANGLE_DEGREES = 25;
    private static final double MIN_EDGE_LENGTH_TO_CUT = 3.0;

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

    /**
     * Greedy best-first search over a coarse grid: from {@code src}, repeatedly expands the frontier node
     * closest to {@code dst}, only linking to neighbors that are visible (raytrace-clear) from it - so every
     * consecutive pair of waypoints in the returned route is guaranteed to have a clear line of sight, which
     * is what {@link ElytraBehavior}'s flight solver assumes between path points. Terminates as soon as a
     * frontier node has a clear view of {@code dst} itself, and reconstructs the route back to {@code src}.
     * Bounded by {@link #MAX_NODES_EXPLORED} so a route that would require weaving through very enclosed
     * terrain just fails fast rather than hanging.
     */
    private List<BetterBlockPos> findRoute(final BlockPos src, final BlockPos dst) {
        final BetterBlockPos start = new BetterBlockPos(src);
        final BetterBlockPos goal = new BetterBlockPos(dst);
        final Vec3 goalVec = Vec3.atCenterOf(goal);

        final Map<BetterBlockPos, BetterBlockPos> cameFrom = new HashMap<>();
        final Comparator<BetterBlockPos> byDistToGoal = Comparator.comparingDouble(p -> p.distSqr(goal));
        final Queue<BetterBlockPos> frontier = new PriorityQueue<>(byDistToGoal);
        final Set<BetterBlockPos> visited = new HashSet<>();
        frontier.add(start);
        visited.add(start);

        int explored = 0;
        while (!frontier.isEmpty() && explored++ < MAX_NODES_EXPLORED) {
            final BetterBlockPos current = frontier.poll();
            if (!current.equals(start) && this.raytrace(Vec3.atCenterOf(current), goalVec)) {
                return reconstructRoute(cameFrom, current, goal);
            }
            final Vec3 currentVec = Vec3.atCenterOf(current);
            for (final BetterBlockPos next : neighbors(current)) {
                if (!visited.contains(next) && this.raytrace(currentVec, Vec3.atCenterOf(next))) {
                    visited.add(next);
                    cameFrom.put(next, current);
                    frontier.add(next);
                }
            }
        }
        return null;
    }

    private static List<BetterBlockPos> reconstructRoute(final Map<BetterBlockPos, BetterBlockPos> cameFrom, final BetterBlockPos last, final BetterBlockPos goal) {
        final List<BetterBlockPos> route = new ArrayList<>();
        route.add(goal);
        BetterBlockPos cur = last;
        while (cur != null) {
            route.add(cur);
            cur = cameFrom.get(cur);
        }
        Collections.reverse(route);
        return route;
    }

    private static List<BetterBlockPos> neighbors(final BetterBlockPos pos) {
        return List.of(
                new BetterBlockPos(pos.x + STEP, pos.y, pos.z),
                new BetterBlockPos(pos.x - STEP, pos.y, pos.z),
                new BetterBlockPos(pos.x, pos.y + STEP, pos.z),
                new BetterBlockPos(pos.x, pos.y - STEP, pos.z),
                new BetterBlockPos(pos.x, pos.y, pos.z + STEP),
                new BetterBlockPos(pos.x, pos.y, pos.z - STEP)
        );
    }

    @Override
    public void destroy() {
        this.executor.shutdownNow();
    }
}
