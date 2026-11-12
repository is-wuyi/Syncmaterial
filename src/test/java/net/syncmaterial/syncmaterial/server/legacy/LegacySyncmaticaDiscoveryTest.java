package net.syncmaterial.syncmaterial.server.legacy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacySyncmaticaDiscoveryTest {
    @TempDir Path temporary;

    @Test
    void attachesOnceAndDetachesExactConsumer() {
        LegacyServerContext owner = owner();
        FakeContext context = new FakeContext(temporary.resolve("world/syncmatica"), true);
        LegacySyncmaticaDiscovery discovery = new LegacySyncmaticaDiscovery(owner, () -> context);
        discovery.tick();
        discovery.tick();
        assertEquals(1, context.manager.added.size());
        Consumer<Object> consumer = context.manager.added.get(0);
        discovery.close();
        discovery.close();
        assertEquals(List.of(consumer), context.manager.removed);
    }

    @Test
    void contextReplacementDetachesBeforeNewAttach() {
        LegacyServerContext owner = owner();
        FakeContext a = new FakeContext(temporary.resolve("world/syncmatica"), true);
        FakeContext b = new FakeContext(temporary.resolve("world/syncmatica"), true);
        AtomicReference<Object> current = new AtomicReference<>(a);
        LegacySyncmaticaDiscovery discovery = new LegacySyncmaticaDiscovery(owner, current::get);
        discovery.tick();
        current.set(b);
        discovery.tick();
        assertEquals(1, a.manager.removed.size());
        assertSame(a.manager.added.get(0), a.manager.removed.get(0));
        assertEquals(1, b.manager.added.size());
        discovery.close();
    }

    @Test
    void wrongWorldOrClientContextNeverAttaches() {
        LegacyServerContext owner = owner();
        FakeContext wrong = new FakeContext(temporary.resolve("other/syncmatica"), true);
        FakeContext client = new FakeContext(temporary.resolve("world/syncmatica"), false);
        AtomicReference<Object> current = new AtomicReference<>(wrong);
        LegacySyncmaticaDiscovery discovery = new LegacySyncmaticaDiscovery(owner, current::get);
        discovery.tick();
        current.set(client);
        discovery.tick();
        assertTrue(wrong.manager.added.isEmpty());
        assertTrue(client.manager.added.isEmpty());
        discovery.close();
    }

    @Test
    void oldConsumerIgnoresPlacementAfterOwnerStops() {
        LegacyServerContext owner = owner();
        FakeContext context = new FakeContext(temporary.resolve("world/syncmatica"), true);
        LegacySyncmaticaDiscovery discovery = new LegacySyncmaticaDiscovery(owner, () -> context);
        discovery.tick();
        Consumer<Object> old = context.manager.added.get(0);
        clearInvocations(owner);
        when(owner.isCurrent()).thenReturn(false);
        old.accept(new FakePlacement());
        assertEquals(0, context.manager.lookups);
        verify(owner, never()).ingest();
        discovery.close();
    }

    @Test
    void oldConsumerIgnoresPlacementAfterContextChange() {
        LegacyServerContext owner = owner();
        FakeContext a = new FakeContext(temporary.resolve("world/syncmatica"), true);
        AtomicReference<Object> current = new AtomicReference<>(a);
        LegacySyncmaticaDiscovery discovery = new LegacySyncmaticaDiscovery(owner, current::get);
        discovery.tick();
        Consumer<Object> old = a.manager.added.get(0);
        current.set(null);
        discovery.tick();
        old.accept(new FakePlacement());
        assertEquals(0, a.manager.lookups);
        discovery.close();
    }

    private LegacyServerContext owner() {
        LegacyServerContext owner = mock(LegacyServerContext.class);
        when(owner.isCurrent()).thenReturn(true);
        when(owner.worldRoot()).thenReturn(temporary.resolve("world"));
        when(owner.ingest()).thenReturn(mock(LegacySchematicIngest.class));
        clearInvocations(owner);
        return owner;
    }

    public static final class FakeContext {
        private final Path config;
        private final boolean server;
        public final FakeManager manager = new FakeManager();

        public FakeContext(Path config, boolean server) {
            this.config = config;
            this.server = server;
        }
        public boolean isServer() { return server; }
        public Path getConfigFolder() { return config; }
        public FakeManager getSyncmaticManager() { return manager; }
    }

    public static final class FakeManager {
        public final List<Consumer<Object>> added = new ArrayList<>();
        public final List<Consumer<Object>> removed = new ArrayList<>();
        public int lookups;
        public void addServerPlacementConsumer(Consumer<Object> consumer) { added.add(consumer); }
        public void removeServerPlacementConsumer(Consumer<Object> consumer) { removed.add(consumer); }
        public Object getPlacement(UUID id) { lookups++; return null; }
    }

    public static final class FakePlacement {
        private final UUID id = UUID.randomUUID();
        public UUID getId() { return id; }
    }
}
