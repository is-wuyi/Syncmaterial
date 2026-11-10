package net.syncmaterial.syncmaterial.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 双版本 payload 类型注册入口。
 *
 * <p>1.21.7/1.21.8 的 Fabric API 使用 {@code playC2S()/playS2C()}，
 * 26.2 改为 {@code serverboundPlay()/clientboundPlay()}。除方法名外签名一致，
 * 因此只在这里做条件转发；注册列表与 codec 仍各只有一份。</p>
 */
public final class PayloadRegistrar {
    private PayloadRegistrar() {
    }

    public static <T extends CustomPacketPayload> void c2s(
            CustomPacketPayload.Type<T> id,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        //? if >=26.2 {
        PayloadTypeRegistry.serverboundPlay().register(id, codec);
        //?} else {
        /*PayloadTypeRegistry.playC2S().register(id, codec);
        *///?}
    }

    public static <T extends CustomPacketPayload> void s2c(
            CustomPacketPayload.Type<T> id,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        //? if >=26.2 {
        PayloadTypeRegistry.clientboundPlay().register(id, codec);
        //?} else {
        /*PayloadTypeRegistry.playS2C().register(id, codec);
        *///?}
    }
}
