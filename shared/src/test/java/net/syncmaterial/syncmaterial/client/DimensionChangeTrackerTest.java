package net.syncmaterial.syncmaterial.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DimensionChangeTrackerTest {
    @Test
    void firstWorldIsNotAChangeAndRepeatedWorldStaysQuiet() {
        DimensionChangeTracker tracker = new DimensionChangeTracker();
        assertFalse(tracker.observe("minecraft:overworld"));
        assertFalse(tracker.observe("minecraft:overworld"));
    }

    @Test
    void changingDimensionReportsOneChangeEvenForTheSameCoordinates() {
        DimensionChangeTracker tracker = new DimensionChangeTracker();
        assertFalse(tracker.observe("minecraft:overworld"));
        // Tracker only sees dimension ids; a chest at the same (x,y,z) in another
        // dimension must still invalidate the cached warehouse containers.
        assertTrue(tracker.observe("minecraft:the_nether"));
        assertFalse(tracker.observe("minecraft:the_nether"));
    }

    @Test
    void leavingAndRejoiningAWorldStartsANewBaseline() {
        DimensionChangeTracker tracker = new DimensionChangeTracker();
        assertFalse(tracker.observe("minecraft:overworld"));
        assertFalse(tracker.observe(null));
        assertFalse(tracker.observe("minecraft:overworld"));
        tracker.reset();
        assertFalse(tracker.observe("minecraft:overworld"));
    }
}
