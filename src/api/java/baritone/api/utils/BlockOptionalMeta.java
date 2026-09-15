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

import baritone.api.utils.accessor.IItemStack;
import baritone.api.utils.accessor.ILootTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class BlockOptionalMeta {
    // id or id[] or id[properties] where id and properties are any text with at least one character
    private static final Pattern PATTERN = Pattern.compile("^(?<id>.+?)(?:\\[(?<properties>.+?)?\\])?$");

    private final Block block;
    private final String propertiesDescription; // exists so toString() can return something more useful than a list of all blockstates
    private final Set<BlockState> blockstates;
    private final ImmutableSet<Integer> stateHashes;
    private final ImmutableSet<Integer> stackHashes;
    private static Map<Block, List<Item>> drops = new HashMap<>();
    private static WeakReference<Object> dropsSource;
    /** Stands in for "not attached to anything", so that it can't be confused with a collected weak referent */
    private static final Object NO_DROPS_SOURCE = new Object();

    public BlockOptionalMeta(@Nonnull Block block) {
        this.block = block;
        this.propertiesDescription = "{}";
        this.blockstates = getStates(block, Collections.emptyMap());
        this.stateHashes = getStateHashes(blockstates);
        this.stackHashes = getStackHashes(blockstates);
    }

    public BlockOptionalMeta(@Nonnull String selector) {
        Matcher matcher = PATTERN.matcher(selector);

        if (!matcher.find()) {
            throw new IllegalArgumentException("invalid block selector");
        }

        block = BlockUtils.stringToBlockRequired(matcher.group("id"));

        String props = matcher.group("properties");
        Map<Property<?>, ?> properties = props == null || props.equals("") ? Collections.emptyMap() : parseProperties(block, props);

        propertiesDescription = props == null ? "{}" : "{" + props.replace("=", ":") + "}";
        blockstates = getStates(block, properties);
        stateHashes = getStateHashes(blockstates);
        stackHashes = getStackHashes(blockstates);
    }

    private static <C extends Comparable<C>, P extends Property<C>> P castToIProperty(Object value) {
        //noinspection unchecked
        return (P) value;
    }

    private static Map<Property<?>, ?> parseProperties(Block block, String raw) {
        ImmutableMap.Builder<Property<?>, Object> builder = ImmutableMap.builder();
        for (String pair : raw.split(",")) {
            String[] parts = pair.split("=");
            if (parts.length != 2) {
                throw new IllegalArgumentException(String.format("\"%s\" is not a valid property-value pair", pair));
            }
            String rawKey = parts[0];
            String rawValue = parts[1];
            Property<?> key = block.getStateDefinition().getProperty(rawKey);
            Comparable<?> value = castToIProperty(key).getValue(rawValue)
                    .orElseThrow(() -> new IllegalArgumentException(String.format(
                            "\"%s\" is not a valid value for %s on %s",
                            rawValue, key, block
                    )));
            builder.put(key, value);
        }
        return builder.build();
    }

    private static Set<BlockState> getStates(@Nonnull Block block, @Nonnull Map<Property<?>, ?> properties) {
        return block.getStateDefinition().getPossibleStates().stream()
                .filter(blockstate -> properties.entrySet().stream().allMatch(entry ->
                        blockstate.getValue(entry.getKey()) == entry.getValue()
                ))
                .collect(Collectors.toSet());
    }

    private static ImmutableSet<Integer> getStateHashes(Set<BlockState> blockstates) {
        return ImmutableSet.copyOf(
                blockstates.stream()
                        .map(BlockState::hashCode)
                        .toArray(Integer[]::new)
        );
    }

    private static ImmutableSet<Integer> getStackHashes(Set<BlockState> blockstates) {
        //noinspection ConstantConditions
        return ImmutableSet.copyOf(
                blockstates.stream()
                        .flatMap(state -> drops(state.getBlock())
                                .stream()
                                .map(item -> new ItemStack(item, 1))
                        )
                        .map(stack -> ((IItemStack) (Object) stack).getBaritoneHash())
                        .toArray(Integer[]::new)
        );
    }

    public Block getBlock() {
        return block;
    }

    public boolean matches(@Nonnull Block block) {
        return block == this.block;
    }

    public boolean matches(@Nonnull BlockState blockstate) {
        Block block = blockstate.getBlock();
        return block == this.block && stateHashes.contains(blockstate.hashCode());
    }

    public boolean matches(ItemStack stack) {
        //noinspection ConstantConditions
        int hash = ((IItemStack) (Object) stack).getBaritoneHash();

        hash -= stack.getDamageValue();

        return stackHashes.contains(hash);
    }

    @Override
    public String toString() {
        return String.format("BlockOptionalMeta{block=%s,properties=%s}", block, propertiesDescription);
    }

    public BlockState getAnyBlockState() {
        if (blockstates.size() > 0) {
            return blockstates.iterator().next();
        }

        return null;
    }

    public Set<BlockState> getAllBlockStates() {
        return blockstates;
    }

    public Set<Integer> stackHashes() {
        return stackHashes;
    }

    private static Method getVanillaServerPack;

    private static VanillaPackResources getVanillaServerPack() {
        if (getVanillaServerPack == null) {
            getVanillaServerPack = Arrays.stream(ServerPacksSource.class.getDeclaredMethods()).filter(field -> field.getReturnType() == VanillaPackResources.class).findFirst().orElseThrow();
            getVanillaServerPack.setAccessible(true);
        }

        try {
            return (VanillaPackResources) getVanillaServerPack.invoke(null);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    private static synchronized List<Item> drops(Block b) {
        // Which loot tables we can see depends on what we're attached to, so a cache built against one world is
        // worthless in the next. Joining a modpack world after computing drops in the main menu used to leave every
        // block permanently cached as dropping nothing. Key on the connection rather than the server, because two
        // different remote servers both have no integrated server and would otherwise share one cache. Hold the key
        // weakly so that a cache entry can't keep a whole finished server or connection alive.
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        // The integrated server builds a new holder every time datapacks are reloaded, so keying on it rather than on
        // the server itself means "/reload" invalidates the cache too.
        Object source = server != null ? server.reloadableRegistries() : Minecraft.getInstance().getConnection();
        if (source == null) {
            source = NO_DROPS_SOURCE;
        }
        if (dropsSource == null || dropsSource.get() != source) {
            drops = new HashMap<>();
            dropsSource = new WeakReference<>(source);
        }
        return drops.computeIfAbsent(b, block -> {
            Optional<ResourceKey<LootTable>> optionalLootTableKey = block.getLootTable();
            if (optionalLootTableKey.isEmpty()) {
                return Collections.emptyList();
            }
            List<Item> items = new ArrayList<>();
            try {
                ServerLevelStub level = ServerLevelStub.fastCreate();
                Optional<LootTable> registered = level.holder().lookup()
                        .lookup(Registries.LOOT_TABLE)
                        .flatMap(registry -> registry.get(optionalLootTableKey.get()))
                        .map(Holder::value);
                if (registered.isEmpty()) {
                    // The block names a loot table that isn't registered at all. Either we're on a remote server,
                    // where loot tables are server data and never reach the client, or a mod defines its drops in
                    // code and ships no table. Assume the block drops itself: right for most modded blocks, and far
                    // better than knowing nothing, which stops Baritone recognising and collecting what it just
                    // mined. A table that does exist and yields nothing is a real answer and is kept as-is.
                    Item self = block.asItem();
                    return self == Items.AIR ? Collections.emptyList() : Collections.singletonList(self);
                }
                LootTable table = registered.get();
                LootParams params = new LootParams.Builder(level)
                        .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                        .withParameter(LootContextParams.BLOCK_STATE, block.defaultBlockState())
                        .withParameter(LootContextParams.TOOL, new ItemStack(Items.NETHERITE_PICKAXE, 1))
                        .create(LootContextParamSets.BLOCK);
                ((ILootTable) table)
                        .invokeGetRandomItems(new LootContext.Builder(params).withOptionalRandomSeed(1).create(null))
                        .stream()
                        .map(ItemStack::getItem)
                        .forEach(items::add);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return items;
        });
    }

    public static class ServerLevelStub extends ServerLevel {
        private static Unsafe unsafe = getUnsafe();
        /**
         * Lazily synthesized vanilla-only registries, used only when there is no real server to ask. Building these
         * is both expensive and, on a modded client, capable of throwing -- so it must not happen eagerly in a static
         * initializer, or one failure would take the whole drop lookup down with it.
         */
        private static CompletableFuture<RegistryAccess> vanillaRegistryAccess;

        public ServerLevelStub(MinecraftServer $$0, Executor $$1, LevelStorageSource.LevelStorageAccess $$2, ServerLevelData $$3, ResourceKey<Level> $$4, LevelStem $$5, boolean $$6, long $$7, List<CustomSpawner> $$8, boolean $$9) {
            super($$0, $$1, $$2, $$3, $$4, $$5, $$6, $$7, $$8, $$9);
        }

        @Override
        public FeatureFlagSet enabledFeatures() {
            // Asserts are off at runtime, so a null level here used to be an NPE swallowed by the caller, which then
            // cached the block as dropping nothing.
            Level level = Minecraft.getInstance().level;
            return level == null ? FeatureFlags.DEFAULT_FLAGS : level.enabledFeatures();
        }

        public static ServerLevelStub fastCreate() {
            try {
                return (ServerLevelStub) unsafe.allocateInstance(ServerLevelStub.class);
            } catch (InstantiationException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public RegistryAccess registryAccess() {
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                return server.registryAccess();
            }
            return vanillaRegistryAccess();
        }

        /**
         * Loot tables are server data. In singleplayer and on a LAN world the integrated server is right here and has
         * already loaded every datapack the modpack ships, so ask it rather than synthesizing our own -- that's the
         * only way modded blocks resolve to their real drops. Falling back to vanilla-only registries is correct for a
         * remote server, where the client never receives the datapacks at all.
         */
        public ReloadableServerRegistries.Holder holder() {
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                return server.reloadableRegistries();
            }
            return new ReloadableServerRegistries.Holder(vanillaRegistryAccess().freeze());
        }

        private static synchronized RegistryAccess vanillaRegistryAccess() {
            if (vanillaRegistryAccess == null) {
                vanillaRegistryAccess = load();
            }
            return vanillaRegistryAccess.join();
        }

        public static Unsafe getUnsafe() {
            try {
                Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                return (Unsafe) theUnsafe.get(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static CompletableFuture<RegistryAccess> load() {
            // Simplified from {@link net.minecraft.server.WorldLoader#load()}
            CloseableResourceManager closeableResourceManager = new MultiPackResourceManager(
                PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources())
            );
            LayeredRegistryAccess<RegistryLayer> baseLayeredRegistry = RegistryLayer.createRegistryAccess();
            List<Registry.PendingTags<?>> pendingTags = TagLoader.loadTagsForExistingRegistries(
                closeableResourceManager, baseLayeredRegistry.getLayer(RegistryLayer.STATIC)
            );
            List<HolderLookup.RegistryLookup<?>> worldRegistryLookupList = TagLoader.buildUpdatedLookups(
                baseLayeredRegistry.getAccessForLoading(RegistryLayer.WORLD),
                pendingTags
            );
            RegistryAccess.Frozen worldRegistries = RegistryDataLoader.load(
                closeableResourceManager,
                worldRegistryLookupList,
                RegistryDataLoader.WORLD_REGISTRIES,
                ForkJoinPool.commonPool()
            ).join();
            LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess = baseLayeredRegistry.replaceFrom(
                RegistryLayer.WORLD,
                worldRegistries
            );
            return ReloadableServerRegistries.reload(
                layeredRegistryAccess,
                pendingTags,
                closeableResourceManager,
                ForkJoinPool.commonPool()
            ).thenApply(r -> r.layers().compositeAccess());
        }
    }
}
