#!/usr/bin/env python3
"""将 network 层以外的公共类转换为 Stonecutter 双版本分支（26.2 官方命名）。

只做类型名/包名/工厂方法的机械替换；方法名差异留给编译器暴露后逐个处理。
"""
import sys
from pathlib import Path

ROOT = Path("/Volumes/赤石/Syncmaterial-1/src/main/java/net/syncmaterial/syncmaterial")

FILES = [
    "network/ModPackets.java",
    "network/ModNetworkHandler.java",
    "network/Phase4Handler.java",
    "network/ProtocolHandshake.java",
    "api/MaterialEntry.java",
    "engine/LitematicaParser.java",
    "engine/impl/DefaultLitematicaParser.java",
    "engine/impl/StatisticsProcessor.java",
    "server/StagingAreaManager.java",
    "server/SchematicFolderWatcher.java",
    "server/SchematicUploadListener.java",
    "server/CollaborationManager.java",
    "server/DatabaseQueryService.java",
    "selection/Box.java",
    "selection/AreaSelection.java",
    "mixin/HandledScreenMixin.java",
    "mixin/BlockEntityMixin.java",
    "mixin/ButtonListenerMixin.java",
    "mixin/MixinLitematicaMainMenu.java",
    "mixin/MixinKeyCallbacks.java",
    "client/InventoryWatcher.java",
    "client/SyncMaterialClient.java",
    "client/gui/StagingAreaSelector.java",
    "client/gui/MaterialListEntry.java",
    "client/gui/MaterialListUtils.java",
    "client/gui/MaterialListBase.java",
    "client/gui/GuiStagingAreaEditorNormal.java",
    "client/gui/GuiStagingAreaEditorSubRegion.java",
    "client/gui/GuiWarehouseEditor.java",
    "client/gui/GuiWarehouseManager.java",
    "client/gui/GuiWarehouseSelect.java",
    "client/gui/GuiWarehouseRefPopup.java",
    "client/gui/GuiMaterialList.java",
    "client/gui/GuiSettings.java",
    "client/gui/CoordinateNudge.java",
    "client/gui/MaterialListHudRenderer.java",
    "client/gui/SyncMaterialList.java",
    "client/gui/widgets/WidgetListStagingAreas.java",
    "client/gui/widgets/WidgetListMaterialList.java",
    "client/gui/widgets/WidgetMaterialListEntry.java",
    "client/gui/widgets/WidgetWarehouseSelectEntry.java",
    "client/gui/widgets/WidgetWarehouseEntry.java",
    "client/gui/widgets/WidgetWarehouseRefEntry.java",
    "client/gui/widgets/WidgetStagingAreaEntry.java",
    "client/gui/widgets/WidgetListWarehouseRefs.java",
    "network/ModNetworkHandlerClient.java",
    "client/config/Configs.java",
    "client/infohud/IInfoHudRenderer.java",
    "SyncMaterial.java",
]

REPLACEMENTS = [
    # FQN（必须先于裸名替换）
    ("net.minecraft.world.chunk.WorldChunk", "net.minecraft.world.level.chunk.LevelChunk"),
    ("net.minecraft.server.network.ServerPlayerEntity", "net.minecraft.server.level.ServerPlayer"),
    ("net.minecraft.network.packet.CustomPayload", "net.minecraft.network.protocol.common.custom.CustomPacketPayload"),
    ("net.minecraft.client.gui.screen.ingame.HandledScreen", "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen"),
    # import 行
    ("import net.minecraft.util.math.BlockPos;", "import net.minecraft.core.BlockPos;"),
    ("import net.minecraft.util.math.Direction;", "import net.minecraft.core.Direction;"),
    ("import net.minecraft.util.Identifier;", "import net.minecraft.resources.Identifier;"),
    ("import net.minecraft.item.ItemStack;", "import net.minecraft.world.item.ItemStack;"),
    ("import net.minecraft.item.Item;", "import net.minecraft.world.item.Item;"),
    ("import net.minecraft.item.Items;", "import net.minecraft.world.item.Items;"),
    ("import net.minecraft.item.BlockItem;", "import net.minecraft.world.item.BlockItem;"),
    ("import net.minecraft.block.BlockState;", "import net.minecraft.world.level.block.state.BlockState;"),
    ("import net.minecraft.block.Block;", "import net.minecraft.world.level.block.Block;"),
    ("import net.minecraft.block.entity.BlockEntity;", "import net.minecraft.world.level.block.entity.BlockEntity;"),
    ("import net.minecraft.server.world.ServerWorld;", "import net.minecraft.server.level.ServerLevel;"),
    ("import net.minecraft.server.network.ServerPlayerEntity;", "import net.minecraft.server.level.ServerPlayer;"),
    ("import net.minecraft.world.chunk.WorldChunk;", "import net.minecraft.world.level.chunk.LevelChunk;"),
    ("import net.minecraft.inventory.Inventory;", "import net.minecraft.world.Container;"),
    ("import net.minecraft.text.Text;", "import net.minecraft.network.chat.Component;"),
    ("import net.minecraft.nbt.NbtCompound;", "import net.minecraft.nbt.CompoundTag;"),
    ("import net.minecraft.nbt.NbtList;", "import net.minecraft.nbt.ListTag;"),
    ("import net.minecraft.nbt.NbtElement;", "import net.minecraft.nbt.Tag;"),
    ("import net.minecraft.component.DataComponentTypes;", "import net.minecraft.core.component.DataComponents;"),
    ("import net.minecraft.registry.Registries;", "import net.minecraft.core.registries.BuiltInRegistries;"),
    ("import net.minecraft.registry.RegistryKey;", "import net.minecraft.resources.ResourceKey;"),
    ("import net.minecraft.registry.RegistryKeys;", "import net.minecraft.core.registries.Registries;"),
    ("import net.minecraft.state.property.Properties;", "import net.minecraft.world.level.block.state.properties.BlockStateProperties;"),
    ("import net.minecraft.block.enums.BedPart;", "import net.minecraft.world.level.block.state.properties.BedPart;"),
    ("import net.minecraft.block.enums.DoubleBlockHalf;", "import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;"),
    ("import net.minecraft.block.enums.SlabType;", "import net.minecraft.world.level.block.state.properties.SlabType;"),
    ("import net.minecraft.screen.ScreenHandler;", "import net.minecraft.world.inventory.AbstractContainerMenu;"),
    ("import net.minecraft.screen.slot.Slot;", "import net.minecraft.world.inventory.Slot;"),
    ("import net.minecraft.client.gui.screen.ingame.HandledScreen;", "import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;"),
    ("import net.minecraft.block.ShulkerBoxBlock;", "import net.minecraft.world.level.block.ShulkerBoxBlock;"),
    # MaLiLib 0.29 类迁移
    ("import fi.dy.masa.malilib.util.JsonUtils;", "import fi.dy.masa.malilib.util.data.json.JsonUtils;"),
    ("import fi.dy.masa.malilib.util.ItemType;", "import fi.dy.masa.malilib.util.data.ItemType;"),
    # 方法名差异（mojmap）
    ("world.getRegistryKey().getValue().toString()", "world.dimension().identifier().toString()"),
    # chunk 存在性判断：yarn getWorldChunk → mojmap getChunkSource().getChunk()
    # 注意必须用 yarn 原文做匹配串；下方裸名规则曾把方法名误改成 getLevelChunk
    (".getChunkManager().getWorldChunk(", ".getChunkSource().getChunk("),
    ("chunk.getPos().x", "chunk.getPos().getX()"),
    ("chunk.getPos().z", "chunk.getPos().getZ()"),
    ("server.getWorld(", "server.getLevel("),
    ("ItemStack.areEqual(", "ItemStack.isSameItem("),
    ("stack.getName().getString()", "stack.getHoverName().getString()"),
    ("server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)", "server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)"),
    # Container 接口方法（变量名为 inventory 的常见形态）
    ("inventory.size()", "inventory.getContainerSize()"),
    ("inventory.getStack(", "inventory.getItem("),
    ("inventory.setStack(", "inventory.setItem("),
    ("inventory.removeItemNoUpdate(", "inventory.takeItem("),
    ("server.getWorld(", "server.getLevel("),
    ("be.getWorld()", "be.getLevel()"),
    ("be.getPos()", "be.getBlockPos()"),
    ("be.markDirty()", "be.setChanged()"),
    # mojmap ServerPlayer 的 UUID 方法是大写 UUID
    (".getUuid()", ".getUUID()"),
    # 潜影盒内容遍历（26.2 的 ItemContainerContents 在 component 子包）
    ("import net.minecraft.world.item.ItemContainerContents;", "import net.minecraft.world.item.component.ItemContainerContents;"),
    ("container.streamNonEmpty()", "container.nonEmptyItemCopyStream()"),
    # 通配与 FQN 兜底
    ("import net.minecraft.block.*;", "import net.minecraft.world.level.block.*;"),
    ("import net.minecraft.client.gui.screen.Screen;", "import net.minecraft.client.gui.screens.Screen;"),
    ("import net.minecraft.util.hit.BlockHitResult;", "import net.minecraft.world.phys.BlockHitResult;"),
    ("import net.minecraft.util.hit.HitResult;", "import net.minecraft.world.phys.HitResult;"),
    ("import net.minecraft.util.profiler.Profiler;", "import net.minecraft.util.profiling.ProfilerFiller;"),
    # 裸类型引用
    ("net.minecraft.util.math.BlockPos", "net.minecraft.core.BlockPos"),
    ("net.minecraft.util.math.MathHelper", "net.minecraft.util.Mth"),
    ("net.minecraft.item.ItemStack", "net.minecraft.world.item.ItemStack"),
    ("net.minecraft.item.BlockItem", "net.minecraft.world.item.BlockItem"),
    ("net.minecraft.item.Items", "net.minecraft.world.item.Items"),
    ("net.minecraft.block.ShulkerBoxBlock", "net.minecraft.world.level.block.ShulkerBoxBlock"),
    ("net.minecraft.component.DataComponentTypes", "net.minecraft.core.component.DataComponents"),
    ("net.minecraft.registry.RegistryKeys.WORLD", "net.minecraft.core.registries.Registries.DIMENSION"),
    ("net.minecraft.registry.RegistryKey", "net.minecraft.resources.ResourceKey"),
    ("net.minecraft.registry.BuiltInRegistries.ITEM", "net.minecraft.core.registries.BuiltInRegistries.ITEM"),
    ("net.minecraft.util.Identifier", "net.minecraft.resources.Identifier"),
    ("net.minecraft.util.WorldSavePath.ROOT", "net.minecraft.server.level.ServerLevel.ROOT"),
    ("DrawContext", "GuiGraphicsExtractor"),
    ("MinecraftClient", "Minecraft"),
    ("TextRenderer", "Font"),
    # Inventory 双语义：先隔离玩家背包与自定义类名，再统一换容器接口
    # 注意顺序：含 PlayerInventory 子串的自定义方法名必须先隔离，
    # 否则占位符还原时会吞掉中间的 Player（upsertPlayerInventory→upsertInventory）
    ("upsertPlayerInventory", "__UPSERT_PI__"),
    ("updatePlayerInventory", "__UPDATE_PI__"),
    ("loadPlayerInventories", "__LOAD_PIS__"),
    ("PlayerInventory", "__PLAYER_INVENTORY__"),
    ("InventoryUpdateC2SPacket", "__INV_UPDATE_PKT__"),
    ("InventoryWatcher", "__INV_WATCHER__"),
    ("Inventory", "Container"),
    ("__PLAYER_INVENTORY__", "Inventory"),
    ("__UPSERT_PI__", "upsertPlayerInventory"),
    ("__UPDATE_PI__", "updatePlayerInventory"),
    ("__LOAD_PIS__", "loadPlayerInventories"),
    ("__INV_UPDATE_PKT__", "InventoryUpdateC2SPacket"),
    ("__INV_WATCHER__", "InventoryWatcher"),
    # Mixin 类名保持原文件名一致
    # ("ContainerWatcher", "InventoryWatcher") 已由上方占位符法取代
    ("PlayerEntity", "Player"),
    ("ScreenHandler", "AbstractContainerMenu"),
    ("HandledScreen", "AbstractContainerScreen"),
    ("ServerPlayerEntity", "ServerPlayer"),
    ("ServerWorld", "ServerLevel"),
    # 裸名 WorldChunk→LevelChunk 已删除：会误伤方法名 getWorldChunk
    ("NbtCompound", "CompoundTag"),
    ("NbtList", "ListTag"),
    # Identifier 工厂
    ("Identifier.of(", "Identifier.fromNamespaceAndPath("),
    ("Registries.ITEM", "BuiltInRegistries.ITEM"),
    ("Registries.BLOCK", "BuiltInRegistries.BLOCK"),
    ("Registries.REGISTRIES", "BuiltInRegistries.REGISTRY"),
    # Registries→BuiltInRegistries 之后补包路径（原 import 行是 net.minecraft.registry.Registries）
    ("net.minecraft.registry.BuiltIn", "net.minecraft.core.registries.BuiltIn"),
    # Fabric 26.2 注册入口改名
    ("PayloadTypeRegistry.playC2S()", "PayloadTypeRegistry.serverboundPlay()"),
    ("PayloadTypeRegistry.playS2C()", "PayloadTypeRegistry.clientboundPlay()"),
    # 玩家名与在线玩家获取
    (".getGameProfile().getName()", ".getName().getString()"),
    (".getPlayerManager().getPlayerList()", ".getPlayerList().getPlayers()"),
    ("server.getRunDirectory()", "server.getServerDirectory()"),
    # 裸名规则产生的错误包路径兜底
    ("import net.minecraft.client.font.Font;", "import net.minecraft.client.gui.Font;"),
    ("net.minecraft.entity.player.Inventory;", "net.minecraft.world.entity.player.Inventory;"),
    ("net.minecraft.entity.player.Player;", "net.minecraft.world.entity.player.Player;"),
    # ServerPlayer 的连接字段与玩家管理器（mojmap）
    (".networkHandler", ".connection"),
    (".getPlayerManager()", ".getPlayerList()"),
    ("player.getWorld().getRegistryKey()", "player.level().dimension()"),
    # 方块状态与属性（StatisticsProcessor 等）
    # 注意：常量规则必须在 FQN 规则之前，否则 FQN 替换后的尾部 Properties 会被二次替换
    ("Properties.WATERLOGGED", "BlockStateProperties.WATERLOGGED"),
    ("Properties.LEVEL_15", "BlockStateProperties.LEVEL_15"),
    ("Properties.CANDLES", "BlockStateProperties.CANDLES"),
    ("Properties.PICKLES", "BlockStateProperties.PICKLES"),
    ("Properties.EGGS", "BlockStateProperties.EGGS"),
    ("Properties.LAYERS", "BlockStateProperties.LAYERS"),
    ("import net.minecraft.block.BlockState;", "import net.minecraft.world.level.block.state.BlockState;"),
    ("import net.minecraft.state.property.Properties;", "import net.minecraft.world.level.block.state.properties.BlockStateProperties;"),
    ("net.minecraft.state.property.Properties", "net.minecraft.world.level.block.state.properties.BlockStateProperties"),
    ("import net.minecraft.nbt.NbtElement;", "import net.minecraft.nbt.Tag;"),
    ("NbtElement", "Tag"),
    ("NbtSizeTracker", "NbtAccounter"),
    # yarn LEVEL_15(0-15 等级属性) 对应 mojmap LEVEL
    ("BlockStateProperties.LEVEL_15", "BlockStateProperties.LEVEL"),
    ("instanceof TallPlantBlock", "instanceof DoublePlantBlock"),
    ("TallPlantBlock.HALF", "DoublePlantBlock.HALF"),
    ("import net.minecraft.block.TallPlantBlock;", "import net.minecraft.world.level.block.DoublePlantBlock;"),
    ("import net.minecraft.block.enums.DoubleBlockHalf;", "import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;"),
    ("LeveledCauldronBlock", "LayeredCauldronBlock"),
    ("((FlowerPotBlock) block).getContent()", "((FlowerPotBlock) block).getPotted()"),
    # StatisticsProcessor 的通配 import 解析不到 mojmap BlockState（在 state 子包），用 FQN 兜底
    ("Map.Entry<BlockPos, BlockState> entry", "Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState> entry"),
    ("BlockState state = entry.getValue()", "net.minecraft.world.level.block.state.BlockState state = entry.getValue()"),
    ("import net.minecraft.block.enums.BedPart;", "import net.minecraft.world.level.block.state.properties.BedPart;"),
    ("import net.minecraft.block.enums.SlabType;", "import net.minecraft.world.level.block.state.properties.SlabType;"),
    ("MultifaceGrowthBlock", "MultifaceBlock"),
    ("collectDirections(", "availableFaces("),
    ("NbtSizeTracker.ofUnlimitedBytes()", "NbtAccounter.unlimitedHeap()"),
    ("bundleContents.stream()", "bundleContents.itemCopyStream()"),
    ("net.minecraft.nbt.NbtHelper.toBlockState(", "net.minecraft.nbt.NbtUtils.readBlockState("),
    # BlockEntity / NBT 杂项
    ("self.getWorld()", "self.getLevel()"),
    ("self.getPos()", "self.getBlockPos()"),
    (".isClient()", ".isClientSide()"),
    (".getKeys()", ".getAllKeys()"),
    (".getDefaultState()", ".defaultBlockState()"),
    # 杂项 API 差异
    (".currentScreen", ".screen"),
    ("DataComponentTypes", "DataComponents"),
    ("BlockPos.ORIGIN", "BlockPos.ZERO"),
    (".hasStack()", ".hasItem()"),
    ("slot.getStack()", "slot.getItem()"),
    ("dimension().getValue()", "dimension().identifier()"),
    # BlockState 方法名（mojmap：hasProperty/getValue/setValue/is）
    ("state.contains(", "state.hasProperty("),
    ("state.get(", "state.getValue("),
    ("state.with(", "state.setValue("),
    ("state.isOf(", "state.is("),
    # NBT 键遍历
    (".getAllKeys()", ".keySet()"),
    # FQN 包路径补丁（裸名常量规则只换了类名段）
    ("net.minecraft.state.property.BlockStateProperties", "net.minecraft.world.level.block.state.properties.BlockStateProperties"),
    ("net.minecraft.block.Blocks.", "net.minecraft.world.level.block.Blocks."),
    # 注册表取 ID：yarn getId 与 mojmap getKey 都直接返回标识符对象
    ("BuiltInRegistries.ITEM.getId(", "BuiltInRegistries.ITEM.getKey("),
]

def convert(text: str) -> str:
    new = text
    for old, repl in REPLACEMENTS:
        new = new.replace(old, repl)
    return new

# Mixin 类名不得跟随目标类改名（文件名/引用一致性）
CLASS_NAME_FIXES = [
    ("class AbstractContainerScreenMixin", "class HandledScreenMixin"),
    # "Inventory -> Container" 规则会误伤含 Inventory 的自定义类名
    ("ContainerWatcher", "InventoryWatcher"),
]

def wrap(old_body: str, new_body: str) -> str:
    return (
        "//? if >=26 {\n"
        + new_body.rstrip("\n")
        + "\n//?} else {\n"
        + old_body.rstrip("\n")
        + "\n//?}\n"
    )

def main():
    converted = unchanged = 0
    for rel in FILES:
        f = ROOT / rel
        if not f.exists():
            print(f"[缺失] {rel}", file=sys.stderr)
            continue
        text = f.read_text(encoding="utf-8")
        if "//? if" in text:
            print(f"[跳过] {rel}")
            continue
        new_body = convert(text)
        # Mixin 类名保持原文件名一致
        for fix_old, fix_new in CLASS_NAME_FIXES:
            new_body = new_body.replace(fix_old, fix_new)
        if new_body == text:
            print(f"[无变化] {rel}")
            unchanged += 1
            continue
        f.write_text(wrap(text, new_body), encoding="utf-8")
        converted += 1
        print(f"[完成] {rel}")
    print(f"\n转换 {converted}，无变化 {unchanged}")

if __name__ == "__main__":
    main()
