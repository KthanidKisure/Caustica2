package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtFramePresenterTest {
    @Test
    void reservesOneSwapchainImageForTheRealFrame() {
        assertEquals(1, RtFramePresenter.maxGeneratedFramesForSwapchain(3, 2));
        assertEquals(2, RtFramePresenter.maxGeneratedFramesForSwapchain(3, 3));
        assertEquals(3, RtFramePresenter.maxGeneratedFramesForSwapchain(3, 4));
    }

    @Test
    void preservesSmallerRequestedCount() {
        assertEquals(1, RtFramePresenter.maxGeneratedFramesForSwapchain(1, 3));
        assertEquals(2, RtFramePresenter.maxGeneratedFramesForSwapchain(2, 4));
    }

    @Test
    void rejectsNonPositiveOrUnusableCounts() {
        assertEquals(0, RtFramePresenter.maxGeneratedFramesForSwapchain(0, 3));
        assertEquals(0, RtFramePresenter.maxGeneratedFramesForSwapchain(-1, 3));
        assertEquals(0, RtFramePresenter.maxGeneratedFramesForSwapchain(2, 1));
        assertEquals(0, RtFramePresenter.maxGeneratedFramesForSwapchain(2, 0));
    }
}
