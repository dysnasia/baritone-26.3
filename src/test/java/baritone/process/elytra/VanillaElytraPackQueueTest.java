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

import baritone.process.elytra.VanillaElytraOccupancy.PackedColumn;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class VanillaElytraPackQueueTest {

    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;
    private static final int SECTIONS = 24;

    private static PackedColumn air() {
        return VanillaElytraOccupancy.airColumn(MIN_Y, SECTIONS);
    }

    private static boolean passable(final ConcurrentHashMap<Long, PackedColumn> occupancy, final int x, final int y, final int z) {
        return VanillaElytraOccupancy.isPassable(occupancy::get, MIN_Y, MAX_Y, x, y, z);
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void packRunsOffCallerAndStaysSolidUntilPut() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final ConcurrentHashMap<Long, PackedColumn> occupancy = new ConcurrentHashMap<>();
            final VanillaElytraPackQueue queue = new VanillaElytraPackQueue(occupancy, executor, () -> false);
            final long key = VanillaElytraOccupancy.chunkKey(0, 0);
            final CountDownLatch started = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final AtomicReference<Thread> packerThread = new AtomicReference<>();
            final Thread caller = Thread.currentThread();

            final CompletableFuture<PackedColumn> future = queue.submitPack(key, () -> {
                packerThread.set(Thread.currentThread());
                started.countDown();
                await(release);
                return air();
            });

            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(future.isDone());
            assertNotSame(caller, packerThread.get());
            assertEquals(1L, queue.submitted());
            assertEquals(0L, queue.completed());
            assertEquals(caller.getName(), queue.lastCaller());
            assertEquals(packerThread.get().getName(), queue.lastWorker());
            assertFalse(queue.lastCaller().equals(queue.lastWorker()));
            assertFalse(occupancy.containsKey(key));
            assertFalse(passable(occupancy, 0, 70, 0));

            release.countDown();
            final PackedColumn packed = future.get(5, TimeUnit.SECONDS);
            assertTrue(packed != null);
            assertEquals(1L, queue.completed());
            assertTrue(occupancy.containsKey(key));
            assertTrue(passable(occupancy, 0, 70, 0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void nullPackerResultDoesNotPut() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final ConcurrentHashMap<Long, PackedColumn> occupancy = new ConcurrentHashMap<>();
            final VanillaElytraPackQueue queue = new VanillaElytraPackQueue(occupancy, executor, () -> false);
            final long key = VanillaElytraOccupancy.chunkKey(1, 0);
            final PackedColumn result = queue.submitPack(key, () -> null).get(5, TimeUnit.SECONDS);
            assertTrue(result == null);
            assertFalse(occupancy.containsKey(key));
            assertFalse(passable(occupancy, 20, 70, 0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void destroyedAtPutSkipsOccupancy() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final ConcurrentHashMap<Long, PackedColumn> occupancy = new ConcurrentHashMap<>();
            final AtomicBoolean destroyed = new AtomicBoolean(false);
            final VanillaElytraPackQueue queue = new VanillaElytraPackQueue(occupancy, executor, destroyed::get);
            final long key = VanillaElytraOccupancy.chunkKey(2, 0);
            final CountDownLatch started = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);

            final CompletableFuture<PackedColumn> future = queue.submitPack(key, () -> {
                started.countDown();
                await(release);
                return air();
            });

            assertTrue(started.await(5, TimeUnit.SECONDS));
            destroyed.set(true);
            release.countDown();
            assertTrue(future.get(5, TimeUnit.SECONDS) == null);
            assertFalse(occupancy.containsKey(key));
            assertFalse(passable(occupancy, 32, 70, 0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test(timeout = 3000)
    public void submitPackDoesNotJoinWhileCallerHoldsPackerLock() throws Exception {
        final Object lock = new Object();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final ConcurrentHashMap<Long, PackedColumn> occupancy = new ConcurrentHashMap<>();
            final VanillaElytraPackQueue queue = new VanillaElytraPackQueue(occupancy, executor, () -> false);
            final long key = VanillaElytraOccupancy.chunkKey(4, 0);
            final CompletableFuture<PackedColumn> future;
            synchronized (lock) {
                future = queue.submitPack(key, () -> {
                    synchronized (lock) {
                        return air();
                    }
                });
                assertFalse(future.isDone());
                assertFalse(occupancy.containsKey(key));
                assertFalse(passable(occupancy, 64, 70, 0));
            }
            assertTrue(future.get(5, TimeUnit.SECONDS) != null);
            assertTrue(occupancy.containsKey(key));
            assertTrue(passable(occupancy, 64, 70, 0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void invalidateDropsInFlightPack() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final ConcurrentHashMap<Long, PackedColumn> occupancy = new ConcurrentHashMap<>();
            final VanillaElytraPackQueue queue = new VanillaElytraPackQueue(occupancy, executor, () -> false);
            final long key = VanillaElytraOccupancy.chunkKey(5, 0);
            final CountDownLatch started = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final CompletableFuture<PackedColumn> future = queue.submitPack(key, () -> {
                started.countDown();
                await(release);
                return air();
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            synchronized (queue) {
                queue.invalidate(key);
                occupancy.remove(key);
            }
            release.countDown();
            assertTrue(future.get(5, TimeUnit.SECONDS) == null);
            assertFalse(occupancy.containsKey(key));
            assertFalse(passable(occupancy, 80, 70, 0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void invalidateWithoutRemoveStillDropsInFlightPack() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final ConcurrentHashMap<Long, PackedColumn> occupancy = new ConcurrentHashMap<>();
            final VanillaElytraPackQueue queue = new VanillaElytraPackQueue(occupancy, executor, () -> false);
            final long key = VanillaElytraOccupancy.chunkKey(6, 0);
            final CountDownLatch started = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final CompletableFuture<PackedColumn> future = queue.submitPack(key, () -> {
                started.countDown();
                await(release);
                return air();
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            queue.invalidate(key);
            release.countDown();
            assertTrue(future.get(5, TimeUnit.SECONDS) == null);
            assertFalse(occupancy.containsKey(key));
            assertFalse(passable(occupancy, 96, 70, 0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void occupancyStampBumpsOnPutAndInvalidateNotOnFailedPut() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final ConcurrentHashMap<Long, PackedColumn> occupancy = new ConcurrentHashMap<>();
            final VanillaElytraPackQueue queue = new VanillaElytraPackQueue(occupancy, executor, () -> false);
            assertEquals(0L, queue.occupancyStamp());
            final long key = VanillaElytraOccupancy.chunkKey(7, 0);
            assertTrue(queue.submitPack(key, VanillaElytraPackQueueTest::air).get(5, TimeUnit.SECONDS) != null);
            final long afterPut = queue.occupancyStamp();
            assertTrue(afterPut > 0L);
            assertFalse(queue.tryPut(key, air(), queue.generation(key) + 1));
            assertEquals(afterPut, queue.occupancyStamp());
            queue.invalidate(key);
            assertTrue(queue.occupancyStamp() > afterPut);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void joinSeesColumnOnlyAfterPack() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final ConcurrentHashMap<Long, PackedColumn> occupancy = new ConcurrentHashMap<>();
            final VanillaElytraPackQueue queue = new VanillaElytraPackQueue(occupancy, executor, () -> false);
            final long key = VanillaElytraOccupancy.chunkKey(3, 0);
            final CountDownLatch started = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final AtomicBoolean sawBeforeRelease = new AtomicBoolean(false);

            final CompletableFuture<PackedColumn> future = queue.submitPack(key, () -> {
                started.countDown();
                await(release);
                return air();
            });

            assertTrue(started.await(5, TimeUnit.SECONDS));
            sawBeforeRelease.set(occupancy.containsKey(key) || passable(occupancy, 48, 70, 0));
            release.countDown();
            future.join();
            assertFalse(sawBeforeRelease.get());
            assertTrue(occupancy.containsKey(key));
            assertTrue(passable(occupancy, 48, 70, 0));
        } finally {
            executor.shutdownNow();
        }
    }
}
