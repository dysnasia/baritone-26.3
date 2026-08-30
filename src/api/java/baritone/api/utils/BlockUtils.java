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

package baritone.api.utils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BlockUtils {

    private static transient Map<String, Block> resourceCache = new HashMap<>();

    public static String blockToString(Block block) {
        Identifier loc = BuiltInRegistries.BLOCK.getKey(block);
        String name = loc.getPath(); // normally, only write the part after the minecraft:
        if (!loc.getNamespace().equals("minecraft")) {
            // Baritone is running on top of forge with mods installed, perhaps?
            name = loc.toString(); // include the namespace with the colon
        }
        return name;
    }

    public static Block stringToBlockRequired(String name) {
        Block block = stringToBlockNullable(name);

        if (block == null) {
            List<Block> candidates = name.contains(":") ? Collections.emptyList() : blocksByPath(name);
            if (candidates.size() > 1) {
                throw new IllegalArgumentException(String.format(
                        "Ambiguous block name %s, %d mods provide it: %s",
                        name,
                        candidates.size(),
                        candidates.stream().map(BlockUtils::blockToString).collect(Collectors.joining(", "))
                ));
            }
            throw new IllegalArgumentException(String.format("Invalid block name %s", name));
        }

        return block;
    }

    public static Block stringToBlockNullable(String name) {
        // do NOT just replace this with a computeWithAbsent, it isn't thread safe
        Block block = resourceCache.get(name); // map is never mutated in place so this is safe
        if (block != null) {
            return block;
        }
        if (resourceCache.containsKey(name)) {
            return null; // cached as null
        }
        block = BuiltInRegistries.BLOCK.getOptional(Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name)).orElse(null);
        if (block == null && !name.contains(":")) {
            // In a modpack players type the block name they see in-game, not the mod id that happens to own it.
            // If the bare name isn't vanilla, accept it when exactly one mod claims that path. Ambiguity stays an
            // error rather than a coin flip -- stringToBlockRequired turns it into a message listing the candidates.
            List<Block> candidates = blocksByPath(name);
            if (candidates.size() == 1) {
                block = candidates.get(0);
            }
        }
        Map<String, Block> copy = new HashMap<>(resourceCache); // read only copy is safe, wont throw concurrentmodification
        copy.put(name, block);
        resourceCache = copy;
        return block;
    }

    /**
     * @param path The path of a block id, i.e. everything after the {@code modid:}
     * @return Every registered block whose id has that path, in any namespace
     */
    public static List<Block> blocksByPath(String path) {
        List<Block> found = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            if (id.getPath().equals(path)) {
                BuiltInRegistries.BLOCK.getOptional(id).ifPresent(found::add);
            }
        }
        return found;
    }

    private BlockUtils() {}
}
