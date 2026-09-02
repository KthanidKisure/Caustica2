package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtDlssFgTest {
    @Test
    void clampsRequestedCountToDriverMaximum() {
        assertEquals(1, RtDlssFg.clampMultiFrameCount(1, 3));
        assertEquals(2, RtDlssFg.clampMultiFrameCount(2, 3));
        assertEquals(3, RtDlssFg.clampMultiFrameCount(8, 3));
    }

    @Test
    void unknownOrInvalidDriverMaximumMeansBaselineGeneration() {
        assertEquals(1, RtDlssFg.clampMultiFrameCount(8, 0));
        assertEquals(1, RtDlssFg.clampMultiFrameCount(8, -1));
        assertEquals(1, RtDlssFg.clampMultiFrameCount(0, 0));
    }
}
