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
    ("import net.minecraft.client.font.TextRenderer;", "import net.minecraft.client.gui.Font;"),
    # 方法名差异（mojmap）
    ("world.getRegistryKey().getValue().toString()", "world.dimension().location().toString()"),
    (".getChunkManager().getLevelChunk(", ".getChunkSource().getChunk("),
    ("chunk.getPos().x", "chunk.getPos().getX()"),
    ("chunk.getPos().z", "chunk.getPos().getZ()"),
    ("server.getWorld(net.minecraft.resources.ResourceKey.of(", "server.getLevel(net.minecraft.resources.ResourceKey.of("),
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
    # Inventory 双语义：先隔离玩家背包，再统一换容器接口
    ("PlayerInventory", "__PLAYER_INVENTORY__"),
    ("Inventory", "Container"),
    ("__PLAYER_INVENTORY__", "Inventory"),
    ("PlayerEntity", "Player"),
    ("ScreenHandler", "AbstractContainerMenu"),
    ("HandledScreen", "AbstractContainerScreen"),
    ("ServerPlayerEntity", "ServerPlayer"),
    ("ServerWorld", "ServerLevel"),
    ("WorldChunk", "LevelChunk"),
    ("NbtCompound", "CompoundTag"),
    ("NbtList", "ListTag"),
    # Identifier 工厂
    ("Identifier.of(", "Identifier.fromNamespaceAndPath("),
    ("Registries.ITEM", "BuiltInRegistries.ITEM"),
    ("Registries.BLOCK", "BuiltInRegistries.BLOCK"),
    ("Registries.REGISTRIES", "BuiltInRegistries.REGISTRY"),
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
