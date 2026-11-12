package net.syncmaterial.syncmaterial.network;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaOverlapMessageTest {
    @BeforeEach
    @AfterEach
    void resetProtocol() {
        ClientProtocolState.reset();
    }

    @Test
    void onlyTheExactMarkerKeyIsAnOverlapWarning() {
        clientSupports(3);
        assertEquals(AreaOverlapStatus.MESSAGE_KEY, ModNetworkHandlerClient.overlapWarningMessage(packet("LIST", true, AreaOverlapStatus.MESSAGE_KEY)));
        assertEquals(AreaOverlapStatus.MESSAGE_KEY, ModNetworkHandlerClient.overlapWarningMessage(packet("LIST_WAREHOUSES", true, "  " + AreaOverlapStatus.MESSAGE_KEY + "  ")));
        assertNull(ModNetworkHandlerClient.overlapWarningMessage(packet("LIST", true, "备货区已添加")), "旧服务端变更确认不是重叠提示");
        assertNull(ModNetworkHandlerClient.overlapWarningMessage(packet("LIST", true, "备货区已更新")));
        assertNull(ModNetworkHandlerClient.overlapWarningMessage(packet("LIST", true, "ok")));
        assertNull(ModNetworkHandlerClient.overlapWarningMessage(packet("LIST", true, "")));
        assertNull(ModNetworkHandlerClient.overlapWarningMessage(packet("LIST", true, "区域有重叠；同一原理图内库存仅计一次（备货区优先）")),
                "非机器键的任意文本（包括旧协议原始中文）不算提示");
        assertNull(ModNetworkHandlerClient.overlapWarningMessage(packet("LIST", false, AreaOverlapStatus.MESSAGE_KEY)));
    }

    @Test
    void legacyProtocolIgnoresEvenTheMarkerKey() {
        clientSupports(2);
        assertNull(ModNetworkHandlerClient.overlapWarningMessage(packet("LIST", true, AreaOverlapStatus.MESSAGE_KEY)));
        assertNull(ModNetworkHandlerClient.overlapWarningMessage(packet("LIST_WAREHOUSES", true, AreaOverlapStatus.MESSAGE_KEY)));
    }

    @Test
    void markerComparisonIgnoresSurroundingWhitespaceOnly() {
        assertTrue(AreaOverlapStatus.isWarning(" " + AreaOverlapStatus.MESSAGE_KEY + " "));
        assertFalse(AreaOverlapStatus.isWarning(AreaOverlapStatus.MESSAGE_KEY + ".suffix"));
        assertFalse(AreaOverlapStatus.isWarning(null));
        assertFalse(AreaOverlapStatus.isWarning(""));
    }

    private static void clientSupports(int version) {
        ClientProtocolState.onHandshakeResponse(version, "test", true);
    }

    private static StagingAreaConfigResponseS2CPacket packet(String action, boolean success, String message) {
        return new StagingAreaConfigResponseS2CPacket(action, "project", "", success, message, List.of());
    }
}
