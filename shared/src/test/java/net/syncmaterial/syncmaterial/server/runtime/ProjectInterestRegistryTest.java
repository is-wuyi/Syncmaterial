package net.syncmaterial.syncmaterial.server.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.server.runtime.ProjectInterestRegistry.Interest;
import net.syncmaterial.syncmaterial.server.runtime.ProjectInterestRegistry.LimitExceededException;
import net.syncmaterial.syncmaterial.server.runtime.ProjectInterestRegistry.Limits;
import net.syncmaterial.syncmaterial.server.runtime.ProjectInterestRegistry.Scope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectInterestRegistryTest {
    private static final Scope PROJECT_A = Scope.project("project-a");
    private static final Scope PROJECT_B = Scope.project("project-b");

    @Test
    void acquireIsIdempotentPerConnectionScopeAndKind() {
        var registry = new ProjectInterestRegistry();
        UUID player = UUID.randomUUID();

        assertTrue(registry.acquire(player, 1, PROJECT_A, InterestKind.MATERIALS));
        assertFalse(registry.acquire(player, 1, PROJECT_A, InterestKind.MATERIALS));
        assertTrue(registry.acquire(player, 1, PROJECT_A, InterestKind.TEAM));
        assertTrue(registry.acquire(player, 1, PROJECT_B, InterestKind.MATERIALS));

        assertEquals(3, registry.interestCount());
        assertEquals(1, registry.playerCount());
        assertEquals(2, registry.projectCount());
        assertEquals(Set.of(player), registry.subscribers("project-a", InterestKind.MATERIALS));
        assertEquals(Set.of(player), registry.subscribers("project-a", InterestKind.TEAM));
        assertEquals(Set.of(player), registry.subscribers("project-b", InterestKind.MATERIALS));
        assertEquals(3, registry.interests(player, 1).size());
    }

    @Test
    void independentLeasesReleaseWithoutAffectingOthers() {
        var registry = new ProjectInterestRegistry();
        UUID player = UUID.randomUUID();
        registry.acquire(player, 1, PROJECT_A, InterestKind.MATERIALS);
        registry.acquire(player, 1, PROJECT_A, InterestKind.TEAM);
        registry.acquire(player, 1, PROJECT_A, InterestKind.WAREHOUSE_CONTENTS);

        assertTrue(registry.release(player, 1, PROJECT_A, InterestKind.MATERIALS));
        assertFalse(registry.release(player, 1, PROJECT_A, InterestKind.MATERIALS));
        assertFalse(registry.release(player, 1, PROJECT_B, InterestKind.MATERIALS));

        assertEquals(2, registry.interestCount());
        assertEquals(Set.of(), registry.subscribers("project-a", InterestKind.MATERIALS));
        assertEquals(Set.of(player), registry.subscribers("project-a", InterestKind.TEAM));
        assertEquals(Set.of(player),
                registry.subscribers("project-a", InterestKind.WAREHOUSE_CONTENTS));

        assertTrue(registry.release(player, 1, PROJECT_A, InterestKind.TEAM));
        assertTrue(registry.release(player, 1, PROJECT_A, InterestKind.WAREHOUSE_CONTENTS));
        assertEquals(0, registry.interestCount());
        assertEquals(0, registry.playerCount());
        assertEquals(0, registry.projectCount());
    }

    @Test
    void oldGenerationCannotReleaseOrDisconnectNewGeneration() {
        var registry = new ProjectInterestRegistry();
        UUID player = UUID.randomUUID();

        assertTrue(registry.acquire(player, 1, PROJECT_A, InterestKind.MATERIALS));
        assertTrue(registry.acquire(player, 2, PROJECT_A, InterestKind.MATERIALS));

        // The new generation superseded the old interest, and the old generation can
        // no longer touch it.
        assertFalse(registry.release(player, 1, PROJECT_A, InterestKind.MATERIALS));
        assertEquals(0, registry.disconnect(player, 1));
        assertEquals(Set.of(player), registry.subscribers("project-a", InterestKind.MATERIALS));
        assertEquals(1, registry.interests(player, 2).size());
        assertTrue(registry.interests(player, 1).isEmpty());

        // An even older acquire must not resurrect the old connection.
        assertFalse(registry.acquire(player, 1, PROJECT_B, InterestKind.TEAM));
        assertEquals(Set.of(), registry.subscribers("project-b", InterestKind.TEAM));

        assertEquals(1, registry.disconnect(player, 2));
        assertTrue(registry.interests(player).isEmpty());
        assertEquals(0, registry.interestCount());
    }

    @Test
    void newGenerationReplacesEveryOldScopeAtOnce() {
        var registry = new ProjectInterestRegistry();
        UUID player = UUID.randomUUID();
        registry.acquire(player, 1, PROJECT_A, InterestKind.MATERIALS);
        registry.acquire(player, 1, PROJECT_A, InterestKind.TEAM);
        registry.acquire(player, 1, PROJECT_B, InterestKind.OUTLINES);
        registry.acquire(player, 1, Scope.global("hud"), InterestKind.AREA_CONFIG);

        assertTrue(registry.acquire(player, 2, PROJECT_B, InterestKind.MATERIALS));

        assertEquals(1, registry.interestCount());
        assertEquals(Set.of(), registry.subscribers("project-a", InterestKind.MATERIALS));
        assertEquals(Set.of(), registry.subscribers("project-a", InterestKind.TEAM));
        assertEquals(Set.of(player), registry.subscribers("project-b", InterestKind.MATERIALS));
        assertEquals(Set.of(), registry.subscribers("project-b", InterestKind.OUTLINES));
        assertEquals(1, registry.projectCount());
        assertEquals(0, registry.globalScopeCount());
        Set<Interest> interests = registry.interests(player);
        assertEquals(1, interests.size());
        Interest interest = interests.iterator().next();
        assertEquals(2, interest.connectionGeneration());
        assertEquals(PROJECT_B, interest.scope());
    }

    @Test
    void disconnectRequiresExactGenerationAndLeavesOtherPlayersAlone() {
        var registry = new ProjectInterestRegistry();
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        registry.acquire(player, 1, PROJECT_A, InterestKind.MATERIALS);
        registry.acquire(other, 1, PROJECT_A, InterestKind.MATERIALS);

        assertEquals(0, registry.disconnect(player, 2));
        assertEquals(Set.of(player, other),
                registry.subscribers("project-a", InterestKind.MATERIALS));

        assertEquals(1, registry.disconnect(player, 1));
        assertEquals(Set.of(other), registry.subscribers("project-a", InterestKind.MATERIALS));
        assertEquals(0, registry.disconnect(player, 1));
        assertEquals(Set.of(other), registry.subscribers("project-a", InterestKind.MATERIALS));
    }

    @Test
    void releaseScopeKindLeavesOtherKindsAndScopes() {
        var registry = new ProjectInterestRegistry();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        registry.acquire(first, 1, PROJECT_A, InterestKind.MATERIALS);
        registry.acquire(first, 1, PROJECT_A, InterestKind.TEAM);
        registry.acquire(second, 1, PROJECT_A, InterestKind.MATERIALS);
        registry.acquire(third, 1, PROJECT_B, InterestKind.MATERIALS);

        assertEquals(Set.of(first, second),
                registry.releaseScope(PROJECT_A, InterestKind.MATERIALS));
        assertEquals(Set.of(), registry.subscribers("project-a", InterestKind.MATERIALS));
        assertEquals(Set.of(first), registry.subscribers("project-a", InterestKind.TEAM));
        assertEquals(Set.of(third), registry.subscribers("project-b", InterestKind.MATERIALS));
        assertEquals(2, registry.projectCount());

        assertEquals(Set.of(first), registry.releaseScope(PROJECT_A, InterestKind.TEAM));
        assertEquals(1, registry.projectCount());
        assertEquals(Set.of(), registry.releaseScope(PROJECT_A, InterestKind.TEAM));
        assertEquals(Set.of(third), registry.subscribers("project-b", InterestKind.MATERIALS));
    }

    @Test
    void globalScopesAreSeparateFromProjectScopes() {
        var registry = new ProjectInterestRegistry();
        UUID projectHolder = UUID.randomUUID();
        UUID globalHolder = UUID.randomUUID();
        Scope global = Scope.global("project-a");
        registry.acquire(projectHolder, 1, PROJECT_A, InterestKind.MATERIALS);
        registry.acquire(globalHolder, 1, global, InterestKind.WAREHOUSE_CONTENTS);

        assertEquals(Set.of(projectHolder),
                registry.subscribers("project-a", InterestKind.MATERIALS));
        assertEquals(2, registry.interestCount());
        assertEquals(1, registry.projectCount());
        assertEquals(1, registry.globalScopeCount());

        // Removing the project must not remove the equally named global scope.
        assertEquals(Set.of(projectHolder), registry.removeProject("project-a"));
        assertEquals(0, registry.projectCount());
        assertEquals(1, registry.globalScopeCount());
        assertEquals(Set.of(globalHolder), registry.interests(globalHolder, 1).stream()
                .map(Interest::playerId)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(global,
                registry.interests(globalHolder, 1).iterator().next().scope());
    }

    @Test
    void globalSubscribersAreQueriedByScopeWithoutProjectAliasing() {
        var registry = new ProjectInterestRegistry();
        UUID projectHolder = UUID.randomUUID();
        UUID globalHolder = UUID.randomUUID();
        Scope global = Scope.global("shared");
        registry.acquire(projectHolder, 1, PROJECT_A, InterestKind.MATERIALS);
        registry.acquire(globalHolder, 1, global, InterestKind.WAREHOUSE_CONTENTS);

        assertEquals(Set.of(projectHolder),
                registry.subscribers(PROJECT_A, InterestKind.MATERIALS));
        assertEquals(Set.of(globalHolder),
                registry.subscribers(global, InterestKind.WAREHOUSE_CONTENTS));
        assertEquals(Set.of(),
                registry.subscribers(PROJECT_A, InterestKind.WAREHOUSE_CONTENTS));
        assertEquals(Set.of(),
                registry.subscribers(Scope.global("project-a"), InterestKind.MATERIALS));

        assertEquals(Set.of(globalHolder),
                registry.releaseScope(global, InterestKind.WAREHOUSE_CONTENTS));
        assertEquals(Set.of(), registry.subscribers(global, InterestKind.WAREHOUSE_CONTENTS));
        assertEquals(Set.of(projectHolder),
                registry.subscribers(PROJECT_A, InterestKind.MATERIALS));
        assertEquals(1, registry.projectCount());
        assertEquals(0, registry.globalScopeCount());
    }

    @Test
    void newerGenerationLimitFailureDoesNotRestoreOldGenerationInterests() {
        var registry = new ProjectInterestRegistry(new Limits(10, 1, 10, 10));
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        registry.acquire(other, 1, PROJECT_A, InterestKind.MATERIALS);
        registry.acquire(player, 1, PROJECT_A, InterestKind.TEAM);

        assertThrows(LimitExceededException.class,
                () -> registry.acquire(player, 2, PROJECT_B, InterestKind.MATERIALS));

        assertTrue(registry.interests(player).isEmpty());
        assertEquals(1, registry.playerCount());
        assertEquals(1, registry.interestCount());
        assertEquals(Set.of(), registry.subscribers("project-a", InterestKind.TEAM));
        assertEquals(Set.of(other),
                registry.subscribers("project-a", InterestKind.MATERIALS));
        assertEquals(1, registry.projectCount());
    }

    @Test
    void removeProjectDeletesEveryKindForThatProjectOnly() {
        var registry = new ProjectInterestRegistry();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        registry.acquire(first, 1, PROJECT_A, InterestKind.MATERIALS);
        registry.acquire(first, 1, PROJECT_A, InterestKind.TEAM);
        registry.acquire(second, 2, PROJECT_A, InterestKind.OUTLINES);
        registry.acquire(third, 1, PROJECT_B, InterestKind.MATERIALS);

        assertEquals(Set.of(first, second), registry.removeProject("project-a"));
        assertEquals(Set.of(), registry.subscribers("project-a", InterestKind.MATERIALS));
        assertEquals(Set.of(), registry.subscribers("project-a", InterestKind.TEAM));
        assertEquals(Set.of(), registry.subscribers("project-a", InterestKind.OUTLINES));
        assertEquals(Set.of(third), registry.subscribers("project-b", InterestKind.MATERIALS));
        assertEquals(1, registry.projectCount());
        assertEquals(1, registry.interestCount());
        assertEquals(Set.of(), registry.removeProject("project-a"));
    }

    @Test
    void subscribersAndInterestsAreImmutableSnapshots() {
        var registry = new ProjectInterestRegistry();
        UUID player = UUID.randomUUID();
        registry.acquire(player, 1, PROJECT_A, InterestKind.MATERIALS);

        Set<UUID> subscribers = registry.subscribers("project-a", InterestKind.MATERIALS);
        Set<Interest> interests = registry.interests(player, 1);
        assertThrows(UnsupportedOperationException.class, () -> subscribers.clear());
        assertThrows(UnsupportedOperationException.class, () -> interests.clear());

        registry.release(player, 1, PROJECT_A, InterestKind.MATERIALS);
        assertEquals(Set.of(player), subscribers);
        assertEquals(1, interests.size());
    }

    @Test
    void closeClearsStateAndRejectsFurtherChanges() {
        var registry = new ProjectInterestRegistry();
        UUID player = UUID.randomUUID();
        registry.acquire(player, 1, PROJECT_A, InterestKind.MATERIALS);
        assertFalse(registry.isClosed());

        registry.close();
        registry.close();

        assertTrue(registry.isClosed());
        assertEquals(0, registry.playerCount());
        assertEquals(0, registry.interestCount());
        assertEquals(0, registry.projectCount());
        assertEquals(0, registry.globalScopeCount());
        assertEquals(Set.of(), registry.subscribers("project-a", InterestKind.MATERIALS));
        assertEquals(Set.of(), registry.interests(player, 1));
        assertEquals(Set.of(), registry.interests(player));

        assertThrows(IllegalStateException.class,
                () -> registry.acquire(player, 2, PROJECT_A, InterestKind.MATERIALS));
        assertThrows(IllegalStateException.class,
                () -> registry.release(player, 1, PROJECT_A, InterestKind.MATERIALS));
        assertThrows(IllegalStateException.class, () -> registry.disconnect(player, 1));
        assertThrows(IllegalStateException.class,
                () -> registry.releaseScope(PROJECT_A, InterestKind.MATERIALS));
        assertThrows(IllegalStateException.class, () -> registry.removeProject("project-a"));
        assertTrue(registry.interests(player).isEmpty());
    }

    @Test
    void playerLimitIsEnforcedWithoutPartialState() {
        var registry = new ProjectInterestRegistry(new Limits(1, 10, 10, 10));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        registry.acquire(first, 1, PROJECT_A, InterestKind.MATERIALS);

        assertThrows(LimitExceededException.class,
                () -> registry.acquire(second, 1, PROJECT_A, InterestKind.MATERIALS));
        assertEquals(1, registry.playerCount());
        assertEquals(1, registry.interestCount());
        assertEquals(Set.of(first),
                registry.subscribers("project-a", InterestKind.MATERIALS));
        assertEquals(Set.of(), registry.interests(second));
    }

    @Test
    void projectAndGlobalScopeLimitsAreEnforcedWithoutPartialState() {
        var projectLimited = new ProjectInterestRegistry(new Limits(10, 1, 10, 10));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        projectLimited.acquire(first, 1, PROJECT_A, InterestKind.MATERIALS);
        assertThrows(LimitExceededException.class,
                () -> projectLimited.acquire(second, 1, PROJECT_B, InterestKind.MATERIALS));
        assertEquals(1, projectLimited.projectCount());
        assertEquals(1, projectLimited.playerCount());
        assertEquals(Set.of(), projectLimited.subscribers("project-b", InterestKind.MATERIALS));

        var globalLimited = new ProjectInterestRegistry(new Limits(10, 10, 1, 10));
        globalLimited.acquire(first, 1, Scope.global("hud"), InterestKind.MATERIALS);
        assertThrows(LimitExceededException.class,
                () -> globalLimited.acquire(first, 1, Scope.global("other"),
                        InterestKind.MATERIALS));
        assertEquals(1, globalLimited.globalScopeCount());
        assertEquals(1, globalLimited.interestCount());

        // The same scope remains idempotent even at the scope limit.
        assertFalse(globalLimited.acquire(first, 1, Scope.global("hud"), InterestKind.MATERIALS));
        assertEquals(1, globalLimited.interestCount());
    }

    @Test
    void interestLimitIsEnforcedAndFreedCapacityIsReusable() {
        var registry = new ProjectInterestRegistry(new Limits(10, 10, 10, 2));
        UUID player = UUID.randomUUID();
        registry.acquire(player, 1, PROJECT_A, InterestKind.MATERIALS);
        registry.acquire(player, 1, PROJECT_A, InterestKind.TEAM);

        assertThrows(LimitExceededException.class,
                () -> registry.acquire(player, 1, PROJECT_A, InterestKind.AREA_CONFIG));
        assertEquals(2, registry.interestCount());
        assertFalse(registry.interests(player, 1).contains(new Interest(player, 1, PROJECT_A,
                InterestKind.AREA_CONFIG)));

        assertTrue(registry.release(player, 1, PROJECT_A, InterestKind.TEAM));
        assertTrue(registry.acquire(player, 1, PROJECT_A, InterestKind.AREA_CONFIG));
        assertEquals(2, registry.interestCount());
    }

    @Test
    void newGenerationCanReuseCapacityFreedByItsOldInterests() {
        var registry = new ProjectInterestRegistry(new Limits(1, 1, 1, 1));
        UUID player = UUID.randomUUID();
        registry.acquire(player, 1, PROJECT_A, InterestKind.MATERIALS);

        assertTrue(registry.acquire(player, 2, PROJECT_A, InterestKind.TEAM));
        assertEquals(1, registry.interestCount());
        assertEquals(1, registry.playerCount());
        assertEquals(Set.of(), registry.subscribers("project-a", InterestKind.MATERIALS));
        assertEquals(Set.of(player), registry.subscribers("project-a", InterestKind.TEAM));
    }

    @Test
    void invalidKeysAndScopesAreRejected() {
        var registry = new ProjectInterestRegistry();
        UUID player = UUID.randomUUID();
        InterestKind kind = InterestKind.MATERIALS;

        assertThrows(NullPointerException.class,
                () -> registry.acquire(null, 1, PROJECT_A, kind));
        assertThrows(NullPointerException.class,
                () -> registry.acquire(player, 1, null, kind));
        assertThrows(NullPointerException.class,
                () -> registry.acquire(player, 1, PROJECT_A, null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.acquire(player, 0, PROJECT_A, kind));
        assertThrows(IllegalArgumentException.class,
                () -> registry.acquire(player, -1, PROJECT_A, kind));
        assertThrows(IllegalArgumentException.class,
                () -> registry.interests(player, 0));
        assertThrows(IllegalArgumentException.class,
                () -> registry.subscribers("", kind));
        assertThrows(IllegalArgumentException.class,
                () -> registry.removeProject(" "));

        assertThrows(IllegalArgumentException.class, () -> Scope.project(""));
        assertThrows(IllegalArgumentException.class, () -> Scope.project(" "));
        assertThrows(IllegalArgumentException.class,
                () -> Scope.project("x".repeat(101)));
        assertThrows(IllegalArgumentException.class, () -> Scope.global(""));
        assertFalse(Scope.global("global").isProject());
        assertEquals("global", Scope.global("global").id());
        assertEquals("project-a", PROJECT_A.projectId());
        assertThrows(IllegalStateException.class, () -> Scope.global("hud").projectId());
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 0));
    }

    @Test
    void concurrentDuplicateAcquiresAddExactlyOneInterest() throws Exception {
        var registry = new ProjectInterestRegistry();
        UUID player = UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger added = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (registry.acquire(player, 1, PROJECT_A, InterestKind.MATERIALS)) {
                        added.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, added.get());
        assertEquals(1, registry.interestCount());
        assertEquals(Set.of(player), registry.subscribers("project-a", InterestKind.MATERIALS));
    }

    @Test
    void concurrentDistinctPlayersRemainConsistent() throws Exception {
        var registry = new ProjectInterestRegistry();
        int threads = 4;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        registry.acquire(UUID.randomUUID(), 1, PROJECT_A,
                                InterestKind.MATERIALS);
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads * perThread, registry.interestCount());
        assertEquals(threads * perThread, registry.playerCount());
        assertEquals(threads * perThread,
                registry.subscribers("project-a", InterestKind.MATERIALS).size());
    }
}
