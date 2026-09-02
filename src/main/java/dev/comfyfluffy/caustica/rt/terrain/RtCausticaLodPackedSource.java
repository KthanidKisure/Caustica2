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
    /** Negative cache for uncovered chunks; imported regions are immutable once the completion marker exists. */
    private static final RtLodTileCache<Boolean> MISSING = new RtLodTileCache<>(32768);
    private static final AtomicBoolean LOGGED_FIRST_QUERY = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_PACK_READY = new AtomicBoolean();

    private static volatile String identity = "";
    private static volatile Path root;
    private static volatile int minY = -64;
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
        minY = level.getMinY();
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
        SurfaceColumn[] surfaceGrid = sampleSurfaceGrid(originBlockX, originBlockZ, scale);
        ArrayList<RtCausticaLodSource.LodBox> boxes =
                new ArrayList<>(RtDhLodRegion.SECTION_BLOCKS * RtDhLodRegion.SECTION_BLOCKS * 3);
        int resolvedCells = 0;

        for (int vx = 0; vx < RtDhLodRegion.SECTION_BLOCKS; vx++) {
            int cellX = originBlockX + vx * scale;
            for (int vz = 0; vz < RtDhLodRegion.SECTION_BLOCKS; vz++) {
                int cellZ = originBlockZ + vz * scale;
                SurfaceColumn column = surfaceGrid[gridIndex(vx + 1, vz + 1)];
                if (column == null || column.groundY == NO_HEIGHT) {
                    continue;
                }
                resolvedCells++;

                int groundY = column.groundY;
                int surfaceY = Math.max(column.surfaceY, groundY);
                BlockState ground = stateById(column.groundStateId);
                BlockState body = stateById(column.bodyStateId);
                BlockState surface = stateById(column.surfaceStateId);
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
    private static SurfaceColumn[] sampleSurfaceGrid(int originBlockX, int originBlockZ, int scale) {
        int edge = RtDhLodRegion.SECTION_BLOCKS + 2;
        SurfaceColumn[] grid = new SurfaceColumn[edge * edge];
        for (int gx = -1; gx <= RtDhLodRegion.SECTION_BLOCKS; gx++) {
            int x = originBlockX + gx * scale;
            for (int gz = -1; gz <= RtDhLodRegion.SECTION_BLOCKS; gz++) {
                int z = originBlockZ + gz * scale;
                grid[gridIndex(gx + 1, gz + 1)] = representativeColumn(x, z, scale);
            }
        }
        return grid;
    }

    private static int gridIndex(int x, int z) {
        return x * (RtDhLodRegion.SECTION_BLOCKS + 2) + z;
    }

    private static int skirtBottom(SurfaceColumn[] grid, int gx, int gz, int groundY) {
        int bottom = groundY;
        int[] indices = {
                gridIndex(gx - 1, gz),
                gridIndex(gx + 1, gz),
                gridIndex(gx, gz - 1),
                gridIndex(gx, gz + 1),
        };
        for (int index : indices) {
            SurfaceColumn neighbor = grid[index];
            if (neighbor != null && neighbor.groundY != NO_HEIGHT) {
                bottom = Math.min(bottom, neighbor.groundY);
            }
        }
        return bottom;
    }

    private static SurfaceColumn representativeColumn(int cellX, int cellZ, int scale) {
        int half = Math.max(0, scale / 2);
        int quarter = Math.max(0, scale / 4);
        int threeQuarter = Math.max(0, (scale * 3) / 4);
        int end = Math.max(0, scale - 1);
        int[][] offsets = {
                {half, half},
                {quarter, quarter},
                {threeQuarter, quarter},
                {quarter, threeQuarter},
                {end, end},
        };

        SurfaceColumn[] samples = new SurfaceColumn[offsets.length];
        int sampleCount = 0;
        SurfaceColumn highest = null;
        for (int[] offset : offsets) {
            SurfaceColumn sample = columnAt(cellX + offset[0], cellZ + offset[1]);
            if (sample == null || sample.groundY == NO_HEIGHT) {
                continue;
            }
            samples[sampleCount++] = sample;
            if (highest == null || sample.surfaceY > highest.surfaceY) {
                highest = sample;
            }
        }
        if (sampleCount == 0) {
            return null;
        }

        // Detail 1-2 cells are only 2-4 blocks wide, so the old upper-envelope sample is both cheap
        // and visually useful. Starting at detail 3 a single outlier would inflate one sampled leaf,
        // spire or puddle across 8x8+ world blocks, so use a robust height representative instead.
        if (scale <= 4 || sampleCount == 1) {
            return highest;
        }

        int[] groundHeights = new int[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            groundHeights[i] = samples[i].groundY;
        }
        java.util.Arrays.sort(groundHeights);
        int medianGround = groundHeights[(sampleCount - 1) / 2];
        SurfaceColumn ground = samples[0];
        for (int i = 0; i < sampleCount; i++) {
            if (samples[i].groundY == medianGround) {
                ground = samples[i];
                break;
            }
        }

        SurfaceColumn[] elevated = new SurfaceColumn[sampleCount];
        int elevatedCount = 0;
        for (int i = 0; i < sampleCount; i++) {
            SurfaceColumn sample = samples[i];
            if (sample.surfaceY > sample.groundY) {
                elevated[elevatedCount++] = sample;
            }
        }

        // Require at least two independent samples before spreading an elevated surface over a coarse
        // virtual cell. This removes isolated foliage/water spikes while retaining roofs, canopies and
        // water bodies that occupy a meaningful fraction of the cell.
        if (elevatedCount < 2) {
            return new SurfaceColumn(ground.groundY, ground.groundY,
                    ground.groundStateId, ground.bodyStateId, ground.groundStateId);
        }

        int[] surfaceHeights = new int[elevatedCount];
        for (int i = 0; i < elevatedCount; i++) {
            surfaceHeights[i] = elevated[i].surfaceY;
        }
        java.util.Arrays.sort(surfaceHeights);
        int representativeSurface = surfaceHeights[(elevatedCount - 1) / 2];
        SurfaceColumn surface = elevated[0];
        for (int i = 0; i < elevatedCount; i++) {
            if (elevated[i].surfaceY == representativeSurface) {
                surface = elevated[i];
                break;
            }
        }
        if (surface.surfaceY <= ground.groundY) {
            return new SurfaceColumn(ground.groundY, ground.groundY,
                    ground.groundStateId, ground.bodyStateId, ground.groundStateId);
        }
        return new SurfaceColumn(ground.groundY, surface.surfaceY,
                ground.groundStateId, ground.bodyStateId, surface.surfaceStateId);
    }

    private static SurfaceColumn columnAt(int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, TILE_EDGE);
        int chunkZ = Math.floorDiv(blockZ, TILE_EDGE);
        RtCausticaLodRegionStore.TileData tile = tile(chunkX, chunkZ);
        if (tile == null) {
            return null;
        }
        int lx = Math.floorMod(blockX, TILE_EDGE);
        int lz = Math.floorMod(blockZ, TILE_EDGE);
        int index = lx * TILE_EDGE + lz;
        return new SurfaceColumn(
                tile.groundY()[index], tile.surfaceY()[index],
                tile.groundStateId()[index], tile.bodyStateId()[index], tile.surfaceStateId()[index]);
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
        packReady = Files.isRegularFile(root.resolve("wynnlod-v2.complete"));
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

    private record SurfaceColumn(short groundY, short surfaceY,
                                 int groundStateId, int bodyStateId, int surfaceStateId) {
    }
}
