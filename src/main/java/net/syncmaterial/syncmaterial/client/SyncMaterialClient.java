package net.syncmaterial.syncmaterial.client;

import java.util.List;
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
import net.syncmaterial.syncmaterial.client.session.ClientProjectCoordinator;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;
import org.slf4j.Logger;

public class SyncMaterialClient implements ClientModInitializer {
    private static final Logger LOGGER = SyncMaterial.LOGGER;
    private static final int PICKUP_RECOMPUTE_INTERVAL = 10;

    private static net.syncmaterial.syncmaterial.client.session.ClientConnectionSession session() {
        return net.syncmaterial.syncmaterial.client.session.ClientSessionManager.current().orElse(null);
    }

    public static ClientProjectCoordinator projectCoordinator() {
        var session = session();
        return session == null || !session.isOpen() ? null : session.projectCoordinator();
    }

    private static SyncMaterialList activeMaterialList() {
        ClientProjectCoordinator coordinator = projectCoordinator();
        return coordinator == null ? null : coordinator.materialList();
    }

    public static SyncMaterialList getActiveMaterialList() {
        return activeMaterialList();
    }

    /** Live container highlighting uses the same inventory instant as its slot scan. */
    public static java.util.Map<String, Long> livePickupNeeds() {
        if (!net.syncmaterial.syncmaterial.client.session.ClientSessionManager.pickup().isActive()
                || activeMaterialList() == null) {
            return java.util.Map.of();
        }
        return PickupModeState.computeNeeds(activeMaterialList().getMaterialsAll(),
                InventoryScanner.liveCountsByItemId(Minecraft.getInstance().player));
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("SyncMaterial Client initialized!");

        Configs.loadFromFile();
        fi.dy.masa.malilib.config.ConfigManager.getInstance()
                .registerConfigHandler(SyncMaterial.MOD_ID, new Configs());

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

        // Network disconnect may cancel the queued client tasks; the permanent tick is the backstop.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (TeamManagementClient.finishPendingDisconnect()) clearDisconnectedState();
            ProjectViewClient.current().ifPresent(ProjectViewClient::tick);
            net.syncmaterial.syncmaterial.client.gui.EditorResponseRouter.tick();
            TeamManagementClient.tick();
            ClientProjectCoordinator coordinator = projectCoordinator();
            if (coordinator != null) coordinator.reconcileScreen(ClientScreenAccess.currentScreen(client));
        });

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var session = session();
            if (session == null || !session.pickup().isActive()
                    || session.nextPickupTick() % PICKUP_RECOMPUTE_INTERVAL != 0) return;
            ClientProjectCoordinator coordinator = session.projectCoordinator();
            if (coordinator != null) coordinator.recomputePickup();
        });

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var session = session();
            if (session == null) return;
            if (client.player == null || client.player.level() == null) {
                session.dimensions().observe(null);
                return;
            }
            String worldId = net.syncmaterial.syncmaterial.compat.McCompat.dimensionId(client.player.level());
            if (session.dimensions().observe(worldId)) onClientDimensionChanged();
        });

        //? if >=26.2 {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("syncmaterial", "material_list_hud"),
                (drawContext, tickDelta) -> {
                    if (activeMaterialList() != null && Configs.Generic.HUD_ENABLED.getBooleanValue()
                            && activeMaterialList().getHudRenderer().getShouldRender()) {
                        fi.dy.masa.malilib.config.HudAlignment alignment =
                                ((HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue()).toMalilib();
                        int x = Configs.Hud.HUD_X_OFFSET.getIntegerValue();
                        int y = Configs.Hud.HUD_Y_OFFSET.getIntegerValue();
                        activeMaterialList().getHudRenderer().render(
                                fi.dy.masa.malilib.render.GuiContext.fromGuiGraphics(drawContext), x, y, alignment);
                    }
                });
        //?} else {
        /*net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (activeMaterialList() != null && Configs.Generic.HUD_ENABLED.getBooleanValue()
                    && activeMaterialList().getHudRenderer().getShouldRender()) {
                fi.dy.masa.malilib.config.HudAlignment alignment =
                        ((HudAlignmentOption) Configs.Hud.HUD_ALIGNMENT.getOptionListValue()).toMalilib();
                int x = Configs.Hud.HUD_X_OFFSET.getIntegerValue();
                int y = Configs.Hud.HUD_Y_OFFSET.getIntegerValue();
                activeMaterialList().getHudRenderer().render(drawContext, x, y, alignment);
            }
        });
        *///?}

        fi.dy.masa.malilib.event.RenderEventHandler.getInstance().registerWorldLastRenderer(
                StagingAreaRenderer.getInstance());

        fi.dy.masa.malilib.event.InputEventHandler.getInputManager().registerKeyboardInputHandler(
                new fi.dy.masa.malilib.hotkeys.IKeyboardInputHandler() {
                    //? if >=26.2 {
                    @Override
                    public boolean onKeyInput(net.minecraft.client.input.KeyEvent event, boolean pressed) {
                        return StagingAreaSelector.getInstance().onKeyInput(event.key(), 0, 0, pressed);
                    }
                    //?} else {
                    /*@Override
                    public boolean onKeyInput(int keyCode, int scanCode, int modifiers, boolean pressed) {
                        return StagingAreaSelector.getInstance().onKeyInput(keyCode, scanCode, modifiers, pressed);
                    }
                    *///?}
                });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client ->
                StagingAreaSelector.getInstance().onTick());

        //? if >=26.2 {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("syncmaterial", "staging_selector_hud"),
                (drawContext, tickDelta) -> StagingAreaSelector.getInstance().onRenderHUD(
                        fi.dy.masa.malilib.render.GuiContext.fromGuiGraphics(drawContext)));
        //?} else {
        /*net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            StagingAreaSelector.getInstance().onRenderHUD(drawContext);
        });
        *///?}

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // INIT bound receivers use this same original handler; the sender cannot fall through to a new connection.
            var session = net.syncmaterial.syncmaterial.client.session.ClientSessionManager.join(
                    handler, sender::sendPacket);
            sender.sendPacket(new net.syncmaterial.syncmaterial.network.HelloC2SPacket(
                    net.syncmaterial.syncmaterial.network.ProtocolVersion.CURRENT,
                    SyncMaterial.getModVersion()));
            net.syncmaterial.syncmaterial.network.ClientProtocolState.onHandshakeSent();
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            long token = TeamManagementClient.invalidateConnection(handler);
            if (token < 0) return;
            client.execute(() -> {
                if (TeamManagementClient.finishDisconnect(token)) clearDisconnectedState();
            });
        });

        net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback.EVENT.register((mc, player, clickCount) ->
                StagingAreaSelector.getInstance().isActive());
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
                StagingAreaSelector.getInstance().isActive()
                        ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) ->
                StagingAreaSelector.getInstance().isActive()
                        ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
    }

    private static void clearDisconnectedState() {
        net.syncmaterial.syncmaterial.client.session.ClientSessionManager.discard();
    }

    public static boolean requestMaterialList(String schematicId) {
        ClientProjectCoordinator coordinator = projectCoordinator();
        return coordinator != null && coordinator.requestMaterialList(schematicId);
    }

    public static void openMaterialListScreen(String schematicId, String schematicName,
            List<MaterialEntry> materials, boolean isOwner, boolean isMainOwner, String ownerName,
            List<String> deputyOwners, boolean allowSelfClaim) {
        ClientProjectCoordinator coordinator = projectCoordinator();
        if (coordinator == null) return;
        if (net.syncmaterial.syncmaterial.network.ClientProtocolState.serverSupports(
                net.syncmaterial.syncmaterial.network.ProtocolVersion.SCOPED_PROJECT_VIEWS)) {
            // Direct callers use the real project/view path too; a local payload is not a v5 subscription.
            coordinator.requestMaterialList(schematicId);
        } else {
            coordinator.presentMaterialList(schematicId, schematicName, materials, isOwner,
                    isMainOwner, ownerName, deputyOwners, allowSelfClaim);
        }
    }

    /** Network-only legacy response; a protocol-5 peer cannot inject the old core model. */
    public static void onLegacyMaterialStats(String schematicId, String schematicName,
            List<MaterialEntry> materials, boolean isOwner, boolean isMainOwner, String ownerName,
            List<String> deputyOwners, boolean allowSelfClaim) {
        ClientProjectCoordinator coordinator = projectCoordinator();
        if (coordinator != null) coordinator.acceptLegacyMaterialList(schematicId, schematicName,
                materials, isOwner, isMainOwner, ownerName, deputyOwners, allowSelfClaim);
    }

    public static void onMaterialCatalogUpdate(
            net.syncmaterial.syncmaterial.network.MaterialCatalogUpdateS2CPacket update) {
        ClientProjectCoordinator coordinator = projectCoordinator();
        if (coordinator != null) coordinator.managementCatalog(update);
    }

    public static void onCollaborationStatus(CollaborationStatusS2CPacket status) {
        ClientProjectCoordinator coordinator = projectCoordinator();
        if (coordinator != null) coordinator.collaboration(status);
    }

    /** Compatibility for legacy broadcasts whose connection was checked on receipt. */
    public static void onCollaborationStatus(long connectionToken, CollaborationStatusS2CPacket status) {
        if (connectionToken != TeamManagementClient.connectionToken()) return;
        onCollaborationStatus(status);
    }

    public static void onAreaInventorySnapshot(
            net.syncmaterial.syncmaterial.network.AreaInventorySnapshotS2CPacket snapshot) {
        ClientProjectCoordinator coordinator = projectCoordinator();
        if (coordinator != null) coordinator.areaInventory(snapshot);
    }

    public static void onClientDimensionChanged() {
        ClientProjectCoordinator coordinator = projectCoordinator();
        if (coordinator != null) coordinator.dimensionChanged();
    }

    public static void clearActiveSchematic(String schematicId) {
        ClientProjectCoordinator coordinator = projectCoordinator();
        if (coordinator != null) coordinator.deleted(schematicId);
    }
}
