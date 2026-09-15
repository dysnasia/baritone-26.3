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
import net.minecraft.world.phys.Vec3;

import java.util.AbstractList;
import java.util.Collections;
import java.util.List;

/**
 * @author Brady
 */
public final class NetherPath extends AbstractList<BetterBlockPos> {

    private static final NetherPath EMPTY_PATH = new NetherPath(Collections.emptyList());

    private final List<BetterBlockPos> backing;
    private Vec3[] vecCache;

    NetherPath(List<BetterBlockPos> backing) {
        this.backing = backing;
    }

    @Override
    public BetterBlockPos get(int index) {
        return this.backing.get(index);
    }

    @Override
    public int size() {
        return this.backing.size();
    }

    /**
     * @return The last position in the path, or {@code null} if empty
     */
    public BetterBlockPos getLast() {
        return this.isEmpty() ? null : this.backing.get(this.backing.size() - 1);
    }

    public Vec3 getVec(int index) {
        Vec3[] cache = this.vecCache;
        if (cache == null) {
            cache = new Vec3[this.backing.size()];
            this.vecCache = cache;
        }
        Vec3 vec = cache[index];
        if (vec == null) {
            final BetterBlockPos pos = this.get(index);
            vec = new Vec3(pos.x, pos.y, pos.z);
            cache[index] = vec;
        }
        return vec;
    }

    public static NetherPath emptyPath() {
        return EMPTY_PATH;
    }
}
