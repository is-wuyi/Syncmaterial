package net.syncmaterial.syncmaterial.network;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.authlib.GameProfile;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.syncmaterial.syncmaterial.server.CollaborationManager;
import net.syncmaterial.syncmaterial.server.DatabaseQueryService;
import net.syncmaterial.syncmaterial.server.SchematicDatabase;
import net.syncmaterial.syncmaterial.server.StagingAreaManager;
import net.syncmaterial.syncmaterial.server.legacy.LegacyServerContext;
import net.syncmaterial.syncmaterial.server.legacy.LegacySubscriptions;
import net.syncmaterial.syncmaterial.server.runtime.OnlineInventoryTracker;
import net.syncmaterial.syncmaterial.server.runtime.ProjectInterestRegistry;
import net.syncmaterial.syncmaterial.server.runtime.ProjectViewTracker;

/** 显式 legacy 上下文夹具，按生产契约模拟原始连接与注册表身份校验。 */
final class LegacyNetworkTestSupport implements AutoCloseable {
    static final String DEFAULT_SCHEMATIC_NAME = "测试原理图";

    final SchematicDatabase db = mock(SchematicDatabase.class);
    final DatabaseQueryService queryService = mock(DatabaseQueryService.class);
    final CollaborationManager collaboration = mock(CollaborationManager.class);
    final StagingAreaManager staging = mock(StagingAreaManager.class);
    final LegacySubscriptions subscriptions = new LegacySubscriptions();
    final ProjectInterestRegistry interests = new ProjectInterestRegistry();
    final ProjectViewTracker views = new ProjectViewTracker(interests);
    final java.util.concurrent.atomic.AtomicLong inventoryClock =
            new java.util.concurrent.atomic.AtomicLong(1L);
    final OnlineInventoryTracker onlineInventories = new OnlineInventoryTracker(
            inventoryClock::get, Duration.ofSeconds(10));
    final LegacyProjectViewNetworkAdapter projectViews;
    final LegacyWarehouseNetworkAdapter warehouseProjection;
    final net.syncmaterial.syncmaterial.server.runtime.TeamRequestTracker teamRequests =
            new net.syncmaterial.syncmaterial.server.runtime.TeamRequestTracker();
    final Map<String, String> placementNames = new ConcurrentHashMap<>();
    final Map<String, Long> claimEpochs = new ConcurrentHashMap<>();
    final AtomicBoolean open = new AtomicBoolean(true);
    final AtomicReference<LegacyServerContext> installed = new AtomicReference<>();
    final AtomicBoolean deferOwnerExecution = new AtomicBoolean(false);
    final AtomicBoolean deferServerExecution = new AtomicBoolean(false);
    final List<Runnable> queued = new ArrayList<>();
    final List<Runnable> queuedServerTasks = new ArrayList<>();

    final MinecraftServer server;
    final ServerLevel level;
    final PlayerList playerList;
    final ServerPlayer player;
    final LegacyServerContext owner;

    LegacyNetworkTestSupport(String playerName) {
        server = mock(MinecraftServer.class);
        level = mock(ServerLevel.class);
        playerList = mock(PlayerList.class);
        player = mock(ServerPlayer.class);

        UUID playerId = UUID.randomUUID();
        when(player.getGameProfile()).thenReturn(new GameProfile(playerId, playerName));
        when(player.getName()).thenReturn(Component.literal(playerName));
        when(player.getUUID()).thenReturn(playerId);
        when(player.level()).thenReturn(level);
        when(level.dimension()).thenReturn(net.minecraft.world.level.Level.OVERWORLD);
        when(level.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(playerList);
        when(playerList.getPlayers()).thenReturn(List.of(player));
        when(playerList.getPlayer(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return player.getUUID().equals(id) ? player : null;
        });
        player.connection = mock(ServerGamePacketListenerImpl.class);

        // MinecraftServer.execute 默认同步执行，模拟主线程调度。
        doAnswer(inv -> {
            Runnable task = inv.getArgument(0);
            if (deferServerExecution.get()) {
                queuedServerTasks.add(task);
            } else {
                task.run();
            }
            return CompletableFuture.completedFuture(null);
        }).when(server).execute(any(Runnable.class));

        owner = mock(LegacyServerContext.class);
        installed.set(owner);
        when(owner.server()).thenReturn(server);
        when(owner.database()).thenReturn(db);
        when(owner.queryService()).thenReturn(queryService);
        when(owner.collaboration()).thenReturn(collaboration);
        when(owner.staging()).thenReturn(staging);
        when(owner.subscriptions()).thenReturn(subscriptions);
        when(owner.interests()).thenReturn(interests);
        when(owner.views()).thenReturn(views);
        when(owner.onlineInventories()).thenReturn(onlineInventories);
        when(owner.teamRequests()).thenReturn(teamRequests);
        when(owner.trackInventory(any(), any(), anyInt(), anyLong())).thenAnswer(inv -> {
            ServerPlayer candidate = inv.getArgument(0);
            String projectId = inv.getArgument(1);
            int materialId = inv.getArgument(2);
            long count = inv.getArgument(3);
            if (candidate != player || !open.get() || projectId == null || projectId.isBlank()
                    || materialId <= 0 || count < 0) {
                return 0L;
            }
            if (!onlineInventories.matchesConnection(candidate.getUUID(), candidate.connection)) {
                onlineInventories.connected(candidate.getUUID(), candidate.connection);
            }
            onlineInventories.update(candidate.getUUID(), projectId, materialId, count);
            return onlineInventories.connection(candidate.getUUID());
        });
        doAnswer(inv -> {
            onlineInventories.forget(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            return null;
        }).when(owner).forgetInventory(any(), any(), anyInt());
        doAnswer(inv -> {
            UUID id = inv.getArgument(0);
            Object connection = inv.getArgument(1);
            if (connection != null && !onlineInventories.matchesConnection(id, connection)) {
                return null;
            }
            if (connection == null && playerList.getPlayer(id) != null
                    && playerList.getPlayer(id).connection != null) {
                return null;
            }
            onlineInventories.disconnected(id, onlineInventories.connection(id));
            return null;
        }).when(owner).disconnectInventory(any(), any());
        doAnswer(inv -> {
            String name = inv.getArgument(0);
            String project = inv.getArgument(1);
            int material = inv.getArgument(2);
            claimEpochs.put(name + "\u0000" + project + "\u0000" + material,
                    System.nanoTime());
            return null;
        }).when(owner).markClaimEpoch(any(), any(), anyInt());
        when(owner.acceptsObservation(any(), any(), anyInt(), anyLong())).thenAnswer(inv -> {
            Long admitted = claimEpochs.get(inv.getArgument(0) + "\u0000"
                    + inv.getArgument(1) + "\u0000" + inv.getArgument(2));
            return admitted == null || (long) inv.getArgument(3) >= admitted;
        });
        doAnswer(inv -> {
            claimEpochs.remove(inv.getArgument(0) + "\u0000" + inv.getArgument(1)
                    + "\u0000" + inv.getArgument(2));
            return null;
        }).when(owner).clearClaimEpoch(any(), any(), anyInt());
        doAnswer(inv -> {
            String prefix = inv.getArgument(0) + "\u0000";
            claimEpochs.keySet().removeIf(key -> key.startsWith(prefix));
            return null;
        }).when(owner).clearPlayerClaimEpochs(any());
        when(owner.findOnlinePlayer(any())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return name != null && name.equals(player.getName().getString()) && open.get()
                    ? player : null;
        });
        projectViews = new LegacyProjectViewNetworkAdapter(owner);
        warehouseProjection = new LegacyWarehouseNetworkAdapter(owner);
        when(owner.projectViews()).thenReturn(projectViews);
        when(owner.warehouseProjection()).thenReturn(warehouseProjection);
        when(owner.isOpen()).thenAnswer(inv -> open.get());
        when(owner.isCurrent()).thenAnswer(inv -> open.get() && installed.get() == owner);
        when(owner.isConnected(any(ServerPlayer.class))).thenAnswer(inv -> {
            ServerPlayer candidate = inv.getArgument(0);
            return open.get() && installed.get() == owner && candidate == player
                    && player.connection != null
                    && playerList.getPlayer(player.getUUID()) == player;
        });
        when(owner.placementNames()).thenAnswer(inv -> Map.copyOf(placementNames));
        doAnswer(inv -> {
            if (owner.isCurrent()) {
                placementNames.put(inv.getArgument(0), inv.getArgument(1));
            }
            return null;
        }).when(owner).setPlacementName(any(), any());
        doAnswer(inv -> {
            String id = inv.getArgument(0);
            placementNames.remove(id);
            return null;
        }).when(owner).forgetPlacementName(any());

        doAnswer(inv -> {
            runOwned(null, inv.getArgument(0), null);
            return null;
        }).when(owner).execute(any(Runnable.class));
        doAnswer(inv -> {
            ServerPlayer target = inv.getArgument(0);
            runOwned(target, inv.getArgument(1), target == null ? null : target.connection);
            return null;
        }).when(owner).execute(any(ServerPlayer.class), any(Runnable.class));

        placementNames.put("s1", DEFAULT_SCHEMATIC_NAME);
        ProtocolHandshake.registerServer(server);
    }

    private void runOwned(ServerPlayer target, Runnable action, Object originalConnection) {
        if (!owner.isCurrent()) {
            return;
        }
        if (target != null && !owner.isConnected(target)) {
            return;
        }
        Runnable guarded = () -> {
            if (!owner.isCurrent()) {
                return;
            }
            if (target != null
                    && (target.connection != originalConnection || !owner.isConnected(target))) {
                return;
            }
            action.run();
        };
        if (deferOwnerExecution.get()) {
            queued.add(guarded);
        } else {
            guarded.run();
        }
    }

    void handshake() {
        ProtocolHandshake.recordHandshake(server, player, ProtocolVersion.CURRENT, "test");
    }

    void closeOwnerAndInstall(LegacyServerContext replacement) {
        open.set(false);
        installed.set(replacement);
    }

    void flushQueued() {
        List<Runnable> copy = List.copyOf(queued);
        queued.clear();
        for (Runnable task : copy) {
            task.run();
        }
    }

    void flushQueuedServerTasks() {
        List<Runnable> copy = List.copyOf(queuedServerTasks);
        queuedServerTasks.clear();
        for (Runnable task : copy) {
            task.run();
        }
    }

    @Override
    public void close() {
        open.set(false);
        installed.set(null);
        queued.clear();
        queuedServerTasks.clear();
        projectViews.close();
        views.close();
        interests.close();
        onlineInventories.clear();
        ProtocolHandshake.clear(server);
    }

    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }
}
