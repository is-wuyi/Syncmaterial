package net.syncmaterial.syncmaterial.server.legacy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LegacySubscriptionsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @Test
    void sameProjectAcrossOwnersHasSeparatePlayersAndWarehouses() {
        LegacySubscriptions a = new LegacySubscriptions();
        LegacySubscriptions b = new LegacySubscriptions();
        ServerPlayer playerA = mock(ServerPlayer.class);
        ServerPlayer playerB = mock(ServerPlayer.class);
        a.subscribeMaterials(playerA, "same");
        b.subscribeMaterials(playerB, "same");
        a.subscribeWarehouses(playerA, "same", Set.of(1));
        b.subscribeWarehouses(playerB, "same", Set.of(2));
        assertEquals(Set.of(playerA), a.materialSubscribers("same"));
        assertEquals(Set.of(playerB), b.materialSubscribers("same"));
        assertEquals(Set.of(1), a.warehouseIds(playerA));
        assertEquals(Set.of(2), b.warehouseIds(playerB));
        a.close();
        assertTrue(a.materialSubscribers("same").isEmpty());
        assertTrue(a.warehouseSubscribers().isEmpty());
        assertEquals(Set.of(playerB), b.materialSubscribers("same"));
    }

    @Test
    void closingSealsFutureSubscriptions() {
        LegacySubscriptions subscriptions = new LegacySubscriptions();
        ServerPlayer player = mock(ServerPlayer.class);
        subscriptions.close();
        subscriptions.close();
        subscriptions.subscribeMaterials(player, "new");
        subscriptions.subscribeWarehouses(player, "new", Set.of(1));
        assertTrue(subscriptions.projects(player).isEmpty());
        assertTrue(subscriptions.warehouseSubscribers().isEmpty());
    }

    @Test
    void unsubscribePreservesOtherProjectAndWarehouseUnion() {
        LegacySubscriptions subscriptions = new LegacySubscriptions();
        ServerPlayer player = mock(ServerPlayer.class);
        subscriptions.subscribeMaterials(player, "a");
        subscriptions.subscribeMaterials(player, "b");
        subscriptions.subscribeWarehouses(player, "a", Set.of(1, 2));
        subscriptions.subscribeWarehouses(player, "b", Set.of(2, 3));
        assertEquals(Set.of(1, 2, 3), subscriptions.warehouseIds(player));
        subscriptions.unsubscribeMaterials(player, "a");
        subscriptions.unsubscribeWarehouses(player, "a");
        assertEquals(Set.of("b"), subscriptions.projects(player));
        assertEquals(Set.of(2, 3), subscriptions.warehouseIds(player));
        subscriptions.forgetPlayer(player);
        assertTrue(subscriptions.materialSubscribers("b").isEmpty());
        assertTrue(subscriptions.warehouseSubscribers().isEmpty());
    }

    @Test
    void forgettingProjectPreservesOtherProjectAndPlayer() {
        LegacySubscriptions subscriptions = new LegacySubscriptions();
        ServerPlayer a = mock(ServerPlayer.class);
        ServerPlayer b = mock(ServerPlayer.class);
        subscriptions.subscribeMaterials(a, "a");
        subscriptions.subscribeMaterials(b, "b");
        subscriptions.subscribeWarehouses(a, "a", Set.of(1));
        subscriptions.subscribeWarehouses(b, "b", Set.of(2));
        subscriptions.forgetProject("a");
        assertTrue(subscriptions.projects(a).isEmpty());
        assertTrue(subscriptions.warehouseIds(a).isEmpty());
        assertEquals(Set.of(b), subscriptions.materialSubscribers("b"));
        assertEquals(Set.of(2), subscriptions.warehouseIds(b));
    }

    @Test
    void snapshotsCannotBeMutatedExternally() {
        LegacySubscriptions subscriptions = new LegacySubscriptions();
        ServerPlayer player = mock(ServerPlayer.class);
        subscriptions.subscribeMaterials(player, "project");
        subscriptions.subscribeWarehouses(player, "project", Set.of(1));
        assertThrows(UnsupportedOperationException.class, () -> subscriptions.materialSubscribers("project").clear());
        assertThrows(UnsupportedOperationException.class, () -> subscriptions.warehouseSubscribers().clear());
        assertThrows(UnsupportedOperationException.class, () -> subscriptions.projects(player).clear());
    }
}
