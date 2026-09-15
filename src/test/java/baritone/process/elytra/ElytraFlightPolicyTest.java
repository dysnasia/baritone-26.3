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

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ElytraFlightPolicyTest {

    private static ResourceKey<Level> dim(final String path) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:" + path));
    }

    private static ResourceKey<Level> foreign(final String path) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse("mymod:" + path));
    }

    @Test
    public void resourceKeysIntern() {
        assertSame(dim("overworld"), dim("overworld"));
    }

    @Test
    public void overworldNetherEndSupported() {
        assertTrue(ElytraFlightPolicy.isSupportedDimension(dim("overworld")));
        assertTrue(ElytraFlightPolicy.isSupportedDimension(dim("the_nether")));
        assertTrue(ElytraFlightPolicy.isSupportedDimension(dim("the_end")));
        assertFalse(ElytraFlightPolicy.isSupportedDimension(dim("foo")));
        assertFalse(ElytraFlightPolicy.isSupportedDimension(foreign("the_nether")));
    }

    @Test
    public void netherOnlyGetsTerrainPrediction() {
        assertTrue(ElytraFlightPolicy.usesNetherTerrainPrediction(dim("the_nether")));
        assertFalse(ElytraFlightPolicy.usesNetherTerrainPrediction(dim("overworld")));
        assertFalse(ElytraFlightPolicy.usesNetherTerrainPrediction(dim("the_end")));
        assertFalse(ElytraFlightPolicy.usesNetherTerrainPrediction(dim("foo")));
        assertFalse(ElytraFlightPolicy.usesNetherTerrainPrediction(foreign("the_nether")));
    }

    @Test
    public void takeoffIsStrictlyGreaterThanOne() {
        assertFalse(ElytraFlightPolicy.canDeployElytra(1.0));
        assertFalse(ElytraFlightPolicy.canDeployElytra(0.0));
        assertTrue(ElytraFlightPolicy.canDeployElytra(Math.nextUp(1.0)));
        assertEquals(1.0, ElytraFlightPolicy.TAKEOFF_FALL_DISTANCE, 0.0);
    }

    @Test
    public void autoJumpGoalYUnchanged() {
        assertEquals(31, ElytraFlightPolicy.AUTO_JUMP_GOAL_Y);
    }

    @Test
    public void landingScanIncludesZeroNotWorldMinY() {
        assertTrue(ElytraFlightPolicy.isLandingScanY(0));
        assertTrue(ElytraFlightPolicy.isLandingScanY(64));
        assertFalse(ElytraFlightPolicy.isLandingScanY(-1));
        assertFalse(ElytraFlightPolicy.isLandingScanY(-64));
        assertEquals(0, ElytraFlightPolicy.LANDING_SCAN_FLOOR_Y);
    }

    @Test
    public void netherGoalXZAlways64() {
        assertEquals(64, ElytraFlightPolicy.goalXZCruiseY(dim("the_nether"), 128, 32, 40));
        assertEquals(64, ElytraFlightPolicy.goalXZCruiseY(dim("the_nether"), 320, 63, 200));
        assertEquals(64, ElytraFlightPolicy.NETHER_GOAL_XZ_Y);
    }

    @Test
    public void overworldGoalXZPrefersSeaPlus80() {
        assertEquals(143, ElytraFlightPolicy.goalXZCruiseY(dim("overworld"), 320, 63, 10));
    }

    @Test
    public void overworldGoalXZUsesPlayerIfHigher() {
        assertEquals(200, ElytraFlightPolicy.goalXZCruiseY(dim("overworld"), 320, 63, 200));
    }

    @Test
    public void overworldGoalXZClampedBelowWorldTop() {
        assertEquals(312, ElytraFlightPolicy.goalXZCruiseY(dim("overworld"), 320, 63, 400));
    }

    @Test
    public void endUsesSameCruiseAsOverworld() {
        assertEquals(
                ElytraFlightPolicy.goalXZCruiseY(dim("overworld"), 320, 63, 80),
                ElytraFlightPolicy.goalXZCruiseY(dim("the_end"), 320, 63, 80));
    }
}
