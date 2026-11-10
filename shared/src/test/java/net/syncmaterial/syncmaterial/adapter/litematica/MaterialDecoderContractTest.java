package net.syncmaterial.syncmaterial.adapter.litematica;

import java.nio.file.Path;
import java.util.List;
import net.syncmaterial.syncmaterial.core.MaterialCount;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decoder remains a functional interface while exposing an interpretation version. */
class MaterialDecoderContractTest {
    @Test
    void lambdaKeepsFunctionalNatureAndDefaultsToFixtureInterpretation() throws Exception {
        MaterialDecoder decoder = path -> List.of(new MaterialCount("minecraft:stone", 1));

        assertEquals("unversioned", decoder.interpretationId());
        assertEquals(1, decoder.decode(Path.of("/tmp/fixture.litematic")).size());
    }

    @Test
    void productionDecoderCanOverrideInterpretationWithoutBreakingDecode() throws Exception {
        MaterialDecoder decoder = new MaterialDecoder() {
            @Override
            public List<MaterialCount> decode(Path schematicPath) {
                return List.of(new MaterialCount("minecraft:stone", 2));
            }

            @Override
            public String interpretationId() {
                return "mc:4325/decoder:1/materials:1";
            }
        };

        assertEquals("mc:4325/decoder:1/materials:1", decoder.interpretationId());
        assertEquals(2, decoder.decode(Path.of("/tmp/production.litematic")).get(0).count());
        assertTrue(decoder instanceof MaterialDecoder);
    }
}
