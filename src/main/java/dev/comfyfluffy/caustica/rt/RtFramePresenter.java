package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;

import it.unimi.dsi.fastutil.longs.LongList;

import net.minecraft.client.Minecraft;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * DLSS Frame Generation present engine. Shows more than one image per rendered frame: the
 * generated frame(s), then the real frame.
 *
 * <p>It hooks Minecraft's frame tail (Minecraft.java: {@code blitFromTexture} → {@code encoder.submit()} →
 * {@code present()}). At {@code blitFromTexture} TAIL it acquires extra swapchain image(s) and records a
 * Y-flipped blit into <em>Minecraft's own command encoder</em> (the persistent singleton), so MC's
 * once-per-frame {@code submit()} flushes our work in the same {@code vkQueueSubmit} that signals the real
 * frame — this is what makes our present semaphores actually get signaled (the deferred-submit model is why
 * a self-contained present here failed validation). Then at {@code present()} HEAD we present the extra
 * image(s) before MC presents the real one, giving display order generated-then-real.
 *
 * <p>The generated frame is {@link RtDlssFg}'s real DLSSG-interpolated output (via
 * {@link RtComposite#fgInterpolate}); when there's simply no captured RT frame this tick (menu/loading/
 * transition — routine) it falls back to duplicating the real frame for just that one frame (see
 * {@code interpFallbackDuplicate} in the present-rate log), but a genuine FG failure is fatal (see that
 * method's docs) rather than silently duplicating forever. Called from both present paths — the normal SDR
 * {@code blitFromTexture} TAIL, and (via {@code hdrBackbuffer=true}) explicitly from inside the HDR present
 * hook, since the HDR/PQ path cancels {@code blitFromTexture} at HEAD and never reaches the TAIL inject.
 * Gated by {@code caustica.rt.fg} (default off).
 */
public final class RtFramePresenter {
    public static final RtFramePresenter INSTANCE = new RtFramePresenter();

    // This runs on Minecraft's render thread. Swapchain backpressure must drop generated frames for the
    // current real frame instead of freezing simulation and input while waiting for another image.
    private static final long ACQUIRE_TIMEOUT_NS = 0L;

    private static final long LOG_INTERVAL_NS = 1_000_000_000L;

    private long[] acquireSemaphores = new long[0];
    private int acquireCursor;
    private boolean failed;
    private boolean loggedSwapchainLimit;

    // Frames acquired + recorded this frame, awaiting present at present() HEAD (after MC's submit flush).
    private int[] pendingImageIndex = new int[0];
    private long[] pendingPresentSem = new long[0];
    private int pendingCount;

    // Present-rate diagnostics: real vs generated vkQueuePresentKHR calls per second, independent of MC's own
    // fps counter (which only counts rendered/simulated frames, not our extra presents).
    private long logWindowStartNs;
    private int realFramesInWindow;
    private int generatedFramesInWindow;
    private int interpOkInWindow;
    private int interpFallbackInWindow;

    private RtFramePresenter() {
    }

    /** Whether FG extra-present should run this frame (enabled, available, in a world). */
    public boolean isActive() {
        return !failed && RtDlssFg.enabled() && RtDlssFg.INSTANCE.isAvailable()
                && Minecraft.getInstance().level != null;
    }

    /**
     * Acquire {@code generatedCount} extra swapchain images and record a Y-flipped blit of {@code srcImage}
     * (the final rendered frame, GENERAL layout) into each, using Minecraft's command encoder {@code enc} so
     * the work rides MC's next {@code submit()}. The presents happen later in {@link #flushPendingPresents}.
     * Blits DLSSG's real interpolated output per generated frame, or a duplicate of the real frame when RT
     * simply isn't producing frames this tick (routine). A genuine DLSSG failure latches FG off for the
     * session — see {@link RtComposite#fgInterpolate}.
     *
     * @param hdrBackbuffer whether {@code backbufferView}/{@code srcImage} is the PQ HDR backbuffer
     *     ({@link RtComposite#hdrBackbufferView()}, already UI-composited) rather than the SDR main target —
     *     selects DLSSG's HDR backbuffer format/flag in {@link RtComposite#fgInterpolate}.
     */
    public void prepareExtraFrames(VulkanCommandEncoder enc, VulkanDevice device, long swapchain,
            LongList swapchainImages, long[] presentSemaphores, int swapW, int swapH,
            long backbufferView, long srcImage, int srcW, int srcH, int generatedCount, boolean hdrBackbuffer) {
        pendingCount = 0;
        if (failed || swapchain == 0L || srcImage == 0L || generatedCount <= 0) {
            return;
        }
        int requestedGeneratedCount = generatedCount;
        generatedCount = maxGeneratedFramesForSwapchain(generatedCount, swapchainImages.size());
        if (generatedCount <= 0) {
            return;
        }
        if (generatedCount < requestedGeneratedCount && !loggedSwapchainLimit) {
            loggedSwapchainLimit = true;
            CausticaMod.LOGGER.warn(
                    "DLSS-FG requested {} generated frames, but the {}-image swapchain can safely provide {}; clamping",
                    requestedGeneratedCount, swapchainImages.size(), generatedCount);
        }
        try {
            ensureCapacity(device, swapchainImages.size() + 1, generatedCount);
            for (int i = 0; i < generatedCount; i++) {
                // null = no captured RT frame this tick (menu/loading/transition — routine, not a bug): fall
                // back to duplicating the real frame for just this one frame. A genuine FG failure instead
                // throws, caught below, which disables FG for the session.
                RtImage interp = RtComposite.INSTANCE.fgInterpolate(enc, backbufferView, srcImage,
                        swapW, swapH, i + 1, generatedCount, hdrBackbuffer);
                if (interp != null) {
                    interpOkInWindow++;
                } else {
                    interpFallbackInWindow++;
                }
                long blitSrc = interp != null ? interp.image : srcImage;
                int copyW = Math.min(swapW, interp != null ? interp.width : srcW);
                int copyH = Math.min(swapH, interp != null ? interp.height : srcH);

                long acquireSem = acquireSemaphores[acquireCursor];
                acquireCursor = (acquireCursor + 1) % acquireSemaphores.length;

                int imageIndex;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer pIndex = stack.callocInt(1);
                    int r = KHRSwapchain.vkAcquireNextImageKHR(device.vkDevice(), swapchain, ACQUIRE_TIMEOUT_NS, acquireSem, 0L, pIndex);
                    if (r == VK10.VK_NOT_READY || r == VK10.VK_TIMEOUT
                            || r == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                        return; // present anything already recorded; let the real frame drive recovery
                    }
                    if (r != VK10.VK_SUCCESS && r != KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                        throw new IllegalStateException("vkAcquireNextImageKHR(FG) failed: " + r);
                    }
                    imageIndex = pIndex.get(0);
                }
                long dstImage = swapchainImages.getLong(imageIndex);
                long presentSem = presentSemaphores[imageIndex];
                recordBlit(enc, blitSrc, dstImage, copyW, copyH, acquireSem, presentSem);

                pendingImageIndex[pendingCount] = imageIndex;
                pendingPresentSem[pendingCount] = presentSem;
                pendingCount++;
            }
        } catch (Throwable t) {
            failed = true;
            // Frames recorded before this failure still own acquired swapchain images. Flush them once at
            // present() HEAD; discarding the batch here would strand those images until swapchain recreation.
            CausticaMod.LOGGER.error("DLSS-FG present-record failed; frame generation disabled", t);
        }
    }

    /**
     * Present the frames acquired in {@link #prepareExtraFrames} (call at {@code present()} HEAD, after MC's
     * {@code submit()} has flushed — so the present semaphores are signaled — and before MC presents the real
     * frame, giving generated-then-real order).
     */
    public void flushPendingPresents(long swapchain, VkQueue presentQueue) {
        int presentedThisFrame = 0;
        if (pendingCount != 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                for (int i = 0; i < pendingCount; i++) {
                    VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default();
                    present.pWaitSemaphores(stack.longs(pendingPresentSem[i]));
                    present.swapchainCount(1);
                    present.pSwapchains(stack.longs(swapchain));
                    present.pImageIndices(stack.ints(pendingImageIndex[i]));
                    int result = KHRSwapchain.vkQueuePresentKHR(presentQueue, present);
                    if (result == VK10.VK_SUCCESS || result == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                        presentedThisFrame++;
                    } else if (result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                        // Normal resize/recreate path. Stop generated presents for this frame and let
                        // Minecraft's real present drive swapchain recovery without latching FG off.
                        break;
                    } else {
                        throw new IllegalStateException("vkQueuePresentKHR(FG) failed: " + result);
                    }
                }
            } catch (Throwable t) {
                failed = true;
                CausticaMod.LOGGER.error("DLSS-FG present failed; frame generation disabled", t);
            } finally {
                pendingCount = 0;
            }
        }
        if (RtDlssFg.enabled()) {
            logPresentRate(presentedThisFrame);
        }
    }

    /**
     * Minecraft already owns one swapchain image for the real frame while this method records extras.
     * Acquiring more than the remaining images before any present can complete would self-block.
     */
    static int maxGeneratedFramesForSwapchain(int requested, int swapchainImageCount) {
        if (requested <= 0 || swapchainImageCount <= 1) {
            return 0;
        }
        return Math.min(requested, swapchainImageCount - 1);
    }

    /**
     * Tracks real vs generated {@code vkQueuePresentKHR} calls per second,
     * separate from MC's own fps counter — that counter only reflects simulated/rendered frames
     * (blitFromTexture calls), so it would NOT show an increase from FG's extra presents even if the display
     * is genuinely receiving more frames. Logged only when {@code caustica.rt.fg} is enabled.
     */
    private void logPresentRate(int generatedThisFrame) {
        realFramesInWindow++;
        generatedFramesInWindow += generatedThisFrame;
        long now = System.nanoTime();
        if (logWindowStartNs == 0L) {
            logWindowStartNs = now;
            return;
        }
        long elapsed = now - logWindowStartNs;
        if (elapsed < LOG_INTERVAL_NS) {
            return;
        }
        double seconds = elapsed / 1.0e9;
        double realFps = realFramesInWindow / seconds;
        double totalFps = (realFramesInWindow + generatedFramesInWindow) / seconds;
        CausticaMod.LOGGER.info(
                "[FG present-rate] real={} gen={} realFps={} totalPresentFps={} configuredMultiFrameCount={} "
                        + "interpOk={} interpFallbackDuplicate={}",
                realFramesInWindow, generatedFramesInWindow,
                String.format("%.1f", realFps), String.format("%.1f", totalFps),
                RtDlssFg.INSTANCE.effectiveMultiFrameCount(), interpOkInWindow, interpFallbackInWindow);
        logWindowStartNs = now;
        realFramesInWindow = 0;
        generatedFramesInWindow = 0;
        interpOkInWindow = 0;
        interpFallbackInWindow = 0;
    }

    private void recordBlit(VulkanCommandEncoder enc, long srcImage, long dstImage, int copyW, int copyH,
            long acquireSem, long presentSem) {
        VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Swapchain UNDEFINED -> TRANSFER_DST (stage/access values mirror MC's blitFromTexture).
            VkImageMemoryBarrier2.Buffer beforeBlit = VkImageMemoryBarrier2.calloc(2, stack).sType$Default();
            // Swapchain UNDEFINED -> TRANSFER_DST.
            beforeBlit.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
            beforeBlit.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            // Make only the actual source image's prior writes visible to the transfer read. This replaces
            // the old global memory barrier, avoiding an unnecessary dependency on unrelated RT resources.
            beforeBlit.get(1).sType$Default().srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(srcImage);
            beforeBlit.get(1).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(beforeBlit);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit final frame (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL, dstImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg blit) failed");
        }
        // Register on MC's encoder (same order as MC's blitFromTexture): wait on the acquire, run the blit,
        // signal the image's present semaphore. MC's once-per-frame submit() flushes this in one
        // vkQueueSubmit, which is what actually signals presentSem (deferred-submit model).
        enc.waitSemaphore(acquireSem, 0L, 65536L);
        enc.execute(cmd);
        enc.signalSemaphore(presentSem, 0L, 4096L);
    }

    private void ensureCapacity(VulkanDevice device, int semaphoreCount, int generatedCount) {
        if (pendingImageIndex.length < generatedCount) {
            pendingImageIndex = new int[generatedCount];
            pendingPresentSem = new long[generatedCount];
        }
        if (acquireSemaphores.length >= semaphoreCount) {
            return;
        }
        long[] replacement = new long[semaphoreCount];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer p = stack.mallocLong(1);
            for (int i = 0; i < semaphoreCount; i++) {
                if (VK10.vkCreateSemaphore(device.vkDevice(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSemaphore(fg acquire) failed at " + i + "/" + semaphoreCount);
                }
                replacement[i] = p.get(0);
            }
        } catch (Throwable failure) {
            for (long sem : replacement) {
                if (sem != 0L) {
                    VK10.vkDestroySemaphore(device.vkDevice(), sem, null);
                }
            }
            throw failure;
        }
        // Pool growth is rare (initial creation / swapchain image-count increase). The old binary acquire
        // semaphores may still be referenced by the previous graphics submit, so do not destroy them until
        // the queue/device is idle. Never put this wait on the steady-state present path.
        if (acquireSemaphores.length != 0) {
            int idle = VK10.vkDeviceWaitIdle(device.vkDevice());
            if (idle != VK10.VK_SUCCESS) {
                for (long sem : replacement) {
                    if (sem != 0L) {
                        VK10.vkDestroySemaphore(device.vkDevice(), sem, null);
                    }
                }
                throw new IllegalStateException("vkDeviceWaitIdle(FG semaphore pool resize) failed: " + idle);
            }
            for (long sem : acquireSemaphores) {
                if (sem != 0L) {
                    VK10.vkDestroySemaphore(device.vkDevice(), sem, null);
                }
            }
        }
        acquireSemaphores = replacement;
        acquireCursor = 0;
    }

    /** Destroy the acquire-semaphore pool (device teardown). */
    public void destroy(VulkanDevice device) {
        for (long sem : acquireSemaphores) {
            if (sem != 0L) {
                VK10.vkDestroySemaphore(device.vkDevice(), sem, null);
            }
        }
        acquireSemaphores = new long[0];
        acquireCursor = 0;
        pendingImageIndex = new int[0];
        pendingPresentSem = new long[0];
        pendingCount = 0;
        failed = false;
        loggedSwapchainLimit = false;
        logWindowStartNs = 0L;
        realFramesInWindow = 0;
        generatedFramesInWindow = 0;
        interpOkInWindow = 0;
        interpFallbackInWindow = 0;
    }
}
