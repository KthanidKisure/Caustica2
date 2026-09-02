package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.InflaterInputStream;

/** Runtime reader for immutable imported CausticaLOD region packs. */
final class RtCausticaLodPackedSource {
    private static final int LIVE_MAGIC = 0x434C4F44; // CLOD
    private static final int LIVE_VERSION = 1;
    private static final int TILE_EDGE = RtCausticaLodRegionStore.TILE_EDGE;
    private static final short NO_HEIGHT = RtCausticaLodRegionStore.NO_HEIGHT;

    private static final RtLodTileCache<RtCausticaLodRegionStore.TileData> MEMORY = new RtLodTileCache<>(8192);
    private static final int GRID_EDGE = RtDhLodRegion.SECTION_BLOCKS + 2;
    private static final int GRID_CELLS = GRID_EDGE * GRID_EDGE;
    /** Per-worker reusable page + five-point reducer scratch; no per-page SurfaceColumn object burst. */
    private static final ThreadLocal<PageScratch> PAGE_SCRATCH =
            ThreadLocal.withInitial(PageScratch::new);
    /** Negative cache for uncovered chunks; imported regions are immutable once the completion marker exists. */
    private static final RtLodTileCache<Boolean> MISSING = new RtLodTileCache<>(32768);
    private static final AtomicBoolean LOGGED_FIRST_QUERY = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_PACK_READY = new AtomicBoolean();

    private static volatile String identity = "";
    private static volatile Path root;
    private static volatile boolean packReady;

    private RtCausticaLodPackedSource() {
    }

    /**
     * Prepares the current session and starts the one-time Wynn import when appropriate. Returns true
     * only after a complete imported pack is present; ordinary live native capture remains the fallback.
     */
    static boolean available() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return false;
        }
        ensureSession(mc, level);
        boolean ready = packReady;
        if (!ready) {
            // The importer publishes packReady after the completion marker is durable enough for this
            // process. Avoid a Files.isRegularFile() metadata lookup on every LOD worker query.
            RtCausticaLodImporter.tick(mc, level, root, identity);
        }
        ready = packReady;
        if (ready && LOGGED_PACK_READY.compareAndSet(false, true)) {
            CausticaMod.LOGGER.info("CausticaLOD packed WynnLOD source is active; no DH/Voxy runtime is involved");
        }
        return ready;
    }

    static RtCausticaLodSource.FetchResult fetchArea(int footprintBlocks, int originBlockX, int originBlockZ) {
        if (!available() || footprintBlocks < 16) {
            return new RtCausticaLodSource.FetchResult(List.of(), false);
        }
        int scale = Math.max(1, footprintBlocks / RtDhLodRegion.SECTION_BLOCKS);
        PageScratch surfaceGrid = sampleSurfaceGrid(originBlockX, originBlockZ, scale);
        ArrayList<RtCausticaLodSource.LodBox> boxes =
                new ArrayList<>(RtDhLodRegion.SECTION_BLOCKS * RtDhLodRegion.SECTION_BLOCKS * 3);
        int resolvedCells = 0;

        for (int vx = 0; vx < RtDhLodRegion.SECTION_BLOCKS; vx++) {
            int cellX = originBlockX + vx * scale;
            for (int vz = 0; vz < RtDhLodRegion.SECTION_BLOCKS; vz++) {
                int cellZ = originBlockZ + vz * scale;
                int gridSlot = gridIndex(vx + 1, vz + 1);
                short sampledGroundY = surfaceGrid.groundY[gridSlot];
                if (sampledGroundY == NO_HEIGHT) {
                    continue;
                }
                resolvedCells++;

                int groundY = sampledGroundY;
                int surfaceY = Math.max(surfaceGrid.surfaceY[gridSlot], groundY);
                BlockState ground = stateById(surfaceGrid.groundStateId[gridSlot]);
                BlockState body = stateById(surfaceGrid.bodyStateId[gridSlot]);
                BlockState surface = stateById(surfaceGrid.surfaceStateId[gridSlot]);
                if (ground.isAir()) {
                    ground = body.isAir() ? Blocks.STONE.defaultBlockState() : body;
                }
                if (body.isAir()) {
                    body = ground;
                }

                int skirtBottom = skirtBottom(surfaceGrid, vx + 1, vz + 1, groundY);
                if (skirtBottom < groundY) {
                    boxes.add(new RtCausticaLodSource.LodBox(
                            cellX, skirtBottom, groundY, cellZ, scale, body, 0, 15));
                }
                boxes.add(new RtCausticaLodSource.LodBox(cellX, groundY, groundY + 1, cellZ, scale, ground, 0, 15));
                if (surfaceY > groundY) {
                    if (!surface.getFluidState().isEmpty()) {
                        boxes.add(new RtCausticaLodSource.LodBox(
                                cellX, groundY + 1, surfaceY + 1, cellZ, scale, surface, 0, 15));
                    } else if (!surface.isAir()) {
                        boxes.add(new RtCausticaLodSource.LodBox(
                                cellX, surfaceY, surfaceY + 1, cellZ, scale, surface, 0, 15));
                    }
                }
            }
        }
        if (resolvedCells == 0) {
            // The imported pack is immutable once packReady is true. No covered cells is therefore a
            // confirmed empty/uncovered result, not a transient source failure that should stall other pages.
            return new RtCausticaLodSource.FetchResult(List.of(), true);
        }
        if (LOGGED_FIRST_QUERY.compareAndSet(false, true)) {
            CausticaMod.LOGGER.info("CausticaLOD packed query succeeded: {}/{} cells, {} surface boxes",
                    resolvedCells, RtDhLodRegion.SECTION_BLOCKS * RtDhLodRegion.SECTION_BLOCKS, boxes.size());
        }
        return new RtCausticaLodSource.FetchResult(boxes, true);
    }

    static void invalidateTile(int chunkX, int chunkZ) {
        long key = packCoords(chunkX, chunkZ);
        MEMORY.remove(key);
        MISSING.remove(key);
    }

    /** Called by the background importer only after every region and the completion marker are published. */
    static synchronized void markImportedPackReady(Path completedRoot) {
        if (completedRoot == null || root == null || !root.equals(completedRoot)) {
            return; // import finished for a session that is no longer active
        }
        MISSING.clear();
        RtCausticaLodRegionStore.reset();
        packReady = true;
    }

    static synchronized void invalidate() {
        MEMORY.clear();
        MISSING.clear();
        identity = "";
        root = null;
        packReady = false;
        LOGGED_FIRST_QUERY.set(false);
        LOGGED_PACK_READY.set(false);
        RtCausticaLodRegionStore.reset();
    }

    /** Pre-sample the page plus a one-cell border so cliff skirts reuse exactly the same data. */
    private static PageScratch sampleSurfaceGrid(int originBlockX, int originBlockZ, int scale) {
        PageScratch grid = PAGE_SCRATCH.get();
        grid.resetGrid();
        for (int gx = -1; gx <= RtDhLodRegion.SECTION_BLOCKS; gx++) {
            int x = originBlockX + gx * scale;
            for (int gz = -1; gz <= RtDhLodRegion.SECTION_BLOCKS; gz++) {
                int z = originBlockZ + gz * scale;
                representativeColumn(grid, gridIndex(gx + 1, gz + 1), x, z, scale);
            }
        }
        return grid;
    }

    private static int gridIndex(int x, int z) {
        return x * GRID_EDGE + z;
    }

    private static int skirtBottom(PageScratch grid, int gx, int gz, int groundY) {
        int bottom = groundY;
        short neighbor = grid.groundY[gridIndex(gx - 1, gz)];
        if (neighbor != NO_HEIGHT) {
            bottom = Math.min(bottom, neighbor);
        }
        neighbor = grid.groundY[gridIndex(gx + 1, gz)];
        if (neighbor != NO_HEIGHT) {
            bottom = Math.min(bottom, neighbor);
        }
        neighbor = grid.groundY[gridIndex(gx, gz - 1)];
        if (neighbor != NO_HEIGHT) {
            bottom = Math.min(bottom, neighbor);
        }
        neighbor = grid.groundY[gridIndex(gx, gz + 1)];
        if (neighbor != NO_HEIGHT) {
            bottom = Math.min(bottom, neighbor);
        }
        return bottom;
    }

    private static void representativeColumn(PageScratch page, int pageSlot, int cellX, int cellZ, int scale) {
        int half = Math.max(0, scale / 2);
        int quarter = Math.max(0, scale / 4);
        int threeQuarter = Math.max(0, (scale * 3) / 4);
        int end = Math.max(0, scale - 1);

        SampleScratch scratch = page.samples;
        scratch.count = 0;
        sampleColumn(scratch, cellX + half, cellZ + half);
        sampleColumn(scratch, cellX + quarter, cellZ + quarter);
        sampleColumn(scratch, cellX + threeQuarter, cellZ + quarter);
        sampleColumn(scratch, cellX + quarter, cellZ + threeQuarter);
        sampleColumn(scratch, cellX + end, cellZ + end);
        int sampleCount = scratch.count;
        if (sampleCount == 0) {
            return;
        }

        int highest = 0;
        for (int i = 1; i < sampleCount; i++) {
            if (scratch.surfaceY[i] > scratch.surfaceY[highest]) {
                highest = i;
            }
        }
        if (scale <= 4 || sampleCount == 1) {
            page.set(pageSlot, scratch.groundY[highest], scratch.surfaceY[highest],
                    scratch.groundStateId[highest], scratch.bodyStateId[highest], scratch.surfaceStateId[highest]);
            return;
        }

        for (int i = 0; i < sampleCount; i++) {
            scratch.sortHeights[i] = scratch.groundY[i];
        }
        java.util.Arrays.sort(scratch.sortHeights, 0, sampleCount);
        int medianGround = scratch.sortHeights[(sampleCount - 1) / 2];
        int ground = 0;
        for (int i = 0; i < sampleCount; i++) {
            if (scratch.groundY[i] == medianGround) {
                ground = i;
                break;
            }
        }

        int elevatedCount = 0;
        for (int i = 0; i < sampleCount; i++) {
            if (scratch.surfaceY[i] > scratch.groundY[i]) {
                scratch.elevatedIndices[elevatedCount] = i;
                scratch.sortHeights[elevatedCount] = scratch.surfaceY[i];
                elevatedCount++;
            }
        }
        if (elevatedCount < 2) {
            page.set(pageSlot, scratch.groundY[ground], scratch.groundY[ground],
                    scratch.groundStateId[ground], scratch.bodyStateId[ground], scratch.groundStateId[ground]);
            return;
        }

        java.util.Arrays.sort(scratch.sortHeights, 0, elevatedCount);
        int representativeSurface = scratch.sortHeights[(elevatedCount - 1) / 2];
        int surface = scratch.elevatedIndices[0];
        for (int i = 0; i < elevatedCount; i++) {
            int candidate = scratch.elevatedIndices[i];
            if (scratch.surfaceY[candidate] == representativeSurface) {
                surface = candidate;
                break;
            }
        }
        if (scratch.surfaceY[surface] <= scratch.groundY[ground]) {
            page.set(pageSlot, scratch.groundY[ground], scratch.groundY[ground],
                    scratch.groundStateId[ground], scratch.bodyStateId[ground], scratch.groundStateId[ground]);
            return;
        }
        page.set(pageSlot, scratch.groundY[ground], scratch.surfaceY[surface],
                scratch.groundStateId[ground], scratch.bodyStateId[ground], scratch.surfaceStateId[surface]);
    }

    private static boolean sampleColumn(SampleScratch scratch, int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, TILE_EDGE);
        int chunkZ = Math.floorDiv(blockZ, TILE_EDGE);
        RtCausticaLodRegionStore.TileData tile = tile(chunkX, chunkZ);
        if (tile == null) {
            return false;
        }
        int lx = Math.floorMod(blockX, TILE_EDGE);
        int lz = Math.floorMod(blockZ, TILE_EDGE);
        int index = lx * TILE_EDGE + lz;
        short groundY = tile.groundY()[index];
        if (groundY == NO_HEIGHT) {
            return false;
        }
        int slot = scratch.count++;
        scratch.groundY[slot] = groundY;
        scratch.surfaceY[slot] = tile.surfaceY()[index];
        scratch.groundStateId[slot] = tile.groundStateId()[index];
        scratch.bodyStateId[slot] = tile.bodyStateId()[index];
        scratch.surfaceStateId[slot] = tile.surfaceStateId()[index];
        return true;
    }

    /** Live captures override the immutable imported pack for terrain the player has actually visited. */
    private static RtCausticaLodRegionStore.TileData tile(int chunkX, int chunkZ) {
        long key = packCoords(chunkX, chunkZ);
        RtCausticaLodRegionStore.TileData cached = MEMORY.get(key);
        if (cached != null) {
            return cached;
        }
        if (MISSING.containsKey(key)) {
            return null;
        }
        Path sessionRoot = root;
        if (sessionRoot == null) {
            return null;
        }
        RtCausticaLodRegionStore.TileData loaded = readLiveTile(
                sessionRoot.resolve("c." + chunkX + "." + chunkZ + ".clod"), chunkX, chunkZ);
        if (loaded == null) {
            loaded = RtCausticaLodRegionStore.read(sessionRoot, chunkX, chunkZ);
        }
        if (loaded == null) {
            MISSING.put(key, Boolean.TRUE);
            return null;
        }
        MISSING.remove(key);
        RtCausticaLodRegionStore.TileData raced = MEMORY.putIfAbsent(key, loaded);
        return raced != null ? raced : loaded;
    }

    private static RtCausticaLodRegionStore.TileData readLiveTile(Path path, int expectedChunkX, int expectedChunkZ) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new InflaterInputStream(Files.newInputStream(path))))) {
            if (in.readInt() != LIVE_MAGIC || in.readInt() != LIVE_VERSION
                    || in.readInt() != expectedChunkX || in.readInt() != expectedChunkZ) {
                return null;
            }
            short[] groundY = new short[RtCausticaLodRegionStore.TILE_COLUMNS];
            short[] surfaceY = new short[RtCausticaLodRegionStore.TILE_COLUMNS];
            int[] groundState = new int[RtCausticaLodRegionStore.TILE_COLUMNS];
            int[] bodyState = new int[RtCausticaLodRegionStore.TILE_COLUMNS];
            int[] surfaceState = new int[RtCausticaLodRegionStore.TILE_COLUMNS];
            for (int i = 0; i < RtCausticaLodRegionStore.TILE_COLUMNS; i++) {
                groundY[i] = in.readShort();
                surfaceY[i] = in.readShort();
                groundState[i] = in.readInt();
                bodyState[i] = in.readInt();
                surfaceState[i] = in.readInt();
            }
            return new RtCausticaLodRegionStore.TileData(groundY, surfaceY, groundState, bodyState, surfaceState);
        } catch (EOFException malformed) {
            return null;
        } catch (IOException | RuntimeException e) {
            CausticaMod.LOGGER.debug("CausticaLOD live override read failed for {}: {}", path, e.toString());
            return null;
        }
    }

    private static synchronized void ensureSession(Minecraft mc, ClientLevel level) {
        String nextIdentity = RtLodSession.identity(mc, level);
        if (nextIdentity.equals(identity) && root != null) {
            return;
        }
        MEMORY.clear();
        MISSING.clear();
        RtCausticaLodRegionStore.reset();
        identity = nextIdentity;
        root = FabricLoader.getInstance().getGameDir()
                .resolve("caustica_lod")
                .resolve(hashIdentity(nextIdentity));
        // One metadata check per session handles packs completed by an earlier client run. A pack
        // completed in this process uses markImportedPackReady() and needs no polling.
        packReady = RtCausticaLodImporter.completedPackMarkerValid(root);
        LOGGED_FIRST_QUERY.set(false);
        LOGGED_PACK_READY.set(false);
    }

    private static BlockState stateById(int id) {
        BlockState state = Block.stateById(id);
        return state != null ? state : Blocks.STONE.defaultBlockState();
    }

    private static long packCoords(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffff_ffffL);
    }

    private static String hashIdentity(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                out.append(Character.forDigit((digest[i] >>> 4) & 0xf, 16));
                out.append(Character.forDigit(digest[i] & 0xf, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toUnsignedString(value.hashCode(), 16);
        }
    }

    private static final class PageScratch {
        final SampleScratch samples = new SampleScratch();
        final short[] groundY = new short[GRID_CELLS];
        final short[] surfaceY = new short[GRID_CELLS];
        final int[] groundStateId = new int[GRID_CELLS];
        final int[] bodyStateId = new int[GRID_CELLS];
        final int[] surfaceStateId = new int[GRID_CELLS];

        void resetGrid() {
            java.util.Arrays.fill(groundY, NO_HEIGHT);
        }

        void set(int index, short ground, short surface, int groundState, int bodyState, int surfaceState) {
            groundY[index] = ground;
            surfaceY[index] = surface;
            groundStateId[index] = groundState;
            bodyStateId[index] = bodyState;
            surfaceStateId[index] = surfaceState;
        }
    }

    private static final class SampleScratch {
        int count;
        final short[] groundY = new short[5];
        final short[] surfaceY = new short[5];
        final int[] groundStateId = new int[5];
        final int[] bodyStateId = new int[5];
        final int[] surfaceStateId = new int[5];
        final int[] elevatedIndices = new int[5];
        final int[] sortHeights = new int[5];
    }
}
