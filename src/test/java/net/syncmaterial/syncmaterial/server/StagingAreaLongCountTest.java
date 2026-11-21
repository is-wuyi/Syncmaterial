package net.syncmaterial.syncmaterial.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Production scanner merge paths keep long totals and fail explicitly on overflow. */
class StagingAreaLongCountTest {
    @TempDir
    Path temporary;

    private SchematicDatabase database;
    private StagingAreaManager staging;

    @BeforeEach
    void setUp() throws Exception {
        database = new SchematicDatabase();
        database.initialize(temporary.resolve("scanner-long.db").toString());
        staging = new StagingAreaManager(database);
        database.executeUpdate("INSERT INTO schematics "
                + "(id, name, file_path, uploaded_by, allow_self_claim) "
                + "VALUES ('p1', 'Project', 'x', 'Owner', 1)");
        database.executeUpdate("INSERT INTO staging_areas "
                + "(id, schematic_id, world, name, x1, y1, z1, x2, y2, z2) "
                + "VALUES (1, 'p1', 'minecraft:overworld', 'A', 0, 0, 0, 15, 15, 15)");
        database.executeUpdate("INSERT INTO staging_areas "
                + "(id, schematic_id, world, name, x1, y1, z1, x2, y2, z2) "
                + "VALUES (2, 'p1', 'minecraft:overworld', 'B', 0, 0, 0, 15, 15, 15)");
        database.executeUpdate("INSERT INTO staging_area_inventory "
                + "(staging_area_id, item_id, count) VALUES (1, 'minecraft:stone', 1)");
        database.executeUpdate("INSERT INTO staging_area_inventory "
                + "(staging_area_id, item_id, count) VALUES (2, 'minecraft:stone', 1)");
    }

    @Test
    void mergeKeepsLongTotalsWithoutIntTruncation() throws Exception {
        invokeMerge(1, Map.of("minecraft:stone", Long.MAX_VALUE - 1));
        assertEquals(Long.MAX_VALUE, count(1));
    }

    @Test
    void mergeOverflowFailsWithoutWritingAndMarksIncomplete() throws Exception {
        invokeMerge(2, Map.of("minecraft:stone", Long.MAX_VALUE));
        assertEquals(1L, count(2), "overflow must roll back instead of wrapping");
        assertTrue(incompleteKeys().contains("S:2"));
    }

    private void invokeMerge(int areaId, Map<String, Long> items) throws Exception {
        Method method = StagingAreaManager.class.getDeclaredMethod(
                "mergeStagingAreaContainer", int.class, Map.class);
        method.setAccessible(true);
        method.invoke(staging, areaId, items);
    }

    private long count(int areaId) throws Exception {
        try (var rows = database.executeQuery(
                "SELECT count FROM staging_area_inventory WHERE staging_area_id = ? "
                        + "AND item_id = 'minecraft:stone'", areaId)) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> incompleteKeys() throws Exception {
        Field field = StagingAreaManager.class.getDeclaredField("incompleteScans");
        field.setAccessible(true);
        return (Set<String>) field.get(staging);
    }
}
