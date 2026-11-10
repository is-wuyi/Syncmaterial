package net.syncmaterial.syncmaterial.compat;

import java.util.stream.Stream;

//? if >=26.2 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 单一接线点：只吸收 1.21.7/1.21.8（officialMojangMappings）与 26.2 之间
 * 无法用同一份签名表达的原生 API 差异。
 *
 * <p>业务与线格式不在这里复制：payload 字段顺序、codec、ID 字符串都仍只有一份。
 * 本类只做类型/方法名的薄转发，不保存状态，不改变任何语义。</p>
 */
public final class McCompat {
    private McCompat() {
    }

    /** 按玩家名查在线玩家（两版 PlayerList 都提供 getPlayerByName）。 */
    public static ServerPlayer findPlayerByName(MinecraftServer server, String name) {
        return server.getPlayerList().getPlayerByName(name);
    }

    /**
     * 解析资源 ID。返回值类型由节点决定（旧版 ResourceLocation，26.2 Identifier），
     * 调用方必须用 {@code var} 接住，避免在业务代码里散落版本条件。
     */
    //? if >=26.2 {
    public static Identifier tryParseId(String value) {
        return Identifier.tryParse(value);
    }

    /** 与 {@link #tryParseId(String)} 相同，但非法值抛异常（沿用原 parse 语义）。 */
    public static Identifier parseId(String value) {
        return Identifier.parse(value);
    }
    //?} else {
    /*public static ResourceLocation tryParseId(String value) {
        return ResourceLocation.tryParse(value);
    }

    public static ResourceLocation parseId(String value) {
        return ResourceLocation.parse(value);
    }
    *///?}

    /** 维度资源 ID 的字符串形式（旧版 ResourceKey.location()，26.2 identifier()）。 */
    public static String dimensionId(Level level) {
        //? if >=26.2 {
        return level.dimension().identifier().toString();
        //?} else {
        /*return level.dimension().location().toString();
        *///?}
    }

    /** 区块 X 坐标（旧版 ChunkPos 是 class 字段，26.2 是 record 访问器）。 */
    public static int chunkX(ChunkPos pos) {
        //? if >=26.2 {
        return pos.x();
        //?} else {
        /*return pos.x;
        *///?}
    }

    /** 区块 Z 坐标（旧版 ChunkPos 是 class 字段，26.2 是 record 访问器）。 */
    public static int chunkZ(ChunkPos pos) {
        //? if >=26.2 {
        return pos.z();
        //?} else {
        /*return pos.z;
        *///?}
    }

    /**
     * 潜影盒内容物流。两版都返回 ItemStack 流，只是方法名不同，
     * 因此调用方仍保持同一份统计逻辑。
     */
    public static Stream<ItemStack> nonEmptyShulkerItems(ItemContainerContents contents) {
        //? if >=26.2 {
        return contents.nonEmptyItemCopyStream();
        //?} else {
        /*return contents.nonEmptyStream();
        *///?}
    }
}
