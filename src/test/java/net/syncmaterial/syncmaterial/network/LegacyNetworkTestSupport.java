package net.syncmaterial.syncmaterial.network;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

/** 显式 legacy 上下文夹具，按生产契约模拟原始连接与注册表身份校验。 */
final class LegacyNetworkTestSupport implements AutoCloseable {
    static final String DEFAULT_SCHEMATIC_NAME = "测试原理图";

    final SchematicDatabase db = mock(SchematicDatabase.class);
    final DatabaseQueryService queryService = mock(DatabaseQueryService.class);
    final CollaborationManager collaboration = mock(CollaborationManager.class);
    final StagingAreaManager staging = mock(StagingAreaManager.class);
    final LegacySubscriptions subscriptions = new LegacySubscriptions();
    final Map<String, String> placementNames = new ConcurrentHashMap<>();
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
        ProtocolHandshake.clear(server);
    }

    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }
}
