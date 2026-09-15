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

import baritone.api.utils.RotationUtils;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra travel-step kernel used by the flight solver. Formulas match the previous
 * {@code Vec3} implementation, including float drag and the old {@code -0.08} gravity.
 */
final class ElytraMotion {

    private ElytraMotion() {}

    static final double COLLISION_PAD = 0.01;

    /**
     * In-place travel step. {@code motion[0..2]} is x,y,z. {@code pitch} is degrees.
     */
    static void step(final double[] motion, final double lookX, final double lookY, final double lookZ, final float pitch) {
        double motionX = motion[0];
        double motionY = motion[1];
        double motionZ = motion[2];

        float pitchRadians = pitch * RotationUtils.DEG_TO_RAD_F;
        double pitchBase2 = Math.sqrt(lookX * lookX + lookZ * lookZ);
        double flatMotion = Math.sqrt(motionX * motionX + motionZ * motionZ);
        double thisIsAlwaysOne = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        float pitchBase3 = Mth.cos(pitchRadians);
        pitchBase3 = (float) ((double) pitchBase3 * (double) pitchBase3 * Math.min(1, thisIsAlwaysOne / 0.4));
        motionY += -0.08 + (double) pitchBase3 * 0.06;
        if (motionY < 0 && pitchBase2 > 0) {
            double speedModifier = motionY * -0.1 * (double) pitchBase3;
            motionY += speedModifier;
            motionX += lookX * speedModifier / pitchBase2;
            motionZ += lookZ * speedModifier / pitchBase2;
        }
        if (pitchRadians < 0) { // if you are looking down (below level)
            double anotherSpeedModifier = flatMotion * (double) (-Mth.sin(pitchRadians)) * 0.04;
            motionY += anotherSpeedModifier * 3.2;
            motionX -= lookX * anotherSpeedModifier / pitchBase2;
            motionZ -= lookZ * anotherSpeedModifier / pitchBase2;
        }
        if (pitchBase2 > 0) { // this is always true unless you are looking literally straight up (let's just say the bot will never do that)
            motionX += (lookX / pitchBase2 * flatMotion - motionX) * 0.1;
            motionZ += (lookZ / pitchBase2 * flatMotion - motionZ) * 0.1;
        }
        motionX *= 0.99f;
        motionY *= 0.98f;
        motionZ *= 0.99f;

        motion[0] = motionX;
        motion[1] = motionY;
        motion[2] = motionZ;
    }

    static boolean isFinite(final double[] motion) {
        return Double.isFinite(motion[0]) && Double.isFinite(motion[1]) && Double.isFinite(motion[2]);
    }

    static Vec3 step(final Vec3 motion, final Vec3 lookDirection, final float pitch) {
        final double[] out = {motion.x, motion.y, motion.z};
        step(out, lookDirection.x, lookDirection.y, lookDirection.z, pitch);
        return new Vec3(out[0], out[1], out[2]);
    }

    /**
     * Firework-rocket acceleration, same expression order as {@code EntityFireworkRocket}.
     */
    static void applyFireworkBoost(final double[] motion, final double lookX, final double lookY, final double lookZ) {
        motion[0] += lookX * 0.1 + (lookX * 1.5 - motion[0]) * 0.5;
        motion[1] += lookY * 0.1 + (lookY * 1.5 - motion[1]) * 0.5;
        motion[2] += lookZ * 0.1 + (lookZ * 1.5 - motion[2]) * 0.5;
    }

    /**
     * {@code AABB.inflate(mx, my, mz).inflate(pad)} on Minecraft 26.2. Inflate is min -= v,
     * max += v; the AABB constructor then sorts min/max, then the pad inflate runs on the
     * sorted box. Combining {@code mx+pad} before the sort does not match when motion inverts an axis.
     * {@code out} is minX, minY, minZ, maxX, maxY, maxZ.
     */
    static void inflateForCollision(
            final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ,
            final double mx, final double my, final double mz,
            final double pad,
            final double[] out) {
        inflateAxis(minX, maxX, mx, pad, out, 0, 3);
        inflateAxis(minY, maxY, my, pad, out, 1, 4);
        inflateAxis(minZ, maxZ, mz, pad, out, 2, 5);
    }

    private static void inflateAxis(final double min, final double max, final double motion, final double pad,
                                    final double[] out, final int minIndex, final int maxIndex) {
        final double a = min - motion;
        final double b = max + motion;
        out[minIndex] = Math.min(a, b) - pad;
        out[maxIndex] = Math.max(a, b) + pad;
    }
}
