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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.InflaterInputStream;

/** Runtime reader for immutable imported CausticaLOD region packs. */
final class RtCausticaLodPackedSource {
    private static final int LIVE_MAGIC = 0x434C4F44; // CLOD
    private static final int LIVE_VERSION = 1;
    private static final int TILE_EDGE = RtCausticaLodRegionStore.TILE_EDGE;
    private static final short NO_HEIGHT = RtCausticaLodRegionStore.NO_HEIGHT;

    private static final ConcurrentHashMap<Long, RtCausticaLodRegionStore.TileData> MEMORY = new ConcurrentHashMap<>();
    private static final AtomicBoolean LOGGED_FIRST_QUERY = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_PACK_READY = new AtomicBoolean();

    private static volatile String identity = "";
    private static volatile Path root;
    private static volatile int minY = -64;

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
        RtCausticaLodImporter.tick(mc, level, root, identity);
        boolean ready = root != null && Files.isRegularFile(root.resolve("wynnlod-v2.complete"));
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
        ArrayList<RtCausticaLodSource.LodBox> boxes =
                new ArrayList<>(RtDhLodRegion.SECTION_BLOCKS * RtDhLodRegion.SECTION_BLOCKS * 3);
        int resolvedCells = 0;

        for (int vx = 0; vx < RtDhLodRegion.SECTION_BLOCKS; vx++) {
            int cellX = originBlockX + vx * scale;
            for (int vz = 0; vz < RtDhLodRegion.SECTION_BLOCKS; vz++) {
                int cellZ = originBlockZ + vz * scale;
                SurfaceColumn column = representativeColumn(cellX, cellZ, scale);
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

                if (groundY > minY) {
                    boxes.add(new RtCausticaLodSource.LodBox(cellX, minY, groundY, cellZ, scale, body, 0, 15));
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
            return new RtCausticaLodSource.FetchResult(List.of(), false);
        }
        if (LOGGED_FIRST_QUERY.compareAndSet(false, true)) {
            CausticaMod.LOGGER.info("CausticaLOD packed query succeeded: {}/{} cells, {} surface boxes",
                    resolvedCells, RtDhLodRegion.SECTION_BLOCKS * RtDhLodRegion.SECTION_BLOCKS, boxes.size());
        }
        return new RtCausticaLodSource.FetchResult(boxes, true);
    }

    static void invalidateTile(int chunkX, int chunkZ) {
        MEMORY.remove(packCoords(chunkX, chunkZ));
    }

    static synchronized void invalidate() {
        MEMORY.clear();
        identity = "";
        root = null;
        LOGGED_FIRST_QUERY.set(false);
        LOGGED_PACK_READY.set(false);
        RtCausticaLodRegionStore.reset();
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
        SurfaceColumn best = null;
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
        Path sessionRoot = root;
        if (sessionRoot == null) {
            return null;
        }
        RtCausticaLodRegionStore.TileData loaded = readLiveTile(sessionRoot.resolve("c." + chunkX + "." + chunkZ + ".clod"),
                chunkX, chunkZ);
        if (loaded == null) {
            loaded = RtCausticaLodRegionStore.read(sessionRoot, chunkX, chunkZ);
        }
        if (loaded == null) {
            return null;
        }
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
        String server = "singleplayer";
        try {
            if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
                server = mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
            }
        } catch (RuntimeException ignored) {
        }
        String nextIdentity = server + "|" + level.dimension();
        if (nextIdentity.equals(identity) && root != null) {
            return;
        }
        MEMORY.clear();
        RtCausticaLodRegionStore.reset();
        identity = nextIdentity;
        root = FabricLoader.getInstance().getGameDir()
                .resolve("caustica_lod")
                .resolve(hashIdentity(nextIdentity));
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
