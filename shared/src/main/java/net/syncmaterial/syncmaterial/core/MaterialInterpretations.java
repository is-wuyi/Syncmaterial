package net.syncmaterial.syncmaterial.core;

/**
 * Material interpretation identifiers shared by the decoder contract and storage.
 *
 * <p>Production decoders must override {@code MaterialDecoder#interpretationId()}
 * with a versioned identifier such as {@code mc:4325/decoder:1/materials:1}. The
 * {@link #UNVERSIONED} value exists only for tests and legacy fixture imports; a
 * change from it to a versioned id counts as a material reinterpretation even when
 * the schematic hash is unchanged.</p>
 */
public final class MaterialInterpretations {
    /** Placeholder used by fixture decoders and pre-interpretation rows. */
    public static final String UNVERSIONED = "unversioned";

    private MaterialInterpretations() {
    }
}
