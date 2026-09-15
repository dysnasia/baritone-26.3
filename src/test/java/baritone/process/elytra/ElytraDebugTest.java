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

import baritone.command.defaults.ElytraCommand;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ElytraDebugTest {

    @Test
    public void formatVanillaFlyingShowsFreezeAndPackThreads() {
        final ElytraDebug.Snapshot snap = new ElytraDebug.Snapshot(
                true,
                "ElytraProcess",
                true,
                "Flying",
                "overworld",
                ElytraDebug.BACKEND_VANILLA,
                "VanillaElytraContext",
                ElytraDebug.SOLVER_FROZEN,
                12,
                4L,
                4L,
                "Render thread",
                "Baritone Vanilla Elytra Pathfinder",
                1823,
                12_400_000L,
                true,
                0L
        );
        final String out = ElytraDebug.format(snap);
        assertTrue(out.contains("native lib: yes"));
        assertTrue(out.contains("active: yes"));
        assertTrue(out.contains("backend: vanilla freeze"));
        assertTrue(out.contains("solver terrain: frozen occupancy"));
        assertTrue(out.contains("last pack caller: Render thread"));
        assertTrue(out.contains("last pack worker: Baritone Vanilla Elytra Pathfinder"));
        assertFalse(out.contains("last pack caller: last pack worker"));
        assertTrue(out.contains("last A* explored: 1823"));
        assertTrue(out.contains("last A* reached: true"));
        assertTrue(out.contains("motion finite-rejects: 0"));
    }

    @Test
    public void formatNetherHidesVanillaPackAndSearch() {
        final ElytraDebug.Snapshot snap = new ElytraDebug.Snapshot(
                true,
                "ElytraProcess",
                true,
                "Flying",
                "the_nether",
                ElytraDebug.BACKEND_NETHER,
                "NetherPathfinderContext",
                ElytraDebug.SOLVER_NETHER,
                null, null, null, null, null, null, null, null,
                3L
        );
        final String out = ElytraDebug.format(snap);
        assertTrue(out.contains("backend: nether JNI"));
        assertTrue(out.contains("solver terrain: nether JNI"));
        assertTrue(out.contains("pack submitted: n/a"));
        assertTrue(out.contains("last A* explored: n/a"));
        assertTrue(out.contains("motion finite-rejects: 3"));
    }

    @Test
    public void formatNotYetUntilASolveRuns() {
        final ElytraDebug.Snapshot snap = new ElytraDebug.Snapshot(
                true,
                "ElytraProcess",
                true,
                "Begin flying",
                "overworld",
                ElytraDebug.BACKEND_VANILLA,
                "VanillaElytraContext",
                ElytraDebug.solverTerrain(null),
                null, null, null, null, null, null, null, null, 0L
        );
        final String out = ElytraDebug.format(snap);
        assertTrue(out.contains("solver terrain: not yet"));
        assertFalse(out.contains("solver terrain: frozen occupancy"));
    }

    @Test
    public void formatIdleAndNullStayNa() {
        final ElytraDebug.Snapshot idle = ElytraDebug.idle(
                true, "ElytraProcess", null, ElytraDebug.BACKEND_VANILLA, "none");
        final String idleOut = ElytraDebug.format(idle);
        assertTrue(idleOut.contains("active: no"));
        assertTrue(idleOut.contains("occupancy columns: n/a"));
        assertTrue(idleOut.contains("last A* explored: n/a"));

        final ElytraDebug.Snapshot missing = ElytraDebug.idle(
                false, "NullElytraProcess", null, ElytraDebug.BACKEND_NONE, "none");
        final String missingOut = ElytraDebug.format(missing);
        assertTrue(missingOut.contains("native lib: no"));
        assertTrue(missingOut.contains("process: NullElytraProcess"));
        assertTrue(missingOut.contains("backend: none"));
    }

    @Test
    public void elytraHelpKeepsOldUsageAndAddsDebug() {
        final List<String> lines = ElytraCommand.longDescLines();
        assertTrue(lines.contains("> elytra - fly to the current goal"));
        assertTrue(lines.contains("> elytra reset - Resets the state of the process, but will try to keep flying to the same goal."));
        assertTrue(lines.contains("> elytra repack - Queues all of the chunks in render distance to be given to the planner."));
        assertTrue(lines.contains("> elytra supported - Tells you if the native nether-pathfinder library loaded (used for Nether terrain prediction)."));
        final String debug = lines.get(lines.size() - 1);
        assertTrue(debug.contains("> elytra debug"));
        assertTrue(debug.contains("Does not start or change flight"));
        assertFalse(debug.toLowerCase(Locale.US).contains("alias"));
        assertFalse(String.join("\n", lines).contains("Alias: debug"));
    }

    @Test
    public void defaultCommandsDoesNotRegisterTopLevelDebug() throws Exception {
        final String defaults = Files.readString(Path.of("src/main/java/baritone/command/defaults/DefaultCommands.java"));
        assertTrue(defaults.contains("DefaultCommands.createAll") || defaults.contains("new ElytraCommand(baritone)"));
        assertTrue(defaults.contains("new ElytraCommand(baritone)"));
        assertFalse(defaults.contains("new CommandAlias(baritone, \"debug\""));
        assertFalse(defaults.contains("DEBUG_ALIAS"));
        assertFalse(defaults.contains("\"debug\""));
        final String manager = Files.readString(Path.of("src/main/java/baritone/command/manager/CommandManager.java"));
        assertTrue(manager.contains("DefaultCommands.createAll(baritone)"));
        final String usage = Files.readString(Path.of("USAGE.md"));
        assertTrue(usage.contains("elytra debug"));
        assertFalse(usage.contains("alias `debug`"));
        assertFalse(usage.contains("alias debug"));
    }
}
