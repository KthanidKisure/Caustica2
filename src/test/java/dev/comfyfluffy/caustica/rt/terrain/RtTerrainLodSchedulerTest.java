package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtTerrainLodSchedulerTest {
    @Test
    void boundsDispatchToFourFramesOfWork() {
        assertEquals(2, RtTerrain.lodDispatchBudget(2, 0));
        assertEquals(2, RtTerrain.lodDispatchBudget(2, 6));
        assertEquals(1, RtTerrain.lodDispatchBudget(2, 7));
        assertEquals(0, RtTerrain.lodDispatchBudget(2, 8));
        assertEquals(4, RtTerrain.lodDispatchBudget(4, 12));
        assertEquals(0, RtTerrain.lodDispatchBudget(4, 16));
        assertEquals(0, RtTerrain.lodDispatchBudget(32, 32));
    }

    @Test
    void dispatchBudgetHandlesDisabledAndDefensiveInputs() {
        assertEquals(0, RtTerrain.lodDispatchBudget(0, 0));
        assertEquals(2, RtTerrain.lodDispatchBudget(2, -3));
    }

    @Test
    void boundsScannerWorkWhenTheRingIsAlreadyFull() {
        assertEquals(0, RtTerrain.lodScanBudget(0, 4_624));
        assertEquals(64, RtTerrain.lodScanBudget(2, 64));
        assertEquals(256, RtTerrain.lodScanBudget(2, 4_624));
        assertEquals(4_096, RtTerrain.lodScanBudget(32, 266_256));
    }

    @Test
    void enumeratesOffsetsInNearestFirstRings() {
        assertEquals(0L, RtTerrain.lodHorizontalOffset(0));
        assertOffset(1, -1, -1);
        assertOffset(2, 0, -1);
        assertOffset(3, 1, -1);
        assertOffset(4, 1, 0);
        assertOffset(5, 1, 1);
        assertOffset(6, 0, 1);
        assertOffset(7, -1, 1);
        assertOffset(8, -1, 0);
        assertOffset(9, -2, -2);
    }

    @Test
    void visitsEveryOffsetInTheConfiguredSquareOnce() {
        int radius = 64;
        int side = radius * 2 + 1;
        Set<Long> offsets = new HashSet<>();
        for (int ordinal = 0; ordinal < side * side; ordinal++) {
            long packed = RtTerrain.lodHorizontalOffset(ordinal);
            int x = (int) (packed >> 32);
            int z = (int) packed;
            assertEquals(Math.max(Math.abs(x), Math.abs(z)),
                    (int) Math.ceil((Math.sqrt(ordinal + 1.0) - 1.0) * 0.5));
            offsets.add(packed);
        }
        assertEquals(side * side, offsets.size());
    }

    private static void assertOffset(int ordinal, int expectedX, int expectedZ) {
        long packed = RtTerrain.lodHorizontalOffset(ordinal);
        assertEquals(expectedX, (int) (packed >> 32));
        assertEquals(expectedZ, (int) packed);
    }
}
