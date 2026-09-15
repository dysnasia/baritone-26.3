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
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.utils.BaritoneMath;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ElytraMotionTest {

    private static Vec3 look(final float pitch) {
        return RotationUtils.calcLookDirectionFromRotation(new Rotation(0.0f, pitch));
    }

    private static void assertStep(final String id, final Vec3 motion, final Vec3 lookDir, final float pitch,
                                   final double x, final double y, final double z) {
        final Vec3 boxed = ElytraMotion.step(motion, lookDir, pitch);
        final double[] prim = {motion.x, motion.y, motion.z};
        ElytraMotion.step(prim, lookDir.x, lookDir.y, lookDir.z, pitch);
        assertEquals(id + " boxed.x", x, boxed.x, 0.0);
        assertEquals(id + " boxed.y", y, boxed.y, 0.0);
        assertEquals(id + " boxed.z", z, boxed.z, 0.0);
        assertEquals(id + " prim.x", boxed.x, prim[0], 0.0);
        assertEquals(id + " prim.y", boxed.y, prim[1], 0.0);
        assertEquals(id + " prim.z", boxed.z, prim[2], 0.0);
    }

    private static Vec3 loopStep(Vec3 motion, final Vec3 lookDir, final float pitch, final int ticks) {
        for (int i = 0; i < ticks; i++) {
            motion = ElytraMotion.step(motion, lookDir, pitch);
        }
        return motion;
    }

    private static Vec3 loopBoost(Vec3 motion, final Vec3 lookDir, final float pitch, final int ticks, final int delay) {
        final double[] m = {motion.x, motion.y, motion.z};
        int remaining = 10;
        for (int i = 0; i < ticks; i++) {
            ElytraMotion.step(m, lookDir.x, lookDir.y, lookDir.z, pitch);
            if (i >= delay && remaining-- > 0) {
                ElytraMotion.applyFireworkBoost(m, lookDir.x, lookDir.y, lookDir.z);
            }
        }
        return new Vec3(m[0], m[1], m[2]);
    }

    @Test
    public void stepGoldensFromPreviousVec3Kernel() {
        final Vec3 zLook = new Vec3(0, 0, 1);
        assertStep("rest-level", Vec3.ZERO, zLook, 0,
                0.0, -0.017640000343322755, 0.0017820000171661380);
        assertStep("cruise", new Vec3(0, 0, 1), zLook, 0,
                0.0, -0.017640000343322755, 0.99178200955390930);
        assertStep("look-up-45", new Vec3(0, 0, 1), look(-45), -45,
                -9.3105054253487840e-18, 0.042143650382458000, 0.96702605322165040);
        assertStep("look-up-1", new Vec3(0, 0, 1), look(-1), -1,
                -1.2266214895431531e-17, -0.015468197412307774, 0.99116125411515610);
        assertStep("look-up-90", new Vec3(0, 0, 1), look(-90), -90,
                -7.7593624841511180e-18, 0.047040000915527350, 0.95436000919342030);
        assertStep("look-down-1", new Vec3(0, -0.2, 1), look(1), 1,
                -1.4524025222811343e-17, -0.19406268054198555, 1.0095976688407233);
        assertStep("look-down-25", new Vec3(0, -0.2, 1), look(25), 25,
                -1.4191866945821460e-17, -0.20752954242912777, 1.0068853931878914);
        assertStep("look-down-45", new Vec3(0, -0.2, 1), look(45), 45,
                -1.3487954226500806e-17, -0.23275000692486770, 1.0011375090598464);
        assertStep("look-down-89", new Vec3(0, -0.2, 1), look(89), 89,
                -1.2124943983138361e-17, -0.27437346190396295, 0.99000768782837540);
        assertStep("desperate-90", new Vec3(0, 0, 1), look(90), 90,
                0.0, -0.078400001525878900, 0.99000000953674320);
        assertStep("falling", new Vec3(0, -0.5, 0.4), zLook, 0,
                0.0, -0.45864000892639160, 0.44233200426101690);
        assertStep("short-look", new Vec3(0, 0, 1), new Vec3(0, 0, 0.2), 0,
                0.0, -0.046550000905990600, 0.99222750955820070);
    }

    @Test
    public void straightUpLookKeepsExistingNaNOnXz() {
        final Vec3 boxed = ElytraMotion.step(new Vec3(0, 0.2, 0), new Vec3(0, 1, 0), -90);
        assertTrue(Double.isNaN(boxed.x));
        assertEquals(0.11760000228881837, boxed.y, 0.0);
        assertTrue(Double.isNaN(boxed.z));
        final double[] prim = {0, 0.2, 0};
        ElytraMotion.step(prim, 0, 1, 0, -90);
        assertTrue(Double.isNaN(prim[0]));
        assertEquals(boxed.y, prim[1], 0.0);
        assertTrue(Double.isNaN(prim[2]));
        assertFalse(ElytraMotion.isFinite(prim));
    }

    @Test
    public void multiTickAndBoostGoldens() {
        final Vec3 zLook = new Vec3(0, 0, 1);
        final Vec3 t20 = loopStep(new Vec3(0, 0, 1), zLook, 0, 20);
        assertEquals(0.0, t20.x, 0.0);
        assertEquals(-0.13735798240418426, t20.y, 0.0);
        assertEquals(1.0020358210382043, t20.z, 0.0);

        final Vec3 t3 = loopStep(new Vec3(0, 0, 1), zLook, 0, 3);
        assertEquals(0.0, t3.x, 0.0);
        assertEquals(-0.046921061110181150, t3.y, 0.0);
        assertEquals(0.98010573784382270, t3.z, 0.0);

        final Vec3 off = loopStep(new Vec3(0, 0, 1), zLook, 0, 5);
        assertEquals(0.0, off.x, 0.0);
        assertEquals(-0.069699501912840450, off.y, 0.0);
        assertEquals(0.97354576422382070, off.z, 0.0);

        final Vec3 delay0 = loopBoost(new Vec3(0, 0, 1), zLook, 0, 5, 0);
        assertEquals(0.0, delay0.x, 0.0);
        assertEquals(-0.015514997741359915, delay0.y, 0.0);
        assertEquals(1.6657583857225167, delay0.z, 0.0);

        final Vec3 delay3 = loopBoost(new Vec3(0, 0, 1), zLook, 0, 6, 3);
        assertEquals(0.0, delay3.x, 0.0);
        assertEquals(-0.018449180482813610, delay3.y, 0.0);
        assertEquals(1.6015818137775357, delay3.z, 0.0);
    }

    @Test
    public void inflateMatchesMinecraftAabbThenFastFloorCeil() {
        final AABB box = new AABB(-0.3, 64.0, -0.3, 0.3, 65.8, 0.3);
        final double[][] motions = {
                {0.4, 0.1, 0.2},
                {-0.4, -0.2, 0.0},
                {0.0, 0.0, 0.0},
                {0.01, -0.08, 1.2},
        };
        final double[] out = new double[6];
        for (final double[] m : motions) {
            final AABB inflated = box.inflate(m[0], m[1], m[2]).inflate(ElytraMotion.COLLISION_PAD);
            ElytraMotion.inflateForCollision(
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                    m[0], m[1], m[2], ElytraMotion.COLLISION_PAD, out);
            assertEquals(inflated.minX, out[0], 0.0);
            assertEquals(inflated.minY, out[1], 0.0);
            assertEquals(inflated.minZ, out[2], 0.0);
            assertEquals(inflated.maxX, out[3], 0.0);
            assertEquals(inflated.maxY, out[4], 0.0);
            assertEquals(inflated.maxZ, out[5], 0.0);
            assertEquals(BaritoneMath.fastFloor(inflated.minX), BaritoneMath.fastFloor(out[0]));
            assertEquals(BaritoneMath.fastCeil(inflated.maxX), BaritoneMath.fastCeil(out[3]));
            assertEquals(BaritoneMath.fastFloor(inflated.minY), BaritoneMath.fastFloor(out[1]));
            assertEquals(BaritoneMath.fastCeil(inflated.maxY), BaritoneMath.fastCeil(out[4]));
            assertEquals(BaritoneMath.fastFloor(inflated.minZ), BaritoneMath.fastFloor(out[2]));
            assertEquals(BaritoneMath.fastCeil(inflated.maxZ), BaritoneMath.fastCeil(out[5]));
        }
    }

    @Test
    public void netherPathGetVecCachesBlockCorner() {
        final NetherPath path = new NetherPath(List.of(
                new BetterBlockPos(1, 2, 3),
                new BetterBlockPos(-4, 64, 8)
        ));
        final Vec3 first = path.getVec(0);
        assertEquals(1.0, first.x, 0.0);
        assertEquals(2.0, first.y, 0.0);
        assertEquals(3.0, first.z, 0.0);
        assertSame(first, path.getVec(0));
        final Vec3 second = path.getVec(1);
        assertEquals(-4.0, second.x, 0.0);
        assertEquals(64.0, second.y, 0.0);
        assertEquals(8.0, second.z, 0.0);
        assertSame(second, path.getVec(1));
    }
}
