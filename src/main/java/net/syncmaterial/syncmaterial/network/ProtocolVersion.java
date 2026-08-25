package net.syncmaterial.syncmaterial.network;

/**
 * 协议版本号，与 mod 版本号完全独立。
 *
 * 只有网络包的线格式或语义真正发生变化时才 +1：
 * 新增包、给 StagingAreaConfigC2SPacket.action 加新取值、
 * 或改变服务端对已有包的处理语义。
 * 纯 UI 调整、渲染修复、性能优化都不要动这个数字 ——
 * 无谓地 bump 会让旧客户端凭空丢失功能。
 *
 * 判断"对方支持什么"完全依赖这个数字：版本号与包的对应关系由本项目自己定义，
 * 因此拿到对端版本号就等于知道了对端的能力集合，不需要逐个探测频道。
 */
public final class ProtocolVersion
{
    /** 当前协议版本。握手时双端各自声明自己的这个值。 */
    public static final int CURRENT = 1;

    /**
     * 服务端可接受的最低客户端协议版本。
     *
     * 仅在旧客户端会真正写坏服务端数据时才抬高（此类破坏性变更应极力避免）。
     * 一旦抬高，低于此值的客户端将被拒绝服务并收到升级提示。
     */
    public static final int MIN_COMPATIBLE = 1;

    private ProtocolVersion() {}

    /** 对端版本是否达到某个功能所需的最低版本。 */
    public static boolean atLeast(int peerVersion, int requiredVersion)
    {
        return peerVersion >= requiredVersion;
    }

    /** 客户端版本是否被服务端接受。 */
    public static boolean isClientAcceptable(int clientVersion)
    {
        return clientVersion >= MIN_COMPATIBLE;
    }

    /**
     * 对端是否比本端新。
     * 用于提示用户"对面有你还用不了的功能"，本身不阻断任何操作。
     */
    public static boolean isPeerNewer(int peerVersion)
    {
        return peerVersion > CURRENT;
    }
}
