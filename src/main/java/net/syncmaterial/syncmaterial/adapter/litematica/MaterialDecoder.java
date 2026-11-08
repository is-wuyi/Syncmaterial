package net.syncmaterial.syncmaterial.adapter.litematica;

import java.nio.file.Path;
import java.util.List;
import net.syncmaterial.syncmaterial.core.MaterialCount;

/**
 * Server-side, Litematica-free contract for turning one schematic file into
 * the material rows stored by the foundation backend.
 *
 * <p>Implementations must be synchronous and must not own an executor: the
 * runtime submits every decode call to its own bounded parsing pool.</p>
 */
public interface MaterialDecoder {
    /**
     * Decode all regions of a schematic. A malformed file must fail the whole
     * call instead of returning a partial material list.
     *
     * @param schematicPath the .litematic file to read
     * @return per-item material counts; may be empty for an all-air schematic
     * @throws Exception if the file cannot be read or is not a valid schematic
     */
    List<MaterialCount> decode(Path schematicPath) throws Exception;
}
