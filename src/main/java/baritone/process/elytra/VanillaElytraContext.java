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
import baritone.process.elytra.VanillaElytraOccupancy.OccupancySnapshot;
import baritone.process.elytra.VanillaElytraOccupancy.PackedColumn;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;

/**
 * Pure-Java {@link ElytraTerrainProvider} for dimensions other than the Nether. Packs air/solid bits on
 * the pathfinder executor; occupancy updates when pack finishes. The pathfinder worker and flight
 * solver read a frozen snapshot. Unknown terrain is solid, matching {@code NetherPathfinder.CACHE_MISS_SOLID}.
 *
 * @author Brady
 */
public final class VanillaElytraContext implements ElytraTerrainProvider {

    static final int STEP = 8;
    static final int GOAL_RADIUS = STEP;
    static final int MAX_NODES_EXPLORED = 20_000;
    static final long MAX_SEARCH_NANOS = 30_000_000L;
    static final double HEURISTIC_WEIGHT = 1.5;
    static final double CLIMB_COST_MULT = 1.6;
    static final double DESCEND_COST_MULT = 0.85;
    private static final int Y_BAND = 96;

    private static final int CORNER_ROUNDING_ITERATIONS = 3;
    private static final double CORNER_CUT_RATIO = 0.3;
    private static final double SHARP_TURN_ANGLE_DEGREES = 25;
    private static final double MIN_EDGE_LENGTH_TO_CUT = 3.0;
    private static final int SMOOTH_LOOKAHEAD = 32;
    private static final int MAX_EDGE_LENGTH = 16;

    static final int NEIGHBOR_COUNT = 26;
    static final int[] NEIGHBOR_DX = new int[NEIGHBOR_COUNT];
    static final int[] NEIGHBOR_DY = new int[NEIGHBOR_COUNT];
    static final int[] NEIGHBOR_DZ = new int[NEIGHBOR_COUNT];
    static final double[] NEIGHBOR_STEP_COST = new double[NEIGHBOR_COUNT];

    static {
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    NEIGHBOR_DX[i] = dx;
                    NEIGHBOR_DY[i] = dy;
                    NEIGHBOR_DZ[i] = dz;
                    NEIGHBOR_STEP_COST[i] = neighborStepCost(dx, dy, dz);
                    i++;
                }
            }
        }
    }

    private final IPlayerContext ctx;
    private final ExecutorService executor;
    private final Object cullingLock = new Object();
    private final int worldMinY;
    private final int worldMaxY;
    private final ConcurrentHashMap<Long, PackedColumn> occupancy = new ConcurrentHashMap<>();
    private final LongFunction<PackedColumn> liveColumns = this.occupancy::get;
    private final VanillaElytraPackQueue packQueue;
    private volatile boolean destroyed;
    private volatile boolean lastSearchPresent;
    private volatile int lastExplored;
    private volatile long lastSearchNanos;
    private volatile boolean lastReached;

    public VanillaElytraContext(final IPlayerContext ctx) {
        this.ctx = ctx;
        final Level world = ctx.world();
        this.worldMinY = world.getMinY();
        // exclusive top, same as minY + height (getMaxY is inclusive)
        this.worldMaxY = world.getMinY() + world.getHeight();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            final Thread thread = new Thread(r, "Baritone Vanilla Elytra Pathfinder");
            thread.setDaemon(true);
            return thread;
        });
        this.packQueue = new VanillaElytraPackQueue(this.occupancy, this.executor, () -> this.destroyed);
    }

    private boolean aborted() {
        return this.destroyed || Thread.currentThread().isInterrupted();
    }

    private final BooleanSupplier abort = this::aborted;

    @Override
    public boolean hasChunk(final ChunkPos pos) {
        return !this.destroyed && this.occupancy.containsKey(VanillaElytraOccupancy.chunkKey(pos.x(), pos.z()));
    }

    @Override
    public void queueForPacking(final LevelChunk chunk) {
        if (this.destroyed || chunk == null) {
            return;
        }
        final SoftReference<LevelChunk> ref = new SoftReference<>(chunk);
        final ChunkPos pos = chunk.getPos();
        final long key = VanillaElytraOccupancy.chunkKey(pos.x(), pos.z());
        this.packQueue.submitPack(key, () -> {
            final LevelChunk live = ref.get();
            return live == null ? null : VanillaElytraOccupancy.pack(live);
        });
    }

    @Override
    public void queueBlockUpdate(final BlockChangeEvent event) {
        if (this.destroyed) {
            return;
        }
        final ChunkPos pos = event.getChunkPos();
        final long key = VanillaElytraOccupancy.chunkKey(pos.x(), pos.z());
        final boolean missing;
        synchronized (this.packQueue) {
            PackedColumn col = this.occupancy.get(key);
            missing = col == null;
            if (col == null) {
                this.packQueue.invalidate(key);
            } else {
                for (final var pair : event.getBlocks()) {
                    final BlockPos block = pair.first();
                    final BlockState state = pair.second();
                    col = VanillaElytraOccupancy.setSolid(col, block.getX(), block.getY(), block.getZ(), !state.isAir());
                }
                this.occupancy.put(key, col);
                this.packQueue.invalidate(key);
            }
        }
        if (missing && this.ctx.world() != null) {
            final LevelChunk chunk = this.ctx.world().getChunkSource().getChunk(pos.x(), pos.z(), false);
            if (chunk != null) {
                this.queueForPacking(chunk);
            }
        }
    }

    @Override
    public void queueCacheCulling(final int chunkX, final int chunkZ, final int maxDistanceBlocks) {
        if (this.destroyed) {
            return;
        }
        this.executor.execute(() -> {
            synchronized (this.cullingLock) {
                if (this.destroyed) {
                    return;
                }
                final int maxChunks = Math.max(1, maxDistanceBlocks >> 4);
                synchronized (this.packQueue) {
                    this.occupancy.entrySet().removeIf(e -> {
                        final PackedColumn col = e.getValue();
                        final int dx = Math.abs(col.chunkX - chunkX);
                        final int dz = Math.abs(col.chunkZ - chunkZ);
                        if (Math.max(dx, dz) > maxChunks) {
                            this.packQueue.invalidate(e.getKey());
                            return true;
                        }
                        return false;
                    });
                }
            }
        });
    }

    @Override
    public void dropColumn(final int chunkX, final int chunkZ) {
        if (this.destroyed) {
            return;
        }
        final long key = VanillaElytraOccupancy.chunkKey(chunkX, chunkZ);
        synchronized (this.packQueue) {
            this.packQueue.invalidate(key);
            this.occupancy.remove(key);
        }
    }

    @Override
    public Object cullingLock() {
        return this.cullingLock;
    }

    @Override
    public boolean isPassable(final int x, final int y, final int z) {
        if (this.destroyed) {
            return false;
        }
        return VanillaElytraOccupancy.isPassable(this.liveColumns, this.worldMinY, this.worldMaxY, x, y, z);
    }

    @Override
    public boolean raytrace(final Vec3 start, final Vec3 end) {
        return this.raytrace(start.x, start.y, start.z, end.x, end.y, end.z);
    }

    boolean raytrace(final double x0, final double y0, final double z0,
                     final double x1, final double y1, final double z1) {
        if (this.destroyed) {
            return false;
        }
        return VanillaElytraOccupancy.raytrace(
                this.liveColumns, this.worldMinY, this.worldMaxY, x0, y0, z0, x1, y1, z1, this.abort);
    }

    @Override
    public boolean raytraceBatch(final int count, final double[] src, final double[] dst) {
        for (int i = 0; i < count; i++) {
            final int o = i * 3;
            if (!this.raytrace(src[o], src[o + 1], src[o + 2], dst[o], dst[o + 1], dst[o + 2])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public CompletableFuture<UnpackedSegment> pathFindAsync(final BlockPos src, final BlockPos dst) {
        if (this.destroyed) {
            return CompletableFuture.failedFuture(new PathCalculationException("destroyed"));
        }
        // Capture chunks on the caller; pack + freeze + search on the executor. Do not join
        // a pack future here: the tick thread may already hold cullingLock, and cull tasks
        // on this same executor also take that lock.
        final long srcKey = VanillaElytraOccupancy.chunkKey(src.getX() >> 4, src.getZ() >> 4);
        final long dstKey = VanillaElytraOccupancy.chunkKey(dst.getX() >> 4, dst.getZ() >> 4);
        final int srcGen = this.packQueue.generation(srcKey);
        final int dstGen = this.packQueue.generation(dstKey);
        final LevelChunk srcChunk = this.unpackedChunk(src);
        final LevelChunk dstChunk = this.unpackedChunk(dst);
        return CompletableFuture.supplyAsync(() -> {
            this.packCaptured(src, srcChunk, srcGen);
            this.packCaptured(dst, dstChunk, dstGen);
            return this.findPath(this.freeze(), src, dst);
        }, this.executor);
    }

    private LevelChunk unpackedChunk(final BlockPos pos) {
        if (this.destroyed) {
            return null;
        }
        final Level world = this.ctx.world();
        if (world == null) {
            return null;
        }
        final int cx = pos.getX() >> 4;
        final int cz = pos.getZ() >> 4;
        final long key = VanillaElytraOccupancy.chunkKey(cx, cz);
        if (this.occupancy.containsKey(key)) {
            return null;
        }
        return world.getChunkSource().getChunk(cx, cz, false);
    }

    private void packCaptured(final BlockPos pos, final LevelChunk chunk, final int gen) {
        if (this.destroyed || chunk == null) {
            return;
        }
        final long key = VanillaElytraOccupancy.chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
        if (this.occupancy.containsKey(key)) {
            return;
        }
        final PackedColumn column = VanillaElytraOccupancy.pack(chunk);
        this.packQueue.tryPut(key, column, gen);
    }

    private void recordSearch(final int explored, final long nanos, final boolean reached) {
        this.lastSearchPresent = true;
        this.lastExplored = explored;
        this.lastSearchNanos = nanos;
        this.lastReached = reached;
    }

    int occupancyColumns() {
        return this.occupancy.size();
    }

    VanillaElytraPackQueue packQueue() {
        return this.packQueue;
    }

    Integer lastExplored() {
        return this.lastSearchPresent ? this.lastExplored : null;
    }

    Long lastSearchNanos() {
        return this.lastSearchPresent ? this.lastSearchNanos : null;
    }

    Boolean lastReached() {
        return this.lastSearchPresent ? this.lastReached : null;
    }

    OccupancySnapshot freeze() {
        synchronized (this.packQueue) {
            return VanillaElytraOccupancy.freeze(this.occupancy, this.worldMinY, this.worldMaxY, this.abort);
        }
    }

    /**
     * Solver freeze keyed by occupancy stamp. Same snapshot instance if {@code previousStamp} still matches.
     */
    VanillaElytraPackQueue.SolverFreeze freezeForSolve(final OccupancySnapshot previous, final long previousStamp) {
        return this.packQueue.freezeForSolve(previous, previousStamp, this.worldMinY, this.worldMaxY, this.abort);
    }

    private record SearchResult(List<BetterBlockPos> route, boolean reachedGoal) {}

    private UnpackedSegment findPath(final OccupancySnapshot snap, final BlockPos src, final BlockPos dst) {
        if (this.aborted()) {
            throw new PathCalculationException("destroyed");
        }
        if (snap.raytraceCenters(src.getX(), src.getY(), src.getZ(), dst.getX(), dst.getY(), dst.getZ())) {
            this.recordSearch(0, 0L, true);
            return UnpackedSegment.of(this.subdivide(List.of(new BetterBlockPos(src), new BetterBlockPos(dst))), true);
        }

        final long started = System.nanoTime();
        final SearchResult result = this.findRoute(snap, src, dst);
        if (result == null) {
            this.recordSearch(this.lastExplored, System.nanoTime() - started, false);
            throw new PathCalculationException("No route out of " + src);
        }
        this.recordSearch(this.lastExplored, System.nanoTime() - started, result.reachedGoal());
        final List<BetterBlockPos> route = this.subdivide(this.roundCorners(snap, this.smoothRoute(snap, result.route())));
        if (route.size() < 2) {
            throw new PathCalculationException("Route from " + src + " collapsed to a single point");
        }
        return UnpackedSegment.of(route, result.reachedGoal());
    }

    private List<BetterBlockPos> smoothRoute(final OccupancySnapshot snap, final List<BetterBlockPos> route) {
        if (route.size() < 2) {
            return new ArrayList<>(route);
        }
        final List<BetterBlockPos> smoothed = new ArrayList<>();
        smoothed.add(route.get(0));

        int i = 0;
        while (i < route.size() - 1) {
            final BetterBlockPos from = route.get(i);
            int farthest = i + 1;
            final int limit = Math.min(route.size() - 1, i + SMOOTH_LOOKAHEAD);
            for (int j = limit; j > i + 1; j--) {
                final BetterBlockPos to = route.get(j);
                if (snap.raytraceCenters(from.x, from.y, from.z, to.x, to.y, to.z)) {
                    farthest = j;
                    break;
                }
            }
            smoothed.add(route.get(farthest));
            i = farthest;
        }
        return smoothed;
    }

    private List<BetterBlockPos> roundCorners(final OccupancySnapshot snap, final List<BetterBlockPos> route) {
        List<BetterBlockPos> current = route;
        for (int iter = 0; iter < CORNER_ROUNDING_ITERATIONS && current.size() > 2; iter++) {
            current = dedupe(this.roundCornersPass(snap, current));
        }
        return current;
    }

    private List<BetterBlockPos> roundCornersPass(final OccupancySnapshot snap, final List<BetterBlockPos> route) {
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
                    || !snap.raytraceCenters(cut1.x, cut1.y, cut1.z, cut2.x, cut2.y, cut2.z)) {
                result.add(cur);
                continue;
            }

            result.add(cut1);
            result.add(cut2);
        }

        result.add(route.get(route.size() - 1));
        return result;
    }

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

    private static BetterBlockPos lerp(final BetterBlockPos from, final BetterBlockPos to, final double ratio) {
        return new BetterBlockPos(
                from.x + (int) Math.round((to.x - from.x) * ratio),
                from.y + (int) Math.round((to.y - from.y) * ratio),
                from.z + (int) Math.round((to.z - from.z) * ratio)
        );
    }

    /**
     * Min-heap of packed positions ordered by f. Duplicate packed keys stay in the heap
     * (decrease-key via extra entries). Compare is {@link Double#compare} on f only.
     */
    static final class PackedFHeap {
        private long[] packed = new long[11];
        private double[] f = new double[11];
        private int size;

        boolean isEmpty() {
            return this.size == 0;
        }

        void add(final long packedPos, final double priority) {
            if (this.size == this.packed.length) {
                final int cap = this.packed.length << 1;
                this.packed = java.util.Arrays.copyOf(this.packed, cap);
                this.f = java.util.Arrays.copyOf(this.f, cap);
            }
            this.packed[this.size] = packedPos;
            this.f[this.size] = priority;
            this.siftUp(this.size);
            this.size++;
        }

        long poll() {
            final long result = this.packed[0];
            this.size--;
            if (this.size > 0) {
                this.packed[0] = this.packed[this.size];
                this.f[0] = this.f[this.size];
                this.siftDown(0);
            }
            return result;
        }

        private void siftUp(int i) {
            while (i > 0) {
                final int parent = (i - 1) >>> 1;
                if (Double.compare(this.f[i], this.f[parent]) >= 0) {
                    break;
                }
                this.swap(i, parent);
                i = parent;
            }
        }

        private void siftDown(int i) {
            while (true) {
                final int left = (i << 1) + 1;
                if (left >= this.size) {
                    break;
                }
                int best = left;
                final int right = left + 1;
                if (right < this.size && Double.compare(this.f[right], this.f[left]) < 0) {
                    best = right;
                }
                if (Double.compare(this.f[best], this.f[i]) >= 0) {
                    break;
                }
                this.swap(i, best);
                i = best;
            }
        }

        private void swap(final int a, final int b) {
            final long p = this.packed[a];
            this.packed[a] = this.packed[b];
            this.packed[b] = p;
            final double pri = this.f[a];
            this.f[a] = this.f[b];
            this.f[b] = pri;
        }
    }

    static double fScore(final double g, final int x, final int y, final int z,
                         final int gx, final int gy, final int gz) {
        return g + HEURISTIC_WEIGHT * Math.sqrt(distSq(x, y, z, gx, gy, gz));
    }

    private SearchResult findRoute(final OccupancySnapshot snap, final BlockPos src, final BlockPos dst) {
        final int startX = src.getX();
        final int startY = src.getY();
        final int startZ = src.getZ();
        final int goalX = dst.getX();
        final int goalY = dst.getY();
        final int goalZ = dst.getZ();
        final long startPacked = VanillaElytraOccupancy.packPos(startX, startY, startZ);

        final int minY = Math.max(snap.worldMinY + 1, Math.min(startY, goalY) - Y_BAND);
        final int maxY = Math.min(snap.worldMaxY - 1, Math.max(startY, goalY) + Y_BAND);

        final Long2LongOpenHashMap cameFrom = new Long2LongOpenHashMap();
        cameFrom.defaultReturnValue(Long.MIN_VALUE);
        final Long2DoubleOpenHashMap gScore = new Long2DoubleOpenHashMap();
        gScore.defaultReturnValue(Double.POSITIVE_INFINITY);
        final LongOpenHashSet closed = new LongOpenHashSet();
        final PackedFHeap frontier = new PackedFHeap();
        this.lastExplored = 0;

        gScore.put(startPacked, 0.0);
        frontier.add(startPacked, 0.0);

        int bestX = startX;
        int bestY = startY;
        int bestZ = startZ;
        double bestHeuristicSq = Double.MAX_VALUE;
        boolean foundBest = false;

        final long deadline = System.nanoTime() + MAX_SEARCH_NANOS;
        final double goalRadiusSq = (double) GOAL_RADIUS * (double) GOAL_RADIUS;
        int explored = 0;

        while (!frontier.isEmpty()) {
            if (this.aborted()) {
                break;
            }
            if (explored >= MAX_NODES_EXPLORED || System.nanoTime() > deadline) {
                break;
            }
            final long packed = frontier.poll();
            if (!closed.add(packed)) {
                continue;
            }
            explored++;
            this.lastExplored = explored;

            final int x = VanillaElytraOccupancy.unpackX(packed);
            final int y = VanillaElytraOccupancy.unpackY(packed);
            final int z = VanillaElytraOccupancy.unpackZ(packed);

            final double heuristicSq = distSq(x, y, z, goalX, goalY, goalZ);
            if (heuristicSq < bestHeuristicSq) {
                bestHeuristicSq = heuristicSq;
                bestX = x;
                bestY = y;
                bestZ = z;
                foundBest = true;
            }

            if (x == goalX && y == goalY && z == goalZ) {
                return new SearchResult(reconstructRoute(cameFrom, packed, startPacked, null), true);
            }
            if (heuristicSq <= goalRadiusSq) {
                if (snap.raytraceCenters(x, y, z, goalX, goalY, goalZ)) {
                    return new SearchResult(reconstructRoute(cameFrom, packed, startPacked, dst), true);
                }
            }

            final double currentG = gScore.get(packed);

            for (int n = 0; n < NEIGHBOR_COUNT; n++) {
                final int ny = y + NEIGHBOR_DY[n] * STEP;
                if (ny < minY || ny > maxY) {
                    continue;
                }
                final int nx = x + NEIGHBOR_DX[n] * STEP;
                final int nz = z + NEIGHBOR_DZ[n] * STEP;
                final long nextPacked = VanillaElytraOccupancy.packPos(nx, ny, nz);
                if (closed.contains(nextPacked)) {
                    continue;
                }
                final double tentativeG = currentG + NEIGHBOR_STEP_COST[n];
                if (tentativeG >= gScore.get(nextPacked)) {
                    continue;
                }
                if (!snap.isPassable(nx, ny, nz)
                        || !snap.raytraceCenters(x, y, z, nx, ny, nz)) {
                    continue;
                }
                gScore.put(nextPacked, tentativeG);
                cameFrom.put(nextPacked, packed);
                frontier.add(nextPacked, fScore(tentativeG, nx, ny, nz, goalX, goalY, goalZ));
            }
        }

        if (this.aborted()) {
            throw new PathCalculationException("destroyed");
        }
        if (!foundBest || (bestX == startX && bestY == startY && bestZ == startZ)) {
            return null;
        }
        return new SearchResult(
                reconstructRoute(cameFrom, VanillaElytraOccupancy.packPos(bestX, bestY, bestZ), startPacked, null),
                false);
    }

    private static double dist(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
        return Math.sqrt(distSq(x0, y0, z0, x1, y1, z1));
    }

    private static double distSq(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
        final double dx = x0 - x1;
        final double dy = y0 - y1;
        final double dz = z0 - z1;
        return dx * dx + dy * dy + dz * dz;
    }

    static double neighborStepCost(final int dx, final int dy, final int dz) {
        return dist(0, 0, 0, dx * STEP, dy * STEP, dz * STEP)
                * (dy > 0 ? CLIMB_COST_MULT : dy < 0 ? DESCEND_COST_MULT : 1.0);
    }

    private static List<BetterBlockPos> reconstructRoute(final Long2LongOpenHashMap cameFrom,
                                                         final long last,
                                                         final long start,
                                                         final BlockPos goal) {
        final List<BetterBlockPos> route = new ArrayList<>();
        if (goal != null && VanillaElytraOccupancy.packPos(goal.getX(), goal.getY(), goal.getZ()) != last) {
            route.add(new BetterBlockPos(goal));
        }
        long cur = last;
        while (true) {
            route.add(new BetterBlockPos(
                    VanillaElytraOccupancy.unpackX(cur),
                    VanillaElytraOccupancy.unpackY(cur),
                    VanillaElytraOccupancy.unpackZ(cur)));
            if (cur == start) {
                break;
            }
            final long prev = cameFrom.get(cur);
            if (prev == Long.MIN_VALUE) {
                break;
            }
            cur = prev;
        }
        Collections.reverse(route);
        return dedupe(route);
    }

    @Override
    public void destroy() {
        this.destroyed = true;
        this.occupancy.clear();
        this.executor.shutdownNow();
        try {
            if (!this.executor.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                System.out.println("Vanilla elytra pathfinder did not shut down in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
