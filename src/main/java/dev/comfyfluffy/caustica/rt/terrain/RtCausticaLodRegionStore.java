package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaMod;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Random-access packed storage for imported CausticaLOD surface tiles.
 *
 * <p>A region covers 32x32 chunks. Its fixed 8 KiB index maps each chunk slot to one independently
 * compressed 4 KiB surface payload, so a whole imported map needs hundreds of region files instead of
 * hundreds of thousands of tiny chunk files. The payload remains independent per chunk: loading a far
 * LOD tile never decompresses its neighbours, and a live per-chunk cache file can override an imported
 * tile without rewriting the immutable region pack.</p>
 */
final class RtCausticaLodRegionStore {
    static final int TILE_EDGE = 16;
    static final int TILE_COLUMNS = TILE_EDGE * TILE_EDGE;
    static final short NO_HEIGHT = Short.MIN_VALUE;

    private static final int MAGIC = 0x434C5231; // CLR1
    private static final int VERSION = 1;
    private static final int REGION_EDGE = 32;
    private static final int REGION_SLOTS = REGION_EDGE * REGION_EDGE;
    private static final int HEADER_BYTES = 16 + REGION_SLOTS * 8;
    private static final int RAW_TILE_BYTES = TILE_COLUMNS * (2 + 2 + 4 + 4 + 4);
    // DEFLATE overhead for a 4 KiB payload is tiny; leave generous headroom while rejecting
    // damaged indexes that would otherwise request pathological heap allocations.
    private static final int MAX_COMPRESSED_TILE_BYTES = RAW_TILE_BYTES + 1024;

    private static final int MAX_OPEN_READERS = 64;
    private static final LinkedHashMap<Path, RegionReader> READERS = new LinkedHashMap<>(64, 0.75f, true);

    private RtCausticaLodRegionStore() {
    }

    /** Dense surface data for one 16x16 chunk. Arrays are owned by the caller/returned tile. */
    record TileData(short[] groundY, short[] surfaceY,
                    int[] groundStateId, int[] bodyStateId, int[] surfaceStateId) {
        TileData {
            if (groundY.length != TILE_COLUMNS || surfaceY.length != TILE_COLUMNS
                    || groundStateId.length != TILE_COLUMNS || bodyStateId.length != TILE_COLUMNS
                    || surfaceStateId.length != TILE_COLUMNS) {
                throw new IllegalArgumentException("CausticaLOD tile arrays must contain " + TILE_COLUMNS + " columns");
            }
        }
    }

    static boolean hasTile(Path root, int chunkX, int chunkZ) {
        synchronized (READERS) {
            RegionReader reader = readerLocked(root, chunkX, chunkZ);
            return reader != null && reader.has(slot(chunkX, chunkZ));
        }
    }

    static TileData read(Path root, int chunkX, int chunkZ) {
        byte[] compressed;
        // Hold the cache lock only while the FileChannel is in use so LRU eviction cannot close it.
        // DEFLATE is CPU work and is intentionally outside this global lock; different terrain workers
        // can therefore decompress independent region tiles concurrently.
        synchronized (READERS) {
            RegionReader reader = readerLocked(root, chunkX, chunkZ);
            compressed = reader != null ? reader.readCompressed(slot(chunkX, chunkZ)) : null;
        }
        if (compressed == null) {
            return null;
        }
        try {
            return decompress(compressed);
        } catch (IOException | RuntimeException e) {
            CausticaMod.LOGGER.debug("CausticaLOD packed tile decompression failed: {}", e.toString());
            return null;
        }
    }

    /** Closes positional-read channels when the client swaps server/dimension or shuts RT down. */
    static void reset() {
        synchronized (READERS) {
            for (RegionReader reader : READERS.values()) {
                reader.close();
            }
            READERS.clear();
        }
    }

    /**
     * Atomically publishes one 32x32-chunk imported region. Only populated slots are written.
     * Existing region files are replaced as a unit, so a crash can leave at most the temporary file.
     */
    static void writeRegion(Path root, int regionX, int regionZ, Map<Integer, TileData> tiles) throws IOException {
        if (tiles.isEmpty()) {
            return;
        }
        Files.createDirectories(root);
        Path target = regionPath(root, regionX, regionZ);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        int[] offsets = new int[REGION_SLOTS];
        int[] lengths = new int[REGION_SLOTS];

        try (RandomAccessFile file = new RandomAccessFile(tmp.toFile(), "rw")) {
            file.setLength(0L);
            file.seek(HEADER_BYTES);
            for (int index = 0; index < REGION_SLOTS; index++) {
                TileData tile = tiles.get(index);
                if (tile == null) {
                    continue;
                }
                byte[] compressed = compress(tile);
                long offset = file.getFilePointer();
                if (offset > Integer.MAX_VALUE) {
                    throw new IOException("CausticaLOD region exceeds 2 GiB: " + target);
                }
                offsets[index] = (int) offset;
                lengths[index] = compressed.length;
                file.write(compressed);
            }

            file.seek(0L);
            file.writeInt(MAGIC);
            file.writeInt(VERSION);
            file.writeInt(regionX);
            file.writeInt(regionZ);
            for (int i = 0; i < REGION_SLOTS; i++) {
                file.writeInt(offsets[i]);
                file.writeInt(lengths[i]);
            }
            file.getFD().sync();
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw t;
        }

        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }

        synchronized (READERS) {
            RegionReader stale = READERS.remove(target.toAbsolutePath().normalize());
            if (stale != null) {
                stale.close();
            }
        }
    }

    static int regionX(int chunkX) {
        return Math.floorDiv(chunkX, REGION_EDGE);
    }

    static int regionZ(int chunkZ) {
        return Math.floorDiv(chunkZ, REGION_EDGE);
    }

    static int slot(int chunkX, int chunkZ) {
        return Math.floorMod(chunkX, REGION_EDGE) * REGION_EDGE + Math.floorMod(chunkZ, REGION_EDGE);
    }

    /** Called only while synchronized on READERS. */
    private static RegionReader readerLocked(Path root, int chunkX, int chunkZ) {
        if (root == null) {
            return null;
        }
        int rx = regionX(chunkX);
        int rz = regionZ(chunkZ);
        Path path = regionPath(root, rx, rz).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            return null;
        }
        RegionReader current = READERS.get(path);
        if (current != null) {
            return current;
        }
        try {
            RegionReader opened = new RegionReader(path, rx, rz);
            READERS.put(path, opened);
            while (READERS.size() > MAX_OPEN_READERS) {
                Map.Entry<Path, RegionReader> eldest = READERS.entrySet().iterator().next();
                READERS.remove(eldest.getKey());
                eldest.getValue().close();
            }
            return opened;
        } catch (IOException | RuntimeException e) {
            CausticaMod.LOGGER.debug("CausticaLOD region open failed for {}: {}", path, e.toString());
            return null;
        }
    }

    private static Path regionPath(Path root, int regionX, int regionZ) {
        return root.resolve("r." + regionX + "." + regionZ + ".clodr");
    }

    private static byte[] compress(TileData tile) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(RAW_TILE_BYTES / 2);
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new DeflaterOutputStream(bytes, deflater, 4096)))) {
            for (int i = 0; i < TILE_COLUMNS; i++) {
                out.writeShort(tile.groundY[i]);
                out.writeShort(tile.surfaceY[i]);
                out.writeInt(tile.groundStateId[i]);
                out.writeInt(tile.bodyStateId[i]);
                out.writeInt(tile.surfaceStateId[i]);
            }
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    private static TileData decompress(byte[] compressed) throws IOException {
        short[] groundY = new short[TILE_COLUMNS];
        short[] surfaceY = new short[TILE_COLUMNS];
        int[] groundStateId = new int[TILE_COLUMNS];
        int[] bodyStateId = new int[TILE_COLUMNS];
        int[] surfaceStateId = new int[TILE_COLUMNS];
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new InflaterInputStream(new ByteArrayInputStream(compressed))))) {
            for (int i = 0; i < TILE_COLUMNS; i++) {
                groundY[i] = in.readShort();
                surfaceY[i] = in.readShort();
                groundStateId[i] = in.readInt();
                bodyStateId[i] = in.readInt();
                surfaceStateId[i] = in.readInt();
            }
            if (in.read() != -1) {
                throw new IOException("CausticaLOD packed tile contains trailing data");
            }
        } catch (EOFException malformed) {
            throw new IOException("Truncated CausticaLOD packed tile", malformed);
        }
        return new TileData(groundY, surfaceY, groundStateId, bodyStateId, surfaceStateId);
    }

    private static final class RegionReader {
        private final FileChannel channel;
        private final int[] offsets = new int[REGION_SLOTS];
        private final int[] lengths = new int[REGION_SLOTS];

        RegionReader(Path path, int expectedRegionX, int expectedRegionZ) throws IOException {
            this.channel = FileChannel.open(path);
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
            readFully(channel, header, 0L);
            header.flip();
            if (header.getInt() != MAGIC || header.getInt() != VERSION
                    || header.getInt() != expectedRegionX || header.getInt() != expectedRegionZ) {
                close();
                throw new IOException("Invalid CausticaLOD region header: " + path);
            }
            long size = channel.size();
            for (int i = 0; i < REGION_SLOTS; i++) {
                int offset = header.getInt();
                int length = header.getInt();
                if (offset == 0 && length == 0) {
                    continue;
                }
                if (offset < HEADER_BYTES || length <= 0 || length > MAX_COMPRESSED_TILE_BYTES
                        || Integer.toUnsignedLong(offset) + length > size) {
                    close();
                    throw new IOException("Invalid CausticaLOD region index entry " + i + " in " + path);
                }
                offsets[i] = offset;
                lengths[i] = length;
            }
        }

        boolean has(int index) {
            return lengths[index] > 0;
        }

        byte[] readCompressed(int index) {
            int length = lengths[index];
            if (length <= 0) {
                return null;
            }
            byte[] bytes = new byte[length];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            try {
                readFully(channel, buffer, Integer.toUnsignedLong(offsets[index]));
                return bytes;
            } catch (IOException | RuntimeException e) {
                CausticaMod.LOGGER.debug("CausticaLOD packed tile read failed: {}", e.toString());
                return null;
            }
        }

        void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long offset) throws IOException {
        long position = offset;
        while (target.hasRemaining()) {
            int read = channel.read(target, position);
            if (read < 0) {
                throw new EOFException("Unexpected EOF in CausticaLOD region");
            }
            if (read == 0) {
                Thread.onSpinWait();
                continue;
            }
            position += read;
        }
    }
}
