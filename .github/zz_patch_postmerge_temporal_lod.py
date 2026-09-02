from pathlib import Path

# --- DLSS RR temporal invalidation API --------------------------------------
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssRr.java')
s = p.read_text()
anchor = '''    public boolean isReady() {\n        return initialized && !failed && !isNull(feature);\n    }\n\n'''
insert = '''    public boolean isReady() {\n        return initialized && !failed && !isNull(feature);\n    }\n\n    /**\n     * Discard temporal reconstruction history without destroying the feature. Used for teleports,\n     * same-dimension world/proxy swaps and long render discontinuities where old motion/history is invalid.\n     */\n    public void invalidateHistory() {\n        resetHistory = true;\n        lastFrameNanos = 0L;\n    }\n\n'''
assert anchor in s
s = s.replace(anchor, insert, 1)
p.write_text(s)

# --- Composite temporal continuity + HDR barrier scoping --------------------
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java')
s = p.read_text()
# ClientLevel type for identity tracking.
s = s.replace('import net.minecraft.client.Minecraft;\n',
              'import net.minecraft.client.Minecraft;\nimport net.minecraft.client.multiplayer.ClientLevel;\n', 1)

old = '''    private boolean mvHasPrev;\n    private float previousWaterWaveTime;\n    private boolean waterWaveTimeValid;\n    private long atlasSampler;\n'''
new = '''    private boolean mvHasPrev;\n    private float previousWaterWaveTime;\n    private boolean waterWaveTimeValid;\n    // Temporal reconstruction must never bridge a proxy/world replacement, teleport, or long pause.\n    // Those discontinuities invalidate RR, ReSTIR, FG and camera/water reprojection together.\n    private static final long TEMPORAL_GAP_NS = 750_000_000L;\n    private static final double TEMPORAL_JUMP_DISTANCE_SQ = 64.0 * 64.0;\n    private ClientLevel temporalLevel;\n    private long temporalLastFrameNanos;\n    private double temporalLastCamX;\n    private double temporalLastCamY;\n    private double temporalLastCamZ;\n    private boolean temporalPositionValid;\n    private long atlasSampler;\n'''
assert old in s
s = s.replace(old, new, 1)

# Insert continuity helpers immediately before updateMotion.
anchor = '''    private void updateMotion() {\n'''
helpers = '''    private void updateTemporalContinuity(ClientLevel level) {\n        long now = System.nanoTime();\n        boolean levelChanged = level != temporalLevel;\n        boolean longGap = temporalLastFrameNanos != 0L && now - temporalLastFrameNanos > TEMPORAL_GAP_NS;\n        boolean cameraJump = false;\n        if (temporalPositionValid) {\n            double dx = camX - temporalLastCamX;\n            double dy = camY - temporalLastCamY;\n            double dz = camZ - temporalLastCamZ;\n            cameraJump = dx * dx + dy * dy + dz * dz > TEMPORAL_JUMP_DISTANCE_SQ;\n        }\n        if (levelChanged || longGap || cameraJump) {\n            invalidateTemporalHistory(levelChanged);\n        }\n        temporalLevel = level;\n        temporalLastFrameNanos = now;\n        temporalLastCamX = camX;\n        temporalLastCamY = camY;\n        temporalLastCamZ = camZ;\n        temporalPositionValid = true;\n    }\n\n    private void invalidateTemporalHistory(boolean resetExposure) {\n        mvHasPrev = false;\n        reservoirHistoryValid = false;\n        fgReset = true;\n        waterWaveTimeValid = false;\n        RtDlssRr.INSTANCE.invalidateHistory();\n        if (resetExposure) {\n            exposure.requestReset();\n        }\n    }\n\n'''
assert anchor in s
s = s.replace(anchor, helpers + anchor, 1)

old = '''            refreshMaterialBindingsIfNeeded(ctx);\n            updateMotion();\n            recordFrame(ctx, active, nativeColor);\n'''
new = '''            refreshMaterialBindingsIfNeeded(ctx);\n            updateTemporalContinuity(Minecraft.getInstance().level);\n            updateMotion();\n            recordFrame(ctx, active, nativeColor);\n'''
assert old in s
s = s.replace(old, new, 1)

# Ensure a full composite teardown cannot carry continuity bookkeeping into a later RT lifetime.
old = '''        fgInterpW = -1;\n        fgInterpH = -1;\n        fgInterpFormat = Integer.MIN_VALUE;\n        if (worldPipeline != null) {\n'''
new = '''        fgInterpW = -1;\n        fgInterpH = -1;\n        fgInterpFormat = Integer.MIN_VALUE;\n        temporalLevel = null;\n        temporalLastFrameNanos = 0L;\n        temporalPositionValid = false;\n        mvHasPrev = false;\n        reservoirHistoryValid = false;\n        fgReset = true;\n        waterWaveTimeValid = false;\n        if (worldPipeline != null) {\n'''
assert old in s
s = s.replace(old, new, 1)

# Narrow HDR world->blit synchronization to the actual source image, matching the hardened FG path.
old = '''            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the HDR compute writes visible to the blit read.\n            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();\n            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)\n                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)\n                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);\n            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);\n            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();\n            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);\n            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);\n            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);\n'''
new = '''            // Swapchain UNDEFINED -> TRANSFER_DST, plus make only this HDR image's prior writes visible\n            // to the transfer read. A global memory barrier needlessly serialized unrelated RT resources.\n            VkImageMemoryBarrier2.Buffer beforeBlit = VkImageMemoryBarrier2.calloc(2, stack).sType$Default();\n            beforeBlit.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)\n                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)\n                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);\n            beforeBlit.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)\n                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);\n            beforeBlit.get(1).sType$Default().srcStageMask(65536L).srcAccessMask(65536L)\n                    .dstStageMask(4096L).dstAccessMask(2048L)\n                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)\n                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(src.image);\n            beforeBlit.get(1).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)\n                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);\n            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(beforeBlit);\n            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);\n'''
assert old in s
s = s.replace(old, new, 1)

old = '''            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();\n            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);\n            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);\n'''
new = '''            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent);\n'''
assert old in s
s = s.replace(old, new, 1)
p.write_text(s)

# --- Move packed-source/import session work to the warmed client tick -------
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtCausticaLodPackedSource.java')
s = p.read_text()
old = '''    static boolean available() {\n        Minecraft mc = Minecraft.getInstance();\n        ClientLevel level = mc.level;\n        if (level == null || mc.player == null) {\n            return false;\n        }\n        ensureSession(mc, level);\n        boolean ready = packReady;\n        if (!ready) {\n            // The importer publishes packReady after the completion marker is durable enough for this\n            // process. Avoid a Files.isRegularFile() metadata lookup on every LOD worker query.\n            RtCausticaLodImporter.tick(mc, level, root, identity);\n        }\n        ready = packReady;\n        if (ready && LOGGED_PACK_READY.compareAndSet(false, true)) {\n            CausticaMod.LOGGER.info("CausticaLOD packed WynnLOD source is active; no DH/Voxy runtime is involved");\n        }\n        return ready;\n    }\n'''
new = '''    static boolean available() {\n        return packReady;\n    }\n\n    /**\n     * Client-thread session/import driver. Worker-side availability/fetch stays read-only and never derives\n     * Minecraft session state or validates/kicks import files. Called only after the warm-up gate.\n     */\n    static void tick() {\n        Minecraft mc = Minecraft.getInstance();\n        ClientLevel level = mc.level;\n        if (level == null || mc.player == null) {\n            return;\n        }\n        ensureSession(mc, level);\n        if (!packReady) {\n            RtCausticaLodImporter.tick(mc, level, root, identity);\n        }\n        if (packReady && LOGGED_PACK_READY.compareAndSet(false, true)) {\n            CausticaMod.LOGGER.info("CausticaLOD packed WynnLOD source is active; no DH/Voxy runtime is involved");\n        }\n    }\n'''
assert old in s
s = s.replace(old, new, 1)

# Fixed striped load gates suppress duplicate same-tile reads/decompression without a global IO lock.
anchor = '''    private static final RtLodTileCache<Boolean> MISSING = new RtLodTileCache<>(32768);\n'''
insert = '''    private static final RtLodTileCache<Boolean> MISSING = new RtLodTileCache<>(32768);\n    private static final Object[] LOAD_LOCKS = createLoadLocks(256);\n'''
assert anchor in s
s = s.replace(anchor, insert, 1)

old = '''    private static RtCausticaLodRegionStore.TileData tile(int chunkX, int chunkZ) {\n        long key = packCoords(chunkX, chunkZ);\n        RtCausticaLodRegionStore.TileData cached = MEMORY.get(key);\n        if (cached != null) {\n            return cached;\n        }\n        if (MISSING.containsKey(key)) {\n            return null;\n        }\n        Path sessionRoot = root;\n        if (sessionRoot == null) {\n            return null;\n        }\n        RtCausticaLodRegionStore.TileData loaded = readLiveTile(\n                sessionRoot.resolve("c." + chunkX + "." + chunkZ + ".clod"), chunkX, chunkZ);\n        if (loaded == null) {\n            loaded = RtCausticaLodRegionStore.read(sessionRoot, chunkX, chunkZ);\n        }\n        if (loaded == null) {\n            MISSING.put(key, Boolean.TRUE);\n            return null;\n        }\n        MISSING.remove(key);\n        RtCausticaLodRegionStore.TileData raced = MEMORY.putIfAbsent(key, loaded);\n        return raced != null ? raced : loaded;\n    }\n'''
new = '''    private static RtCausticaLodRegionStore.TileData tile(int chunkX, int chunkZ) {\n        long key = packCoords(chunkX, chunkZ);\n        RtCausticaLodRegionStore.TileData cached = MEMORY.get(key);\n        if (cached != null) {\n            return cached;\n        }\n        if (MISSING.containsKey(key)) {\n            return null;\n        }\n        synchronized (loadLock(key)) {\n            cached = MEMORY.get(key);\n            if (cached != null) {\n                return cached;\n            }\n            if (MISSING.containsKey(key)) {\n                return null;\n            }\n            Path sessionRoot = root;\n            if (sessionRoot == null) {\n                return null;\n            }\n            RtCausticaLodRegionStore.TileData loaded = readLiveTile(\n                    sessionRoot.resolve("c." + chunkX + "." + chunkZ + ".clod"), chunkX, chunkZ);\n            if (loaded == null) {\n                loaded = RtCausticaLodRegionStore.read(sessionRoot, chunkX, chunkZ);\n            }\n            if (loaded == null) {\n                MISSING.put(key, Boolean.TRUE);\n                return null;\n            }\n            MISSING.remove(key);\n            RtCausticaLodRegionStore.TileData raced = MEMORY.putIfAbsent(key, loaded);\n            return raced != null ? raced : loaded;\n        }\n    }\n'''
assert old in s
s = s.replace(old, new, 1)

anchor = '''    private static long packCoords(int x, int z) {\n'''
helpers = '''    private static Object[] createLoadLocks(int count) {\n        Object[] locks = new Object[count];\n        java.util.Arrays.setAll(locks, ignored -> new Object());\n        return locks;\n    }\n\n    private static Object loadLock(long key) {\n        long mixed = key ^ (key >>> 33) ^ (key << 11);\n        return LOAD_LOCKS[(int) mixed & (LOAD_LOCKS.length - 1)];\n    }\n\n'''
assert anchor in s
s = s.replace(anchor, helpers + anchor, 1)
p.write_text(s)

# --- Live native source duplicate-read suppression --------------------------
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtCausticaLodSource.java')
s = p.read_text()
anchor = '''    private static final RtLodTileCache<Boolean> UNAVAILABLE_ON_DISK = new RtLodTileCache<>(32768);\n'''
insert = '''    private static final RtLodTileCache<Boolean> UNAVAILABLE_ON_DISK = new RtLodTileCache<>(32768);\n    private static final Object[] LOAD_LOCKS = createLoadLocks(256);\n'''
assert anchor in s
s = s.replace(anchor, insert, 1)

old = '''    private static SurfaceTile tile(int chunkX, int chunkZ) {\n        long key = chunkKey(chunkX, chunkZ);\n        SurfaceTile resident = MEMORY.get(key);\n        if (resident != null) {\n            return resident;\n        }\n        if (UNAVAILABLE_ON_DISK.containsKey(key)) {\n            return null;\n        }\n        Path path = tilePath(chunkX, chunkZ);\n        if (path == null) {\n            return null;\n        }\n        if (!Files.isRegularFile(path)) {\n            UNAVAILABLE_ON_DISK.put(key, Boolean.TRUE);\n            return null;\n        }\n        SurfaceTile loaded = readTile(path, chunkX, chunkZ);\n        if (loaded != null) {\n            UNAVAILABLE_ON_DISK.remove(key);\n            SurfaceTile raced = MEMORY.putIfAbsent(key, loaded);\n            KNOWN_ON_DISK.put(key, Boolean.TRUE);\n            if (LOGGED_FIRST_DISK_LOAD.compareAndSet(false, true)) {\n                CausticaMod.LOGGER.info("CausticaLOD restored persistent native surface tile {},{}", chunkX, chunkZ);\n            }\n            return raced != null ? raced : loaded;\n        }\n        // A stale/truncated tile must not permanently suppress recapture merely because the file exists.\n        KNOWN_ON_DISK.remove(key);\n        UNAVAILABLE_ON_DISK.put(key, Boolean.TRUE);\n        return null;\n    }\n'''
new = '''    private static SurfaceTile tile(int chunkX, int chunkZ) {\n        long key = chunkKey(chunkX, chunkZ);\n        SurfaceTile resident = MEMORY.get(key);\n        if (resident != null) {\n            return resident;\n        }\n        if (UNAVAILABLE_ON_DISK.containsKey(key)) {\n            return null;\n        }\n        synchronized (loadLock(key)) {\n            resident = MEMORY.get(key);\n            if (resident != null) {\n                return resident;\n            }\n            if (UNAVAILABLE_ON_DISK.containsKey(key)) {\n                return null;\n            }\n            Path path = tilePath(chunkX, chunkZ);\n            if (path == null) {\n                return null;\n            }\n            if (!Files.isRegularFile(path)) {\n                UNAVAILABLE_ON_DISK.put(key, Boolean.TRUE);\n                return null;\n            }\n            SurfaceTile loaded = readTile(path, chunkX, chunkZ);\n            if (loaded != null) {\n                UNAVAILABLE_ON_DISK.remove(key);\n                SurfaceTile raced = MEMORY.putIfAbsent(key, loaded);\n                KNOWN_ON_DISK.put(key, Boolean.TRUE);\n                if (LOGGED_FIRST_DISK_LOAD.compareAndSet(false, true)) {\n                    CausticaMod.LOGGER.info("CausticaLOD restored persistent native surface tile {},{}", chunkX, chunkZ);\n                }\n                return raced != null ? raced : loaded;\n            }\n            // A stale/truncated tile must not permanently suppress recapture merely because the file exists.\n            KNOWN_ON_DISK.remove(key);\n            UNAVAILABLE_ON_DISK.put(key, Boolean.TRUE);\n            return null;\n        }\n    }\n'''
assert old in s
s = s.replace(old, new, 1)

anchor = '''    private static long chunkKey(int chunkX, int chunkZ) {\n'''
helpers = '''    private static Object[] createLoadLocks(int count) {\n        Object[] locks = new Object[count];\n        java.util.Arrays.setAll(locks, ignored -> new Object());\n        return locks;\n    }\n\n    private static Object loadLock(long key) {\n        long mixed = key ^ (key >>> 33) ^ (key << 11);\n        return LOAD_LOCKS[(int) mixed & (LOAD_LOCKS.length - 1)];\n    }\n\n'''
assert anchor in s
s = s.replace(anchor, helpers + anchor, 1)
p.write_text(s)

# --- Facade/client tick importer ownership ----------------------------------
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtDhLodSource.java')
s = p.read_text()
old = '''    public static boolean available() {\n        // Calling packed availability also starts the one-time WynnLOD conversion when the current\n        // server is Wynncraft and LOD is enabled. It only schedules background work; no IO happens here.\n        return RtCausticaLodPackedSource.available() || RtCausticaLodSource.available();\n    }\n'''
new = '''    public static boolean available() {\n        return RtCausticaLodPackedSource.available() || RtCausticaLodSource.available();\n    }\n\n    /** Client-thread driver for packed-session discovery and the optional one-time WynnLOD import. */\n    public static void tick() {\n        RtCausticaLodPackedSource.tick();\n    }\n'''
assert old in s
s = s.replace(old, new, 1)
p.write_text(s)

p = Path('src/main/java/dev/comfyfluffy/caustica/client/CausticaClient.java')
s = p.read_text()
old = '''\t\t\tif (RtCausticaLodWarmup.ready(client.level)) {\n\t\t\t\tRtCausticaLodSource.tick();\n\t\t\t}\n'''
new = '''\t\t\tif (RtCausticaLodWarmup.ready(client.level)) {\n\t\t\t\tRtCausticaLodSource.tick();\n\t\t\t\tRtDhLodSource.tick();\n\t\t\t}\n'''
assert old in s
s = s.replace(old, new, 1)
p.write_text(s)
