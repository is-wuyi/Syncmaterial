//? if >=26 {
package net.syncmaterial.syncmaterial.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.config.HudAlignmentOption;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;
import org.slf4j.Logger;

import java.util.List;

public class SyncMaterialClient implements ClientModInitializer {
    private static final Logger LOGGER = SyncMaterial.LOGGER;
    private static SyncMaterialList activeMaterialList;

    public static SyncMaterialList getActiveMaterialList() { return activeMaterialList; }

    @Override
    public void onInitializeClient() {
        LOGGER.info("SyncMaterial Client initialized!");

        // 加载配置
        Configs.loadFromFile();
        fi.dy.masa.malilib.config.ConfigManager.getInstance()
                .registerConfigHandler(SyncMaterial.MOD_ID, new Configs());

        // 注册热键到 MaLiLib 输入系统（必须用 IKeybindProvider，addHotkeysForCategory 只做展示）
        fi.dy.masa.malilib.event.InputEventHandler.getKeybindManager().registerKeybindProvider(
                new fi.dy.masa.malilib.hotkeys.IKeybindProvider() {
                    @Override
                    public void addKeysToMap(fi.dy.masa.malilib.hotkeys.IKeybindManager manager) {
                        manager.addKeybindToMap(Configs.Generic.HUD_ENABLED.getKeybind());
                        manager.addKeybindToMap(Configs.Generic.WAREHOUSE_RENDER_ENABLED.getKeybind());
                    }
                    @Override
                    public void addHotkeys(fi.dy.masa.malilib.hotkeys.IKeybindManager manager) {
                        manager.addKeybindToMap(Configs.Generic.HUD_ENABLED.getKeybind());
                        manager.addKeybindToMap(Configs.Generic.WAREHOUSE_RENDER_ENABLED.getKeybind());
                    }
                });

        net.syncmaterial.syncmaterial.network.ModNetworkHandlerClient.register();
        InventoryWatcher.register();

        //? if >=26 {
        // 26.2 移除了 HudRenderCallback，改用 HudElementRegistry
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("syncmaterial", "material_list_hud"),
                (drawContext, tickDelta) -> {
                    if (activeMaterialList != null && Configs.Generic.HUD_ENABLED.getBooleanValue()
                            && activeMaterialList.getHudRenderer().getShouldRender()) {
                        fi.dy.masa.malilib.config.HudAlignment alignment =
                                ((HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue()).toMalilib();
                        int x = Configs.Hud.HUD_X_OFFSET.getIntegerValue();
                        int y = Configs.Hud.HUD_Y_OFFSET.getIntegerValue();
                        activeMaterialList.getHudRenderer().render(fi.dy.masa.malilib.render.GuiContext.fromGuiGraphics(drawContext), x, y, alignment);
                    }
                });
        //?} else {
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (activeMaterialList != null && Configs.Generic.HUD_ENABLED.getBooleanValue()
                    && activeMaterialList.getHudRenderer().getShouldRender()) {
                fi.dy.masa.malilib.config.HudAlignment alignment =
                        ((HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue()).toMalilib();
                int x = Configs.Hud.HUD_X_OFFSET.getIntegerValue();
                int y = Configs.Hud.HUD_Y_OFFSET.getIntegerValue();
                activeMaterialList.getHudRenderer().render(drawContext, x, y, alignment);
            }
        });
        //?}

        fi.dy.masa.malilib.event.RenderEventHandler.getInstance().registerWorldLastRenderer(
                StagingAreaRenderer.getInstance());

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            StagingAreaSelector.getInstance().onTick();
        });

        //? if >=26 {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("syncmaterial", "staging_selector_hud"),
                (drawContext, tickDelta) -> {
                    StagingAreaSelector.getInstance().onRenderHUD(fi.dy.masa.malilib.render.GuiContext.fromGuiGraphics(drawContext));
                });
        //?} else {
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            StagingAreaSelector.getInstance().onRenderHUD(drawContext);
        });
        //?}

        // 进服后立刻发版本握手：服务端据此得知本客户端装了本 mod 及其协议版本，
        // 握手通过后才会推送备货区与仓库的初始数据
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            net.syncmaterial.syncmaterial.network.ClientProtocolState.reset();
            sender.sendPacket(new net.syncmaterial.syncmaterial.network.HelloC2SPacket(
                    net.syncmaterial.syncmaterial.network.ProtocolVersion.CURRENT,
                    SyncMaterial.getModVersion()));
            net.syncmaterial.syncmaterial.network.ClientProtocolState.onHandshakeSent();
        });

        // 断开连接时清除编辑器状态
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal.clearCurrentEditor();
            // 仓库数据按服务器隔离：不清理会导致换服后渲染上一个服务器的仓库线框
            StagingAreaRenderer.getInstance().clearWarehouseAreas();
            StagingAreaRenderer.getInstance().clearWarehouseContainers();
            // 选区状态也要清：中途断线时 active 会残留，导致下次进服仍处于选区模式，
            // 且编辑上下文残留会让对应区域被正式渲染永久跳过
            StagingAreaSelector.getInstance().reset();
            // 协议状态也要清：不清会把上一个服务器的版本信息带到下一个服务器
            net.syncmaterial.syncmaterial.network.ClientProtocolState.reset();
        });

        // 准星选区模式下屏蔽方块交互
        net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback.EVENT.register((mc, player, clickCount) -> {
            return StagingAreaSelector.getInstance().isActive();
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            return StagingAreaSelector.getInstance().isActive()
                    ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            return StagingAreaSelector.getInstance().isActive()
                    ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS;
        });
    }

    public static void openMaterialListScreen(String schematicId, String schematicName, List<MaterialEntry> materials, boolean isOwner, boolean isMainOwner, String ownerName, List<String> deputyOwners, boolean allowSelfClaim) {
        LOGGER.info("收到材料清单响应，准备打开 UI。共 {} 项。isOwner={}, isMainOwner={}", materials.size(), isOwner, isMainOwner);
        // 分闸状态独立于 HUD_ENABLED 总闸：仅继承上一个界面的分闸，首次打开默认开启。
        // 不读取总闸值，否则总闸关闭期间新开界面会把分闸也带成关闭状态。
        boolean hudState = activeMaterialList == null
                || activeMaterialList.getHudRenderer().getShouldRender();
        GuiMaterialList gui = new GuiMaterialList(schematicId, schematicName, materials, isOwner, isMainOwner, ownerName, deputyOwners, allowSelfClaim);
        activeMaterialList = gui.getMaterialList();
        activeMaterialList.getHudRenderer().setShouldRender(hudState);
        Minecraft.getInstance().setScreenAndShow(gui);
    }

    public static void onCollaborationStatus(CollaborationStatusS2CPacket status) {
        LOGGER.info("收到协作状态更新: 材料 {} (总量: {}, 备货区: {}, 仓库: {})", status.materialId(), status.totalCount(), status.stagingCount(), status.warehouseCount());
        if (activeMaterialList != null) {
            activeMaterialList.onCollaborationStatus(status);
        }
    }

    /**
     * 原理图被删除时清理客户端状态：关闭 GUI、清除 HUD、清理渲染。
     * 由 SchematicUploadListener 调用（已通过 Minecraft.execute() 调度到渲染线程）。
     */
    public static void clearActiveSchematic(String schematicId) {
        // 关闭当前打开的材料列表 GUI（如果属于被删除的原理图）
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof GuiMaterialList gui) {
            if (schematicId.equals(gui.getMaterialList().getSchematicId())) {
                mc.setScreenAndShow(null);
            }
        }


        // 清除 HUD（如果属于被删除的原理图）
        if (activeMaterialList != null && schematicId.equals(activeMaterialList.getSchematicId())) {
            LOGGER.info("原理图 {} 已删除，清除 HUD", schematicId);
            activeMaterialList = null;
        }
    }
}
//?} else {
package net.syncmaterial.syncmaterial.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.config.HudAlignmentOption;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;
import org.slf4j.Logger;

import java.util.List;

public class SyncMaterialClient implements ClientModInitializer {
    private static final Logger LOGGER = SyncMaterial.LOGGER;
    private static SyncMaterialList activeMaterialList;

    public static SyncMaterialList getActiveMaterialList() { return activeMaterialList; }

    @Override
    public void onInitializeClient() {
        LOGGER.info("SyncMaterial Client initialized!");

        // 加载配置
        Configs.loadFromFile();
        fi.dy.masa.malilib.config.ConfigManager.getInstance()
                .registerConfigHandler(SyncMaterial.MOD_ID, new Configs());

        // 注册热键到 MaLiLib 输入系统（必须用 IKeybindProvider，addHotkeysForCategory 只做展示）
        fi.dy.masa.malilib.event.InputEventHandler.getKeybindManager().registerKeybindProvider(
                new fi.dy.masa.malilib.hotkeys.IKeybindProvider() {
                    @Override
                    public void addKeysToMap(fi.dy.masa.malilib.hotkeys.IKeybindManager manager) {
                        manager.addKeybindToMap(Configs.Generic.HUD_ENABLED.getKeybind());
                        manager.addKeybindToMap(Configs.Generic.WAREHOUSE_RENDER_ENABLED.getKeybind());
                    }
                    @Override
                    public void addHotkeys(fi.dy.masa.malilib.hotkeys.IKeybindManager manager) {
                        manager.addKeybindToMap(Configs.Generic.HUD_ENABLED.getKeybind());
                        manager.addKeybindToMap(Configs.Generic.WAREHOUSE_RENDER_ENABLED.getKeybind());
                    }
                });

        net.syncmaterial.syncmaterial.network.ModNetworkHandlerClient.register();
        InventoryWatcher.register();

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (activeMaterialList != null && Configs.Generic.HUD_ENABLED.getBooleanValue()
                    && activeMaterialList.getHudRenderer().getShouldRender()) {
                fi.dy.masa.malilib.config.HudAlignment alignment =
                        ((HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue()).toMalilib();
                int x = Configs.Hud.HUD_X_OFFSET.getIntegerValue();
                int y = Configs.Hud.HUD_Y_OFFSET.getIntegerValue();
                activeMaterialList.getHudRenderer().render(drawContext, x, y, alignment);
            }
        });

        fi.dy.masa.malilib.event.RenderEventHandler.getInstance().registerWorldLastRenderer(
                StagingAreaRenderer.getInstance());

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            StagingAreaSelector.getInstance().onTick();
        });

        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            StagingAreaSelector.getInstance().onRenderHUD(drawContext);
        });

        // 进服后立刻发版本握手：服务端据此得知本客户端装了本 mod 及其协议版本，
        // 握手通过后才会推送备货区与仓库的初始数据
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            net.syncmaterial.syncmaterial.network.ClientProtocolState.reset();
            sender.sendPacket(new net.syncmaterial.syncmaterial.network.HelloC2SPacket(
                    net.syncmaterial.syncmaterial.network.ProtocolVersion.CURRENT,
                    SyncMaterial.getModVersion()));
            net.syncmaterial.syncmaterial.network.ClientProtocolState.onHandshakeSent();
        });

        // 断开连接时清除编辑器状态
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal.clearCurrentEditor();
            // 仓库数据按服务器隔离：不清理会导致换服后渲染上一个服务器的仓库线框
            StagingAreaRenderer.getInstance().clearWarehouseAreas();
            StagingAreaRenderer.getInstance().clearWarehouseContainers();
            // 选区状态也要清：中途断线时 active 会残留，导致下次进服仍处于选区模式，
            // 且编辑上下文残留会让对应区域被正式渲染永久跳过
            StagingAreaSelector.getInstance().reset();
            // 协议状态也要清：不清会把上一个服务器的版本信息带到下一个服务器
            net.syncmaterial.syncmaterial.network.ClientProtocolState.reset();
        });

        // 准星选区模式下屏蔽方块交互
        net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback.EVENT.register((mc, player, clickCount) -> {
            return StagingAreaSelector.getInstance().isActive();
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            return StagingAreaSelector.getInstance().isActive()
                    ? net.minecraft.util.ActionResult.FAIL : net.minecraft.util.ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            return StagingAreaSelector.getInstance().isActive()
                    ? net.minecraft.util.ActionResult.FAIL : net.minecraft.util.ActionResult.PASS;
        });
    }

    public static void openMaterialListScreen(String schematicId, String schematicName, List<MaterialEntry> materials, boolean isOwner, boolean isMainOwner, String ownerName, List<String> deputyOwners, boolean allowSelfClaim) {
        LOGGER.info("收到材料清单响应，准备打开 UI。共 {} 项。isOwner={}, isMainOwner={}", materials.size(), isOwner, isMainOwner);
        // 分闸状态独立于 HUD_ENABLED 总闸：仅继承上一个界面的分闸，首次打开默认开启。
        // 不读取总闸值，否则总闸关闭期间新开界面会把分闸也带成关闭状态。
        boolean hudState = activeMaterialList == null
                || activeMaterialList.getHudRenderer().getShouldRender();
        GuiMaterialList gui = new GuiMaterialList(schematicId, schematicName, materials, isOwner, isMainOwner, ownerName, deputyOwners, allowSelfClaim);
        activeMaterialList = gui.getMaterialList();
        activeMaterialList.getHudRenderer().setShouldRender(hudState);
        MinecraftClient.getInstance().setScreen(gui);
    }

    public static void onCollaborationStatus(CollaborationStatusS2CPacket status) {
        LOGGER.info("收到协作状态更新: 材料 {} (总量: {}, 备货区: {}, 仓库: {})", status.materialId(), status.totalCount(), status.stagingCount(), status.warehouseCount());
        if (activeMaterialList != null) {
            activeMaterialList.onCollaborationStatus(status);
        }
    }

    /**
     * 原理图被删除时清理客户端状态：关闭 GUI、清除 HUD、清理渲染。
     * 由 SchematicUploadListener 调用（已通过 MinecraftClient.execute() 调度到渲染线程）。
     */
    public static void clearActiveSchematic(String schematicId) {
        // 关闭当前打开的材料列表 GUI（如果属于被删除的原理图）
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof GuiMaterialList gui) {
            if (schematicId.equals(gui.getMaterialList().getSchematicId())) {
                mc.setScreen(null);
            }
        }


        // 清除 HUD（如果属于被删除的原理图）
        if (activeMaterialList != null && schematicId.equals(activeMaterialList.getSchematicId())) {
            LOGGER.info("原理图 {} 已删除，清除 HUD", schematicId);
            activeMaterialList = null;
        }
    }
}
//?}
