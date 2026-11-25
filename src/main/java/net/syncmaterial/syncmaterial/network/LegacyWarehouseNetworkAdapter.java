package net.syncmaterial.syncmaterial.network;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.syncmaterial.syncmaterial.SyncMaterial;
import net.syncmaterial.syncmaterial.compat.McCompat;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;

/** Owner-bound legacy pickup/container and warehouse-wireframe projection. */
public final class LegacyWarehouseNetworkAdapter {
    private final LegacyServerContext owner;

    public LegacyWarehouseNetworkAdapter(LegacyServerContext owner) {
        this.owner = Objects.requireNonNull(owner);
    }

    /** Subscribes one project and sends its initial union, including an empty snapshot. */
    public void subscribe(ServerPlayer player, String schematicId) {
        if (!owner.isCurrent() || player == null) return;
        StagingAreaManager manager = owner.staging();
        if (manager == null) return;
        var warehouses = manager.getWarehousesForSchematic(schematicId);
        Set<Integer> warehouseIds = warehouses.stream()
                .map(StagingAreaManager.Warehouse::id)
                .collect(Collectors.toSet());
        owner.subscriptions().subscribeWarehouses(player, schematicId, warehouseIds);
        sendContainers(player, owner.subscriptions().warehouseIds(player), true);
        SyncMaterial.LOGGER.info("[Phase5] 玩家 {} 订阅取货模式: 原理图 {}, 仓库 {}",
                player.getName().getString(), schematicId, warehouseIds);
    }

    public void unsubscribe(ServerPlayer player, String schematicId) {
        if (!owner.isCurrent() || player == null || owner.staging() == null) return;
        owner.subscriptions().unsubscribeWarehouses(player, schematicId);
        SyncMaterial.LOGGER.info("[Phase5] 玩家 {} 取消订阅取货模式: 原理图 {}",
                player.getName().getString(), schematicId);
    }

    /** Publishes changed containers; empty unions do not produce unsolicited empty packets. */
    public void publishContainers() {
        if (!owner.isCurrent()) return;
        for (var entry : owner.subscriptions().warehouseSubscribers().entrySet()) {
            sendContainers(entry.getKey(), entry.getValue(), false);
        }
    }

    /** Refreshes only existing per-project pickup subscriptions and always pushes the result. */
    public List<StagingAreaConfigResponseS2CPacket.AreaInfo> refreshProjectReferences(String schematicId) {
        if (!owner.isCurrent()) return List.of();
        StagingAreaManager manager = owner.staging();
        if (manager == null) return List.of();
        var warehouses = manager.getWarehousesForSchematic(schematicId);
        Set<Integer> warehouseIds = warehouses.stream()
                .map(StagingAreaManager.Warehouse::id)
                .collect(Collectors.toSet());
        Set<ServerPlayer> affected = owner.subscriptions().refreshWarehouses(schematicId, warehouseIds);
        for (ServerPlayer subscriber : affected) {
            sendContainers(subscriber, owner.subscriptions().warehouseIds(subscriber), true);
        }
        return StagingAreaManager.buildWarehouseInfos(warehouses);
    }

    /** Sends the current warehouse definitions and material-list references to one player. */
    public void sendAreas(ServerPlayer player) {
        if (!owner.isCurrent()) return;
        StagingAreaManager manager = owner.staging();
        if (manager == null) return;
        try {
            sendAreas(player, StagingAreaManager.buildWarehouseInfos(manager.getAllWarehouses()));
        } catch (Exception error) {
            SyncMaterial.LOGGER.error("[Phase5] 推送仓库区域数据失败", error);
        }
    }

    /** Broadcasts warehouse definitions; material-list references remain per-player. */
    public void broadcastAreas() {
        if (!owner.isCurrent()) return;
        StagingAreaManager manager = owner.staging();
        if (manager == null) return;
        try {
            var server = owner.server();
            var players = server == null || server.getPlayerList() == null
                    ? null : server.getPlayerList().getPlayers();
            if (players == null) return;
            var infos = StagingAreaManager.buildWarehouseInfos(manager.getAllWarehouses());
            for (ServerPlayer player : players) {
                sendAreas(player, infos);
            }
        } catch (Exception error) {
            SyncMaterial.LOGGER.error("[Phase5] 广播仓库区域数据失败", error);
        }
    }

    private void sendContainers(ServerPlayer player, Set<Integer> warehouseIds, boolean sendEmpty) {
        if ((!sendEmpty && warehouseIds.isEmpty()) || !canSend(player)) return;
        StagingAreaManager manager = owner.staging();
        if (manager == null) return;
        List<WarehouseContainerResponseS2CPacket.ContainerEntry> containers =
                manager.getContainerEntriesForWarehouses(warehouseIds,
                        McCompat.dimensionId(player.level()));
        ServerPlayNetworking.send(player, new WarehouseContainerResponseS2CPacket(containers));
    }

    private void sendAreas(ServerPlayer player,
                           List<StagingAreaConfigResponseS2CPacket.AreaInfo> warehouseInfos) {
        if (!canSend(player)) return;
        Set<Integer> referenced = new HashSet<>();
        for (String schematicId : owner.subscriptions().projects(player)) {
            for (var warehouse : owner.staging().getWarehousesForSchematic(schematicId)) {
                referenced.add(warehouse.id());
            }
        }
        ServerPlayNetworking.send(player,
                new WarehouseAreaResponseS2CPacket(warehouseInfos, List.copyOf(referenced)));
    }

    private boolean canSend(ServerPlayer player) {
        return owner.isCurrent() && player != null && owner.isConnected(player)
                && ProtocolHandshake.hasHandshaked(player);
    }
}
