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

package baritone.cache;

import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FasterWorldScannerTest {

    @Test
    public void takeClosestKeepsNearbyVeinWhenFarHitsFillTheCap() {
        BlockPos origin = new BlockPos(0, 64, 0);
        List<BlockPos> found = new ArrayList<>();
        // 80 far hits first, same encounter-order trap as palette-index scan filling the 64 cap.
        for (int i = 0; i < 80; i++) {
            found.add(new BlockPos(200 + i, 64, 200));
        }
        List<BlockPos> vein = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            BlockPos ore = new BlockPos(i, 64, 1);
            vein.add(ore);
            found.add(ore);
        }

        List<BlockPos> kept = FasterWorldScanner.takeClosest(found, origin, 64);

        assertEquals(64, kept.size());
        for (BlockPos ore : vein) {
            assertTrue("nearby vein block " + ore + " dropped by scan cap", kept.contains(ore));
        }
    }

    @Test
    public void takeClosestMaxZeroIsEmpty() {
        List<BlockPos> found = new ArrayList<>();
        found.add(new BlockPos(1, 64, 1));
        assertEquals(0, FasterWorldScanner.takeClosest(found, BlockPos.ZERO, 0).size());
    }

    @Test
    public void takeClosestNegativeMaxKeepsAll() {
        List<BlockPos> found = new ArrayList<>();
        found.add(new BlockPos(1, 64, 1));
        found.add(new BlockPos(2, 64, 2));
        assertEquals(found, FasterWorldScanner.takeClosest(found, BlockPos.ZERO, -1));
    }
}
