from pathlib import Path

p = Path('src/main/java/dev/comfyfluffy/caustica/rt/RtFramePresenter.java')
s = p.read_text()

# Check vkQueuePresentKHR return values instead of silently counting failures.
old = '''                    KHRSwapchain.vkQueuePresentKHR(presentQueue, present);
                    presentedThisFrame++;
'''
new = '''                    int result = KHRSwapchain.vkQueuePresentKHR(presentQueue, present);
                    if (result == VK10.VK_SUCCESS || result == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                        presentedThisFrame++;
                    } else if (result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                        // Normal resize/recreate path. Stop generated presents for this frame and let
                        // Minecraft's real present drive swapchain recovery without latching FG off.
                        break;
                    } else {
                        throw new IllegalStateException("vkQueuePresentKHR(FG) failed: " + result);
                    }
'''
assert old in s
s = s.replace(old, new, 1)

# Scope the producer->blit visibility dependency to the actual source image instead of a global memory barrier.
old = '''            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            // Make the source's prior writes visible to the blit read — the world render (duplicate path) or
            // the DLSSG evaluate that wrote the interp image earlier in this same submit (interp path).
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);
'''
new = '''            VkImageMemoryBarrier2.Buffer beforeBlit = VkImageMemoryBarrier2.calloc(2, stack).sType$Default();
            // Swapchain UNDEFINED -> TRANSFER_DST.
            beforeBlit.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
            beforeBlit.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            // Make only the actual source image's prior writes visible to the transfer read. This replaces
            // the old global memory barrier, avoiding an unnecessary dependency on unrelated RT resources.
            beforeBlit.get(1).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(srcImage);
            beforeBlit.get(1).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(beforeBlit);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);
'''
assert old in s
s = s.replace(old, new, 1)

# The destination image barrier fully covers the blit write before present; remove the unrelated global barrier.
old = '''            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
'''
new = '''            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent);
'''
assert old in s
s = s.replace(old, new, 1)

# Make semaphore pool growth transactional and wait only on the rare resize/growth path before destroying old semaphores.
old = '''        if (acquireSemaphores.length >= semaphoreCount) {
            return;
        }
        destroy(device);
        acquireSemaphores = new long[semaphoreCount];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer p = stack.mallocLong(1);
            for (int i = 0; i < semaphoreCount; i++) {
                if (VK10.vkCreateSemaphore(device.vkDevice(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSemaphore(fg acquire) failed");
                }
                acquireSemaphores[i] = p.get(0);
            }
        }
        acquireCursor = 0;
'''
new = '''        if (acquireSemaphores.length >= semaphoreCount) {
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
'''
assert old in s
s = s.replace(old, new, 1)

# Reset present-engine failure + diagnostic state at device teardown.
old = '''        acquireSemaphores = new long[0];
        acquireCursor = 0;
        pendingCount = 0;
    }
}
'''
new = '''        acquireSemaphores = new long[0];
        acquireCursor = 0;
        pendingImageIndex = new int[0];
        pendingPresentSem = new long[0];
        pendingCount = 0;
        failed = false;
        logWindowStartNs = 0L;
        realFramesInWindow = 0;
        generatedFramesInWindow = 0;
        interpOkInWindow = 0;
        interpFallbackInWindow = 0;
    }
}
'''
assert old in s
s = s.replace(old, new, 1)

p.write_text(s)
