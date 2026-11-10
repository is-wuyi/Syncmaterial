/*
 * This file is part of SyncMaterial, licensed under GNU Lesser General Public
 * License v3 (LGPL-3.0).
 */
package net.syncmaterial.shared.litematic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NbtValidationTest {
    @Test
    void acceptsVanillaResourceLocations() {
        assertTrue(NbtValidation.isResourceLocation("minecraft:stone"));
        assertTrue(NbtValidation.isResourceLocation("modid:block/path"));
        assertTrue(NbtValidation.isResourceLocation("stone"));
        assertTrue(NbtValidation.isResourceLocation("a_b.c-d:e_f.g-h/i"));
    }

    @Test
    void rejectsDescriptorInjectionAndBadNamespaces() {
        assertFalse(NbtValidation.isResourceLocation("minecraft:stone[foo]"));
        assertFalse(NbtValidation.isResourceLocation("Minecraft:Stone"));
        assertFalse(NbtValidation.isResourceLocation(":stone"));
        assertFalse(NbtValidation.isResourceLocation("a:b:c"));
        assertFalse(NbtValidation.isResourceLocation("minecraft:"));
        assertFalse(NbtValidation.isResourceLocation(""));
        assertFalse(NbtValidation.isResourceLocation(null));
    }

    @Test
    void descriptorTokensRejectSyntaxCharacters() {
        assertTrue(NbtValidation.isSafeDescriptorToken("facing"));
        assertTrue(NbtValidation.isSafeDescriptorToken("north"));
        assertFalse(NbtValidation.isSafeDescriptorToken("foo]"));
        assertFalse(NbtValidation.isSafeDescriptorToken("a=b"));
        assertFalse(NbtValidation.isSafeDescriptorToken("a b"));
        assertFalse(NbtValidation.isSafeDescriptorToken(""));
    }
}
