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

import baritone.utils.accessor.IPalettedContainer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.BitStorage;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;

/**
 * Packed air/solid occupancy for {@link VanillaElytraContext}. Unknown columns are solid.
 * Bit 1 = solid, 0 = air. A null section array means the section is all air (loaded empty).
 */
final class VanillaElytraOccupancy {

    static final int SECTION_LONGS = 64; // 16^3 bits

    private VanillaElytraOccupancy() {}

    static final class PackedColumn {
        final int chunkX;
        final int chunkZ;
        final int minY;
        final int sectionCount;
        final long[][] sections;

        PackedColumn(final int chunkX, final int chunkZ, final int minY, final int sectionCount, final long[][] sections) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.minY = minY;
            this.sectionCount = sectionCount;
            this.sections = sections;
        }
    }

    static PackedColumn airColumn(final int minY, final int sectionCount) {
        return new PackedColumn(0, 0, minY, sectionCount, new long[sectionCount][]);
    }

    static PackedColumn pack(final LevelChunk chunk) {
        final ChunkPos pos = chunk.getPos();
        final int minY = chunk.getMinY();
        final LevelChunkSection[] src = chunk.getSections();
        final long[][] sections = new long[src.length][];
        for (int s = 0; s < src.length; s++) {
            sections[s] = packSection(src[s]);
        }
        return new PackedColumn(pos.x(), pos.z(), minY, src.length, sections);
    }

    static PackedColumn setSolid(final PackedColumn col, final int x, final int y, final int z, final boolean solid) {
        final int relY = y - col.minY;
        if (relY < 0 || relY >= col.sectionCount << 4) {
            return col;
        }
        final int s = relY >> 4;
        final int idx = (relY & 15) << 8 | (z & 15) << 4 | (x & 15);
        final int word = idx >>> 6;
        final long mask = 1L << (idx & 63);
        long[] bits = col.sections[s];
        final boolean currentlySolid = bits != null && (bits[word] & mask) != 0;
        if (currentlySolid == solid) {
            return col;
        }
        final long[][] sections = col.sections.clone();
        if (bits == null) {
            bits = new long[SECTION_LONGS];
        } else {
            bits = bits.clone();
        }
        if (solid) {
            bits[word] |= mask;
        } else {
            bits[word] &= ~mask;
        }
        sections[s] = bits;
        return new PackedColumn(col.chunkX, col.chunkZ, col.minY, col.sectionCount, sections);
    }

    /**
     * @return {@code true} if the voxel is air in a packed column. {@code column == null} is unknown = solid.
     */
    static boolean isPassable(final PackedColumn column, final int worldMinY, final int worldMaxY,
                             final int x, final int y, final int z) {
        if (y < worldMinY || y >= worldMaxY) {
            return false;
        }
        if (column == null) {
            return false;
        }
        final int relY = y - column.minY;
        if (relY < 0 || relY >= column.sectionCount << 4) {
            return false;
        }
        final long[] bits = column.sections[relY >> 4];
        if (bits == null) {
            return true;
        }
        final int idx = (relY & 15) << 8 | (z & 15) << 4 | (x & 15);
        return (bits[idx >>> 6] & (1L << (idx & 63))) == 0;
    }

    static boolean isPassable(final LongFunction<PackedColumn> columns, final int worldMinY, final int worldMaxY,
                             final int x, final int y, final int z) {
        return isPassable(columns.apply(chunkKey(x >> 4, z >> 4)), worldMinY, worldMaxY, x, y, z);
    }

    /**
     * Column cache for one frozen occupancy snapshot. Not thread-safe. Do not use on a live map.
     */
    static final class OccupancyCursor {
        private final LongFunction<PackedColumn> columns;
        private final int worldMinY;
        private final int worldMaxY;
        private int prevChunkX = Integer.MAX_VALUE;
        private int prevChunkZ = Integer.MAX_VALUE;
        private PackedColumn cached;

        OccupancyCursor(final LongFunction<PackedColumn> columns, final int worldMinY, final int worldMaxY) {
            this.columns = columns;
            this.worldMinY = worldMinY;
            this.worldMaxY = worldMaxY;
        }

        boolean isPassable(final int x, final int y, final int z) {
            if (y < this.worldMinY || y >= this.worldMaxY) {
                return false;
            }
            final int cx = x >> 4;
            final int cz = z >> 4;
            if (cx != this.prevChunkX || cz != this.prevChunkZ) {
                this.prevChunkX = cx;
                this.prevChunkZ = cz;
                this.cached = this.columns.apply(chunkKey(cx, cz));
            }
            return VanillaElytraOccupancy.isPassable(this.cached, this.worldMinY, this.worldMaxY, x, y, z);
        }
    }

    /**
     * Shallow copy of a live column map plus an {@link OccupancyCursor} on that copy. A later pack,
     * cull, or block update on {@code live} is not visible. Missing columns stay solid.
     */
    static OccupancySnapshot freeze(final Map<Long, PackedColumn> live, final int worldMinY, final int worldMaxY,
                                    final BooleanSupplier abort) {
        final Long2ObjectOpenHashMap<PackedColumn> copy = new Long2ObjectOpenHashMap<>(Math.max(16, live.size()));
        // ConcurrentHashMap may shrink during copy. fastutil putAll(Map) walks size() then
        // next() that many times and throws if the live map lost entries. Weakly-consistent
        // entrySet iteration does not.
        for (final Map.Entry<Long, PackedColumn> e : live.entrySet()) {
            copy.put(e.getKey().longValue(), e.getValue());
        }
        return new OccupancySnapshot(worldMinY, worldMaxY, copy, abort);
    }

    /**
     * Frozen occupancy view. Construct only via {@link #freeze}; the map argument is already a copy.
     */
    static final class OccupancySnapshot {
        final int worldMinY;
        final int worldMaxY;
        private final OccupancyCursor cursor;
        private final BooleanSupplier abort;

        OccupancySnapshot(final int worldMinY, final int worldMaxY,
                          final Long2ObjectOpenHashMap<PackedColumn> columns,
                          final BooleanSupplier abort) {
            this.worldMinY = worldMinY;
            this.worldMaxY = worldMaxY;
            this.cursor = new OccupancyCursor(columns::get, worldMinY, worldMaxY);
            this.abort = abort;
        }

        boolean isPassable(final int x, final int y, final int z) {
            return this.cursor.isPassable(x, y, z);
        }

        boolean raytrace(final double x0, final double y0, final double z0,
                         final double x1, final double y1, final double z1) {
            return VanillaElytraOccupancy.raytrace(this.cursor, x0, y0, z0, x1, y1, z1, this.abort);
        }

        boolean raytraceCenters(final int x0, final int y0, final int z0, final int x1, final int y1, final int z1) {
            return VanillaElytraOccupancy.raytraceCenters(this.cursor, x0, y0, z0, x1, y1, z1, this.abort);
        }

        boolean raytraceBatch(final int count, final double[] src, final double[] dst) {
            for (int i = 0; i < count; i++) {
                final int o = i * 3;
                if (!this.raytrace(src[o], src[o + 1], src[o + 2], dst[o], dst[o + 1], dst[o + 2])) {
                    return false;
                }
            }
            return true;
        }
    }

    static boolean raytraceCenters(final LongFunction<PackedColumn> columns, final int worldMinY, final int worldMaxY,
                                   final int x0, final int y0, final int z0,
                                   final int x1, final int y1, final int z1,
                                   final BooleanSupplier abort) {
        return raytrace(columns, worldMinY, worldMaxY,
                x0 + 0.5, y0 + 0.5, z0 + 0.5,
                x1 + 0.5, y1 + 0.5, z1 + 0.5,
                abort);
    }

    static boolean raytraceCenters(final OccupancyCursor cursor,
                                   final int x0, final int y0, final int z0,
                                   final int x1, final int y1, final int z1,
                                   final BooleanSupplier abort) {
        return raytrace(cursor,
                x0 + 0.5, y0 + 0.5, z0 + 0.5,
                x1 + 0.5, y1 + 0.5, z1 + 0.5,
                abort);
    }

    /**
     * Amanatides–Woo voxel walk. Missing columns and out-of-world Y are solid. {@code start == end} is
     * clear iff that voxel is passable. The {@link LongFunction} overload looks up every voxel (live map).
     * The {@link OccupancyCursor} overload is for a frozen snapshot only.
     */
    static boolean raytrace(final LongFunction<PackedColumn> columns, final int worldMinY, final int worldMaxY,
                            final double x0, final double y0, final double z0,
                            final double x1, final double y1, final double z1,
                            final BooleanSupplier abort) {
        return raytraceWalk(columns, worldMinY, worldMaxY, null, x0, y0, z0, x1, y1, z1, abort);
    }

    static boolean raytrace(final OccupancyCursor cursor,
                            final double x0, final double y0, final double z0,
                            final double x1, final double y1, final double z1,
                            final BooleanSupplier abort) {
        return raytraceWalk(null, 0, 0, cursor, x0, y0, z0, x1, y1, z1, abort);
    }

    private static boolean voxelPassable(final OccupancyCursor cursor, final LongFunction<PackedColumn> columns,
                                         final int worldMinY, final int worldMaxY,
                                         final int x, final int y, final int z) {
        return cursor != null
                ? cursor.isPassable(x, y, z)
                : isPassable(columns, worldMinY, worldMaxY, x, y, z);
    }

    private static boolean raytraceWalk(final LongFunction<PackedColumn> columns, final int worldMinY, final int worldMaxY,
                                        final OccupancyCursor cursor,
                                        final double x0, final double y0, final double z0,
                                        final double x1, final double y1, final double z1,
                                        final BooleanSupplier abort) {
        if (x0 == x1 && y0 == y1 && z0 == z1) {
            return voxelPassable(cursor, columns, worldMinY, worldMaxY, Mth.floor(x0), Mth.floor(y0), Mth.floor(z0));
        }

        int x = Mth.floor(x0);
        int y = Mth.floor(y0);
        int z = Mth.floor(z0);
        final int xEnd = Mth.floor(x1);
        final int yEnd = Mth.floor(y1);
        final int zEnd = Mth.floor(z1);

        final double dx = x1 - x0;
        final double dy = y1 - y0;
        final double dz = z1 - z0;

        final int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        final int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        final int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;

        final double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        final double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        final double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

        double tMaxX = nextBoundaryT(x0, x, dx, stepX);
        double tMaxY = nextBoundaryT(y0, y, dy, stepY);
        double tMaxZ = nextBoundaryT(z0, z, dz, stepZ);

        final int maxSteps = Math.abs(xEnd - x) + Math.abs(yEnd - y) + Math.abs(zEnd - z) + 3;
        for (int i = 0; i < maxSteps; i++) {
            if (abort.getAsBoolean()) {
                return false;
            }
            if (!voxelPassable(cursor, columns, worldMinY, worldMaxY, x, y, z)) {
                return false;
            }
            if (x == xEnd && y == yEnd && z == zEnd) {
                return true;
            }
            final double tNext = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            final boolean sx = stepX != 0 && tMaxX <= tNext;
            final boolean sy = stepY != 0 && tMaxY <= tNext;
            final boolean sz = stepZ != 0 && tMaxZ <= tNext;
            // On a tied tMax the ray grazes extra face-adjacent voxels; treat those as solid too.
            if (sx && !voxelPassable(cursor, columns, worldMinY, worldMaxY, x + stepX, y, z)) {
                return false;
            }
            if (sy && !voxelPassable(cursor, columns, worldMinY, worldMaxY, x, y + stepY, z)) {
                return false;
            }
            if (sz && !voxelPassable(cursor, columns, worldMinY, worldMaxY, x, y, z + stepZ)) {
                return false;
            }
            if (sx && sy && !voxelPassable(cursor, columns, worldMinY, worldMaxY, x + stepX, y + stepY, z)) {
                return false;
            }
            if (sx && sz && !voxelPassable(cursor, columns, worldMinY, worldMaxY, x + stepX, y, z + stepZ)) {
                return false;
            }
            if (sy && sz && !voxelPassable(cursor, columns, worldMinY, worldMaxY, x, y + stepY, z + stepZ)) {
                return false;
            }
            if (sx) {
                x += stepX;
                tMaxX += tDeltaX;
            }
            if (sy) {
                y += stepY;
                tMaxY += tDeltaY;
            }
            if (sz) {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        return false;
    }

    private static double nextBoundaryT(final double pos, final int voxel, final double delta, final int step) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        final double boundary = step > 0 ? (voxel + 1) - pos : pos - voxel;
        return boundary / Math.abs(delta);
    }

    private static long[] packSection(final LevelChunkSection section) {
        if (section == null || section.hasOnlyAir()) {
            return null;
        }
        final PalettedContainer<BlockState> container = section.getStates();
        final IPalettedContainer<BlockState> ipc = (IPalettedContainer<BlockState>) container;
        final BitStorage storage = ipc.getStorage();
        if (storage == null) {
            return packSectionSlow(section);
        }
        final Palette<BlockState> palette = ipc.getPalette();
        final long[] bits = new long[SECTION_LONGS];
        final long[] raw = storage.getRaw();
        final int arraySize = storage.getSize();
        final int bitsPerEntry = storage.getBits();
        if (bitsPerEntry == 0) {
            return packSingleValue(palette, bits);
        }
        final long maxEntryValue = (1L << bitsPerEntry) - 1L;
        boolean anySolid = false;
        for (int i = 0, idx = 0; i < raw.length && idx < arraySize; i++) {
            long word = raw[i];
            for (int offset = 0; offset <= (64 - bitsPerEntry) && idx < arraySize; offset += bitsPerEntry, idx++) {
                final int value = (int) ((word >> offset) & maxEntryValue);
                if (!palette.valueFor(value).isAir()) {
                    bits[idx >>> 6] |= 1L << (idx & 63);
                    anySolid = true;
                }
            }
        }
        return anySolid ? bits : null;
    }

    private static long[] packSingleValue(final Palette<BlockState> palette, final long[] bits) {
        if (palette.valueFor(0).isAir()) {
            return null;
        }
        java.util.Arrays.fill(bits, -1L);
        return bits;
    }

    private static long[] packSectionSlow(final LevelChunkSection section) {
        final long[] bits = new long[SECTION_LONGS];
        boolean anySolid = false;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (!section.getBlockState(x, y, z).isAir()) {
                        final int idx = y << 8 | z << 4 | x;
                        bits[idx >>> 6] |= 1L << (idx & 63);
                        anySolid = true;
                    }
                }
            }
        }
        return anySolid ? bits : null;
    }

    static long chunkKey(final int chunkX, final int chunkZ) {
        return ChunkPos.pack(chunkX, chunkZ);
    }

    static long packPos(final int x, final int y, final int z) {
        return BlockPos.asLong(x, y, z);
    }

    static int unpackX(final long packed) {
        return BlockPos.getX(packed);
    }

    static int unpackY(final long packed) {
        return BlockPos.getY(packed);
    }

    static int unpackZ(final long packed) {
        return BlockPos.getZ(packed);
    }
}
