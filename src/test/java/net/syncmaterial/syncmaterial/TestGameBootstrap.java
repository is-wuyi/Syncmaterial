package net.syncmaterial.syncmaterial;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.mojang.serialization.Lifecycle;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;

/**
 * 单测环境的 MC 初始化垫片。
 *
 * 26.2 的物品组件改为延迟烘焙：Bootstrap.bootStrap() 只注册内容，
 * 组件要等 DATA_COMPONENT_INITIALIZERS.build(provider) + apply() 才绑到
 * Item holder 上（游戏里由 ReloadableServerResources.updateComponentsAndStaticRegistryTags
 * 在资源重载时触发，单测 JVM 没有这条管线）。
 *
 * 烘焙中部分组件初始化器会 getOrThrow 数据包级标签（如 fireResistant 要查
 * damage_type/is_fire），而 damage_type 注册表只在数据包加载后才存在，
 * bootstrap 出来的 REGISTRY 根本查不到它 —— 因此 lookup 兜底一层：
 * 标签缺失时返回空 named 集，与"加载了空标签包"语义一致。
 */
public final class TestGameBootstrap {
    private static boolean bound;

    private TestGameBootstrap() {}

    @SuppressWarnings("unchecked")
    public static synchronized void bindDataComponents() {
        if (bound) return;
        HolderLookup.Provider base = net.minecraft.data.registries.VanillaRegistries.createLookup();
        net.minecraft.core.HolderOwner<?> owner = BuiltInRegistries.BLOCK;

        HolderLookup.Provider provider = new HolderLookup.Provider() {
            @Override
            public Stream<ResourceKey<? extends net.minecraft.core.Registry<?>>> listRegistryKeys() {
                return base.listRegistryKeys();
            }

            @Override
            public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(
                ResourceKey<? extends net.minecraft.core.Registry<? extends T>> key) {
                HolderLookup.RegistryLookup<T> real = base.lookup(key).orElse(null);
                if (real != null) {
                    return Optional.of(real);
                }
                return Optional.of(synthetic(key, (net.minecraft.core.HolderOwner<T>) owner));
            }
        };

        List<DataComponentInitializers.PendingComponents<?>> pending =
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(provider);
        for (DataComponentInitializers.PendingComponents<?> entry : pending) {
            entry.apply();
        }
        bound = true;
    }

    private static <T> HolderLookup.RegistryLookup<T> synthetic(
        ResourceKey<? extends net.minecraft.core.Registry<? extends T>> key,
        net.minecraft.core.HolderOwner<T> owner) {
        return new HolderLookup.RegistryLookup<>() {
            @Override
            public ResourceKey<? extends net.minecraft.core.Registry<? extends T>> key() {
                return key;
            }

            @Override
            public Lifecycle registryLifecycle() {
                return Lifecycle.stable();
            }

            @Override
            public Stream<Holder.Reference<T>> listElements() {
                return Stream.empty();
            }

            @Override
            public Stream<HolderSet.Named<T>> listTags() {
                return Stream.empty();
            }

            @Override
            public Optional<Holder.Reference<T>> get(ResourceKey<T> valueKey) {
                return Optional.empty();
            }

            @Override
            public Optional<HolderSet.Named<T>> get(TagKey<T> tag) {
                return Optional.of(HolderSet.emptyNamed(owner, tag));
            }
        };
    }
}
