package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorSubRegion;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;

public class ModNetworkHandlerClient {

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(MaterialStatsResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                SyncMaterialClient.openMaterialListScreen(payload.schematicId(), payload.schematicName(), payload.materials(), payload.isOwner(), payload.isMainOwner(), payload.ownerName(), payload.deputyOwners(), payload.allowSelfClaim());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(CollaborationStatusS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                SyncMaterialClient.onCollaborationStatus(payload);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(StagingAreaConfigResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                var screen = MinecraftClient.getInstance().currentScreen;

                // Phase 5: 仓库管理界面响应
                if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiWarehouseManager warehouseMgr)
                {
                    warehouseMgr.onWarehouseListResponse(payload.areas());
                    return;
                }

                // Phase 5: 仓库选择界面响应
                if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiWarehouseSelect warehouseSelect)
                {
                    warehouseSelect.onWarehouseListResponse(payload.areas());
                    return;
                }

                // Phase 5: 仓库引用弹窗响应
                if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiWarehouseRefPopup popup)
                {
                    popup.onWarehouseListResponse(payload.areas());
                    return;
                }

                if (screen instanceof GuiStagingAreaEditorNormal editor)
                {
                    editor.onServerResponse(payload);
                }
                else
                {
                    handleStagingAreaConfigResponseForWorld(payload);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(RescanStagingAreaResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                net.syncmaterial.syncmaterial.SyncMaterial.LOGGER.info("[Rescan] 收到重新扫描响应: success={}, message={}", payload.success(), payload.message());
                var screen = MinecraftClient.getInstance().currentScreen;
                if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiMaterialList materialListScreen) {
                    materialListScreen.onRescanResponse(payload.success(), payload.message());
                }
            });
        });

        // Phase 4: 负责人操作响应
        ClientPlayNetworking.registerGlobalReceiver(OwnerActionResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                var screen = MinecraftClient.getInstance().currentScreen;
                if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiMaterialList materialListScreen) {
                    materialListScreen.onOwnerActionResponse(payload.success(), payload.message(), payload.ownerName(), payload.deputyOwners(), payload.allowSelfClaim());
                }
            });
        });

        // Phase 4: 批量分配响应
        ClientPlayNetworking.registerGlobalReceiver(BatchAssignResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                var screen = MinecraftClient.getInstance().currentScreen;
                if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiMaterialList materialListScreen) {
                    materialListScreen.onBatchAssignResponse(payload.success(), payload.message());
                }
            });
        });

        // Phase 4: 踢出响应
        ClientPlayNetworking.registerGlobalReceiver(KickFromMaterialResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                var screen = MinecraftClient.getInstance().currentScreen;
                if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiMaterialList materialListScreen) {
                    materialListScreen.onKickResponse(payload.success(), payload.message());
                }
            });
        });

        // Phase 4: 玩家列表响应
        ClientPlayNetworking.registerGlobalReceiver(PlayerListResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                var screen = MinecraftClient.getInstance().currentScreen;
                if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiMaterialList materialListScreen) {
                    materialListScreen.onPlayerListResponse(payload.players());
                }
            });
        });

        // Phase 5: 仓库容器数据响应
        ClientPlayNetworking.registerGlobalReceiver(WarehouseContainerResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> handleWarehouseContainerResponse(payload));
        });
    }

    /**
     * 备货区配置响应的世界侧处理（不在任何相关界面打开时）：
     * 同步渲染器的选区数据、处理原理图删除/备货区清空的清理。
     */
    static void handleStagingAreaConfigResponseForWorld(StagingAreaConfigResponseS2CPacket payload) {
        var renderer = StagingAreaRenderer.getInstance();

        // 设置原理图名称（用于框线文字标注）
        if (payload.schematicName() != null && !payload.schematicName().isEmpty()) {
            renderer.setSchematicName(payload.schematicId(), payload.schematicName());
        }

        // 原理图被删除：清理渲染 + HUD
        if ("SCHEMATIC_DELETED".equals(payload.action())) {
            renderer.removeRenderData(payload.schematicId());
            net.syncmaterial.syncmaterial.client.SyncMaterialClient.clearActiveSchematic(payload.schematicId());
            return;
        }

        // 空 areas 列表表示该原理图的备货区已被删除
        if (payload.areas().isEmpty())
        {
            renderer.removeRenderData(payload.schematicId());
        }
        else
        {
            var selection = renderer.getSelection(payload.schematicId());
            if (selection == null)
            {
                selection = new net.syncmaterial.syncmaterial.selection.AreaSelection();
                renderer.updateSelection(payload.schematicId(), selection);
            }
            for (var area : payload.areas())
            {
                var box = new net.syncmaterial.syncmaterial.selection.Box(
                    new net.minecraft.util.math.BlockPos(area.x1(), area.y1(), area.z1()),
                    new net.minecraft.util.math.BlockPos(area.x2(), area.y2(), area.z2()),
                    area.name());
                selection.addSubRegionBox(box, true);
                selection.setServerId(area.name(), area.areaId());
            }
        }
    }

    /** 仓库容器数据响应：更新客户端容器缓存（取货模式高亮用） */
    static void handleWarehouseContainerResponse(WarehouseContainerResponseS2CPacket payload) {
        String worldId = MinecraftClient.getInstance().player != null
            ? MinecraftClient.getInstance().player.getWorld().getRegistryKey().getValue().toString()
            : "";
        net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer.getInstance()
            .updateWarehouseContainers(payload.containers(), worldId);
        net.syncmaterial.syncmaterial.SyncMaterial.LOGGER.info("[Phase5] 收到仓库容器数据: {} 个箱子", payload.containers().size());
    }
}
