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
import net.minecraft.network.Connection;
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
 * broadcastStatus 按参与者名字能解析到它；EmbeddedChannel 上挂的出站
 * 捕获 handler 能拿到真实发给它的每一个包（编码前的原始 packet 对象）。
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
            Connection botConnection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel botChannel = new EmbeddedChannel(botConnection);
            botChannel.pipeline().addLast("mock-bot-capture", new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    captured.add(msg);
                    super.write(ctx, msg, promise);
                }
            });
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

    /** 把假人移出服务端玩家列表 */
    void despawn(TestDedicatedServerContext server) {
        server.computeOnServer(s -> {
            var bot = s.getPlayerList().getPlayer(player.getName().getString());
            if (bot != null) {
                s.getPlayerList().remove(bot);
            }
            return null;
        });
    }
}
