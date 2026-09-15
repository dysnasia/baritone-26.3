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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VanillaElytraSearchTest {

    @Test
    public void abortCapsUnchanged() {
        assertEquals(20_000, VanillaElytraContext.MAX_NODES_EXPLORED);
        assertEquals(30_000_000L, VanillaElytraContext.MAX_SEARCH_NANOS);
        assertEquals(1.5, VanillaElytraContext.HEURISTIC_WEIGHT, 0.0);
    }

    @Test
    public void fScoreIsGPlusHeuristicTimesEuclidean() {
        final int[][] samples = {
                {0, 0, 0, 0, 0, 0, 0},
                {0, 8, 0, 0, 0, 0, 0},
                {0, 0, 8, 0, 0, 0, 0},
                {0, 0, 0, 8, 0, 0, 0},
                {0, 8, 8, 8, 0, 0, 0},
                {10, 16, -8, 24, 0, 64, 0},
                {3, -4, 5, -6, 10, 20, 30},
        };
        for (final int[] s : samples) {
            final double g = s[0];
            final double expected = g + VanillaElytraContext.HEURISTIC_WEIGHT * Math.sqrt(
                    (double) (s[1] - s[4]) * (s[1] - s[4])
                            + (double) (s[2] - s[5]) * (s[2] - s[5])
                            + (double) (s[3] - s[6]) * (s[3] - s[6]));
            assertEquals(expected, VanillaElytraContext.fScore(g, s[1], s[2], s[3], s[4], s[5], s[6]), 0.0);
        }
    }

    @Test
    public void packedFHeapPollsMinFFirst() {
        final VanillaElytraContext.PackedFHeap heap = new VanillaElytraContext.PackedFHeap();
        heap.add(3L, 3.0);
        heap.add(1L, 1.0);
        heap.add(4L, 4.0);
        heap.add(2L, 2.0);
        assertEquals(1L, heap.poll());
        assertEquals(2L, heap.poll());
        assertEquals(3L, heap.poll());
        assertEquals(4L, heap.poll());
        assertTrue(heap.isEmpty());
    }

    @Test
    public void packedFHeapKeepsDuplicatePackedEntries() {
        final VanillaElytraContext.PackedFHeap heap = new VanillaElytraContext.PackedFHeap();
        final long packed = VanillaElytraOccupancy.packPos(8, 70, 8);
        heap.add(packed, 5.0);
        heap.add(packed, 1.0);
        heap.add(99L, 3.0);
        assertEquals(packed, heap.poll());
        assertEquals(99L, heap.poll());
        assertEquals(packed, heap.poll());
        assertTrue(heap.isEmpty());
    }
}
