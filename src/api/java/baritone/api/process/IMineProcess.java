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

package baritone.api.process;

import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import java.util.stream.Stream;
import net.minecraft.world.level.block.Block;

/**
 * @author Brady
 * @since 9/23/2018
 */
public interface IMineProcess extends IBaritoneProcess {

    /**
     * Begins searching for and mining the specified blocks, stopping once the given number of matching blocks has
     * been broken. What those blocks drop does not affect the count.
     *
     * @param quantity The number of matching blocks to break, or zero or less for no limit
     * @param blocks   The blocks to mine
     */
    void mineByName(int quantity, String... blocks);

    /**
     * Begins searching for and mining the blocks matched by the given filter, stopping once the given number of
     * matching blocks has been broken. What those blocks drop does not affect the count.
     *
     * @param quantity The number of matching blocks to break, or zero or less for no limit
     * @param filter   The blocks to mine, or {@code null} to stop mining
     */
    void mine(int quantity, BlockOptionalMetaLookup filter);

    /**
     * Begins searching for and mining the specified blocks. Mining continues until cancelled, with no block limit.
     *
     * @param filter The blocks to mine
     */
    default void mine(BlockOptionalMetaLookup filter) {
        mine(0, filter);
    }

    /**
     * Begins searching for and mining the specified blocks. Mining continues until cancelled, with no block limit.
     *
     * @param blocks The blocks to mine
     */
    default void mineByName(String... blocks) {
        mineByName(0, blocks);
    }

    /**
     * Begins searching for and mining the specified blocks, stopping once the given number of matching blocks has
     * been broken.
     *
     * @param quantity The number of matching blocks to break, or zero or less for no limit
     * @param boms     The blocks to mine
     */
    default void mine(int quantity, BlockOptionalMeta... boms) {
        mine(quantity, new BlockOptionalMetaLookup(boms));
    }

    /**
     * Begins searching for and mining the specified blocks. Mining continues until cancelled, with no block limit.
     *
     * @param boms The blocks to mine
     */
    default void mine(BlockOptionalMeta... boms) {
        mine(0, boms);
    }

    /**
     * Begins searching for and mining the specified blocks, stopping once the given number of matching blocks has
     * been broken.
     *
     * @param quantity The number of matching blocks to break, or zero or less for no limit
     * @param blocks   The blocks to mine
     */
    default void mine(int quantity, Block... blocks) {
        mine(quantity, new BlockOptionalMetaLookup(
                Stream.of(blocks)
                        .map(BlockOptionalMeta::new)
                        .toArray(BlockOptionalMeta[]::new)
        ));
    }

    /**
     * Begins searching for and mining the specified blocks. Mining continues until cancelled, with no block limit.
     *
     * @param blocks The blocks to mine
     */
    default void mine(Block... blocks) {
        mine(0, blocks);
    }

    /**
     * Cancels the current mining task
     */
    default void cancel() {
        onLostControl();
    }
}
