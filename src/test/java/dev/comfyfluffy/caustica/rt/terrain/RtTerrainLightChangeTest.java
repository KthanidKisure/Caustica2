package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtTerrainLightChangeTest {
    @Test
    void treatsMissingAndEmptyLightListsAsEquivalent() {
        assertTrue(RtTerrain.sameLightRecords(null, null));
        assertTrue(RtTerrain.sameLightRecords(null, new float[0]));
        assertTrue(RtTerrain.sameLightRecords(new float[0], null));
    }

    @Test
    void acceptsBitIdenticalReextractionAndRejectsActualChanges() {
        float[] original = {1f, 2f, 3f, 4f};
        assertTrue(RtTerrain.sameLightRecords(original, original.clone()));
        assertFalse(RtTerrain.sameLightRecords(original, new float[]{1f, 2f, 3f, 5f}));
        assertFalse(RtTerrain.sameLightRecords(original, null));
        assertFalse(RtTerrain.sameLightRecords(null, original));
    }

    @Test
    void boundsLodDispatchToFourFramesOfWork() {
        assertEquals(2, RtTerrain.lodDispatchBudget(2, 0));
        assertEquals(2, RtTerrain.lodDispatchBudget(2, 6));
        assertEquals(1, RtTerrain.lodDispatchBudget(2, 7));
        assertEquals(0, RtTerrain.lodDispatchBudget(2, 8));
        assertEquals(4, RtTerrain.lodDispatchBudget(4, 12));
        assertEquals(0, RtTerrain.lodDispatchBudget(4, 16));
        assertEquals(0, RtTerrain.lodDispatchBudget(32, 32));
    }

    @Test
    void lodDispatchBudgetHandlesDisabledAndDefensiveInputs() {
        assertEquals(0, RtTerrain.lodDispatchBudget(0, 0));
        assertEquals(2, RtTerrain.lodDispatchBudget(2, -3));
    }
}
