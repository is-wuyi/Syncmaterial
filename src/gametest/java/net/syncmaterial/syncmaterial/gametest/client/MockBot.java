package net.syncmaterial.syncmaterial.gametest.client;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.authlib.GameProfile;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.syncmaterial.syncmaterial.network.CollaborationStatusS2CPacket;

/**
 * 服务端假人：照抄原版 GameTestHelper.makeMockServerPlayerInLevel() 的
 * 构造配方（CommonListenerCookie + 匿名 ServerPlayer 子类 + Connection 包
 * EmbeddedChannel + PlayerList.placeNewPlayer）。
 *
 * 构造后假人真实存在于服务端玩家列表：玩家列表响应会包含它，
 * broadcastStatus 按参与者名字能解析到它；Connection 出站重载捕获编码前的
 * 原始 packet 对象，EmbeddedChannel 只负责假连接生命周期。
 */
final class MockBot {

    private final ServerPlayer player;
    private final List<Object> outboundPackets;

    private MockBot(ServerPlayer player, List<Object> outboundPackets) {
        this.player = player;
        this.outboundPackets = outboundPackets;
    }

    static MockBot spawn(TestDedicatedServerContext server, String name) {
        return server.computeOnServer(s -> {
            ServerLevel level = s.overworld();
            GameProfile profile = new GameProfile(UUID.randomUUID(), name);
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
            ServerPlayer bot = new ServerPlayer(s, level, cookie.gameProfile(), cookie.clientInformation()) {
                @Override
                public GameType gameMode() {
                    return GameType.CREATIVE;
                }
            };
            List<Object> captured = new CopyOnWriteArrayList<>();
            // 假人没有接收端：直接捕获编码前的出站包，不污染测试框架的真实网络计数。
            Connection botConnection = new Connection(PacketFlow.SERVERBOUND) {
                private Channel outboundChannel;

                @Override
                public void channelActive(ChannelHandlerContext context) throws Exception {
                    super.channelActive(context);
                    outboundChannel = context.channel();
                }

                @Override
                public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
                    captured.add(packet);
                    var future = flush ? outboundChannel.writeAndFlush(packet) : outboundChannel.write(packet);
                    if (listener != null) future.addListener(listener);
                }
            };
            new EmbeddedChannel(botConnection);
            s.getPlayerList().placeNewPlayer(botConnection, bot, cookie);
            return new MockBot(bot, captured);
        });
    }

    /** 假人是否已收到指定材料的协作状态广播 */
    boolean receivedCollaborationStatus(int materialId) {
        return outboundPackets.stream().anyMatch(msg ->
            msg instanceof ClientboundCustomPayloadPacket p
                && p.payload() instanceof CollaborationStatusS2CPacket status
                && status.materialId() == materialId);
    }

    /** 假人最近收到的该材料协作状态里的参与者数量；-1 表示从未收到 */
    int latestParticipantCount(int materialId) {
        for (int i = outboundPackets.size() - 1; i >= 0; i--) {
            Object msg = outboundPackets.get(i);
            if (msg instanceof ClientboundCustomPayloadPacket p
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
            var bot = s.getPlayerList().getPlayerByName(player.getName().getString());
            if (bot != null) {
                s.getPlayerList().remove(bot);
            }
            return null;
        });
    }
}
