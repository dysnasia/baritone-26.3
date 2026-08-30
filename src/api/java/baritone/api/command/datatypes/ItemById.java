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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public enum ItemById implements IDatatypeFor<Item> {
    INSTANCE;

    @Override
    public Item get(IDatatypeContext ctx) throws CommandException {
        Identifier id = Identifier.parse(ctx.getConsumer().getString());
        Item item;
        if ((item = BuiltInRegistries.ITEM.getOptional(id).orElse(null)) == null) {
            throw new IllegalArgumentException("No item found by that id");
        }
        return item;
    }

    /**
     * Every item id as a string. The registry is frozen once mods have loaded, so this is built once rather than on
     * every keystroke -- in a large modpack that is tens of thousands of strings per character typed.
     */
    private static volatile List<String> itemIds;

    /** How many suggestions to offer, matching {@link BlockById} */
    private static final int MAX_SUGGESTIONS = 64;

    @Override
    public Stream<String> tabComplete(IDatatypeContext ctx) throws CommandException {
        return new TabCompleteHelper()
                .append(itemIds().stream())
                .filterPrefixNamespacedOrPath(ctx.getConsumer().getString())
                // By the name the player is typing, so that a mod's item and the vanilla item sit next to each other
                // instead of the whole of "minecraft:" coming first and filling the list on its own.
                .sort(Comparator.comparing((String id) -> id.substring(id.indexOf(':') + 1), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(id -> !id.startsWith("minecraft:"))
                        .thenComparing(String.CASE_INSENSITIVE_ORDER))
                .stream()
                .limit(MAX_SUGGESTIONS);
    }

    private static List<String> itemIds() {
        List<String> ids = itemIds;
        if (ids == null) {
            ids = BuiltInRegistries.ITEM.keySet()
                    .stream()
                    .map(Identifier::toString)
                    .toList();
            itemIds = ids;
        }
        return ids;
    }
}
