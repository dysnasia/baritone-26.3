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

package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.utils.BlockUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public enum BlockById implements IDatatypeFor<Block> {
    INSTANCE;

    @Override
    public Block get(IDatatypeContext ctx) throws CommandException {
        // BlockUtils resolves an unqualified name against modded namespaces too, so that a modpack block can be
        // named the same way a vanilla one is.
        return BlockUtils.stringToBlockRequired(ctx.getConsumer().getString());
    }

    /**
     * Every block id as a string. The registry is frozen once mods have loaded, so this is built once rather than on
     * every keystroke -- in a large modpack that is twenty thousand strings per character typed.
     */
    private static volatile List<String> blockIds;

    /**
     * How many suggestions to offer. The completion popup shows ten, and sorting every block in a modpack to display
     * ten of them is work nobody sees.
     */
    private static final int MAX_SUGGESTIONS = 64;

    @Override
    public Stream<String> tabComplete(IDatatypeContext ctx) throws CommandException {
        String arg = ctx.getConsumer().getString();

        return new TabCompleteHelper()
                .append(blockIds().stream())
                .filterPrefixNamespacedOrPath(arg)
                // Order by the name the player is actually typing, so that a mod's block and the vanilla block sit
                // next to each other instead of the whole of "minecraft:" coming first -- with a capped list, sorting
                // vanilla first would push every modded block past the cap and out of sight. Vanilla wins only
                // between two blocks with the same name.
                .sort(Comparator.comparing((String id) -> id.substring(id.indexOf(':') + 1), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(id -> !id.startsWith("minecraft:"))
                        .thenComparing(String.CASE_INSENSITIVE_ORDER))
                .stream()
                .limit(MAX_SUGGESTIONS);
    }

    private static List<String> blockIds() {
        List<String> ids = blockIds;
        if (ids == null) {
            ids = BuiltInRegistries.BLOCK.keySet()
                    .stream()
                    .map(Object::toString)
                    .toList();
            blockIds = ids;
        }
        return ids;
    }
}
