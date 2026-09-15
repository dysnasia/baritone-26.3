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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Packs occupancy columns on an executor. The queuing thread does not run the packer.
 * Missing keys stay solid until {@code occupancy.put} of a real column.
 */
final class VanillaElytraPackQueue {

    private final ConcurrentHashMap<Long, PackedColumn> occupancy;
    private final ConcurrentHashMap<Long, Integer> generation = new ConcurrentHashMap<>();
    private final Executor executor;
    private final BooleanSupplier destroyed;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong occupancyStamp = new AtomicLong();
    private volatile String lastCaller;
    private volatile String lastWorker;

    VanillaElytraPackQueue(final ConcurrentHashMap<Long, PackedColumn> occupancy, final Executor executor,
                           final BooleanSupplier destroyed) {
        this.occupancy = occupancy;
        this.executor = executor;
        this.destroyed = destroyed;
    }

    synchronized int generation(final long key) {
        return this.generation.getOrDefault(key, 0);
    }

    synchronized void invalidate(final long key) {
        this.generation.merge(key, 1, Integer::sum);
        this.occupancyStamp.incrementAndGet();
    }

    /**
     * Bumps when live occupancy changes (successful put, invalidate/cull/drop/block update).
     * Solver freeze cache keys off this.
     */
    synchronized long occupancyStamp() {
        return this.occupancyStamp.get();
    }

    static final class SolverFreeze {
        final OccupancySnapshot snapshot;
        final long stamp;

        SolverFreeze(final OccupancySnapshot snapshot, final long stamp) {
            this.snapshot = snapshot;
            this.stamp = stamp;
        }
    }

    /**
     * Copy live occupancy for a real solve, or return {@code previous} when the stamp is unchanged.
     * Equals-only solver-context construction must not call this.
     */
    synchronized SolverFreeze freezeForSolve(final OccupancySnapshot previous, final long previousStamp,
                                             final int worldMinY, final int worldMaxY,
                                             final BooleanSupplier abort) {
        final long gen = this.occupancyStamp.get();
        if (previous != null && previousStamp == gen) {
            return new SolverFreeze(previous, gen);
        }
        return new SolverFreeze(
                VanillaElytraOccupancy.freeze(this.occupancy, worldMinY, worldMaxY, abort),
                this.occupancyStamp.get());
    }

    /**
     * Put {@code column} only if {@code gen} still matches. Callers that drop or update
     * occupancy must hold this queue's monitor around {@link #invalidate} plus the map write.
     */
    synchronized boolean tryPut(final long key, final PackedColumn column, final int gen) {
        if (this.destroyed.getAsBoolean() || column == null || this.generation(key) != gen) {
            return false;
        }
        this.occupancy.put(key, column);
        this.occupancyStamp.incrementAndGet();
        return true;
    }

    long submitted() {
        return this.submitted.get();
    }

    long completed() {
        return this.completed.get();
    }

    String lastCaller() {
        return this.lastCaller;
    }

    String lastWorker() {
        return this.lastWorker;
    }

    CompletableFuture<PackedColumn> submitPack(final long key, final Supplier<PackedColumn> packer) {
        final int gen;
        synchronized (this) {
            gen = this.generation(key);
        }
        this.lastCaller = Thread.currentThread().getName();
        this.submitted.incrementAndGet();
        try {
            return CompletableFuture.supplyAsync(() -> {
                this.lastWorker = Thread.currentThread().getName();
                final PackedColumn column = packer.get();
                this.completed.incrementAndGet();
                return this.tryPut(key, column, gen) ? column : null;
            }, this.executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
