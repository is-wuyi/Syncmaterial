package net.syncmaterial.syncmaterial.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectProgressMaterialViewTest {
    @Test
    void saturatedObservedTotalIsExplicitlyABoundRatherThanAnExactCount() {
        ProjectProgressMaterialView view = new ProjectProgressMaterialView(1, "minecraft:stone",
                Long.MAX_VALUE, 0, 0, true, Long.MAX_VALUE, 1, 0, 0,
                false, true, true);

        assertTrue(view.teamObservedSaturated());
        assertEquals(Long.MAX_VALUE, view.teamObserved());
        assertTrue(view.teamCollected(), "the true total still covers every representable requirement");
        assertFalse(new ProjectProgressMaterialView(1, "minecraft:stone", 64, 0, 0,
                true, 32, 32, 0, 0, false, true, true).teamObservedSaturated());
    }

    @Test
    void compatibilityConstructorCannotHideTheExplicitCumulativeFlag() {
        ProjectProgressMaterialView cumulative = new ProjectProgressMaterialView(1, "minecraft:stone",
                64, 0, 0, false, 0, Long.MAX_VALUE, 0, 0,
                false, false, true, true);
        assertTrue(cumulative.teamObservedSaturated());
        assertEquals(Long.MAX_VALUE, cumulative.teamObserved());

        ProjectProgressMaterialView exactSingleMax = new ProjectProgressMaterialView(1, "minecraft:stone",
                64, 0, 0, false, 0, Long.MAX_VALUE, 0, 0,
                false, false, true, false);
        assertFalse(exactSingleMax.teamObservedSaturated());
        assertEquals(Long.MAX_VALUE, exactSingleMax.teamObserved());
    }

    @Test
    void negativeObservedCountsRemainRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressMaterialView(
                1, "minecraft:stone", 1, 0, 0, true, -1, 0, 0, 0, false, false, true));
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressMaterialView(
                1, "minecraft:stone", 1, 0, 0, true, 0, -1, 0, 0, false, false, true));
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressMaterialView(
                1, "minecraft:stone", 1, 0, 0, true, 0, 0, -1, 0, false, false, true));
        assertThrows(IllegalArgumentException.class, () -> new ProjectProgressMaterialView(
                1, "minecraft:stone", 1, 0, 0, true, 0, 0, 0, -1, false, false, true));
    }
}
