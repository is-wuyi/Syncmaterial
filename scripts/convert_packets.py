#!/usr/bin/env python3
"""将 network 包下的 packet 文件转换为 Stonecutter 双版本分支。

26.2 分支：官方命名 API（CustomPacketPayload / StreamCodec / RegistryFriendlyByteBuf）
旧版分支：保持现有 Yarn API 原样。
字段顺序与语义完全不变（协议冻结）。
"""
import sys
from pathlib import Path

SRC = Path("/Volumes/赤石/Syncmaterial-1/src/main/java/net/syncmaterial/syncmaterial/network")

# 26.2 分支的机械替换（顺序敏感：先长后短）
REPLACEMENTS = [
    ("import net.minecraft.network.packet.CustomPayload;",
     "import net.minecraft.network.protocol.common.custom.CustomPacketPayload;"),
    ("import net.minecraft.network.RegistryByteBuf;",
     "import net.minecraft.network.RegistryFriendlyByteBuf;"),
    ("import net.minecraft.network.codec.PacketCodec;",
     "import net.minecraft.network.codec.StreamCodec;"),
    ("import net.minecraft.network.codec.PacketCodecs;",
     "import net.minecraft.network.codec.ByteBufCodecs;"),
    # 裸类型引用（import 替换后正文里还会出现短名）
    ("RegistryByteBuf", "RegistryFriendlyByteBuf"),
    ("PacketCodec.tuple", "StreamCodec.composite"),
    ("PacketCodec<", "StreamCodec<"),
    ("PacketCodecs.STRING", "ByteBufCodecs.STRING_UTF8"),
    ("PacketCodecs.BOOLEAN", "ByteBufCodecs.BOOL"),
    ("PacketCodecs.VAR_INT", "ByteBufCodecs.VAR_INT"),
    ("PacketCodecs.INTEGER", "ByteBufCodecs.VAR_INT"),
    ("PacketCodecs.toList()", "ByteBufCodecs.collection(ByteBufCodecs.VAR_INT)"),
    ("CustomPayload.Id<", "CustomPacketPayload.Type<"),
    ("new CustomPayload.Id<>", "new CustomPacketPayload.Type<>"),
    ("CustomPayload.Id<? extends CustomPayload>", "CustomPacketPayload.Type<? extends CustomPacketPayload>"),
    ("? extends CustomPayload", "? extends CustomPacketPayload"),
    ("implements CustomPayload", "implements CustomPacketPayload"),
    # getId() 方法：签名与注解一起换
    ("    @Override\n    public Id<? extends CustomPayload> getId() {",
     "    @Override\n    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {"),
    ("public Id<? extends CustomPayload> getId()",
     "public CustomPacketPayload.Type<? extends CustomPacketPayload> type()"),
]

def convert(text: str) -> str:
    new = text
    for old, repl in REPLACEMENTS:
        new = new.replace(old, repl)
    return new

def wrap(old_body: str, new_body: str) -> str:
    # Stonecutter 对不匹配的分支直接整段移除，因此两侧都无需注释包裹，
    # 含 javadoc（*/）的源码也能安全处理。
    return (
        "//? if >=26 {\n"
        + new_body.rstrip("\n")
        + "\n//?} else {\n"
        + old_body.rstrip("\n")
        + "\n//?}\n"
    )

def main():
    converted = skipped = failed = 0
    for f in sorted(SRC.glob("*Packet.java")):
        text = f.read_text(encoding="utf-8")
        if "//? if" in text:
            skipped += 1
            continue
        try:
            new_body = convert(text)
            if new_body == text:
                print(f"[无变化] {f.name}")
                failed += 1
                continue
            f.write_text(wrap(text, new_body), encoding="utf-8")
            converted += 1
            print(f"[完成] {f.name}")
        except Exception as e:
            failed += 1
            print(f"[失败] {f.name}: {e}", file=sys.stderr)
    print(f"\n转换 {converted}，跳过(已处理) {skipped}，未处理/失败 {failed}")

if __name__ == "__main__":
    main()
