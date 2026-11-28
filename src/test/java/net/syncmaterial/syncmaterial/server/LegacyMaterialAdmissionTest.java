package net.syncmaterial.syncmaterial.server;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.syncmaterial.syncmaterial.TestGameBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Real SQLite coverage for project-scoped material admission and inventory cleanup. */
class LegacyMaterialAdmissionTest {
    @TempDir
    Path temporary;

    private SchematicDatabase database;
    private CollaborationManager collaboration;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        TestGameBootstrap.bindDataComponents();
    }

    @BeforeEach
    void setUp() {
        database = new SchematicDatabase();
        database.initialize(temporary.resolve("legacy.db").toString());
        collaboration = new CollaborationManager(database);
        collaboration.setStagingAreaManager(mock(StagingAreaManager.class));
    }

    @AfterEach
    void tearDown() {
        collaboration.close();
        database.close();
    }

    @Test
    void leaveWithoutMatchingClaimDoesNotClearAnotherProjectsInventory() throws SQLException {
        int materialA = project("research-a");
        int materialB = project("research-b");
        assertTrue(collaboration.joinCollaboration("research-b", materialB, "Player0"));
        collaboration.updatePlayerInventory("Player0", "research-b", materialB, 7);

        assertTrue(collaboration.leaveCollaboration("research-a", materialB, "Player0"));

        assertEquals(7, collaboration.getCollaborationStatus("research-b", materialB)
                .participants().getFirst().count());
        assertEquals(7, persistedCount("research-b", materialB));
        assertEquals(List.of(materialA), collaboration.getAllMaterialIds("research-a"));
        assertEquals(List.of(materialB), collaboration.getAllMaterialIds("research-b"));
    }

    @Test
    void validLeaveClearsOnlyTargetProjectsInMemoryObservation() throws SQLException {
        int materialA = project("research-a");
        int materialB = project("research-b");
        assertTrue(collaboration.joinCollaboration("research-a", materialA, "Player0"));
        assertTrue(collaboration.joinCollaboration("research-b", materialB, "Player0"));
        collaboration.updatePlayerInventory("Player0", "research-a", materialA, 5);
        collaboration.updatePlayerInventory("Player0", "research-b", materialB, 7);

        assertTrue(collaboration.leaveCollaboration("research-a", materialA, "Player0"));

        assertTrue(collaboration.getParticipants("research-a", materialA).isEmpty());
        assertEquals(7, collaboration.getCollaborationStatus("research-b", materialB)
                .participants().getFirst().count());
        assertEquals(7, persistedCount("research-b", materialB));
    }

    private int project(String projectId) throws SQLException {
        database.executeUpdate("INSERT INTO schematics (id, name, file_path, uploaded_by) VALUES (?, ?, ?, ?)",
                projectId, projectId, "/research.litematic", "Player0");
        database.executeUpdate("INSERT INTO material_entries (schematic_id, item_id, count) VALUES (?, ?, ?)",
                projectId, "minecraft:dirt", 64);
        try (var result = database.executeQuery("SELECT last_insert_rowid()")) {
            result.next();
            return result.getInt(1);
        }
    }

    private long persistedCount(String projectId, int materialId) throws SQLException {
        try (var result = database.executeQuery(
                "SELECT count FROM player_inventories WHERE schematic_id = ? AND material_id = ?",
                projectId, materialId)) {
            return result.next() ? result.getLong("count") : -1;
        }
    }
}
