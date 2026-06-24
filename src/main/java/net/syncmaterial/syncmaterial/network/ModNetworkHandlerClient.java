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

                if (screen instanceof GuiStagingAreaEditorNormal editor)
                {
                    editor.onServerResponse(payload);
                }
                else
                {
                    var renderer = StagingAreaRenderer.getInstance();

                    // 设置原理图名称（用于框线文字标注）
                    if (payload.schematicName() != null && !payload.schematicName().isEmpty()) {
                        renderer.setSchematicName(payload.schematicId(), payload.schematicName());
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
            context.client().execute(() -> {
                // TODO P5-9: 更新客户端容器缓存 + 触发渲染
                net.syncmaterial.syncmaterial.SyncMaterial.LOGGER.info("[Phase5] 收到仓库容器数据: {} 个箱子", payload.containers().size());
            });
        });
    }
}
