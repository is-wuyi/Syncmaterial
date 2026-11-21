package net.syncmaterial.syncmaterial.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The legacy material query must preserve long totals beyond the int range. */
class DatabaseQueryServiceLongTest {
    @TempDir
    Path temporary;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.syncmaterial.syncmaterial.TestGameBootstrap.bindDataComponents();
    }

    @Test
    void materialQueryReturnsLongTotalWithoutIntClamp() throws Exception {
        SchematicDatabase database = new SchematicDatabase();
        database.initialize(temporary.resolve("query-long.db").toString());
        database.executeUpdate("INSERT INTO schematics "
                + "(id, name, file_path, uploaded_by, allow_self_claim) "
                + "VALUES ('p1', 'Project', 'x', 'Owner', 1)");
        long expected = (long) Integer.MAX_VALUE + 42L;
        database.executeUpdate("INSERT INTO material_entries "
                + "(id, schematic_id, item_id, count) VALUES (1, 'p1', 'minecraft:stone', ?)",
                expected);

        var materials = new DatabaseQueryService(database).getMaterials("p1");

        assertEquals(1, materials.size());
        assertEquals(expected, materials.getFirst().getCountTotal());
        assertEquals(Items.STONE, materials.getFirst().getStack().getItem());
        database.close();
    }
}
