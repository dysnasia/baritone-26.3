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

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.*;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.process.ElytraProcess;
import baritone.utils.BlockStateInterface;
import baritone.utils.IRenderer;
import baritone.utils.PathRenderer;
import baritone.utils.accessor.IFireworkRocketEntity;
import com.mojang.blaze3d.vertex.BufferBuilder;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import static baritone.utils.BaritoneMath.fastCeil;
import static baritone.utils.BaritoneMath.fastFloor;

public final class ElytraBehavior implements Helper {
    private final Baritone baritone;
    private final IPlayerContext ctx;

    // Render stuff
    private final List<Pair<Vec3, Vec3>> clearLines;
    private final List<Pair<Vec3, Vec3>> blockedLines;
    private List<Vec3> simulationLine;
    private BlockPos aimPos;
    private List<BetterBlockPos> visiblePath;

    // :sunglasses:
    public final ElytraTerrainProvider context;
    public final PathManager pathManager;
    private final ElytraProcess process;

    /**
     * Remaining cool-down ticks between firework usage
     */
    private int remainingFireworkTicks;

    /**
     * Remaining cool-down ticks after the player's position and rotation are reset by the server
     */
    private int remainingSetBackTicks;

    public boolean landingMode;

    /**
     * The most recent minimum number of firework boost ticks, equivalent to {@code 10 * (1 + Flight)}
     * <p>
     * Updated every time a firework is automatically used
     */
    private int minimumBoostTicks;

    private boolean deployedFireworkLastTick;
    private final int[] nextTickBoostCounter;

    private BlockStateInterface bsi;
    public final BetterBlockPos destination;
    private final boolean appendDestination;

    private final AtomicLong motionFiniteRejects = new AtomicLong();
    private VanillaElytraOccupancy.OccupancySnapshot solverOccupancy;
    private long solverOccupancyStamp = Long.MIN_VALUE;
    private volatile String lastSolveTerrain;
    private final ExecutorService solverExecutor;
    private Future<Solution> solver;
    private Solution pendingSolution;
    private boolean solveNextTick;

    private long timeLastCacheCull = 0L;

    // firework entity lookup cache (avoids re-scanning the entity list every solve call)
    private Optional<FireworkRocketEntity> cachedAttachedFirework = Optional.empty();
    private boolean cachedAttachedFireworkValid = false;
    private int fireworkScanCount = 0;
    private int fireworkScanCallsSaved = 0;

    // reused by isHitboxClear; filled after solver.get() so tick and solver never overlap
    private final double[] hitboxRaySrc = new double[24];
    private final double[] hitboxRayDst = new double[24];

    // auto swap
    private int invTickCountdown = 0;
    private final Queue<Runnable> invTransactionQueue = new LinkedList<>();

    public ElytraBehavior(Baritone baritone, ElytraProcess process, BlockPos destination, boolean appendDestination) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.clearLines = new CopyOnWriteArrayList<>();
        this.blockedLines = new CopyOnWriteArrayList<>();
        this.pathManager = this.new PathManager();
        this.process = process;
        this.destination = new BetterBlockPos(destination);
        this.appendDestination = appendDestination;
        this.solverExecutor = Executors.newSingleThreadExecutor();
        this.nextTickBoostCounter = new int[2];

        this.context = ElytraFlightPolicy.usesNetherTerrainPrediction(this.ctx.world().dimension())
                ? new NetherPathfinderContext(Baritone.settings().elytraNetherSeed.value)
                : new VanillaElytraContext(this.ctx);
    }

    /** Minimum spacing between attempts to extend an unfinished path. */
    private static final int EXTEND_SEGMENT_INTERVAL_TICKS = 20;
    /** Extend an unfinished path once its tail is within this distance, squared. */
    private static final double EXTEND_SEGMENT_RADIUS_SQ = 256 * 256;
    /** ...or once the player is within this many path nodes of the tail, whichever comes first. */
    private static final int EXTEND_SEGMENT_NODE_MARGIN = 8;

    public final class PathManager {

        public NetherPath path;
        private volatile boolean completePath;
        private volatile boolean recalculating;

        private int maxPlayerNear;
        private int ticksNearUnchanged;
        private int playerNear;

        /**
         * Failure back-off. A path calculation that fails is not, on its own, a reason to try again
         * immediately: the inputs are usually identical on the next tick, so the failure is too, and the
         * retry is pure waste that also produces one chat line per tick. These fields make failure something
         * that participates in the decision to retry, rather than a side effect that changes nothing.
         */
        private int failureBackoffTicks;
        private int consecutiveFailures;
        private long lastFailureLogMs;
        /** Ticks until {@link #attemptNextSegment()} may extend an unfinished path again. */
        private int nextSegmentCooldown;

        public PathManager() {
            // lol imagine initializing fields normally
            this.clear();
        }

        public void tick() {
            // Recalculate closest path node
            this.updatePlayerNear();
            final int prevMaxNear = this.maxPlayerNear;
            this.maxPlayerNear = Math.max(this.maxPlayerNear, this.playerNear);

            if (this.maxPlayerNear == prevMaxNear && ctx.player().isFallFlying()) {
                this.ticksNearUnchanged++;
            } else {
                this.ticksNearUnchanged = 0;
            }

            if (this.failureBackoffTicks > 0) {
                this.failureBackoffTicks--;
            }
            if (this.nextSegmentCooldown > 0) {
                this.nextSegmentCooldown--;
            }

            // Obstacles are more important than an incomplete path, handle those first.
            this.pathfindAroundObstacles();
            this.attemptNextSegment();
        }

        public CompletableFuture<Void> pathToDestination() {
            return this.pathToDestination(ctx.playerFeet());
        }

        public CompletableFuture<Void> pathToDestination(final BlockPos from) {
            final long start = System.nanoTime();
            return this.path0(from, ElytraBehavior.this.destination, UnaryOperator.identity())
                    .thenRun(() -> {
                        final double distance = this.path.get(0).distanceTo(this.path.get(this.path.size() - 1));
                        if (this.completePath) {
                            logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenCompleteAsync((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                this.onPathFailure("Failed to compute path to destination");
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    }, ctx.minecraft()::execute);
        }

        /**
         * Records a failed path calculation and reports it at most once per five seconds. Both halves matter:
         * without the back-off a deterministic failure re-runs a full search every tick forever, and without
         * the log throttle it prints one chat line per tick while doing so.
         */
        private void onPathFailure(final String message) {
            this.consecutiveFailures++;
            this.failureBackoffTicks = Math.min(20 << Math.min(this.consecutiveFailures, 4), 200);

            final long now = System.currentTimeMillis();
            if (this.consecutiveFailures == 1 || now - this.lastFailureLogMs > 5000L) {
                this.lastFailureLogMs = now;
                logDirect(this.consecutiveFailures == 1
                        ? message
                        : message + " (x" + this.consecutiveFailures + ")");
            } else {
                logVerbose(message);
            }
        }

        public CompletableFuture<Void> pathRecalcSegment(final OptionalInt upToIncl) {
            if (this.recalculating) {
                throw new IllegalStateException("already recalculating");
            }

            this.recalculating = true;
            final List<BetterBlockPos> after = upToIncl.isPresent() ? this.path.subList(upToIncl.getAsInt() + 1, this.path.size()) : Collections.emptyList();
            final boolean complete = this.completePath;

            return this.path0(ctx.playerFeet(), upToIncl.isPresent() ? this.path.get(upToIncl.getAsInt()) : ElytraBehavior.this.destination, segment -> segment.append(after.stream(), complete || (segment.isFinished() && !upToIncl.isPresent())))
                    .whenCompleteAsync((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                this.onPathFailure("Failed to recompute segment");
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    }, ctx.minecraft()::execute);
        }

        public void pathNextSegment(final int afterIncl) {
            if (this.recalculating) {
                return;
            }

            this.recalculating = true;
            final List<BetterBlockPos> before = this.path.subList(0, afterIncl + 1);
            final long start = System.nanoTime();
            final BetterBlockPos pathStart = this.path.get(afterIncl);

            this.path0(pathStart, ElytraBehavior.this.destination, segment -> segment.prepend(before.stream()))
                    .thenRun(() -> {
                        final int recompute = this.path.size() - before.size() - 1;
                        final double distance = this.path.get(0).distanceTo(this.path.get(recompute));

                        if (this.completePath) {
                            logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenCompleteAsync((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                this.onPathFailure("Failed to compute next segment");
                                if (pathStart.distToCenterSqr(ctx.player().position()) < 16 * 16) {
                                    logVerbose("Player is near the segment start, therefore repeating this calculation is pointless. Marking as complete");
                                    completePath = true;
                                }
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    }, ctx.minecraft()::execute);
        }

        public void clear() {
            this.path = NetherPath.emptyPath();
            this.completePath = true;
            this.recalculating = false;
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
            this.failureBackoffTicks = 0;
            this.consecutiveFailures = 0;
            this.nextSegmentCooldown = 0;
        }

        private void setPath(final UnpackedSegment segment) {
            List<BetterBlockPos> path = segment.collect();
            // Only a segment that actually reaches the destination can be asked to land at it. On a partial
            // segment the last point is wherever the planner ran out of loaded world, so testing it against
            // the destination would condemn a perfectly good landing spot.
            if (ElytraBehavior.this.appendDestination && segment.isFinished()) {
                BlockPos dest = ElytraBehavior.this.destination;
                BlockPos last = !path.isEmpty() ? path.get(path.size() - 1) : null;
                if (last != null && ElytraBehavior.this.clearView(Vec3.atLowerCornerOf(dest), Vec3.atLowerCornerOf(last), false)) {
                    path.add(new BetterBlockPos(dest));
                } else {
                    logDirect("unable to land at " + ElytraBehavior.this.destination);
                    process.landingSpotIsBad(new BetterBlockPos(ElytraBehavior.this.destination));
                }
            }
            this.path = new NetherPath(path);
            this.completePath = segment.isFinished();
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
            this.consecutiveFailures = 0;
            this.failureBackoffTicks = 0;
        }

        public NetherPath getPath() {
            return this.path;
        }

        public int getNear() {
            return this.playerNear;
        }

        // mickey resigned
        private CompletableFuture<Void> path0(BlockPos src, BlockPos dst, UnaryOperator<UnpackedSegment> operator) {
            return ElytraBehavior.this.context.pathFindAsync(src, dst)
                    .thenApply(operator)
                    .thenAcceptAsync(this::setPath, ctx.minecraft()::execute);
        }

        private void pathfindAroundObstacles() {
            if (this.recalculating || this.failureBackoffTicks > 0) {
                return;
            }

            int rangeStartIncl = playerNear;
            int rangeEndExcl = playerNear;
            while (rangeEndExcl < path.size() && context.hasChunk(ChunkPos.containing(path.get(rangeEndExcl)))) {
                rangeEndExcl++;
            }
            // rangeEndExcl now represents an index either not in the path, or just outside render distance
            if (rangeStartIncl >= rangeEndExcl) {
                // not loaded yet?
                return;
            }
            final BetterBlockPos rangeStart = path.get(rangeStartIncl);
            if (!ElytraBehavior.this.passable(rangeStart.x, rangeStart.y, rangeStart.z, false)) {
                // we're in a wall
                return; // previous iterations of this function SHOULD have fixed this by now :rage_cat:
            }

            if (ElytraBehavior.this.process.state != ElytraProcess.State.LANDING && this.ticksNearUnchanged > 100) {
                this.pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1))
                        .thenRun(() -> {
                            logVerbose("Recalculating segment, no progress in last 100 ticks");
                        });
                this.ticksNearUnchanged = 0;
                return;
            }

            boolean canSeeAny = false;
            for (int i = rangeStartIncl; i < rangeEndExcl - 1; i++) {
                if (!canSeeAny && (ElytraBehavior.this.clearView(ctx.playerFeetAsVec(), this.path.getVec(i), false) || ElytraBehavior.this.clearView(ctx.playerHead(), this.path.getVec(i), false))) {
                    canSeeAny = true;
                }
                if (!ElytraBehavior.this.clearView(this.path.getVec(i), this.path.getVec(i + 1), false)) {
                    // obstacle. where do we return to pathing?
                    // if the end of render distance is closer to goal, then that's fine, otherwise we'd be "digging our hole deeper" and making an already bad backtrack worse
                    OptionalInt rejoinMainPathAt;
                    if (this.path.get(rangeEndExcl - 1).distanceSq(ElytraBehavior.this.destination) < ctx.playerFeet().distanceSq(ElytraBehavior.this.destination)) {
                        rejoinMainPathAt = OptionalInt.of(rangeEndExcl - 1); // rejoin after current render distance
                    } else {
                        rejoinMainPathAt = OptionalInt.empty(); // large backtrack detected. ignore render distance, rejoin later on
                    }

                    final BetterBlockPos blockage = this.path.get(i);
                    final double distance = ctx.playerFeet().distanceTo(this.path.get(rejoinMainPathAt.orElse(path.size() - 1)));

                    final long start = System.nanoTime();
                    this.pathRecalcSegment(rejoinMainPathAt)
                            .thenRun(() -> {
                                logVerbose(String.format("Recalculated segment around path blockage near %s %s %s (next %.1f blocks in %.4f seconds)",
                                        SettingsUtil.maybeCensor(blockage.x),
                                        SettingsUtil.maybeCensor(blockage.y),
                                        SettingsUtil.maybeCensor(blockage.z),
                                        distance,
                                        (System.nanoTime() - start) / 1e9d
                                ));
                            });
                    return;
                }
            }
            if (!canSeeAny && rangeStartIncl < rangeEndExcl - 2 && process.state != ElytraProcess.State.GET_TO_JUMP) {
                this.pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1)).thenRun(() -> logVerbose("Recalculated segment since no path points were visible"));
            }
        }

        private void attemptNextSegment() {
            if (this.recalculating || this.failureBackoffTicks > 0 || this.nextSegmentCooldown > 0) {
                return;
            }

            final int last = this.path.size() - 1;
            if (this.completePath || !ctx.world().isLoaded(this.path.get(last))) {
                return;
            }
            // A backend with no terrain prediction always stops at the edge of the loaded world, so the tail
            // of an unfinished path is loaded by definition and this would otherwise fire every single tick.
            // Extend it as the tail comes within reach instead, and rate limit regardless.
            this.nextSegmentCooldown = EXTEND_SEGMENT_INTERVAL_TICKS;
            if (this.playerNear < last - EXTEND_SEGMENT_NODE_MARGIN
                    && ctx.playerFeet().distanceSq(this.path.get(last)) > EXTEND_SEGMENT_RADIUS_SQ) {
                return;
            }
            this.pathNextSegment(last);
        }

        public void updatePlayerNear() {
            if (this.path.isEmpty()) {
                return;
            }

            int index = this.playerNear;
            final BetterBlockPos pos = ctx.playerFeet();
            for (int i = index; i >= Math.max(index - 1000, 0); i -= 10) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 1000, path.size()); i += 10) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i >= Math.max(index - 50, 0); i--) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 50, path.size()); i++) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            this.playerNear = index;
        }

        public boolean isComplete() {
            return this.completePath;
        }
    }

    public void onRenderPass(RenderEvent event) {

        final Settings settings = Baritone.settings();
        if (this.visiblePath != null) {
            PathRenderer.drawPath(event.getModelViewStack(), this.visiblePath, 0, Color.RED, false, 0, 0, 0.0D);
        }
        if (this.aimPos != null) {
            PathRenderer.drawGoal(event.getModelViewStack(), ctx, new GoalBlock(this.aimPos), event.getPartialTicks(), Color.GREEN);
        }
        if (!this.clearLines.isEmpty() && settings.elytraRenderRaytraces.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(Color.GREEN);
            for (Pair<Vec3, Vec3> line : this.clearLines) {
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), line.first(), line.second(), settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
        if (!this.blockedLines.isEmpty() && Baritone.settings().elytraRenderRaytraces.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(Color.BLUE);
            for (Pair<Vec3, Vec3> line : this.blockedLines) {
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), line.first(), line.second(), settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
        if (this.simulationLine != null && Baritone.settings().elytraRenderSimulation.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(new Color(0x36CCDC));
            final Vec3 offset = ctx.player().getPosition(event.getPartialTicks());
            for (int i = 0; i < this.simulationLine.size() - 1; i++) {
                final Vec3 src = this.simulationLine.get(i).add(offset);
                final Vec3 dst = this.simulationLine.get(i + 1).add(offset);
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), src, dst, settings.pathRenderLineWidthPixels.value);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
    }

    public void onChunkEvent(ChunkEvent event) {
        if (this.context == null) {
            return;
        }
        if (event.isPostPopulate()) {
            final LevelChunk chunk = ctx.world().getChunk(event.getX(), event.getZ());
            this.context.queueForPacking(chunk);
        } else if (event.getType() == ChunkEvent.Type.UNLOAD) {
            this.context.dropColumn(event.getX(), event.getZ());
        }
    }

    public void onBlockChange(BlockChangeEvent event) {
        this.context.queueBlockUpdate(event);
    }

    public void onReceivePacket(PacketEvent event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            ctx.minecraft().execute(() -> {
                this.remainingSetBackTicks = Baritone.settings().elytraFireworkSetbackUseDelay.value;
            });
        }
    }

    public void pathTo() {
        if (!Baritone.settings().elytraAutoJump.value || ctx.player().isFallFlying()) {
            this.pathManager.pathToDestination();
        }
    }

    public void destroy() {
        if (this.solver != null) {
            this.solver.cancel(true);
        }
        this.solverExecutor.shutdown();
        try {
            while (!this.solverExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {}
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        this.context.destroy();
    }

    public void repackChunks() {
        ChunkSource chunkProvider = ctx.world().getChunkSource();

        BetterBlockPos playerPos = ctx.playerFeet();

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        int minX = playerChunkX - 40;
        int minZ = playerChunkZ - 40;
        int maxX = playerChunkX + 40;
        int maxZ = playerChunkZ + 40;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                LevelChunk chunk = chunkProvider.getChunk(x, z, false);

                if (chunk != null && !chunk.isEmpty()) {
                    this.context.queueForPacking(chunk);
                }
            }
        }
    }

    public void onTick() {
        synchronized (this.context.cullingLock()) {
            this.onTick0();
        }
        final long now = System.currentTimeMillis();
        if ((now - this.timeLastCacheCull) / 1000 > Baritone.settings().elytraTimeBetweenCacheCullSecs.value) {
            this.context.queueCacheCulling(ctx.player().chunkPosition().x(), ctx.player().chunkPosition().z(), Baritone.settings().elytraCacheCullDistance.value);
            this.timeLastCacheCull = now;
        }
    }

    private void onTick0() {
        // A new real game tick has started, the firework entity list can only have changed since the last time
        // we looked, so throw away last tick's cached lookup.
        this.cachedAttachedFireworkValid = false;

        // Fetch the previous solution, regardless of if it's going to be used
        this.pendingSolution = null;
        if (this.solver != null) {
            try {
                this.pendingSolution = this.solver.get();
            } catch (Exception ignored) {
                // it doesn't matter if get() fails since the solution can just be recalculated synchronously
            } finally {
                this.solver = null;
            }
        }

        tickInventoryTransactions();

        // Certified mojang employee incident
        if (this.remainingFireworkTicks > 0) {
            this.remainingFireworkTicks--;
        }
        if (this.remainingSetBackTicks > 0) {
            this.remainingSetBackTicks--;
        }
        if (!this.getAttachedFirework().isPresent()) {
            this.minimumBoostTicks = 0;
        }

        // Reset rendered elements
        this.clearLines.clear();
        this.blockedLines.clear();
        this.visiblePath = null;
        this.simulationLine = null;
        this.aimPos = null;

        final List<BetterBlockPos> path = this.pathManager.getPath();
        if (path.isEmpty()) {
            return;
        } else if (this.destination == null) {
            this.pathManager.clear();
            return;
        }

        // ctx AND context???? :DDD
        this.bsi = new BlockStateInterface(ctx);
        this.pathManager.tick();

        final int playerNear = this.pathManager.getNear();
        this.visiblePath = path.subList(
                Math.max(playerNear - 30, 0),
                Math.min(playerNear + 100, path.size())
        );
    }

    /**
     * Called by {@link baritone.process.ElytraProcess#onTick(boolean, boolean)} when the process is in control and the player is flying
     */
    public void tick() {
        if (this.pathManager.getPath().isEmpty()) {
            return;
        }

        trySwapElytra();

        if (ctx.player().horizontalCollision) {
            logVerbose("hbonk");
        }
        if (ctx.player().verticalCollision) {
            logVerbose("vbonk");
        }

        final SolverContext solverContext = this.new SolverContext(false);
        this.solveNextTick = true;

        // If there's no previously calculated solution to use, or the context used at the end of last tick doesn't match this tick
        final Solution solution;
        if (this.pendingSolution == null || !this.pendingSolution.context.equals(solverContext)) {
            solution = this.solveAngles(solverContext);
        } else {
            solution = this.pendingSolution;
        }

        if (this.deployedFireworkLastTick) {
            this.nextTickBoostCounter[solverContext.boost.isBoosted() ? 1 : 0]++;
            this.deployedFireworkLastTick = false;
        }

        final boolean inLava = ctx.player().isInLava();
        if (inLava) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        }

        if (solution == null) {
            logVerbose("no solution");
            return;
        }

        baritone.getLookBehavior().updateTarget(solution.rotation, false);

        if (!solution.solvedPitch) {
            logVerbose("no pitch solution, probably gonna crash in a few ticks LOL!!!");
            return;
        } else {
            this.aimPos = new BetterBlockPos(solution.goingTo.x, solution.goingTo.y, solution.goingTo.z);
        }

        this.tickUseFireworks(
                solution.context.start,
                solution.goingTo,
                solution.context.boost.isBoosted(),
                solution.forceUseFirework || inLava
        );
    }

    public void onPostTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN && this.solveNextTick) {
            // We're at the end of the tick, the player's position likely updated and the closest path node could've
            // changed. Updating it now will avoid unnecessary recalculation on the main thread.
            this.pathManager.updatePlayerNear();

            final SolverContext context = this.new SolverContext(true);
            this.solver = this.solverExecutor.submit(() -> this.solveAngles(context));
            this.solveNextTick = false;
        }
    }

    private Solution solveAngles(final SolverContext context) {
        context.bindSolveTerrain();
        final NetherPath path = context.path;
        final int playerNear = landingMode ? path.size() - 1 : context.playerNear;
        final Vec3 start = context.start;
        Solution solution = null;

        for (int relaxation = 0; relaxation < 3; relaxation++) { // try for a strict solution first, then relax more and more (if we're in a corner or near some blocks, it will have to relax its constraints a bit)
            int[] heights = context.boost.isBoosted() ? new int[]{20, 10, 5, 0} : new int[]{0}; // attempt to gain height, if we can, so as not to waste the boost
            int lookahead = relaxation == 0 ? 2 : 3; // ideally this would be expressed as a distance in blocks, rather than a number of voxel steps
            //int minStep = Math.max(0, playerNear - relaxation);
            int minStep = playerNear;

            for (int i = Math.min(playerNear + 20, path.size() - 1); i >= minStep; i--) {
                final List<Pair<Vec3, Integer>> candidates = new ArrayList<>();
                for (int dy : heights) {
                    if (relaxation == 0 || i == minStep) {
                        // no interp
                        candidates.add(new Pair<>(path.getVec(i), dy));
                    } else if (relaxation == 1) {
                        final double[] interps = new double[]{1.0, 0.75, 0.5, 0.25};
                        for (double interp : interps) {
                            final Vec3 dest = interp == 1.0
                                    ? path.getVec(i)
                                    : path.getVec(i).scale(interp).add(path.getVec(i - 1).scale(1.0 - interp));
                            candidates.add(new Pair<>(dest, dy));
                        }
                    } else {
                        // Create a point along the segment every block
                        final Vec3 delta = path.getVec(i).subtract(path.getVec(i - 1));
                        final int steps = fastFloor(delta.length());
                        final Vec3 step = delta.normalize();
                        Vec3 stepped = path.getVec(i);
                        for (int interp = 0; interp < steps; interp++) {
                            candidates.add(new Pair<>(stepped, dy));
                            stepped = stepped.subtract(step);
                        }
                    }
                }

                for (final Pair<Vec3, Integer> candidate : candidates) {
                    final Integer augment = candidate.second();
                    Vec3 dest = candidate.first().add(0, augment, 0);
                    if (landingMode) {
                        dest = dest.add(0.5, 0.5, 0.5);
                    }

                    if (augment != 0) {
                        if (i + lookahead >= path.size()) {
                            continue;
                        }
                        if (start.distanceTo(dest) < 40) {
                            if (!this.clearView(dest, path.getVec(i + lookahead).add(0, augment, 0), false, context::raytrace)
                                    || !this.clearView(dest, path.getVec(i + lookahead), false, context::raytrace)) {
                                // aka: don't go upwards if doing so would prevent us from being able to see the next position **OR** the modified next position
                                continue;
                            }
                        } else {
                            // but if it's far away, allow gaining altitude if we could lose it again by the time we get there
                            if (!this.clearView(dest, path.getVec(i), false, context::raytrace)) {
                                continue;
                            }
                        }
                    }

                    final double minAvoidance = Baritone.settings().elytraMinimumAvoidance.value;
                    final Double growth = relaxation == 2 ? null
                            : relaxation == 0 ? 2 * minAvoidance : minAvoidance;

                    if (this.isHitboxClear(context, dest, growth)) {
                        // Yaw is trivial, just calculate the rotation required to face the destination
                        final float yaw = RotationUtils.calcRotationFromVec3d(start, dest, ctx.playerRotations()).getYaw();

                        final Pair<Float, Boolean> pitch = this.solvePitch(context, dest, relaxation);
                        if (pitch == null) {
                            solution = new Solution(context, new Rotation(yaw, ctx.playerRotations().getPitch()), null, false, false);
                            continue;
                        }

                        // A solution was found with yaw AND pitch, so just immediately return it.
                        return new Solution(context, new Rotation(yaw, pitch.first()), dest, true, pitch.second());
                    }
                }
            }
        }
        return solution;
    }

    private void tickUseFireworks(final Vec3 start, final Vec3 goingTo, final boolean isBoosted, final boolean forceUseFirework) {
        if (this.remainingSetBackTicks > 0) {
            logDebug("waiting for elytraFireworkSetbackUseDelay: " + this.remainingSetBackTicks);
            return;
        }
        if (this.landingMode) {
            return;
        }
        final boolean useOnDescend = !Baritone.settings().elytraConserveFireworks.value || ctx.player().position().y < goingTo.y + 5;
        final double currentSpeed = new Vec3(
                ctx.player().getDeltaMovement().x,
                // ignore y component if we are BOTH below where we want to be AND descending
                ctx.player().position().y < goingTo.y ? Math.max(0, ctx.player().getDeltaMovement().y) : ctx.player().getDeltaMovement().y,
                ctx.player().getDeltaMovement().z
        ).lengthSqr();

        final double elytraFireworkSpeed = Baritone.settings().elytraFireworkSpeed.value;
        if (this.remainingFireworkTicks <= 0 && (forceUseFirework || (!isBoosted
                && useOnDescend
                && (ctx.player().position().y < goingTo.y - 5 || start.distanceTo(new Vec3(goingTo.x + 0.5, ctx.player().position().y, goingTo.z + 0.5)) > 5) // UGH!!!!!!!
                && currentSpeed < elytraFireworkSpeed * elytraFireworkSpeed))
        ) {
            // Prioritize boosting fireworks over regular ones
            // TODO: Take the minimum boost time into account?
            if (!baritone.getInventoryBehavior().throwaway(true, ElytraBehavior::isBoostingFireworks) &&
                    !baritone.getInventoryBehavior().throwaway(true, ElytraBehavior::isFireworks)) {
                logDirect("no fireworks");
                return;
            }
            logVerbose("attempting to use firework" + (forceUseFirework ? " (forced)" : ""));
            ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
            this.minimumBoostTicks = 10 * (1 + getFireworkBoost(ctx.player().getItemInHand(InteractionHand.MAIN_HAND)).orElse(0));
            this.remainingFireworkTicks = 10;
            this.deployedFireworkLastTick = true;
        }
    }

    private final class SolverContext {

        public final NetherPath path;
        public final int playerNear;
        public final Vec3 start;
        public final Vec3 motion;
        public final AABB boundingBox;
        public final boolean ignoreLava;
        public final FireworkBoost boost;
        public final IAimProcessor aimProcessor;
        private VanillaElytraOccupancy.OccupancySnapshot occupancy;
        private boolean terrainBound;

        /**
         * Creates a new SolverContext using the current state of the path, player, and firework boost at the time of
         * construction.
         *
         * @param async Whether the computation is being done asynchronously at the end of a game tick.
         */
        public SolverContext(boolean async) {
            this.path = ElytraBehavior.this.pathManager.getPath();
            this.playerNear = ElytraBehavior.this.pathManager.getNear();

            this.start = ctx.playerFeetAsVec();
            this.motion = ctx.playerMotion();
            this.boundingBox = ctx.player().getBoundingBox();
            this.ignoreLava = ctx.player().isInLava();
            this.occupancy = null;

            final Integer fireworkTicksExisted;
            if (async && ElytraBehavior.this.deployedFireworkLastTick) {
                final int[] counter = ElytraBehavior.this.nextTickBoostCounter;
                fireworkTicksExisted = counter[1] > counter[0] ? 0 : null;
            } else {
                fireworkTicksExisted = ElytraBehavior.this.getAttachedFirework().map(e -> e.tickCount).orElse(null);
            }
            this.boost = new FireworkBoost(fireworkTicksExisted, ElytraBehavior.this.minimumBoostTicks);

            ITickableAimProcessor aim = ElytraBehavior.this.baritone.getLookBehavior().getAimProcessor().fork();
            if (async) {
                // async computation is done at the end of a tick, advance by 1 to prepare for the next tick
                aim.advance(1);
            }
            this.aimProcessor = aim;
        }

        void bindSolveTerrain() {
            if (this.terrainBound) {
                return;
            }
            this.terrainBound = true;
            if (ElytraBehavior.this.context instanceof VanillaElytraContext vanilla) {
                final VanillaElytraPackQueue.SolverFreeze frozen = vanilla.freezeForSolve(
                        ElytraBehavior.this.solverOccupancy,
                        ElytraBehavior.this.solverOccupancyStamp);
                this.occupancy = frozen.snapshot;
                ElytraBehavior.this.solverOccupancy = frozen.snapshot;
                ElytraBehavior.this.solverOccupancyStamp = frozen.stamp;
                ElytraBehavior.this.lastSolveTerrain = ElytraDebug.SOLVER_FROZEN;
            } else {
                this.occupancy = null;
                ElytraBehavior.this.lastSolveTerrain = ElytraDebug.SOLVER_NETHER;
            }
        }

        boolean isPassable(final int x, final int y, final int z) {
            return this.occupancy != null
                    ? this.occupancy.isPassable(x, y, z)
                    : ElytraBehavior.this.context.isPassable(x, y, z);
        }

        boolean raytrace(final Vec3 start, final Vec3 dest) {
            return this.occupancy != null
                    ? this.occupancy.raytrace(start.x, start.y, start.z, dest.x, dest.y, dest.z)
                    : ElytraBehavior.this.context.raytrace(start, dest);
        }

        boolean raytraceBatch(final int count, final double[] src, final double[] dst) {
            return this.occupancy != null
                    ? this.occupancy.raytraceBatch(count, src, dst)
                    : ElytraBehavior.this.context.raytraceBatch(count, src, dst);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != SolverContext.class) {
                return false;
            }

            SolverContext other = (SolverContext) o;
            return this.path == other.path  // Contents aren't modified, just compare by reference
                    && this.playerNear == other.playerNear
                    && Objects.equals(this.start, other.start)
                    && Objects.equals(this.motion, other.motion)
                    && Objects.equals(this.boundingBox, other.boundingBox)
                    && this.ignoreLava == other.ignoreLava
                    && Objects.equals(this.boost, other.boost);
        }
    }

    private static final class FireworkBoost {

        private final Integer fireworkTicksExisted;
        private final int minimumBoostTicks;
        private final int maximumBoostTicks;

        /**
         * @param fireworkTicksExisted The ticksExisted of the attached firework entity, or {@code null} if no entity.
         * @param minimumBoostTicks    The minimum number of boost ticks that the attached firework entity, if any, will
         *                             provide.
         */
        public FireworkBoost(final Integer fireworkTicksExisted, final int minimumBoostTicks) {
            this.fireworkTicksExisted = fireworkTicksExisted;

            // this.lifetime = 10 * i + this.rand.nextInt(6) + this.rand.nextInt(7);
            this.minimumBoostTicks = minimumBoostTicks;
            this.maximumBoostTicks = minimumBoostTicks + 11;
        }

        public boolean isBoosted() {
            return this.fireworkTicksExisted != null;
        }

        /**
         * @return The guaranteed number of remaining ticks with boost
         */
        public int getGuaranteedBoostTicks() {
            return this.isBoosted() ? Math.max(0, this.minimumBoostTicks - this.fireworkTicksExisted) : 0;
        }

        /**
         * @return The maximum number of remaining ticks with boost
         */
        public int getMaximumBoostTicks() {
            return this.isBoosted() ? Math.max(0, this.maximumBoostTicks - this.fireworkTicksExisted) : 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != FireworkBoost.class) {
                return false;
            }

            FireworkBoost other = (FireworkBoost) o;
            if (!this.isBoosted() && !other.isBoosted()) {
                return true;
            }

            return Objects.equals(this.fireworkTicksExisted, other.fireworkTicksExisted)
                    && this.minimumBoostTicks == other.minimumBoostTicks
                    && this.maximumBoostTicks == other.maximumBoostTicks;
        }
    }

    private static final class PitchResult {

        public final float pitch;
        public final double dot;
        public final List<Vec3> steps;

        public PitchResult(float pitch, double dot, List<Vec3> steps) {
            this.pitch = pitch;
            this.dot = dot;
            this.steps = steps;
        }
    }

    private static final class Solution {

        public final SolverContext context;
        public final Rotation rotation;
        public final Vec3 goingTo;
        public final boolean solvedPitch;
        public final boolean forceUseFirework;

        public Solution(SolverContext context, Rotation rotation, Vec3 goingTo, boolean solvedPitch, boolean forceUseFirework) {
            this.context = context;
            this.rotation = rotation;
            this.goingTo = goingTo;
            this.solvedPitch = solvedPitch;
            this.forceUseFirework = forceUseFirework;
        }
    }

    public static boolean isFireworks(final ItemStack itemStack) {
        if (itemStack.getItem() != Items.FIREWORK_ROCKET) {
            return false;
        }
        Fireworks fw = itemStack.get(DataComponents.FIREWORKS);
        return fw != null && fw.explosions().isEmpty();
    }

    private static boolean isBoostingFireworks(final ItemStack itemStack) {
        return getFireworkBoost(itemStack).isPresent();
    }

    private static OptionalInt getFireworkBoost(final ItemStack itemStack) {
        Fireworks fw = itemStack.get(DataComponents.FIREWORKS);
        if (fw != null && fw.explosions().isEmpty()) {
            return OptionalInt.of(fw.flightDuration());
        }
        return OptionalInt.empty();
    }

    private Optional<FireworkRocketEntity> getAttachedFirework() {
        if (this.cachedAttachedFireworkValid) {
            this.fireworkScanCallsSaved++;
            return this.cachedAttachedFirework;
        }

        this.cachedAttachedFirework = ctx.entitiesStream()
                .filter(x -> x instanceof FireworkRocketEntity)
                .filter(x -> Objects.equals(((IFireworkRocketEntity) x).getBoostedEntity(), ctx.player()))
                .map(x -> (FireworkRocketEntity) x)
                .findFirst();
        this.cachedAttachedFireworkValid = true;

        this.fireworkScanCount++;
        if (Baritone.settings().elytraChatSpam.value && this.fireworkScanCount % 200 == 0) {
            logDebug(String.format("firework entity scan #%d this session (%d calls served from cache since last log)",
                    this.fireworkScanCount, this.fireworkScanCallsSaved));
            this.fireworkScanCallsSaved = 0;
        }

        return this.cachedAttachedFirework;
    }

    private boolean isHitboxClear(final SolverContext context, final Vec3 dest, final Double growAmount) {
        final Vec3 start = context.start;
        final boolean ignoreLava = context.ignoreLava;

        if (!this.clearView(start, dest, ignoreLava, context::raytrace)) {
            return false;
        }
        if (growAmount == null) {
            return true;
        }

        final AABB bb = context.boundingBox.inflate(growAmount);

        final double ox = dest.x - start.x;
        final double oy = dest.y - start.y;
        final double oz = dest.z - start.z;

        final double[] src = this.hitboxRaySrc;
        src[0] = bb.minX; src[1] = bb.minY; src[2] = bb.minZ;
        src[3] = bb.minX; src[4] = bb.minY; src[5] = bb.maxZ;
        src[6] = bb.minX; src[7] = bb.maxY; src[8] = bb.minZ;
        src[9] = bb.minX; src[10] = bb.maxY; src[11] = bb.maxZ;
        src[12] = bb.maxX; src[13] = bb.minY; src[14] = bb.minZ;
        src[15] = bb.maxX; src[16] = bb.minY; src[17] = bb.maxZ;
        src[18] = bb.maxX; src[19] = bb.maxY; src[20] = bb.minZ;
        src[21] = bb.maxX; src[22] = bb.maxY; src[23] = bb.maxZ;

        final double[] dst = this.hitboxRayDst;
        dst[0] = bb.minX + ox; dst[1] = bb.minY + oy; dst[2] = bb.minZ + oz;
        dst[3] = bb.minX + ox; dst[4] = bb.minY + oy; dst[5] = bb.maxZ + oz;
        dst[6] = bb.minX + ox; dst[7] = bb.maxY + oy; dst[8] = bb.minZ + oz;
        dst[9] = bb.minX + ox; dst[10] = bb.maxY + oy; dst[11] = bb.maxZ + oz;
        dst[12] = bb.maxX + ox; dst[13] = bb.minY + oy; dst[14] = bb.minZ + oz;
        dst[15] = bb.maxX + ox; dst[16] = bb.minY + oy; dst[17] = bb.maxZ + oz;
        dst[18] = bb.maxX + ox; dst[19] = bb.maxY + oy; dst[20] = bb.minZ + oz;
        dst[21] = bb.maxX + ox; dst[22] = bb.maxY + oy; dst[23] = bb.maxZ + oz;

        // Use non-batching method without early failure
        if (Baritone.settings().elytraRenderHitboxRaytraces.value) {
            boolean clear = true;
            for (int i = 0; i < 8; i++) {
                final Vec3 s = new Vec3(src[i * 3], src[i * 3 + 1], src[i * 3 + 2]);
                final Vec3 d = new Vec3(dst[i * 3], dst[i * 3 + 1], dst[i * 3 + 2]);
                // Don't forward ignoreLava since the batch call doesn't care about it
                if (!this.clearView(s, d, false, context::raytrace)) {
                    clear = false;
                }
            }
            return clear;
        }

        return context.raytraceBatch(8, src, dst);
    }

    public boolean clearView(Vec3 start, Vec3 dest, boolean ignoreLava) {
        return this.clearView(start, dest, ignoreLava, this.context::raytrace);
    }

    /**
     * Shared LOS: lava clip, start==dest JNI guard, render-line recording.
     * Path-manager uses live {@link #context}; solver passes the solve's terrain.
     */
    private boolean clearView(final Vec3 start, final Vec3 dest, final boolean ignoreLava,
                              final LineOfSight los) {
        final boolean clear;
        if (!ignoreLava) {
            // if start == dest then the cpp raytracer dies
            clear = start.equals(dest) || los.clear(start, dest);
        } else {
            clear = ctx.world().clip(new ClipContext(start, dest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player())).getType() == HitResult.Type.MISS;
        }

        if (Baritone.settings().elytraRenderRaytraces.value) {
            (clear ? this.clearLines : this.blockedLines).add(new Pair<>(start, dest));
        }
        return clear;
    }

    @FunctionalInterface
    private interface LineOfSight {
        boolean clear(Vec3 start, Vec3 dest);
    }

    private boolean solverPassable(final SolverContext context, final int x, final int y, final int z, final boolean ignoreLava) {
        if (ignoreLava) {
            final BlockState state = this.bsi.get0(x, y, z);
            return state.getBlock() instanceof AirBlock || MovementHelper.isLava(state);
        } else {
            return context.isPassable(x, y, z);
        }
    }

    private static FloatArrayList pitchesToSolveFor(final float goodPitch, final boolean desperate) {
        final float minPitch = desperate ? -90 : Math.max(goodPitch - Baritone.settings().elytraPitchRange.value, -89);
        final float maxPitch = desperate ? 90 : Math.min(goodPitch + Baritone.settings().elytraPitchRange.value, 89);

        final FloatArrayList pitchValues = new FloatArrayList(fastCeil(maxPitch - minPitch) + 1);
        for (float pitch = goodPitch; pitch <= maxPitch; pitch++) {
            pitchValues.add(pitch);
        }
        for (float pitch = goodPitch - 1; pitch >= minPitch; pitch--) {
            pitchValues.add(pitch);
        }

        return pitchValues;
    }

    @FunctionalInterface
    private interface IntTriFunction<T> {
        T apply(int first, int second, int third);
    }

    private static final class IntTriple {
        public final int first;
        public final int second;
        public final int third;

        public IntTriple(int first, int second, int third) {
            this.first = first;
            this.second = second;
            this.third = third;
        }
    }

    private Pair<Float, Boolean> solvePitch(final SolverContext context, final Vec3 goal, final int relaxation) {
        final boolean desperate = relaxation == 2;
        final float goodPitch = RotationUtils.calcRotationFromVec3d(context.start, goal, ctx.playerRotations()).getPitch();
        final FloatArrayList pitches = pitchesToSolveFor(goodPitch, desperate);

        final IntTriFunction<PitchResult> solve = (ticks, ticksBoosted, ticksBoostDelay) ->
                this.solvePitch(context, goal, relaxation, pitches.iterator(), ticks, ticksBoosted, ticksBoostDelay);

        final List<IntTriple> tests = new ArrayList<>();

        if (context.boost.isBoosted()) {
            final int guaranteed = context.boost.getGuaranteedBoostTicks();
            if (guaranteed == 0) {
                // uncertain when boost will run out
                final int lookahead = Math.max(4, 10 - context.boost.getMaximumBoostTicks());
                tests.add(new IntTriple(lookahead, 1, 0));
            } else if (guaranteed <= 5) {
                // boost will run out within 5 ticks
                tests.add(new IntTriple(guaranteed + 5, guaranteed, 0));
            } else {
                // there's plenty of guaranteed boost
                tests.add(new IntTriple(guaranteed + 1, guaranteed, 0));
            }
        }

        // Standard test, assume (not) boosted for entire duration
        final int ticks = desperate ? 3 : context.boost.isBoosted() ? Math.max(5, context.boost.getGuaranteedBoostTicks()) : Baritone.settings().elytraSimulationTicks.value;
        tests.add(new IntTriple(ticks, context.boost.isBoosted() ? ticks : 0, 0));

        final Optional<PitchResult> result = tests.stream()
                .map(i -> solve.apply(i.first, i.second, i.third))
                .filter(Objects::nonNull)
                .findFirst();
        if (result.isPresent()) {
            return new Pair<>(result.get().pitch, false);
        }

        // If we used a firework would we be able to get out of the current situation??? perhaps
        if (desperate) {
            final List<IntTriple> testsBoost = new ArrayList<>();
            testsBoost.add(new IntTriple(ticks, 10, 3));
            testsBoost.add(new IntTriple(ticks, 10, 2));
            testsBoost.add(new IntTriple(ticks, 10, 1));

            final Optional<PitchResult> resultBoost = testsBoost.stream()
                    .map(i -> solve.apply(i.first, i.second, i.third))
                    .filter(Objects::nonNull)
                    .findFirst();
            if (resultBoost.isPresent()) {
                return new Pair<>(resultBoost.get().pitch, true);
            }
        }

        return null;
    }

    private PitchResult solvePitch(final SolverContext context, final Vec3 goal, final int relaxation,
                                   final FloatIterator pitches, final int ticks, final int ticksBoosted,
                                   final int ticksBoostDelay) {
        // we are at a certain velocity, but we have a target velocity
        // what pitch would get us closest to our target velocity?
        // yaw is easy so we only care about pitch

        final Vec3 goalDelta = goal.subtract(context.start);
        final Vec3 goalDirection = goalDelta.normalize();

        final Deque<PitchResult> bestResults = new ArrayDeque<>();

        final double[] disp = new double[(ticks + 1) * 3];
        while (pitches.hasNext()) {
            final float pitch = pitches.nextFloat();
            final int n = this.simulate(
                    context,
                    goalDelta,
                    pitch,
                    ticks,
                    ticksBoosted,
                    ticksBoostDelay,
                    disp
            );
            if (n < 0) {
                continue;
            }
            final int lastOff = (n - 1) * 3;
            final Vec3 last = new Vec3(disp[lastOff], disp[lastOff + 1], disp[lastOff + 2]);
            double goodness = goalDirection.dot(last.normalize());
            if (landingMode) {
                goodness = -goalDelta.subtract(last).length();
            }
            final PitchResult bestSoFar = bestResults.peek();
            if (bestSoFar == null || goodness > bestSoFar.dot) {
                bestResults.push(new PitchResult(pitch, goodness, boxDisplacement(disp, n)));
            }
        }

        outer:
        for (final PitchResult result : bestResults) {
            if (relaxation < 2) {
                // Ensure that the goal is visible along the entire simulated path
                // Reverse order iteration since the last position is most likely to fail
                for (int i = result.steps.size() - 1; i >= 1; i--) {
                    if (!this.clearView(context.start.add(result.steps.get(i)), goal, context.ignoreLava, context::raytrace)) {
                        continue outer;
                    }
                }
            } else {
                // Ensure that the goal is visible from the final position
                if (!this.clearView(context.start.add(result.steps.get(result.steps.size() - 1)), goal, context.ignoreLava, context::raytrace)) {
                    continue;
                }
            }

            this.simulationLine = result.steps;
            return result;
        }
        return null;
    }

    private static List<Vec3> boxDisplacement(final double[] disp, final int n) {
        final List<Vec3> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final int o = i * 3;
            out.add(new Vec3(disp[o], disp[o + 1], disp[o + 2]));
        }
        return out;
    }

    /**
     * @return number of displacement points written to {@code disp}, or {@code -1} on collision
     */
    private int simulate(final SolverContext context, final Vec3 goalDelta, final float pitch, final int ticks,
                         final int ticksBoosted, final int ticksBoostDelay, final double[] disp) {
        final ITickableAimProcessor aimProcessor = context.aimProcessor.fork();
        double dx = goalDelta.x;
        double dy = goalDelta.y;
        double dz = goalDelta.z;
        final double[] motion = {context.motion.x, context.motion.y, context.motion.z};
        double minX = context.boundingBox.minX;
        double minY = context.boundingBox.minY;
        double minZ = context.boundingBox.minZ;
        double maxX = context.boundingBox.maxX;
        double maxY = context.boundingBox.maxY;
        double maxZ = context.boundingBox.maxZ;
        final double[] bounds = new double[6];
        disp[0] = 0.0;
        disp[1] = 0.0;
        disp[2] = 0.0;
        int n = 1;
        double cumX = 0.0;
        double cumY = 0.0;
        double cumZ = 0.0;
        int remainingTicksBoosted = ticksBoosted;

        for (int i = 0; i < ticks; i++) {
            if (dx * dx + dy * dy + dz * dz < 1) {
                break;
            }
            final Rotation rotation = aimProcessor.nextRotation(
                    RotationUtils.calcRotationFromVec3d(Vec3.ZERO, new Vec3(dx, dy, dz), ctx.playerRotations()).withPitch(pitch)
            );
            final Vec3 lookDirection = RotationUtils.calcLookDirectionFromRotation(rotation);

            ElytraMotion.step(motion, lookDirection.x, lookDirection.y, lookDirection.z, rotation.getPitch());
            if (!ElytraMotion.isFinite(motion)) {
                this.motionFiniteRejects.incrementAndGet();
                return -1;
            }
            dx -= motion[0];
            dy -= motion[1];
            dz -= motion[2];

            ElytraMotion.inflateForCollision(
                    minX, minY, minZ, maxX, maxY, maxZ,
                    motion[0], motion[1], motion[2], ElytraMotion.COLLISION_PAD, bounds);

            int xmin = fastFloor(bounds[0]);
            int xmax = fastCeil(bounds[3]);
            int ymin = fastFloor(bounds[1]);
            int ymax = fastCeil(bounds[4]);
            int zmin = fastFloor(bounds[2]);
            int zmax = fastCeil(bounds[5]);
            for (int x = xmin; x < xmax; x++) {
                for (int y = ymin; y < ymax; y++) {
                    for (int z = zmin; z < zmax; z++) {
                        if (!this.solverPassable(context, x, y, z, context.ignoreLava)) {
                            return -1;
                        }
                    }
                }
            }

            minX += motion[0];
            minY += motion[1];
            minZ += motion[2];
            maxX += motion[0];
            maxY += motion[1];
            maxZ += motion[2];
            cumX += motion[0];
            cumY += motion[1];
            cumZ += motion[2];
            disp[n * 3] = cumX;
            disp[n * 3 + 1] = cumY;
            disp[n * 3 + 2] = cumZ;
            n++;

            if (i >= ticksBoostDelay && remainingTicksBoosted-- > 0) {
                ElytraMotion.applyFireworkBoost(motion, lookDirection.x, lookDirection.y, lookDirection.z);
            }
        }

        return n;
    }

    private boolean passable(int x, int y, int z, boolean ignoreLava) {
        if (ignoreLava) {
            final BlockState state = this.bsi.get0(x, y, z);
            return state.getBlock() instanceof AirBlock || MovementHelper.isLava(state);
        } else {
            return this.context.isPassable(x, y, z);
        }
    }

    private void tickInventoryTransactions() {
        if (invTickCountdown <= 0) {
            Runnable r = invTransactionQueue.poll();
            if (r != null) {
                r.run();
                invTickCountdown = Baritone.settings().ticksBetweenInventoryMoves.value;
            }
        }
        if (invTickCountdown > 0) invTickCountdown--;
    }

    private void queueWindowClick(int windowId, int slotId, int button, ContainerInput input) {
        invTransactionQueue.add(() -> ctx.playerController().windowClick(windowId, slotId, button, input, ctx.player()));
    }

    private int findGoodElytra() {
        NonNullList<ItemStack> invy = ctx.player().getInventory().getNonEquipmentItems();
        for (int i = 0; i < invy.size(); i++) {
            ItemStack slot = invy.get(i);
            if (slot.getItem() == Items.ELYTRA && (slot.getMaxDamage() - slot.getDamageValue()) > Baritone.settings().elytraMinimumDurability.value) {
                return i;
            }
        }
        return -1;
    }

    private void trySwapElytra() {
        if (!Baritone.settings().elytraAutoSwap.value || !invTransactionQueue.isEmpty()) {
            return;
        }

        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA
                || chest.getMaxDamage() - chest.getDamageValue() > Baritone.settings().elytraMinimumDurability.value) {
            return;
        }

        int goodElytraSlot = findGoodElytra();
        if (goodElytraSlot != -1) {
            final int CHEST_SLOT = 6;
            final int slotId = goodElytraSlot < 9 ? goodElytraSlot + 36 : goodElytraSlot;
            queueWindowClick(ctx.player().inventoryMenu.containerId, slotId, 0, ContainerInput.PICKUP);
            queueWindowClick(ctx.player().inventoryMenu.containerId, CHEST_SLOT, 0, ContainerInput.PICKUP);
            queueWindowClick(ctx.player().inventoryMenu.containerId, slotId, 0, ContainerInput.PICKUP);
        }
    }

    public ElytraDebug.Snapshot debugSnapshot(final String state, final IPlayerContext playerCtx) {
        final boolean vanilla = this.context instanceof VanillaElytraContext;
        final boolean nether = this.context instanceof NetherPathfinderContext;
        Integer columns = null;
        Long submitted = null;
        Long completed = null;
        String caller = null;
        String worker = null;
        Integer explored = null;
        Long nanos = null;
        Boolean reached = null;
        if (vanilla) {
            final VanillaElytraContext vanillaCtx = (VanillaElytraContext) this.context;
            columns = vanillaCtx.occupancyColumns();
            submitted = vanillaCtx.packQueue().submitted();
            completed = vanillaCtx.packQueue().completed();
            caller = vanillaCtx.packQueue().lastCaller();
            worker = vanillaCtx.packQueue().lastWorker();
            explored = vanillaCtx.lastExplored();
            nanos = vanillaCtx.lastSearchNanos();
            reached = vanillaCtx.lastReached();
        }
        final String solver = ElytraDebug.solverTerrain(this.lastSolveTerrain);
        return new ElytraDebug.Snapshot(
                true,
                "ElytraProcess",
                true,
                state,
                ElytraDebug.dimensionPath(playerCtx),
                vanilla ? ElytraDebug.BACKEND_VANILLA : nether ? ElytraDebug.BACKEND_NETHER : ElytraDebug.BACKEND_NONE,
                this.context.getClass().getSimpleName(),
                solver,
                columns,
                submitted,
                completed,
                caller,
                worker,
                explored,
                nanos,
                reached,
                this.motionFiniteRejects.get()
        );
    }

    void logVerbose(String message) {
        if (Baritone.settings().elytraChatSpam.value) {
            logDebug(message);
        }
    }
}
