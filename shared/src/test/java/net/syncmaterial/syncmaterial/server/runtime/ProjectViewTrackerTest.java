package net.syncmaterial.syncmaterial.server.runtime;

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
}
