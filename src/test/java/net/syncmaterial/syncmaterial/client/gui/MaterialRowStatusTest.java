package net.syncmaterial.syncmaterial.client.gui;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure mapping tests for the stable material row status ladder. */
class MaterialRowStatusTest {

    @Test
    void zeroDemandStaysDistinctFromEveryProgressState() {
        assertEquals(MaterialRowStatus.NO_DEMAND,
                MaterialRowStatus.of(view(0, 0, 0, 0, 0, false, false, true, false)));
        assertEquals(MaterialRowStatus.NO_DEMAND,
                MaterialRowStatus.of(view(0, 0, 0, 0, 0, false, false, false, false)));
        assertEquals(MaterialRowStatus.NO_DEMAND,
                MaterialRowStatus.of(view(0, Long.MAX_VALUE, 5, 2, 3, false, false, true, true)));
    }

    @Test
    void unknownPreparationOverridesCountsAndCompletionClaims() {
        assertEquals(MaterialRowStatus.UNKNOWN,
                MaterialRowStatus.of(view(10, 10, 10, 10, 10, false, false, false, false)));

        assertThrows(IllegalArgumentException.class,
                () -> view(10, 10, 0, 0, 0, true, false, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> view(10, 0, 0, 10, 0, false, true, false, false));
    }

    @Test
    void stagingReadyAndTeamCollectedRemainDistinct() {
        assertEquals(MaterialRowStatus.STAGING_READY,
                MaterialRowStatus.of(view(10, 10, 0, 0, 0, true, false, true, false)));
        assertEquals(MaterialRowStatus.TEAM_COLLECTED,
                MaterialRowStatus.of(view(10, 0, 0, 10, 0, false, true, true, false)));
        assertNotEquals(MaterialRowStatus.STAGING_READY, MaterialRowStatus.TEAM_COLLECTED);
    }

    @Test
    void progressLadderUsesStagingBackpacksThenWarehouse() {
        assertEquals(MaterialRowStatus.PARTIAL_STAGING,
                MaterialRowStatus.of(view(10, 4, 0, 0, 0, false, false, true, false)));
        assertEquals(MaterialRowStatus.PARTIAL_STAGING,
                MaterialRowStatus.of(view(10, 4, 9, 2, 3, false, false, true, false)));

        assertEquals(MaterialRowStatus.IN_BACKPACKS,
                MaterialRowStatus.of(view(10, 0, 0, 3, 0, false, false, true, false)));
        assertEquals(MaterialRowStatus.IN_BACKPACKS,
                MaterialRowStatus.of(view(10, 0, 0, 0, 3, false, false, true, false)));
        assertEquals(MaterialRowStatus.IN_BACKPACKS,
                MaterialRowStatus.of(view(10, 0, 9, 3, 0, false, false, true, false)));

        assertEquals(MaterialRowStatus.WAREHOUSE_AVAILABLE,
                MaterialRowStatus.of(view(10, 0, 3, 0, 0, false, false, true, false)));
        assertEquals(MaterialRowStatus.NOT_STARTED,
                MaterialRowStatus.of(view(10, 0, 0, 0, 0, false, false, true, false)));
        assertEquals(MaterialRowStatus.NOT_STARTED,
                MaterialRowStatus.of(view(10, 0, 0, 0, 0, false, false, true, true)));
    }

    @Test
    void unknownCannotBecomeReadyOrCollected() {
        ProjectProgressMaterialView unknown = view(10, 10, 10, 10, 10,
                false, false, false, false);
        assertEquals(MaterialRowStatus.UNKNOWN, MaterialRowStatus.of(unknown));
        assertNotEquals(MaterialRowStatus.STAGING_READY, MaterialRowStatus.of(unknown));
        assertNotEquals(MaterialRowStatus.TEAM_COLLECTED, MaterialRowStatus.of(unknown));
    }

    @Test
    void translationKeysAreStableEnglishI18nKeys() {
        Set<String> keys = new HashSet<>();
        for (MaterialRowStatus status : MaterialRowStatus.values()) {
            String key = status.translationKey();
            assertTrue(key != null && !key.isEmpty());
            assertTrue(key.startsWith("syncmaterial.gui.material_status."));
            assertTrue(key.chars().allMatch(value -> value < 128),
                    "i18n keys must not embed Chinese labels");
            assertTrue(keys.add(key), "duplicate translation key: " + key);
        }
        assertEquals(MaterialRowStatus.values().length, keys.size());
        assertEquals(8, MaterialRowStatus.values().length);
        assertEquals(8, Arrays.stream(MaterialRowStatus.values())
                .map(MaterialRowStatus::translationKey)
                .distinct()
                .count());
    }

    private static ProjectProgressMaterialView view(long required,
                                                    long staging,
                                                    long warehouse,
                                                    long mine,
                                                    long others,
                                                    boolean stagingReady,
                                                    boolean teamCollected,
                                                    boolean preparationKnown,
                                                    boolean claimed) {
        long held = saturatingAdd(mine, others);
        long accounted = saturatingAdd(saturatingAdd(staging, warehouse), held);
        long teamMissing = accounted >= required ? 0L : required - accounted;
        long pickupMissing = Math.min(teamMissing, warehouse);
        return new ProjectProgressMaterialView(
                1,
                "minecraft:stone",
                required,
                staging,
                warehouse,
                claimed,
                mine,
                others,
                teamMissing,
                pickupMissing,
                stagingReady,
                teamCollected,
                preparationKnown);
    }

    private static long saturatingAdd(long left, long right) {
        long result = left + right;
        return result < 0L ? Long.MAX_VALUE : result;
    }
}
