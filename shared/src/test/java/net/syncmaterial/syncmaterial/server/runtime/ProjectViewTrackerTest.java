package net.syncmaterial.syncmaterial.server.runtime;

import java.util.List;
import java.util.UUID;
import net.syncmaterial.syncmaterial.core.InterestKind;
import net.syncmaterial.syncmaterial.core.ProjectViewScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectViewTrackerTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final ProjectViewScope PROJECT = ProjectViewScope.project("project");

    @Test
    void openSetAndCloseUseTheExactConnectionAndViewGeneration() {
        ProjectInterestRegistry interests = new ProjectInterestRegistry();
        ProjectViewTracker views = new ProjectViewTracker(interests);
        int base = InterestKind.MATERIALS.bit() | InterestKind.TEAM.bit();
        assertNotNull(views.open(PLAYER, 1, PROJECT, 7, 3, base));
        assertTrue(views.matches(PLAYER, 1, PROJECT, 7, 3));
        assertFalse(views.matches(PLAYER, 1, PROJECT, 7, 2));
        assertNull(views.open(PLAYER, 1, PROJECT, 8, 3, base));
        assertNull(views.open(PLAYER, 1, PROJECT, 7, 2, base));

        int withWarehouse = base | InterestKind.WAREHOUSE_CONTENTS.bit();
        assertNotNull(views.setInterests(PLAYER, 1, PROJECT, 7, 3, withWarehouse));
        assertEquals(1, views.views("project", InterestKind.MATERIALS).size());
        assertEquals(1, views.views("project", InterestKind.WAREHOUSE_CONTENTS).size());
        assertFalse(views.close(PLAYER, 1, PROJECT, 7, 2));
        assertTrue(views.close(PLAYER, 1, PROJECT, 7, 3));
        assertTrue(views.views("project", InterestKind.MATERIALS).isEmpty());
        assertEquals(0, interests.interestCount());
    }

    @Test
    void aNewViewGenerationReplacesTheOldScopeAndResetsItsMask() {
        ProjectInterestRegistry interests = new ProjectInterestRegistry();
        ProjectViewTracker views = new ProjectViewTracker(interests);
        int all = InterestKind.ALL_MASK;
        views.open(PLAYER, 1, PROJECT, 7, 3, all);
        int base = InterestKind.MATERIALS.bit() | InterestKind.TEAM.bit();
        views.open(PLAYER, 1, PROJECT, 9, 4, base);

        assertFalse(views.matches(PLAYER, 1, PROJECT, 7, 3));
        assertTrue(views.matches(PLAYER, 1, PROJECT, 9, 4));
        assertTrue(views.views("project", InterestKind.WAREHOUSE_CONTENTS).isEmpty());
        assertFalse(views.close(PLAYER, 1, PROJECT, 7, 3));
        assertTrue(views.matches(PLAYER, 1, PROJECT, 9, 4));
    }

    @Test
    void staleConnectionCannotReopenOrClearANewSessionEvenWhenRegistryWasEmpty() {
        ProjectInterestRegistry interests = new ProjectInterestRegistry();
        ProjectViewTracker views = new ProjectViewTracker(interests);
        views.open(PLAYER, 2, PROJECT, 7, 3, InterestKind.MATERIALS.bit());
        interests.disconnect(PLAYER, 2);

        assertNull(views.open(PLAYER, 1, PROJECT, 7, 3, InterestKind.MATERIALS.bit()));
        views.open(PLAYER, 2, PROJECT, 7, 3, InterestKind.MATERIALS.bit());
        views.disconnected(PLAYER, 1);
        assertTrue(views.matches(PLAYER, 2, PROJECT, 7, 3));
        views.disconnected(PLAYER, 2);
        assertEquals(0, views.viewCount());
    }

    @Test
    void sameGenerationMaskLimitFailureKeepsThePreviousMask() {
        ProjectInterestRegistry interests = new ProjectInterestRegistry(
                new ProjectInterestRegistry.Limits(1, 1, 1, 2));
        int base = InterestKind.MATERIALS.bit() | InterestKind.TEAM.bit();
        assertTrue(interests.setMask(PLAYER, 1, ProjectInterestRegistry.Scope.project("project"), base));
        assertThrows(ProjectInterestRegistry.LimitExceededException.class, () -> interests.setMask(
                PLAYER, 1, ProjectInterestRegistry.Scope.project("project"), InterestKind.ALL_MASK));
        assertEquals(2, interests.interestCount());
        assertEquals(1, interests.subscribers("project", InterestKind.MATERIALS).size());
        assertEquals(1, interests.subscribers("project", InterestKind.TEAM).size());
        assertTrue(interests.subscribers("project", InterestKind.OUTLINES).isEmpty());
    }

    @Test
    void trackerAndRegistryCloseExplicitly() {
        ProjectInterestRegistry interests = new ProjectInterestRegistry();
        ProjectViewTracker views = new ProjectViewTracker(interests);
        views.open(PLAYER, 1, PROJECT, 7, 3, InterestKind.MATERIALS.bit());
        views.close();
        interests.close();
        assertEquals(0, views.viewCount());
        assertTrue(views.views("project", InterestKind.MATERIALS).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> views.open(PLAYER, 1, PROJECT, 7, 3, InterestKind.MATERIALS.bit()));
    }

    @Test
    void hasAnyInterestChecksTheRequestedKindsNotAnyNonZeroMask() {
        try (ProjectInterestRegistry interests = new ProjectInterestRegistry();
             ProjectViewTracker views = new ProjectViewTracker(interests)) {
            int mask = InterestKind.MATERIALS.bit() | InterestKind.TEAM.bit();
            views.open(PLAYER, 1, PROJECT, 7, 3, mask);

            // A nonzero mask must still answer false when none of the requested kinds is held.
            assertTrue(views.hasAnyInterest(PLAYER, 1, PROJECT, InterestKind.MATERIALS));
            assertTrue(views.hasAnyInterest(PLAYER, 1, PROJECT,
                    InterestKind.MATERIALS, InterestKind.TEAM));
            assertTrue(views.hasAnyInterest(PLAYER, 1, PROJECT,
                    InterestKind.WAREHOUSE_CONTENTS, InterestKind.TEAM));
            assertFalse(views.hasAnyInterest(PLAYER, 1, PROJECT, InterestKind.AREA_CONFIG));
            assertFalse(views.hasAnyInterest(PLAYER, 1, PROJECT,
                    InterestKind.AREA_CONFIG, InterestKind.OUTLINES, InterestKind.WAREHOUSE_CONTENTS));
        }
    }

    @Test
    void hasAnyInterestIsolatesPlayerConnectionAndScope() {
        UUID otherPlayer = UUID.randomUUID();
        ProjectViewScope otherProject = ProjectViewScope.project("other-project");
        try (ProjectInterestRegistry interests = new ProjectInterestRegistry();
             ProjectViewTracker views = new ProjectViewTracker(interests)) {
            views.open(PLAYER, 1, PROJECT, 7, 3, InterestKind.MATERIALS.bit());
            views.open(otherPlayer, 1, PROJECT, 8, 3, InterestKind.TEAM.bit());
            views.open(PLAYER, 1, otherProject, 9, 3, InterestKind.AREA_CONFIG.bit());

            assertTrue(views.hasAnyInterest(PLAYER, 1, PROJECT, InterestKind.MATERIALS));
            assertFalse(views.hasAnyInterest(otherPlayer, 1, PROJECT, InterestKind.MATERIALS));
            assertTrue(views.hasAnyInterest(otherPlayer, 1, PROJECT, InterestKind.TEAM));
            assertFalse(views.hasAnyInterest(UUID.randomUUID(), 1, PROJECT, InterestKind.MATERIALS));
            assertFalse(views.hasAnyInterest(PLAYER, 1, otherProject, InterestKind.MATERIALS));
            assertTrue(views.hasAnyInterest(PLAYER, 1, otherProject, InterestKind.AREA_CONFIG));

            views.open(PLAYER, 2, PROJECT, 10, 4, InterestKind.MATERIALS.bit());
            assertFalse(views.hasAnyInterest(PLAYER, 1, PROJECT, InterestKind.MATERIALS));
            assertFalse(views.hasAnyInterest(PLAYER, 3, PROJECT, InterestKind.MATERIALS));
            assertTrue(views.hasAnyInterest(PLAYER, 2, PROJECT, InterestKind.MATERIALS));
            assertFalse(views.hasAnyInterest(PLAYER, 2, PROJECT, InterestKind.TEAM));
        }
    }

    @Test
    void hasAnyInterestReflectsSetInterestsAndClose() {
        try (ProjectInterestRegistry interests = new ProjectInterestRegistry();
             ProjectViewTracker views = new ProjectViewTracker(interests)) {
            views.open(PLAYER, 1, PROJECT, 7, 3, InterestKind.MATERIALS.bit());
            assertTrue(views.hasAnyInterest(PLAYER, 1, PROJECT, InterestKind.MATERIALS));

            views.setInterests(PLAYER, 1, PROJECT, 7, 3, InterestKind.TEAM.bit());
            assertFalse(views.hasAnyInterest(PLAYER, 1, PROJECT, InterestKind.MATERIALS));
            assertTrue(views.hasAnyInterest(PLAYER, 1, PROJECT, InterestKind.TEAM));

            views.setInterests(PLAYER, 1, PROJECT, 7, 3, 0);
            assertFalse(views.hasAnyInterest(PLAYER, 1, PROJECT, InterestKind.TEAM));
            assertTrue(views.interestedViews(PROJECT).isEmpty());

            views.setInterests(PLAYER, 1, PROJECT, 7, 3,
                    InterestKind.MATERIALS.bit() | InterestKind.WAREHOUSE_CONTENTS.bit());
            assertTrue(views.hasAnyInterest(PLAYER, 1, PROJECT,
                    InterestKind.MATERIALS, InterestKind.TEAM));

            assertTrue(views.close(PLAYER, 1, PROJECT, 7, 3));
            assertFalse(views.hasAnyInterest(PLAYER, 1, PROJECT, InterestKind.MATERIALS));
            assertTrue(views.interestedViews(PROJECT).isEmpty());
            assertEquals(0, interests.interestCount());
        }
    }

    @Test
    void hasAnyInterestSeparatesGlobalFromSameNamedProject() {
        String sharedId = "shared-scope";
        ProjectViewScope project = ProjectViewScope.project(sharedId);
        ProjectViewScope global = ProjectViewScope.global(sharedId);
        try (ProjectInterestRegistry interests = new ProjectInterestRegistry();
             ProjectViewTracker views = new ProjectViewTracker(interests)) {
            views.open(PLAYER, 1, project, 7, 3, InterestKind.MATERIALS.bit());
            views.open(PLAYER, 1, global, 8, 3, InterestKind.AREA_CONFIG.bit());

            assertTrue(views.hasAnyInterest(PLAYER, 1, project, InterestKind.MATERIALS));
            assertFalse(views.hasAnyInterest(PLAYER, 1, project, InterestKind.AREA_CONFIG));
            assertTrue(views.hasAnyInterest(PLAYER, 1, global, InterestKind.AREA_CONFIG));
            assertFalse(views.hasAnyInterest(PLAYER, 1, global, InterestKind.MATERIALS));

            assertEquals(List.of(ProjectViewScope.project(sharedId)),
                    views.interestedViews(project).stream().map(ProjectViewTracker.View::scope).toList());
            assertEquals(List.of(ProjectViewScope.global(sharedId)),
                    views.interestedViews(global).stream().map(ProjectViewTracker.View::scope).toList());
        }
    }

    @Test
    void interestedViewsReturnsEachNonZeroViewOnceEvenWithoutMaterialsInterest() {
        UUID otherPlayer = UUID.randomUUID();
        UUID zeroPlayer = UUID.randomUUID();
        try (ProjectInterestRegistry interests = new ProjectInterestRegistry();
             ProjectViewTracker views = new ProjectViewTracker(interests)) {
            views.open(PLAYER, 1, PROJECT, 7, 3,
                    InterestKind.MATERIALS.bit() | InterestKind.TEAM.bit());
            views.open(otherPlayer, 1, PROJECT, 8, 3, InterestKind.TEAM.bit());
            views.open(zeroPlayer, 1, PROJECT, 9, 3, 0);

            List<ProjectViewTracker.View> interested = views.interestedViews(PROJECT);
            assertEquals(2, interested.size());
            assertEquals(1, interested.stream()
                    .filter(view -> view.playerId().equals(PLAYER)).count());
            assertEquals(1, interested.stream()
                    .filter(view -> view.playerId().equals(otherPlayer)).count());
            assertTrue(interested.stream().noneMatch(view -> view.playerId().equals(zeroPlayer)));
            assertTrue(interested.stream().anyMatch(view -> view.playerId().equals(otherPlayer)
                    && view.mask() == InterestKind.TEAM.bit()));

            views.setInterests(PLAYER, 1, PROJECT, 7, 3, 0);
            List<ProjectViewTracker.View> onlyTeam = views.interestedViews(PROJECT);
            assertEquals(1, onlyTeam.size());
            assertEquals(otherPlayer, onlyTeam.get(0).playerId());
        }
    }

    @Test
    void interestedViewsReturnsImmutableSnapshots() {
        try (ProjectInterestRegistry interests = new ProjectInterestRegistry();
             ProjectViewTracker views = new ProjectViewTracker(interests)) {
            views.open(PLAYER, 1, PROJECT, 7, 3, InterestKind.MATERIALS.bit());
            List<ProjectViewTracker.View> before = views.interestedViews(PROJECT);
            assertEquals(1, before.size());

            views.setInterests(PLAYER, 1, PROJECT, 7, 3,
                    InterestKind.MATERIALS.bit() | InterestKind.TEAM.bit());
            assertEquals(InterestKind.MATERIALS.bit(), before.get(0).mask());
            assertEquals(InterestKind.MATERIALS.bit() | InterestKind.TEAM.bit(),
                    views.interestedViews(PROJECT).get(0).mask());
        }
    }
}
