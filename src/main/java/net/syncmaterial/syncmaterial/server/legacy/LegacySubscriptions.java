package net.syncmaterial.syncmaterial.server.legacy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;

public final class LegacySubscriptions implements AutoCloseable {
    private boolean closed;
    private final Map<String, Set<ServerPlayer>> materialLists = new ConcurrentHashMap<>();
    private final Map<ServerPlayer, Map<String, Set<Integer>>> warehouses = new ConcurrentHashMap<>();

    public synchronized void subscribeMaterials(ServerPlayer player, String schematicId) {
        if (closed) return;
        materialLists.computeIfAbsent(schematicId, ignored -> ConcurrentHashMap.newKeySet()).add(player);
    }

    public synchronized void unsubscribeMaterials(ServerPlayer player, String schematicId) {
        Set<ServerPlayer> players = materialLists.get(schematicId);
        if (players == null) return;
        players.remove(player);
        if (players.isEmpty()) materialLists.remove(schematicId, players);
    }

    public synchronized Set<ServerPlayer> materialSubscribers(String schematicId) {
        Set<ServerPlayer> players = materialLists.get(schematicId);
        return players == null ? Set.of() : Set.copyOf(players);
    }

    public synchronized Set<String> projects(ServerPlayer player) {
        Set<String> result = new HashSet<>();
        materialLists.forEach((id, players) -> {
            if (players.contains(player)) result.add(id);
        });
        return Set.copyOf(result);
    }

    public synchronized void subscribeWarehouses(ServerPlayer player, String schematicId, Set<Integer> ids) {
        if (closed) return;
        warehouses.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>()).put(schematicId, Set.copyOf(ids));
    }

    public synchronized void unsubscribeWarehouses(ServerPlayer player, String schematicId) {
        Map<String, Set<Integer>> projects = warehouses.get(player);
        if (projects == null) return;
        projects.remove(schematicId);
        if (projects.isEmpty()) warehouses.remove(player, projects);
    }

    public synchronized Set<Integer> warehouseIds(ServerPlayer player) {
        Map<String, Set<Integer>> projects = warehouses.get(player);
        if (projects == null) return Set.of();
        Set<Integer> result = new HashSet<>();
        projects.values().forEach(result::addAll);
        return Set.copyOf(result);
    }

    public synchronized Map<ServerPlayer, Set<Integer>> warehouseSubscribers() {
        Map<ServerPlayer, Set<Integer>> result = new HashMap<>();
        warehouses.keySet().forEach(player -> result.put(player, warehouseIds(player)));
        return Map.copyOf(result);
    }

    public synchronized void forgetPlayer(ServerPlayer player) {
        materialLists.forEach((id, players) -> {
            players.remove(player);
            if (players.isEmpty()) materialLists.remove(id, players);
        });
        warehouses.remove(player);
    }

    public synchronized void forgetProject(String schematicId) {
        materialLists.remove(schematicId);
        warehouses.forEach((player, projects) -> {
            projects.remove(schematicId);
            if (projects.isEmpty()) warehouses.remove(player, projects);
        });
    }

    public synchronized void clear() {
        materialLists.clear();
        warehouses.clear();
    }

    @Override
    public synchronized void close() {
        closed = true;
        clear();
    }
}
