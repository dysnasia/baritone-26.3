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

import baritone.api.process.IElytraProcess;
import baritone.api.utils.IPlayerContext;
import baritone.process.ElytraProcess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * One-shot in-game snapshot of the elytra optimizer. Does not start or change flight.
 */
public final class ElytraDebug {

    private ElytraDebug() {}

    public static final String BACKEND_VANILLA = "vanilla freeze";
    public static final String BACKEND_NETHER = "nether JNI";
    public static final String BACKEND_NONE = "none";
    public static final String SOLVER_FROZEN = "frozen occupancy";
    public static final String SOLVER_NETHER = "nether JNI";
    public static final String SOLVER_NOT_YET = "not yet";

    public record Snapshot(
            boolean nativeLoaded,
            String process,
            boolean active,
            String state,
            String dimension,
            String backend,
            String context,
            String solverTerrain,
            Integer occupancyColumns,
            Long packSubmitted,
            Long packCompleted,
            String lastPackCaller,
            String lastPackWorker,
            Integer lastExplored,
            Long lastSearchNanos,
            Boolean lastReached,
            Long motionFiniteRejects
    ) {}

    public static String dump(final IElytraProcess process, final IPlayerContext ctx) {
        return format(snapshot(process, ctx));
    }

    static Snapshot snapshot(final IElytraProcess process, final IPlayerContext ctx) {
        if (process instanceof NullElytraProcess) {
            return idle(false, "NullElytraProcess", ctx, BACKEND_NONE, "none");
        }
        if (process instanceof ElytraProcess elytra) {
            return elytra.debugSnapshot(ctx);
        }
        return idle(process.isLoaded(), process.getClass().getSimpleName(), ctx, BACKEND_NONE, "none");
    }

    public static Snapshot idle(final boolean nativeLoaded, final String process, final IPlayerContext ctx,
                                final String backend, final String contextName) {
        return new Snapshot(
                nativeLoaded,
                process,
                false,
                "n/a",
                dimensionPath(ctx),
                backend,
                contextName,
                "n/a",
                null, null, null, null, null, null, null, null, null
        );
    }

    static String dimensionPath(final IPlayerContext ctx) {
        if (ctx == null || ctx.world() == null) {
            return "n/a";
        }
        final ResourceKey<Level> dim = ctx.world().dimension();
        return dim.identifier().getPath();
    }

    /**
     * Solver terrain label from the last actual solve. {@code null} means no solve has run yet,
     * not a dump-only flag set on unused context construction.
     */
    public static String solverTerrain(final String lastSolveTerrain) {
        return lastSolveTerrain == null ? SOLVER_NOT_YET : lastSolveTerrain;
    }

    public static String predictedBackend(final IPlayerContext ctx) {
        if (ctx == null || ctx.world() == null) {
            return BACKEND_NONE;
        }
        return ElytraFlightPolicy.usesNetherTerrainPrediction(ctx.world().dimension())
                ? BACKEND_NETHER : BACKEND_VANILLA;
    }

    public static String format(final Snapshot s) {
        return String.join("\n",
                "native lib: " + yn(s.nativeLoaded()),
                "process: " + s.process(),
                "active: " + yn(s.active()),
                "state: " + na(s.state()),
                "dimension: " + na(s.dimension()),
                "backend: " + na(s.backend()),
                "context: " + na(s.context()),
                "solver terrain: " + na(s.solverTerrain()),
                "occupancy columns: " + na(s.occupancyColumns()),
                "pack submitted: " + na(s.packSubmitted()),
                "pack completed: " + na(s.packCompleted()),
                "last pack caller: " + na(s.lastPackCaller()),
                "last pack worker: " + na(s.lastPackWorker()),
                "last A* explored: " + na(s.lastExplored()),
                "last A* time ns: " + na(s.lastSearchNanos()),
                "last A* reached: " + na(s.lastReached()),
                "motion finite-rejects: " + na(s.motionFiniteRejects())
        );
    }

    private static String yn(final boolean v) {
        return v ? "yes" : "no";
    }

    private static String na(final Object v) {
        return v == null ? "n/a" : String.valueOf(v);
    }
}
