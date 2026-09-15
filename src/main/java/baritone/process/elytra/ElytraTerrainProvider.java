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
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/**
 * Abstraction over the terrain/collision backend that {@link ElytraBehavior} paths and steers against.
 * <p>
 * There are two implementations: {@link NetherPathfinderContext}, which delegates to the native
 * nether-pathfinder library (fast, supports long-range terrain prediction, but hardcoded to a 128-block
 * vertical range and Nether-generator-specific), and {@link VanillaElytraContext}, a pure-Java fallback
 * backed by real loaded chunk data (works at any Y, in any dimension, but is reactive-only - no
 * long-range terrain prediction).
 *
 * @author Brady
 */
public interface ElytraTerrainProvider {

    /**
     * @return {@code true} if the chunk at the given position is ready to be pathed/raytraced through
     */
    boolean hasChunk(ChunkPos pos);

    /**
     * Queues the given chunk to be packed into this backend's internal representation, if applicable.
     */
    void queueForPacking(LevelChunk chunk);

    /**
     * Notifies this backend of a block change, so its internal representation can be kept in sync.
     */
    void queueBlockUpdate(BlockChangeEvent event);

    /**
     * Queues culling of far away cached chunks, if applicable.
     */
    void queueCacheCulling(int chunkX, int chunkZ, int maxDistanceBlocks);

    /**
     * Drops a packed column when the chunk unloads. Default is a no-op (native backend culls on its own).
     */
    default void dropColumn(int chunkX, int chunkZ) {}

    /**
     * @return the lock that must be held while there are active pointers into cached chunk data
     */
    Object cullingLock();

    /**
     * Computes a path from {@code src} to {@code dst}.
     */
    CompletableFuture<UnpackedSegment> pathFindAsync(BlockPos src, BlockPos dst);

    /**
     * Performs a raytrace from the given start position to the given end position, returning {@code true} if
     * there is visibility (i.e. no obstruction) between the two points.
     */
    boolean raytrace(Vec3 start, Vec3 end);

    /**
     * Batched form of {@link #raytrace(Vec3, Vec3)}, returning {@code true} only if every one of the
     * {@code count} segments is clear. The default implementation just loops {@link #raytrace(Vec3, Vec3)};
     * backends that can do better (e.g. a native batched call) should override this.
     */
    default boolean raytraceBatch(final int count, final double[] src, final double[] dst) {
        for (int i = 0; i < count; i++) {
            final Vec3 s = new Vec3(src[i * 3], src[i * 3 + 1], src[i * 3 + 2]);
            final Vec3 d = new Vec3(dst[i * 3], dst[i * 3 + 1], dst[i * 3 + 2]);
            if (!raytrace(s, d)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return {@code true} if the block at the given position is solid/blocking
     */
    boolean isPassable(int x, int y, int z);

    /**
     * Releases any resources held by this backend.
     */
    void destroy();
}
