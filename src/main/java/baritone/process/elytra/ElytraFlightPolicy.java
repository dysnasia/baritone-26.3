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

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Frozen player-facing elytra contracts. Internals (occupancy packing, DDA) may change;
 * these predicates must not, unless the user explicitly asks.
 * <p>
 * {@link baritone.process.ElytraProcess#create} still gates on
 * {@link NetherPathfinderContext#isSupported()} in every dimension. That control flow
 * does not live here.
 */
public final class ElytraFlightPolicy {

    private ElytraFlightPolicy() {}

    /**
     * Client 26.2 {@code Entity.fallDistance} is a public double. Takeoff is strictly
     * greater than this value ({@code >}, never {@code >=}).
     */
    public static final double TAKEOFF_FALL_DISTANCE = 1.0;

    /**
     * Auto-jump walking goal Y. Original nether leftover; still live.
     */
    public static final int AUTO_JUMP_GOAL_Y = 31;

    /**
     * {@code GoalXZ} cruise altitude in the Nether. Overworld/End do not use this:
     * Y=64 there is usually stone or water, so the planner could never confirm arrival.
     */
    public static final int NETHER_GOAL_XZ_Y = 64;

    /**
     * Inclusive floor for the landing-column scan. Original nether bedrock band.
     * Overworld minY is -64; this is current behavior, not a world-height fix.
     */
    public static final int LANDING_SCAN_FLOOR_Y = 0;

    public static boolean isSupportedDimension(final ResourceKey<Level> dimension) {
        return isMinecraftDim(dimension, "overworld")
                || isMinecraftDim(dimension, "the_nether")
                || isMinecraftDim(dimension, "the_end");
    }

    public static boolean usesNetherTerrainPrediction(final ResourceKey<Level> dimension) {
        return isMinecraftDim(dimension, "the_nether");
    }

    private static boolean isMinecraftDim(final ResourceKey<Level> dimension, final String path) {
        if (dimension == null) {
            return false;
        }
        final Identifier id = dimension.identifier();
        return "minecraft".equals(id.getNamespace()) && path.equals(id.getPath());
    }

    public static boolean canDeployElytra(final double fallDistance) {
        return fallDistance > TAKEOFF_FALL_DISTANCE;
    }

    public static boolean isLandingScanY(final int y) {
        return y >= LANDING_SCAN_FLOOR_Y;
    }

    /**
     * @param exclusiveMaxY world top exclusive ({@code minY + height()}), never {@link net.minecraft.world.level.Level#getMaxY()}
     */
    public static int goalXZCruiseY(
            final ResourceKey<Level> dimension,
            final int exclusiveMaxY,
            final int seaLevel,
            final int playerY) {
        if (usesNetherTerrainPrediction(dimension)) {
            return NETHER_GOAL_XZ_Y;
        }
        return Math.min(exclusiveMaxY - 8, Math.max(seaLevel + 80, playerY));
    }
}
