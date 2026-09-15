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

import baritone.process.elytra.VanillaElytraOccupancy.OccupancySnapshot;
import baritone.process.elytra.VanillaElytraOccupancy.PackedColumn;
import baritone.process.elytra.VanillaElytraPackQueue.SolverFreeze;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SolverOccupancyTest {

    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;
    private static final int SECTIONS = 24;

    private static PackedColumn air() {
        return VanillaElytraOccupancy.airColumn(MIN_Y, SECTIONS);
    }

    private static SolverFreeze freeze(final VanillaElytraPackQueue queue,
                                       final OccupancySnapshot previous, final long previousStamp) {
        return queue.freezeForSolve(previous, previousStamp, MIN_Y, MAX_Y, () -> false);
    }

    @Test
    public void freezeSnapshotIgnoresLaterLivePutWithoutStampBump() {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final ConcurrentHashMap<Long, PackedColumn> occupancy = new ConcurrentHashMap<>();
            final VanillaElytraPackQueue queue = new VanillaElytraPackQueue(occupancy, executor, () -> false);
            final long key = VanillaElytraOccupancy.chunkKey(0, 0);
            assertTrue(queue.tryPut(key, air(), 0));

            // Equals-only solver-context construction does not call freezeForSolve.
            assertEquals(ElytraDebug.SOLVER_NOT_YET, ElytraDebug.solverTerrain(null));

            final SolverFreeze first = freeze(queue, null, Long.MIN_VALUE);
            assertTrue(first.snapshot.isPassable(0, 70, 0));

            occupancy.put(key, VanillaElytraOccupancy.setSolid(air(), 0, 70, 0, true));
            assertTrue(first.snapshot.isPassable(0, 70, 0));
            assertFalse(VanillaElytraOccupancy.isPassable(occupancy::get, MIN_Y, MAX_Y, 0, 70, 0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void secondSolveSameStampDoesNotRecopy() {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final ConcurrentHashMap<Long, PackedColumn> occupancy = new ConcurrentHashMap<>();
            final VanillaElytraPackQueue queue = new VanillaElytraPackQueue(occupancy, executor, () -> false);
            assertTrue(queue.tryPut(VanillaElytraOccupancy.chunkKey(0, 0), air(), 0));
            final SolverFreeze first = freeze(queue, null, Long.MIN_VALUE);
            final SolverFreeze second = freeze(queue, first.snapshot, first.stamp);
            assertSame(first.snapshot, second.snapshot);
            assertEquals(first.stamp, second.stamp);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void generationChangeNextSolveCopiesAndIgnoresLaterLiveMutation() {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final ConcurrentHashMap<Long, PackedColumn> occupancy = new ConcurrentHashMap<>();
            final VanillaElytraPackQueue queue = new VanillaElytraPackQueue(occupancy, executor, () -> false);
            final long key = VanillaElytraOccupancy.chunkKey(0, 0);
            assertTrue(queue.tryPut(key, air(), 0));
            final SolverFreeze first = freeze(queue, null, Long.MIN_VALUE);
            assertTrue(first.snapshot.isPassable(0, 70, 0));

            synchronized (queue) {
                queue.invalidate(key);
                occupancy.put(key, VanillaElytraOccupancy.setSolid(air(), 0, 70, 0, true));
            }
            final SolverFreeze second = freeze(queue, first.snapshot, first.stamp);
            assertNotSame(first.snapshot, second.snapshot);
            assertTrue(second.stamp > first.stamp);
            assertFalse(second.snapshot.isPassable(0, 70, 0));

            occupancy.put(key, air());
            assertFalse(second.snapshot.isPassable(0, 70, 0));
            assertFalse(second.snapshot.raytraceCenters(0, 70, 0, 8, 70, 0));
            assertTrue(VanillaElytraOccupancy.isPassable(occupancy::get, MIN_Y, MAX_Y, 0, 70, 0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void successfulTryPutBumpsStampSoNextSolveRecopies() {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final ConcurrentHashMap<Long, PackedColumn> occupancy = new ConcurrentHashMap<>();
            final VanillaElytraPackQueue queue = new VanillaElytraPackQueue(occupancy, executor, () -> false);
            final long key0 = VanillaElytraOccupancy.chunkKey(0, 0);
            final long key1 = VanillaElytraOccupancy.chunkKey(1, 0);
            assertTrue(queue.tryPut(key0, air(), 0));
            final SolverFreeze first = freeze(queue, null, Long.MIN_VALUE);
            assertFalse(first.snapshot.isPassable(20, 70, 0));

            assertTrue(queue.tryPut(key1, air(), 0));
            final SolverFreeze second = freeze(queue, first.snapshot, first.stamp);
            assertNotSame(first.snapshot, second.snapshot);
            assertTrue(second.stamp > first.stamp);
            assertTrue(second.snapshot.isPassable(20, 70, 0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void solverTerrainLabelComesFromSolveNotContextBuild() {
        assertEquals(ElytraDebug.SOLVER_NOT_YET, ElytraDebug.solverTerrain(null));
        final String fromSolve = ElytraDebug.solverTerrain(ElytraDebug.SOLVER_FROZEN);
        assertEquals(ElytraDebug.SOLVER_FROZEN, fromSolve);
        final String out = ElytraDebug.format(new ElytraDebug.Snapshot(
                true, "ElytraProcess", true, "Flying", "overworld",
                ElytraDebug.BACKEND_VANILLA, "VanillaElytraContext", fromSolve,
                1, 1L, 1L, null, null, null, null, null, 0L));
        assertTrue(out.contains("solver terrain: frozen occupancy"));
        assertEquals(ElytraDebug.SOLVER_NETHER, ElytraDebug.solverTerrain(ElytraDebug.SOLVER_NETHER));
    }

    @Test
    public void solverContextCtorDoesNotFreeze() throws IOException {
        final String src = Files.readString(Path.of("src/main/java/baritone/process/elytra/ElytraBehavior.java"));
        assertFalse(src.contains("lastSolverUsedFreeze"));
        assertFalse(src.contains("solverClearView"));
        final int ctor = src.indexOf("public SolverContext(boolean async)");
        final int bind = src.indexOf("void bindSolveTerrain()");
        assertTrue(ctor >= 0 && bind > ctor);
        final String ctorBody = src.substring(ctor, bind);
        assertFalse(ctorBody.contains("freezeForSolve"));
        assertFalse(ctorBody.contains("freezeIfVanilla"));
        assertFalse(ctorBody.contains("freeze("));
        assertFalse(ctorBody.contains("lastSolveTerrain"));
        final String bindBody = src.substring(bind, src.indexOf("boolean isPassable", bind));
        assertTrue(bindBody.contains("freezeForSolve"));
        assertTrue(src.contains("context.bindSolveTerrain()"));
        final int pathMgr = src.indexOf("public final class PathManager");
        final int onTick = src.indexOf("public void onTick()");
        assertTrue(pathMgr >= 0 && onTick > pathMgr);
        final String pathManager = src.substring(pathMgr, onTick);
        assertTrue(pathManager.contains("ElytraBehavior.this.clearView("));
        assertFalse(pathManager.contains("context::raytrace"));
        assertFalse(pathManager.contains("bindSolveTerrain"));
        assertTrue(src.contains("this.context::raytrace"));
        assertTrue(src.contains("false, context::raytrace)"));
        assertTrue(src.contains("context.ignoreLava, context::raytrace)"));
    }
}
