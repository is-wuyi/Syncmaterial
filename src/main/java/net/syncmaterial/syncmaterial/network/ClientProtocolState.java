package net.syncmaterial.syncmaterial.network;

import net.syncmaterial.syncmaterial.SyncMaterial;

/**
 * 客户端侧的协议握手状态：记录当前连接的服务端协议版本。
 *
 * 三种状态必须区分清楚，否则界面无法给出准确提示：
 * - PENDING：已发出握手，还没收到回应。刚进服的正常状态，也可能是服务端没装本 mod。
 * - ACCEPTED：握手成功，可正常使用。
 * - REJECTED：服务端明确拒绝了本客户端的版本，需要升级 mod。
 *
 * 这里不做超时判定。"发了握手但一直没回应"既可能是服务端没装 mod，
 * 也可能只是网络慢，客户端无法可靠区分，因此保持 PENDING 并在用户
 * 实际尝试使用功能时才提示，避免误报。
 */
public final class ClientProtocolState
{
    public enum Status { PENDING, ACCEPTED, REJECTED }

    private static volatile Status status = Status.PENDING;
    private static volatile int serverProtocolVersion = 0;
    private static volatile String serverModVersion = "";

    private ClientProtocolState() {}

    /**
     * 已向服务端发出握手时调用。
     *
     * 单独打这条日志是为了让链路可诊断：只有"已发出"而始终没有"握手成功"，
     * 就说明服务端没装本 mod 或没处理握手；两条都没有则是客户端自己没发出去。
     */
    public static void onHandshakeSent()
    {
        SyncMaterial.LOGGER.info("已向服务端发出版本握手：本地协议版本 {}（mod 版本 {}）",
                ProtocolVersion.CURRENT, SyncMaterial.getModVersion());
    }

    /** 收到服务端握手回应时调用。 */
    public static void onHandshakeResponse(int protocolVersion, String modVersion, boolean accepted)
    {
        serverProtocolVersion = protocolVersion;
        serverModVersion = modVersion != null ? modVersion : "";
        status = accepted ? Status.ACCEPTED : Status.REJECTED;

        if (accepted)
        {
            SyncMaterial.LOGGER.info("版本握手成功：服务端协议版本 {}（mod 版本 {}），本地协议版本 {}",
                    protocolVersion, serverModVersion, ProtocolVersion.CURRENT);
        }
        else
        {
            SyncMaterial.LOGGER.warn("服务端拒绝了本客户端：服务端协议版本 {}（mod 版本 {}），本地协议版本 {}，请升级 SyncMaterial",
                    protocolVersion, serverModVersion, ProtocolVersion.CURRENT);
        }
    }

    /** 断开连接时重置，避免把上一个服务器的版本信息带到下一个服务器。 */
    public static void reset()
    {
        status = Status.PENDING;
        serverProtocolVersion = 0;
        serverModVersion = "";
    }

    public static Status getStatus()
    {
        return status;
    }

    /** 是否可以正常使用本 mod 的功能。 */
    public static boolean isUsable()
    {
        return status == Status.ACCEPTED;
    }

    /** 服务端协议版本；未收到握手回应时返回 0。 */
    public static int getServerProtocolVersion()
    {
        return serverProtocolVersion;
    }

    /** 服务端 mod 版本字符串，仅用于展示。 */
    public static String getServerModVersion()
    {
        return serverModVersion;
    }

    /**
     * 服务端是否支持某个需要最低协议版本的功能。
     *
     * 新增功能时在发包处调用，例如：
     * if (ClientProtocolState.serverSupports(2)) { 发新包 } else { 按钮置灰并提示 }
     */
    public static boolean serverSupports(int requiredVersion)
    {
        return isUsable() && ProtocolVersion.atLeast(serverProtocolVersion, requiredVersion);
    }

    /**
     * 服务端协议版本是否高于本地。
     * 用于提示"服务端有你还用不了的新功能"，本身不阻断任何操作。
     */
    public static boolean isServerNewer()
    {
        return status == Status.ACCEPTED && ProtocolVersion.isPeerNewer(serverProtocolVersion);
    }
}
