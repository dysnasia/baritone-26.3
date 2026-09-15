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

import baritone.api.utils.BetterBlockPos;
import baritone.process.elytra.VanillaElytraOccupancy.PackedColumn;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class VanillaElytraOccupancyTest {

    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;
    private static final int SECTIONS = 24; // 384 high

    private static PackedColumn air() {
        return VanillaElytraOccupancy.airColumn(MIN_Y, SECTIONS);
    }

    private static LongFunction<PackedColumn> lookup(final PackedColumn col, final int chunkX, final int chunkZ) {
        final long key = VanillaElytraOccupancy.chunkKey(chunkX, chunkZ);
        return k -> k == key ? col : null;
    }

    private static LongFunction<PackedColumn> emptyWorld() {
        return k -> null;
    }

    @Test
    public void emptyColumnIsAirAtWorldExtents() {
        final PackedColumn col = air();
        assertTrue(VanillaElytraOccupancy.isPassable(col, MIN_Y, MAX_Y, 0, MIN_Y, 0));
        assertTrue(VanillaElytraOccupancy.isPassable(col, MIN_Y, MAX_Y, 0, MIN_Y + SECTIONS * 16 - 1, 0));
        assertFalse(VanillaElytraOccupancy.isPassable(col, MIN_Y, MAX_Y, 0, MIN_Y - 1, 0));
        assertFalse(VanillaElytraOccupancy.isPassable(col, MIN_Y, MAX_Y, 0, MAX_Y, 0));
    }

    @Test
    public void setSolidAffectsOnlyThatVoxel() {
        PackedColumn col = VanillaElytraOccupancy.setSolid(air(), 3, -64, 5, true);
        assertFalse(VanillaElytraOccupancy.isPassable(col, MIN_Y, MAX_Y, 3, -64, 5));
        assertTrue(VanillaElytraOccupancy.isPassable(col, MIN_Y, MAX_Y, 4, -64, 5));
        assertTrue(VanillaElytraOccupancy.isPassable(col, MIN_Y, MAX_Y, 3, -63, 5));
        assertTrue(VanillaElytraOccupancy.isPassable(col, MIN_Y, MAX_Y, 3, -64, 6));
    }

    @Test
    public void unknownColumnIsSolid() {
        assertFalse(VanillaElytraOccupancy.isPassable(emptyWorld(), MIN_Y, MAX_Y, 0, 64, 0));
        assertTrue(VanillaElytraOccupancy.isPassable(lookup(air(), 0, 0), MIN_Y, MAX_Y, 0, 64, 0));
    }

    @Test
    public void snapshotCursorLooksUpOneChunkOnce() {
        final PackedColumn col = air();
        final long key = VanillaElytraOccupancy.chunkKey(0, 0);
        final int[] lookups = {0};
        final LongFunction<PackedColumn> cols = k -> {
            lookups[0]++;
            return k == key ? col : null;
        };
        final VanillaElytraOccupancy.OccupancyCursor cursor =
                new VanillaElytraOccupancy.OccupancyCursor(cols, MIN_Y, MAX_Y);
        assertTrue(VanillaElytraOccupancy.raytraceCenters(cursor, 1, 70, 1, 8, 70, 8, () -> false));
        assertEquals(1, lookups[0]);
    }

    @Test
    public void liveRaytraceLooksUpEveryVoxel() {
        final PackedColumn col = air();
        final long key = VanillaElytraOccupancy.chunkKey(0, 0);
        final int[] lookups = {0};
        final LongFunction<PackedColumn> cols = k -> {
            lookups[0]++;
            return k == key ? col : null;
        };
        assertTrue(VanillaElytraOccupancy.raytraceCenters(cols, MIN_Y, MAX_Y, 1, 70, 1, 8, 70, 8, () -> false));
        assertTrue(lookups[0] > 1);
    }

    @Test
    public void raytraceAllAirIsClear() {
        final LongFunction<PackedColumn> cols = lookup(air(), 0, 0);
        assertTrue(VanillaElytraOccupancy.raytraceCenters(cols, MIN_Y, MAX_Y, 1, 70, 1, 8, 70, 8, () -> false));
    }

    @Test
    public void raytraceHitsSolidAndStopsBefore() {
        PackedColumn col = VanillaElytraOccupancy.setSolid(air(), 5, 70, 0, true);
        final LongFunction<PackedColumn> cols = lookup(col, 0, 0);
        assertFalse(VanillaElytraOccupancy.raytraceCenters(cols, MIN_Y, MAX_Y, 0, 70, 0, 8, 70, 0, () -> false));
        assertTrue(VanillaElytraOccupancy.raytraceCenters(cols, MIN_Y, MAX_Y, 0, 70, 0, 3, 70, 0, () -> false));
    }

    @Test
    public void raytraceStartEqualsEndUsesThatVoxel() {
        PackedColumn col = VanillaElytraOccupancy.setSolid(air(), 2, 80, 2, true);
        final LongFunction<PackedColumn> cols = lookup(col, 0, 0);
        assertTrue(VanillaElytraOccupancy.raytrace(cols, MIN_Y, MAX_Y, 1.5, 80.5, 1.5, 1.5, 80.5, 1.5, () -> false));
        assertFalse(VanillaElytraOccupancy.raytrace(cols, MIN_Y, MAX_Y, 2.5, 80.5, 2.5, 2.5, 80.5, 2.5, () -> false));
    }

    @Test
    public void raytraceYBelowWorldIsBlocked() {
        final LongFunction<PackedColumn> cols = lookup(air(), 0, 0);
        assertFalse(VanillaElytraOccupancy.raytrace(cols, MIN_Y, MAX_Y, 0.5, -80, 0.5, 0.5, -70, 0.5, () -> false));
    }

    @Test
    public void raytraceMissingColumnIsBlocked() {
        final Map<Long, PackedColumn> map = new HashMap<>();
        map.put(VanillaElytraOccupancy.chunkKey(0, 0), air());
        final LongFunction<PackedColumn> cols = map::get;
        assertFalse(VanillaElytraOccupancy.raytraceCenters(cols, MIN_Y, MAX_Y, 8, 70, 8, 24, 70, 8, () -> false));
    }

    @Test
    public void raytraceDiagonalHitsCornerVoxel() {
        PackedColumn col = VanillaElytraOccupancy.setSolid(air(), 1, 70, 1, true);
        final LongFunction<PackedColumn> cols = lookup(col, 0, 0);
        assertFalse(VanillaElytraOccupancy.raytrace(
                cols, MIN_Y, MAX_Y, 0.1, 70.5, 0.1, 2.9, 70.5, 2.9, () -> false));
    }

    @Test
    public void raytraceFortyFiveGrazesOffDiagonalVoxel() {
        // (0.5,0.5) → (8.5,8.5) would skip (1,70,0) if only one axis advances on a tMax tie.
        PackedColumn col = VanillaElytraOccupancy.setSolid(air(), 1, 70, 0, true);
        final LongFunction<PackedColumn> cols = lookup(col, 0, 0);
        assertFalse(VanillaElytraOccupancy.raytraceCenters(cols, MIN_Y, MAX_Y, 0, 70, 0, 8, 70, 8, () -> false));
    }

    @Test
    public void raytraceAcrossTwoAirChunksIsClear() {
        final Map<Long, PackedColumn> map = new HashMap<>();
        map.put(VanillaElytraOccupancy.chunkKey(0, 0), air());
        map.put(VanillaElytraOccupancy.chunkKey(1, 0), air());
        final LongFunction<PackedColumn> cols = map::get;
        assertTrue(VanillaElytraOccupancy.raytraceCenters(cols, MIN_Y, MAX_Y, 8, 70, 8, 24, 70, 8, () -> false));
    }

    @Test
    public void raytraceAcrossChunkHitsSolidInSecondChunk() {
        final Map<Long, PackedColumn> map = new HashMap<>();
        map.put(VanillaElytraOccupancy.chunkKey(0, 0), air());
        map.put(VanillaElytraOccupancy.chunkKey(1, 0), VanillaElytraOccupancy.setSolid(air(), 20, 70, 8, true));
        final LongFunction<PackedColumn> cols = map::get;
        assertFalse(VanillaElytraOccupancy.raytraceCenters(cols, MIN_Y, MAX_Y, 8, 70, 8, 24, 70, 8, () -> false));
        assertTrue(VanillaElytraOccupancy.raytraceCenters(cols, MIN_Y, MAX_Y, 8, 70, 8, 18, 70, 8, () -> false));
    }

    @Test
    public void occupancyCursorSurvivesChunkBoundaryAndNegativeChunks() {
        final Map<Long, PackedColumn> map = new HashMap<>();
        map.put(VanillaElytraOccupancy.chunkKey(0, 0), air());
        map.put(VanillaElytraOccupancy.chunkKey(-1, 0), VanillaElytraOccupancy.setSolid(air(), -1, 70, 0, true));
        final VanillaElytraOccupancy.OccupancyCursor cursor =
                new VanillaElytraOccupancy.OccupancyCursor(map::get, MIN_Y, MAX_Y);
        assertTrue(cursor.isPassable(0, 70, 0));
        assertTrue(cursor.isPassable(15, 70, 0));
        assertFalse(cursor.isPassable(-1, 70, 0));
        assertTrue(cursor.isPassable(-2, 70, 0));
        assertFalse(cursor.isPassable(0, MIN_Y - 1, 0));
        assertTrue(cursor.isPassable(1, 70, 0));
    }

    @Test
    public void raytraceFortyFiveGrazesSolidInNextChunk() {
        final Map<Long, PackedColumn> map = new HashMap<>();
        map.put(VanillaElytraOccupancy.chunkKey(0, 0), air());
        map.put(VanillaElytraOccupancy.chunkKey(1, 0), VanillaElytraOccupancy.setSolid(air(), 16, 70, 0, true));
        final LongFunction<PackedColumn> cols = map::get;
        assertFalse(VanillaElytraOccupancy.raytraceCenters(cols, MIN_Y, MAX_Y, 15, 70, 0, 23, 70, 8, () -> false));
    }

    @Test
    public void snapshotCursorMissingChunkLooksUpOnce() {
        final PackedColumn col = air();
        final long present = VanillaElytraOccupancy.chunkKey(0, 0);
        final long missing = VanillaElytraOccupancy.chunkKey(1, 0);
        final int[] lookups = {0};
        final int[] missingLookups = {0};
        final LongFunction<PackedColumn> cols = k -> {
            lookups[0]++;
            if (k == missing) {
                missingLookups[0]++;
                return null;
            }
            return k == present ? col : null;
        };
        final VanillaElytraOccupancy.OccupancyCursor cursor =
                new VanillaElytraOccupancy.OccupancyCursor(cols, MIN_Y, MAX_Y);
        assertFalse(VanillaElytraOccupancy.raytraceCenters(cursor, 8, 70, 8, 24, 70, 8, () -> false));
        assertEquals(1, missingLookups[0]);
        assertTrue(lookups[0] <= 2);
    }

    @Test
    public void goalRadiusSqMatchesEuclidean() {
        final double radius = VanillaElytraContext.GOAL_RADIUS;
        final double radiusSq = radius * radius;
        final int[][] offsets = {
                {0, 0, 0},
                {VanillaElytraContext.GOAL_RADIUS, 0, 0},
                {0, VanillaElytraContext.GOAL_RADIUS, 0},
                {0, 0, VanillaElytraContext.GOAL_RADIUS},
                {5, 5, 0},
                {6, 6, 0},
                {VanillaElytraContext.GOAL_RADIUS, 1, 0},
                {4, 4, 4},
                {7, 3, 2},
        };
        for (final int[] o : offsets) {
            final double distSq = (double) o[0] * o[0] + (double) o[1] * o[1] + (double) o[2] * o[2];
            final double dist = Math.sqrt(distSq);
            assertEquals(dist <= radius, distSq <= radiusSq);
        }
    }

    @Test
    public void neighborStepCostMatchesDistTimesClimbDescend() {
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    final double expected = Math.sqrt(
                            (double) (dx * VanillaElytraContext.STEP) * (dx * VanillaElytraContext.STEP)
                                    + (double) (dy * VanillaElytraContext.STEP) * (dy * VanillaElytraContext.STEP)
                                    + (double) (dz * VanillaElytraContext.STEP) * (dz * VanillaElytraContext.STEP))
                            * (dy > 0 ? VanillaElytraContext.CLIMB_COST_MULT
                            : dy < 0 ? VanillaElytraContext.DESCEND_COST_MULT : 1.0);
                    assertEquals(expected, VanillaElytraContext.neighborStepCost(dx, dy, dz), 0.0);
                    assertEquals(dx, VanillaElytraContext.NEIGHBOR_DX[i]);
                    assertEquals(dy, VanillaElytraContext.NEIGHBOR_DY[i]);
                    assertEquals(dz, VanillaElytraContext.NEIGHBOR_DZ[i]);
                    assertEquals(expected, VanillaElytraContext.NEIGHBOR_STEP_COST[i], 0.0);
                    i++;
                }
            }
        }
        assertEquals(VanillaElytraContext.NEIGHBOR_COUNT, i);
    }

    @Test
    public void asLongRoundtrip() {
        final int[][] samples = {
                {0, 0, 0},
                {1, 2, 3},
                {-123, -64, 456},
                {30000000, 319, -30000000},
        };
        for (final int[] s : samples) {
            final long packed = BlockPos.asLong(s[0], s[1], s[2]);
            assertEquals(s[0], BlockPos.getX(packed));
            assertEquals(s[1], BlockPos.getY(packed));
            assertEquals(s[2], BlockPos.getZ(packed));
            assertEquals(s[0], VanillaElytraOccupancy.unpackX(packed));
            assertEquals(s[1], VanillaElytraOccupancy.unpackY(packed));
            assertEquals(s[2], VanillaElytraOccupancy.unpackZ(packed));
        }
        // native path packing is a different layout; do not mix with A* keys
        assertNotEquals(BlockPos.asLong(1, 2, 3), BetterBlockPos.serializeToLong(1, 2, 3));
    }

    @Test
    public void freezeIgnoresLaterLiveMutation() {
        final Map<Long, PackedColumn> live = new HashMap<>();
        live.put(VanillaElytraOccupancy.chunkKey(0, 0), air());
        final VanillaElytraOccupancy.OccupancySnapshot snap =
                VanillaElytraOccupancy.freeze(live, MIN_Y, MAX_Y, () -> false);
        assertTrue(snap.isPassable(0, 70, 0));
        assertTrue(VanillaElytraOccupancy.raytraceCenters(live::get, MIN_Y, MAX_Y, 0, 70, 0, 8, 70, 0, () -> false));

        live.put(VanillaElytraOccupancy.chunkKey(0, 0), VanillaElytraOccupancy.setSolid(air(), 0, 70, 0, true));
        assertTrue(snap.isPassable(0, 70, 0));
        assertTrue(snap.raytraceCenters(0, 70, 0, 8, 70, 0));
        assertFalse(VanillaElytraOccupancy.isPassable(live::get, MIN_Y, MAX_Y, 0, 70, 0));

        live.remove(VanillaElytraOccupancy.chunkKey(0, 0));
        assertTrue(snap.isPassable(0, 70, 0));
        assertFalse(VanillaElytraOccupancy.isPassable(live::get, MIN_Y, MAX_Y, 0, 70, 0));
    }

    @Test
    public void freezeMissingColumnIsSolidAndIgnoresLaterPack() {
        final Map<Long, PackedColumn> live = new HashMap<>();
        live.put(VanillaElytraOccupancy.chunkKey(0, 0), air());
        final VanillaElytraOccupancy.OccupancySnapshot snap =
                VanillaElytraOccupancy.freeze(live, MIN_Y, MAX_Y, () -> false);
        assertFalse(snap.isPassable(20, 70, 8));
        assertFalse(snap.raytraceCenters(8, 70, 8, 24, 70, 8));

        live.put(VanillaElytraOccupancy.chunkKey(1, 0), air());
        assertFalse(snap.isPassable(20, 70, 8));
        assertFalse(snap.raytraceCenters(8, 70, 8, 24, 70, 8));
        assertTrue(VanillaElytraOccupancy.isPassable(live::get, MIN_Y, MAX_Y, 20, 70, 8));
        assertTrue(VanillaElytraOccupancy.raytraceCenters(live::get, MIN_Y, MAX_Y, 8, 70, 8, 24, 70, 8, () -> false));
    }

    @Test
    public void freezeRaytraceBatchSeesFrozenSolidNotLaterPack() {
        final Map<Long, PackedColumn> live = new HashMap<>();
        live.put(VanillaElytraOccupancy.chunkKey(0, 0), air());
        final VanillaElytraOccupancy.OccupancySnapshot snap =
                VanillaElytraOccupancy.freeze(live, MIN_Y, MAX_Y, () -> false);

        final double[] src = {0.5, 70.5, 0.5, 8.5, 70.5, 8.5};
        final double[] dst = {8.5, 70.5, 0.5, 24.5, 70.5, 8.5};
        assertFalse(snap.raytraceBatch(2, src, dst));

        live.put(VanillaElytraOccupancy.chunkKey(1, 0), air());
        assertFalse(snap.raytraceBatch(2, src, dst));
        assertTrue(VanillaElytraOccupancy.raytrace(live::get, MIN_Y, MAX_Y, 8.5, 70.5, 8.5, 24.5, 70.5, 8.5, () -> false));
    }
}
