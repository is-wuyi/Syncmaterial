package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.authlib.GameProfile;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameMode;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;

/**
 * 服务端假人：照抄原版 TestContext.createMockCreativeServerPlayerInWorld() 的
 * 构造配方（ConnectedClientData + 匿名 ServerPlayerEntity 子类 + ClientConnection 包
 * EmbeddedChannel + PlayerManager.onPlayerConnect）。
 *
 * 构造后假人真实存在于服务端玩家列表：玩家列表响应会包含它，
 * broadcastStatus 按参与者名字能解析到它；EmbeddedChannel 上挂的出站
 * 捕获 handler 能拿到真实发给它的每一个包（编码前的原始 packet 对象）。
 */
final class MockBot {

    private final ServerPlayerEntity player;
    private final List<Object> outboundPackets;

    private MockBot(ServerPlayerEntity player, List<Object> outboundPackets) {
        this.player = player;
        this.outboundPackets = outboundPackets;
    }

    static MockBot spawn(TestDedicatedServerContext server, String name) {
        return server.computeOnServer(s -> {
            ServerWorld level = s.getOverworld();
            GameProfile profile = new GameProfile(UUID.randomUUID(), name);
            ConnectedClientData cookie = ConnectedClientData.createDefault(profile, false);
            ServerPlayerEntity bot = new ServerPlayerEntity(s, level, cookie.gameProfile(), cookie.syncedOptions()) {
                @Override
                public GameMode getGameMode() {
                    return GameMode.CREATIVE;
                }
            };
            List<Object> captured = new CopyOnWriteArrayList<>();
            ClientConnection botConnection = new ClientConnection(NetworkSide.SERVERBOUND);
            EmbeddedChannel botChannel = new EmbeddedChannel(botConnection);
            botChannel.pipeline().addLast("mock-bot-capture", new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    captured.add(msg);
                    super.write(ctx, msg, promise);
                }
            });
            s.getPlayerManager().onPlayerConnect(botConnection, bot, cookie);
            return new MockBot(bot, captured);
        });
    }

    /** 假人是否已收到指定材料的协作状态广播 */
    boolean receivedCollaborationStatus(int materialId) {
        return outboundPackets.stream().anyMatch(msg ->
            msg instanceof CustomPayloadS2CPacket p
                && p.payload() instanceof CollaborationStatusS2CPacket status
                && status.materialId() == materialId);
    }

    /** 假人最近收到的该材料协作状态里的参与者数量；-1 表示从未收到 */
    int latestParticipantCount(int materialId) {
        for (int i = outboundPackets.size() - 1; i >= 0; i--) {
            Object msg = outboundPackets.get(i);
            if (msg instanceof CustomPayloadS2CPacket p
                && p.payload() instanceof CollaborationStatusS2CPacket status
                && status.materialId() == materialId) {
                return status.participants().size();
            }
        }
        return -1;
    }

    /** 把假人移出服务端玩家列表 */
    void despawn(TestDedicatedServerContext server) {
        server.computeOnServer(s -> {
            var bot = s.getPlayerManager().getPlayer(player.getName().getString());
            if (bot != null) {
                s.getPlayerManager().remove(bot);
            }
            return null;
        });
    }
}
