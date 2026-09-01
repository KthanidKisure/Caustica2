package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Caustica-native persistent distant-terrain source.
 *
 * <p>The cache stores only information visible from outside a chunk column: terrain height, the top
 * material, a representative body material, and an optional higher surface such as water or foliage.
 * Caves, ores, underground structures and internal faces are intentionally absent. At query time the
 * cache is sampled directly into Caustica's existing virtual-block LOD representation, so the result
 * still travels through the ordinary material mesher, BLAS builder and TLAS path.</p>
 *
 * <p>This class is deliberately independent of DH/Voxy. External datasets (WynnLOD, world saves,
 * future importers) only need to populate the same compact {@link SurfaceTile} format; rendering never
 * depends on another LOD mod being installed or initialized.</p>
 */
public final class RtCausticaLodSource {
    private static final int MAGIC = 0x434C4F44; // CLOD
    private static final int VERSION = 1;
    private static final int TILE_EDGE = 16;
    private static final int TILE_COLUMNS = TILE_EDGE * TILE_EDGE;
    private static final short NO_HEIGHT = Short.MIN_VALUE;

    // Two chunks per 20-TPS client tick is only 512 height samples/tick and fills a 16-chunk-radius
    // vanilla window in a few seconds without turning chunk arrival into a frame hitch.
    private static final int CAPTURE_CHUNKS_PER_TICK = 2;
    private static final int MAX_SCAN_PER_TICK = 96;

    private static final RtLodTileCache<SurfaceTile> MEMORY = new RtLodTileCache<>(8192);
    private static final RtLodTileCache<Boolean> KNOWN_ON_DISK = new RtLodTileCache<>(32768);
    private static final ConcurrentHashMap<Long, Boolean> WRITE_PENDING = new ConcurrentHashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "caustica-lod-io");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private static final AtomicBoolean LOGGED_SESSION = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_FIRST_CAPTURE = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_FIRST_DISK_LOAD = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_FIRST_QUERY = new AtomicBoolean();
    private static final AtomicLong CAPTURED_TILES = new AtomicLong();

    private static volatile String sessionIdentity = "";
    private static volatile Path sessionRoot;
    private static volatile int worldMinY = -64;
    private static volatile int scanCursor;

    private RtCausticaLodSource() {
    }

    public record LodBox(int blockX, int bottomY, int topY, int blockZ, int sizeXZ,
                         BlockState blockState, int blockLight, int skyLight) {
    }

    public record FetchResult(List<LodBox> boxes, boolean querySucceeded) {
        public FetchResult {
            boxes = List.copyOf(boxes);
        }
    }

    /**
     * Render/client-thread capture pass. It never touches unloaded chunks and never performs disk IO
     * synchronously. Newly seen terrain is immediately visible to workers through MEMORY, then written
     * to the persistent cache on the dedicated low-priority IO thread.
     */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }

        ensureSession(mc, level);
        worldMinY = level.getMinY();

        ClientChunkCache chunkSource = level.getChunkSource();
        int pcx = mc.player.getBlockX() >> 4;
        int pcz = mc.player.getBlockZ() >> 4;
        int radius = Math.max(1, mc.options.getEffectiveRenderDistance());
        int diameter = radius * 2 + 1;
        int total = diameter * diameter;
        if (total <= 0) {
            return;
        }

        int captured = 0;
        int inspected = 0;
        while (captured < CAPTURE_CHUNKS_PER_TICK && inspected < Math.min(total, MAX_SCAN_PER_TICK)) {
            int index = Math.floorMod(scanCursor++, total);
            inspected++;
            int dx = index % diameter - radius;
            int dz = index / diameter - radius;
            int cx = pcx + dx;
            int cz = pcz + dz;
            if (!chunkSource.hasChunk(cx, cz)) {
                continue;
            }

            long key = chunkKey(cx, cz);
            if (MEMORY.containsKey(key) || KNOWN_ON_DISK.containsKey(key)) {
                continue;
            }
            Path path = tilePath(cx, cz);
            if (path != null && Files.isRegularFile(path)) {
                KNOWN_ON_DISK.put(key, Boolean.TRUE);
                continue;
            }

            SurfaceTile tile = captureTile(level, cx, cz);
            if (tile == null) {
                continue;
            }
            MEMORY.put(key, tile);
            KNOWN_ON_DISK.put(key, Boolean.TRUE);
            CAPTURED_TILES.incrementAndGet();
            scheduleWrite(tile);
            captured++;

            if (LOGGED_FIRST_CAPTURE.compareAndSet(false, true)) {
                CausticaMod.LOGGER.info(
                        "CausticaLOD native surface capture active: first tile {},{} cached (surface-only, no chunk internals)",
                        cx, cz);
            }
        }
    }

    /** True once a Minecraft world exists; no external LOD mod is required. */
    public static boolean available() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.player != null;
    }

    /**
     * Samples one Caustica virtual LOD section. {@code footprintBlocks} is normally 16*2^detail.
     * Each virtual X/Z cell chooses representative visible terrain from a five-point sample rather than
     * expanding every cached block. That keeps query work effectively constant as LOD distance grows.
     */
    public static FetchResult fetchArea(int footprintBlocks, int originBlockX, int originBlockZ) {
        if (!available() || footprintBlocks < 16) {
            return new FetchResult(List.of(), false);
        }

        int scale = Math.max(1, footprintBlocks / RtDhLodRegion.SECTION_BLOCKS);
        SurfaceColumn[] surfaceGrid = sampleSurfaceGrid(originBlockX, originBlockZ, scale);
        ArrayList<LodBox> boxes = new ArrayList<>(RtDhLodRegion.SECTION_BLOCKS * RtDhLodRegion.SECTION_BLOCKS * 3);
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

                // Store only the exterior height-field shell. The vertical skirt reaches only as low
                // as an adjacent sampled surface, which closes visible cliffs without inventing a solid
                // pillar down to world minimum under every distant roof, island, hill and plateau.
                int skirtBottom = skirtBottom(surfaceGrid, vx + 1, vz + 1, groundY);
                if (skirtBottom < groundY) {
                    boxes.add(new LodBox(cellX, skirtBottom, groundY, cellZ, scale, body, 0, 15));
                }
                boxes.add(new LodBox(cellX, groundY, groundY + 1, cellZ, scale, ground, 0, 15));

                if (surfaceY > groundY) {
                    if (!surface.getFluidState().isEmpty()) {
                        // Preserve the visible water volume separately from the terrain body.
                        boxes.add(new LodBox(cellX, groundY + 1, surfaceY + 1, cellZ, scale, surface, 0, 15));
                    } else if (!surface.isAir()) {
                        // Foliage/structures above the terrain are kept as a silhouette cap, not as a
                        // full hidden voxel column.
                        boxes.add(new LodBox(cellX, surfaceY, surfaceY + 1, cellZ, scale, surface, 0, 15));
                    }
                }
            }
        }

        if (resolvedCells == 0) {
            return new FetchResult(List.of(), false);
        }
        if (LOGGED_FIRST_QUERY.compareAndSet(false, true)) {
            CausticaMod.LOGGER.info(
                    "CausticaLOD native query succeeded: {}/{} virtual cells covered, {} surface boxes",
                    resolvedCells,
                    RtDhLodRegion.SECTION_BLOCKS * RtDhLodRegion.SECTION_BLOCKS,
                    boxes.size());
        }
        return new FetchResult(boxes, true);
    }

    /**
     * Drops only in-memory session state. Persistent tiles stay on disk and are loaded lazily when the
     * player returns. This is safe on proxy/dimension transitions because the next tick derives a new
     * session key before any capture/query work.
     */
    public static synchronized void invalidate() {
        MEMORY.clear();
        KNOWN_ON_DISK.clear();
        WRITE_PENDING.clear();
        sessionIdentity = "";
        sessionRoot = null;
        scanCursor = 0;
        LOGGED_SESSION.set(false);
        LOGGED_FIRST_CAPTURE.set(false);
        LOGGED_FIRST_DISK_LOAD.set(false);
        LOGGED_FIRST_QUERY.set(false);
    }

    private static synchronized void ensureSession(Minecraft mc, ClientLevel level) {
        String identity = RtLodSession.identity(mc, level);
        if (identity.equals(sessionIdentity) && sessionRoot != null) {
            return;
        }

        MEMORY.clear();
        KNOWN_ON_DISK.clear();
        WRITE_PENDING.clear();
        sessionIdentity = identity;
        scanCursor = 0;
        sessionRoot = FabricLoader.getInstance().getGameDir()
                .resolve("caustica_lod")
                .resolve(hashIdentity(identity));
        try {
            Files.createDirectories(sessionRoot);
        } catch (IOException e) {
            CausticaMod.LOGGER.warn("Unable to create CausticaLOD cache directory {}: {}", sessionRoot, e.toString());
        }
        if (LOGGED_SESSION.compareAndSet(false, true)) {
            CausticaMod.LOGGER.info("CausticaLOD native cache session: {} -> {}", identity, sessionRoot);
        }
    }

    private static SurfaceTile captureTile(ClientLevel level, int chunkX, int chunkZ) {
        SurfaceTile tile = new SurfaceTile(chunkX, chunkZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinY();

        for (int lx = 0; lx < TILE_EDGE; lx++) {
            int x = (chunkX << 4) + lx;
            for (int lz = 0; lz < TILE_EDGE; lz++) {
                int z = (chunkZ << 4) + lz;
                int index = lx * TILE_EDGE + lz;

                int groundY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                if (groundY < minY) {
                    tile.groundY[index] = NO_HEIGHT;
                    tile.surfaceY[index] = NO_HEIGHT;
                    continue;
                }
                surfaceY = Math.max(surfaceY, groundY);

                BlockState ground = stateAtOrBelow(level, pos, x, groundY, z, minY, 8);
                BlockState body = stateAtOrBelow(level, pos, x, groundY - 1, z, minY, 16);
                BlockState surface = stateAtOrBelow(level, pos, x, surfaceY, z, minY, 4);
                if (ground.isAir()) {
                    ground = body.isAir() ? Blocks.STONE.defaultBlockState() : body;
                }
                if (body.isAir()) {
                    body = ground;
                }
                if (surface.isAir()) {
                    surface = ground;
                    surfaceY = groundY;
                }

                tile.groundY[index] = clampHeight(groundY);
                tile.surfaceY[index] = clampHeight(surfaceY);
                tile.groundStateId[index] = Block.getId(ground);
                tile.bodyStateId[index] = Block.getId(body);
                tile.surfaceStateId[index] = Block.getId(surface);
            }
        }
        return tile;
    }

    private static BlockState stateAtOrBelow(ClientLevel level, BlockPos.MutableBlockPos pos,
                                             int x, int startY, int z, int minY, int maxSteps) {
        int y = Math.max(startY, minY);
        int end = Math.max(minY, y - Math.max(0, maxSteps));
        while (y >= end) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            if (!state.isAir()) {
                return state;
            }
            y--;
        }
        return Blocks.AIR.defaultBlockState();
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

        SurfaceColumn best = null;
        int[][] offsets = {
                {half, half},
                {quarter, quarter},
                {threeQuarter, quarter},
                {quarter, threeQuarter},
                {end, end},
        };
        for (int[] offset : offsets) {
            SurfaceColumn sample = columnAt(cellX + offset[0], cellZ + offset[1]);
            if (sample == null || sample.groundY == NO_HEIGHT) {
                continue;
            }
            if (best == null || sample.surfaceY > best.surfaceY) {
                best = sample;
            }
        }
        return best;
    }

    private static SurfaceColumn columnAt(int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, TILE_EDGE);
        int chunkZ = Math.floorDiv(blockZ, TILE_EDGE);
        SurfaceTile tile = tile(chunkX, chunkZ);
        if (tile == null) {
            return null;
        }
        int lx = Math.floorMod(blockX, TILE_EDGE);
        int lz = Math.floorMod(blockZ, TILE_EDGE);
        int index = lx * TILE_EDGE + lz;
        return new SurfaceColumn(
                tile.groundY[index],
                tile.surfaceY[index],
                tile.groundStateId[index],
                tile.bodyStateId[index],
                tile.surfaceStateId[index]);
    }

    private static SurfaceTile tile(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        SurfaceTile resident = MEMORY.get(key);
        if (resident != null) {
            return resident;
        }
        Path path = tilePath(chunkX, chunkZ);
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        SurfaceTile loaded = readTile(path, chunkX, chunkZ);
        if (loaded != null) {
            SurfaceTile raced = MEMORY.putIfAbsent(key, loaded);
            KNOWN_ON_DISK.put(key, Boolean.TRUE);
            if (LOGGED_FIRST_DISK_LOAD.compareAndSet(false, true)) {
                CausticaMod.LOGGER.info("CausticaLOD restored persistent native surface tile {},{}", chunkX, chunkZ);
            }
            return raced != null ? raced : loaded;
        }
        return null;
    }

    private static void scheduleWrite(SurfaceTile tile) {
        long key = chunkKey(tile.chunkX, tile.chunkZ);
        if (WRITE_PENDING.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        IO.execute(() -> {
            try {
                writeTile(tile);
                // Once the live override is atomically visible, evict any imported copy cached by the
                // packed source so the next distant query immediately observes the visited terrain.
                RtCausticaLodPackedSource.invalidateTile(tile.chunkX, tile.chunkZ);
            } finally {
                WRITE_PENDING.remove(key);
            }
        });
    }

    private static void writeTile(SurfaceTile tile) {
        Path target = tilePath(tile.chunkX, tile.chunkZ);
        if (target == null) {
            return;
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    new DeflaterOutputStream(Files.newOutputStream(tmp))))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(tile.chunkX);
                out.writeInt(tile.chunkZ);
                for (int i = 0; i < TILE_COLUMNS; i++) {
                    out.writeShort(tile.groundY[i]);
                    out.writeShort(tile.surfaceY[i]);
                    out.writeInt(tile.groundStateId[i]);
                    out.writeInt(tile.bodyStateId[i]);
                    out.writeInt(tile.surfaceStateId[i]);
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            CausticaMod.LOGGER.debug("CausticaLOD tile write failed for {},{}: {}", tile.chunkX, tile.chunkZ, e.toString());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }

    private static SurfaceTile readTile(Path path, int expectedChunkX, int expectedChunkZ) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new InflaterInputStream(Files.newInputStream(path))))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                return null;
            }
            int chunkX = in.readInt();
            int chunkZ = in.readInt();
            if (chunkX != expectedChunkX || chunkZ != expectedChunkZ) {
                return null;
            }
            SurfaceTile tile = new SurfaceTile(chunkX, chunkZ);
            for (int i = 0; i < TILE_COLUMNS; i++) {
                tile.groundY[i] = in.readShort();
                tile.surfaceY[i] = in.readShort();
                tile.groundStateId[i] = in.readInt();
                tile.bodyStateId[i] = in.readInt();
                tile.surfaceStateId[i] = in.readInt();
            }
            return tile;
        } catch (EOFException malformed) {
            return null;
        } catch (IOException | RuntimeException e) {
            CausticaMod.LOGGER.debug("CausticaLOD tile read failed for {}: {}", path, e.toString());
            return null;
        }
    }

    private static Path tilePath(int chunkX, int chunkZ) {
        Path root = sessionRoot;
        return root == null ? null : root.resolve("c." + chunkX + "." + chunkZ + ".clod");
    }

    private static BlockState stateById(int id) {
        BlockState state = Block.stateById(id);
        return state != null ? state : Blocks.STONE.defaultBlockState();
    }

    private static short clampHeight(int y) {
        return (short) Math.clamp(y, Short.MIN_VALUE + 1, Short.MAX_VALUE);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFF_FFFFL);
    }

    private static String hashIdentity(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                out.append(Character.forDigit((digest[i] >>> 4) & 0xF, 16));
                out.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toUnsignedString(identity.hashCode(), 16);
        }
    }

    private static final class SurfaceTile {
        final int chunkX;
        final int chunkZ;
        final short[] groundY = new short[TILE_COLUMNS];
        final short[] surfaceY = new short[TILE_COLUMNS];
        final int[] groundStateId = new int[TILE_COLUMNS];
        final int[] bodyStateId = new int[TILE_COLUMNS];
        final int[] surfaceStateId = new int[TILE_COLUMNS];

        SurfaceTile(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            java.util.Arrays.fill(groundY, NO_HEIGHT);
            java.util.Arrays.fill(surfaceY, NO_HEIGHT);
        }
    }

    private record SurfaceColumn(short groundY, short surfaceY,
                                 int groundStateId, int bodyStateId, int surfaceStateId) {
    }
}
