package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.syncmaterial.syncmaterial.client.SyncMaterialClient;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorNormal;
import net.syncmaterial.syncmaterial.client.gui.GuiStagingAreaEditorSubRegion;
import net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer;

public class ModNetworkHandlerClient {

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(HelloS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                ClientProtocolState.onHandshakeResponse(
                        payload.protocolVersion(), payload.modVersion(), payload.accepted());

                if (!payload.accepted()) {
                    fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                            fi.dy.masa.malilib.gui.Message.MessageType.ERROR,
                            fi.dy.masa.malilib.util.StringUtils.translate(
                                    "syncmaterial.message.protocol.client_too_old",
                                    payload.modVersion()));
                } else if (ClientProtocolState.isServerNewer()) {
                    fi.dy.masa.malilib.util.InfoUtils.showGuiOrActionBarMessage(
                            fi.dy.masa.malilib.gui.Message.MessageType.WARNING,
                            fi.dy.masa.malilib.util.StringUtils.translate(
                                    "syncmaterial.message.protocol.server_newer",
                                    payload.modVersion()));
                }
            });
        });

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
                var screen = currentScreen();

                // Phase 5: 仓库管理界面响应
                if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiWarehouseManager warehouseMgr)
                {
                    warehouseMgr.onWarehouseListResponse(payload.areas());
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
                var screen = currentScreen();
                if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiMaterialList materialListScreen) {
                    materialListScreen.onRescanResponse(payload.success(), payload.message());
                }
            });
        });

        // Phase 4: 负责人操作响应（选择弹窗打开中 → 弹窗；否则 → 材料列表右栏兜底）
        ClientPlayNetworking.registerGlobalReceiver(OwnerActionResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                var screen = currentScreen();
                if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog select) {
                    select.onOwnerActionResponse(payload.success(), payload.message(), payload.ownerName(), payload.deputyOwners(), payload.allowSelfClaim());
                } else if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiMaterialList materialListScreen) {
                    materialListScreen.onOwnerActionResponse(payload.success(), payload.message(), payload.ownerName(), payload.deputyOwners(), payload.allowSelfClaim());
                }
            });
        });

        // Phase 4: 批量分配响应
        ClientPlayNetworking.registerGlobalReceiver(BatchAssignResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                var screen = currentScreen();
                if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog select) {
                    select.onBatchAssignResponse(payload.success(), payload.message());
                } else if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiMaterialList materialListScreen) {
                    materialListScreen.onBatchAssignResponse(payload.success(), payload.message());
                }
            });
        });

        // Phase 4: 踢出响应
        ClientPlayNetworking.registerGlobalReceiver(KickFromMaterialResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                var screen = currentScreen();
                if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog select) {
                    select.onKickResponse(payload.success(), payload.message());
                } else if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiMaterialList materialListScreen) {
                    materialListScreen.onKickResponse(payload.success(), payload.message());
                }
            });
        });

        // Phase 4: 玩家列表响应（请求必由玩家选择弹窗发起，直接路由给它）
        ClientPlayNetworking.registerGlobalReceiver(PlayerListResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> {
                var screen = currentScreen();
                if (screen instanceof net.syncmaterial.syncmaterial.client.gui.GuiPlayerSelectDialog select) {
                    select.onPlayerListResponse(payload.players());
                }
            });
        });

        // Phase 5: 仓库容器数据响应
        ClientPlayNetworking.registerGlobalReceiver(WarehouseContainerResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> handleWarehouseContainerResponse(payload));
        });

        // Phase 5: 仓库区域线框数据（全局广播）
        ClientPlayNetworking.registerGlobalReceiver(WarehouseAreaResponseS2CPacket.ID, (payload, context) -> {
            context.client().execute(() -> handleWarehouseAreaResponse(payload));
        });
    }

    /**
     * 当前打开的界面；客户端未完全初始化（gui 为 null）时返回 null。
     */
    static net.minecraft.client.gui.screens.Screen currentScreen() {
        var mc = Minecraft.getInstance();
        return mc == null || mc.gui == null ? null : mc.gui.screen();
    }

    /**
     * 备货区配置响应的世界侧处理（不在任何相关界面打开时）：
     * 同步渲染器的选区数据、处理原理图删除/备货区清空的清理。
     */
    static void handleStagingAreaConfigResponseForWorld(StagingAreaConfigResponseS2CPacket payload) {
        var renderer = StagingAreaRenderer.getInstance();

        // 仓库类响应的 schematicId 是空串，其 areas 是仓库而非备货区。
        // 若不拦截，仓库会被写进 key 为 "" 的 selections 并按备货区颜色渲染。
        if (payload.schematicId() == null || payload.schematicId().isEmpty()) {
            return;
        }

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
                    new net.minecraft.core.BlockPos(area.x1(), area.y1(), area.z1()),
                    new net.minecraft.core.BlockPos(area.x2(), area.y2(), area.z2()),
                    area.name());
                selection.addSubRegionBox(box, true);
                selection.setServerId(area.name(), area.areaId());
            }
        }
    }

    /** 仓库区域数据响应：更新客户端仓库线框缓存 */
    static void handleWarehouseAreaResponse(WarehouseAreaResponseS2CPacket payload) {
        StagingAreaRenderer.getInstance()
            .updateWarehouseAreas(payload.warehouses(), payload.referencedIds());
        net.syncmaterial.syncmaterial.SyncMaterial.LOGGER.debug(
            "[Phase5] 收到仓库区域数据: {} 个仓库，其中 {} 个被当前原理图引用",
            payload.warehouses().size(), payload.referencedIds().size());
    }

    /** 仓库容器数据响应：更新客户端容器缓存（取货模式高亮用） */
    static void handleWarehouseContainerResponse(WarehouseContainerResponseS2CPacket payload) {
        String worldId = Minecraft.getInstance().player != null
            ? Minecraft.getInstance().player.level().dimension().identifier().toString()
            : "";
        net.syncmaterial.syncmaterial.client.render.StagingAreaRenderer.getInstance()
            .updateWarehouseContainers(payload.containers(), worldId);
        net.syncmaterial.syncmaterial.SyncMaterial.LOGGER.info("[Phase5] 收到仓库容器数据: {} 个箱子", payload.containers().size());
    }
}
