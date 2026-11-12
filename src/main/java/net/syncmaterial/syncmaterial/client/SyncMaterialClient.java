package net.syncmaterial.syncmaterial.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.api.MaterialEntry;
import net.syncmaterial.syncmaterial.client.config.Configs;
import net.syncmaterial.syncmaterial.client.config.HudAlignmentOption;
import net.syncmaterial.syncmaterial.client.gui.GuiMaterialList;
import net.syncmaterial.syncmaterial.client.gui.MaterialListUtils;
import net.syncmaterial.syncmaterial.client.gui.StagingAreaSelector;
import net.syncmaterial.syncmaterial.client.gui.SyncMaterialList;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;
import org.slf4j.Logger;

import java.util.List;

public class SyncMaterialClient implements ClientModInitializer {
    private static final Logger LOGGER = SyncMaterial.LOGGER;
    private static SyncMaterialList activeMaterialList;
    private static long catalogRevision = -1;
    /** 维度变化检测：仓库容器响应不带维度字段，换维度必须丢缓存并重订阅。 */
    private static final DimensionChangeTracker WAREHOUSE_DIMENSION_TRACKER = new DimensionChangeTracker();

    /** 取货需求重算周期（tick）。10 tick ≈ 0.5 秒，比原先挂在 HUD 的 2 秒更及时 */
    private static final int PICKUP_RECOMPUTE_INTERVAL = 10;
    private static int pickupRecomputeTicks;

    public static SyncMaterialList getActiveMaterialList() { return activeMaterialList; }

    /**
     * 就地计算取货需求量，供箱子格子高亮使用。
     *
     * 与 PickupModeState.getNeeds()（每 10 tick 采样一次）的区别在采样时刻：
     * 格子高亮是"从第一格往后累加直到凑够需求量"，需求量必须与容器内容
     * 同一瞬间采样，否则差多少就多点亮几格。
     *
     * 举例：需要 128，箱子每格 64。取走第 0 格后容器变成 [空, 64, 64]，
     * 若需求还是旧的 128，贪心会选中第 1、2 格 —— 第 2 格本不该亮，
     * 等下一次 tick 把需求修正为 64 才恢复，表现为"后面的物品闪一下"。
     */
    public static java.util.Map<String, Integer> livePickupNeeds() {
        if (!PickupModeState.isActive() || activeMaterialList == null) {
            return java.util.Map.of();
        }
        return PickupModeState.computeNeeds(
                activeMaterialList.getMaterialsAll(),
                InventoryScanner.liveCountsByItemId(Minecraft.getInstance().player));
    }

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

        // 取货需求量在 tick 中重算，不挂渲染回调：挂渲染会让 HUD 关闭后
        // 需求永不更新，格子高亮与仓库线框停在陈旧数据上
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!PickupModeState.isActive()) return;
            if (++pickupRecomputeTicks % PICKUP_RECOMPUTE_INTERVAL != 0) return;
            // 背包实测数就地采集：服务端回传的 countAvailable 有整条上报往返的
            // 延迟，用它算需求会在快速取货时把后面不需要的同种物品也点亮
            PickupModeState.recompute(
                    activeMaterialList == null ? null : activeMaterialList.getMaterialsAll(),
                    InventoryScanner.liveCountsByItemId(client.player));
        });

        // 仓库容器缓存按维度隔离，但响应包不带维度；换维度时清缓存并按当前原理图重订阅，
        // 否则新维度里同坐标的箱子会沿用上个维度的取货高亮。
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.player.level() == null) {
                WAREHOUSE_DIMENSION_TRACKER.observe(null);
                return;
            }
            String worldId = net.syncmaterial.syncmaterial.compat.McCompat.dimensionId(client.player.level());
            if (WAREHOUSE_DIMENSION_TRACKER.observe(worldId)) {
                onClientDimensionChanged();
            }
        });

        // 26.2 移除了 HudRenderCallback，改用 HudElementRegistry；
        // 1.21.7/1.21.8 仍用 HudRenderCallback，渲染上下文是 GuiGraphics
        //? if >=26.2 {
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
        /*net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (activeMaterialList != null && Configs.Generic.HUD_ENABLED.getBooleanValue()
                    && activeMaterialList.getHudRenderer().getShouldRender()) {
                fi.dy.masa.malilib.config.HudAlignment alignment =
                        ((HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue()).toMalilib();
                int x = Configs.Hud.HUD_X_OFFSET.getIntegerValue();
                int y = Configs.Hud.HUD_Y_OFFSET.getIntegerValue();
                activeMaterialList.getHudRenderer().render(drawContext, x, y, alignment);
            }
        });
        *///?}

        fi.dy.masa.malilib.event.RenderEventHandler.getInstance().registerWorldLastRenderer(
                StagingAreaRenderer.getInstance());

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            StagingAreaSelector.getInstance().onTick();
        });

        //? if >=26.2 {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("syncmaterial", "staging_selector_hud"),
                (drawContext, tickDelta) -> {
                    StagingAreaSelector.getInstance().onRenderHUD(fi.dy.masa.malilib.render.GuiContext.fromGuiGraphics(drawContext));
                });
        //?} else {
        /*net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            StagingAreaSelector.getInstance().onRenderHUD(drawContext);
        });
        *///?}

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
            // 备货区线框同样按服务器隔离：schematicId 是各服自增值跨服会撞号，
            // 不清会把上个服的备货区线框（及陈旧 serverId）带进新服世界
            StagingAreaRenderer.getInstance().clearAllSelections();
            // 选区状态也要清：中途断线时 active 会残留，导致下次进服仍处于选区模式，
            // 且编辑上下文残留会让对应区域被正式渲染永久跳过
            StagingAreaSelector.getInstance().reset();
            // 协议状态也要清：不清会把上一个服务器的版本信息带到下一个服务器
            net.syncmaterial.syncmaterial.network.ClientProtocolState.reset();
            // HUD 与背包监听按服务器隔离：不清会让 HUD 继续挂着上个服的材料清单，
            // 且 InventoryWatcher 仍持旧 schematicId，换服后朝新服发它不认识的背包更新包
            activeMaterialList = null;
            catalogRevision = -1;
            WAREHOUSE_DIMENSION_TRACKER.reset();
            InventoryWatcher.clearContext();
            // 取货模式同理：不清会带着上个服的需求量继续高亮格子与仓库线框
            PickupModeState.clear();
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
        if (activeMaterialList != null && !activeMaterialList.getSchematicId().equals(schematicId)) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                    new net.syncmaterial.syncmaterial.network.MaterialListCloseC2SPacket(activeMaterialList.getSchematicId()));
        }
        GuiMaterialList gui = new GuiMaterialList(schematicId, schematicName, materials, isOwner, isMainOwner, ownerName, deputyOwners, allowSelfClaim);
        activeMaterialList = gui.getMaterialList();
        catalogRevision = -1;
        activeMaterialList.getHudRenderer().setShouldRender(hudState);
        ClientScreenAccess.showScreen(Minecraft.getInstance(), gui);
    }

    public static void onMaterialCatalogUpdate(net.syncmaterial.syncmaterial.network.MaterialCatalogUpdateS2CPacket update) {
        if (!net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                net.syncmaterial.syncmaterial.network.ProtocolVersion.MATERIAL_CATALOG_UPDATES)
                || activeMaterialList == null || !activeMaterialList.getSchematicId().equals(update.schematicId())
                || update.revision() <= catalogRevision) return;
        catalogRevision = update.revision();
        // 目录更新同时失效旧区域快照：旧序号/计数不能继续冒充当前目录的权威值。
        activeMaterialList.setMaterialEntries(update.materials(), update.revision());
        activeMaterialList.setTitle(update.schematicName());
        activeMaterialList.useFoundationBackend();
        InventoryWatcher.enablePeriodicReports();
        StagingAreaRenderer.getInstance().setSchematicName(update.schematicId(), update.schematicName());
        var mc = Minecraft.getInstance();
        if (mc.player != null) MaterialListUtils.updateAvailableCounts(activeMaterialList.getMaterialsAll(), mc.player);
        if (ClientScreenAccess.currentScreen(mc) instanceof GuiMaterialList gui
                && gui.getSchematicId().equals(update.schematicId())) {
            gui.onMaterialCatalogUpdated();
        }
    }

    public static void onCollaborationStatus(CollaborationStatusS2CPacket status) {
        LOGGER.info("收到协作状态更新: 材料 {} (总量: {}, 备货区: {}, 仓库: {})", status.materialId(), status.totalCount(), status.stagingCount(), status.warehouseCount());
        if (activeMaterialList != null && activeMaterialList.getSchematicId().equals(status.schematicId())) {
            activeMaterialList.onCollaborationStatus(status);
        }
    }

    /**
     * v3 区域库存权威快照入口。先做服务端能力闸门（旧服务端不发/客户端不认），
     * 再交给当前材料目录做目录序号、快照序号与材料全集校验。
     */
    public static void onAreaInventorySnapshot(net.syncmaterial.syncmaterial.network.AreaInventorySnapshotS2CPacket snapshot) {
        if (!net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                net.syncmaterial.syncmaterial.network.ProtocolVersion.AREA_INVENTORY_SNAPSHOTS)) return;
        if (activeMaterialList == null || !activeMaterialList.getSchematicId().equals(snapshot.schematicId())) return;
        if (!activeMaterialList.applyAreaInventorySnapshot(snapshot)) return;
        var mc = Minecraft.getInstance();
        if (ClientScreenAccess.currentScreen(mc) instanceof GuiMaterialList gui
                && gui.getSchematicId().equals(snapshot.schematicId())) {
            // 空列表也下发：空表示当前没有未初始化区域，必须清掉旧警告。
            gui.updateFreshnessWarnings(snapshot.freshnessInfo());
        }
    }

    /**
     * 维度切换后的仓库缓存处理：丢掉旧维度容器，若正处于取货模式则按当前原理图
     * 重发订阅（复用既有 WarehouseContainerRequestC2SPacket，不新增包）。
     */
    public static void onClientDimensionChanged() {
        StagingAreaRenderer.getInstance().clearWarehouseContainers();
        if (activeMaterialList == null) {
            return;
        }
        PickupModeState.recompute(activeMaterialList.getMaterialsAll(),
                InventoryScanner.liveCountsByItemId(Minecraft.getInstance().player));
        if (PickupModeState.isActive()) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                    new net.syncmaterial.syncmaterial.network.WarehouseContainerRequestC2SPacket(
                            activeMaterialList.getSchematicId(), true));
        }
    }

    /**
     * 原理图被删除时清理客户端状态：关闭 GUI、清除 HUD、清理渲染。
     * 由 SchematicUploadListener 调用（已通过 Minecraft.execute() 调度到渲染线程）。
     */
    public static void clearActiveSchematic(String schematicId) {
        // 关闭当前打开的材料列表 GUI（如果属于被删除的原理图）
        Minecraft mc = Minecraft.getInstance();
        if (ClientScreenAccess.currentScreen(mc) instanceof GuiMaterialList gui) {
            if (schematicId.equals(gui.getMaterialList().getSchematicId())) {
                ClientScreenAccess.showScreen(mc, null);
            }
        }


        // 清除 HUD（如果属于被删除的原理图）。
        // HUD 退场时背包监听与服务端订阅一并清理：GUI 关闭不再做这件事
        // （HUD 比 GUI 活得久，数据源必须随 HUD 存续，见 GuiMaterialList.closeGui）
        if (activeMaterialList != null && schematicId.equals(activeMaterialList.getSchematicId())) {
            LOGGER.info("原理图 {} 已删除，清除 HUD", schematicId);
            activeMaterialList = null;
            catalogRevision = -1;
            InventoryWatcher.clearContext();
            PickupModeState.clear();
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                new net.syncmaterial.syncmaterial.network.MaterialListCloseC2SPacket(schematicId));
        }
    }
}
