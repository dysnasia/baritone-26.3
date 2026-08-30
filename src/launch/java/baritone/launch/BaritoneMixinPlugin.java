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

package baritone.launch;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin shared by every mod loader we ship on.
 * <p>
 * This lives in the common {@code launch} source set, which is shaded into every platform jar, so it cannot link
 * against any loader's API directly. Loader detection is done reflectively and every lookup is allowed to fail: a
 * loader we don't recognise simply means "baritone is not already present", which is the safe default (we apply our
 * mixins).
 */
public class BaritoneMixinPlugin implements IMixinConfigPlugin {

    private static final String MIXIN_PACKAGE = "baritone.launch.mixins";

    /**
     * The mod id of a standalone Baritone. If the user already has one installed, ours stands down instead of
     * applying a second copy of the same mixins.
     */
    private static final String BARITONE_MOD_ID = "baritone";

    private static boolean loaded;

    private static boolean isBaritonePresent;

    @Override
    public void onLoad(String mixinPackage) {
        if (loaded) return;

        isBaritonePresent = detectBaritone();

        loaded = true;
    }

    private static boolean detectBaritone() {
        Boolean fabric = isModLoadedFabric();
        if (fabric != null) return fabric;

        Boolean forge = isModLoadedForge();
        if (forge != null) return forge;

        return false;
    }

    /**
     * @return whether baritone is loaded, or {@code null} if we aren't on Fabric (or couldn't tell)
     */
    private static Boolean isModLoadedFabric() {
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = loaderClass.getMethod("getInstance").invoke(null);
            return (Boolean) loaderClass.getMethod("isModLoaded", String.class).invoke(loader, BARITONE_MOD_ID);
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            return null;
        }
    }

    /**
     * @return whether baritone is loaded, or {@code null} if we aren't on Forge/NeoForge (or couldn't tell)
     */
    private static Boolean isModLoadedForge() {
        // Mixin plugins run during FML's early loading, before ModList exists, so we ask the LoadingModList that FML
        // builds during mod discovery. Its shape differs between (and across versions of) the two loaders: modern
        // Forge exposes getModFileById as a static on an interface, while NeoForge keeps it an instance method
        // reached through a static get(). Try both.
        for (String className : new String[]{
                "net.minecraftforge.fml.loading.LoadingModList",
                "net.neoforged.fml.loading.moddiscovery.LoadingModList",
                "net.minecraftforge.fml.loading.moddiscovery.LoadingModList"
        }) {
            try {
                Class<?> listClass = Class.forName(className);
                Method getModFileById = listClass.getMethod("getModFileById", String.class);

                Object receiver = null;
                if (!Modifier.isStatic(getModFileById.getModifiers())) {
                    receiver = listClass.getMethod("get").invoke(null);
                    if (receiver == null) continue;
                }

                return getModFileById.invoke(receiver, BARITONE_MOD_ID) != null;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // not this loader, or the mod list isn't ready yet -- try the next candidate
            }
        }
        return null;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(MIXIN_PACKAGE)) {
            throw new RuntimeException("Mixin " + mixinClassName + " is not in the mixin package");
        } else {
            return !isBaritonePresent;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
