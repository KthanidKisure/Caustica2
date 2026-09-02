from pathlib import Path
import re

# Consume build->graphics timeline dependencies exactly once -----------------
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/RtGpuExecutor.java')
s = p.read_text()
old = '        long waitValue = pendingPublishWaitValue.get();\n'
new = ('        // Publication and graphics-use reservation are render-thread operations, so no publisher can\n'
       '        // race this exchange. Once one graphics submission waits on the newest published build,\n'
       '        // later submissions on the same graphics queue do not need to re-attach that old wait.\n'
       '        long waitValue = pendingPublishWaitValue.getAndSet(0L);\n')
assert old in s, 'RtGpuExecutor pending wait read not found'
s = s.replace(old, new, 1)
p.write_text(s)

# Publish packed-source readiness directly from the importer -----------------
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtCausticaLodPackedSource.java')
s = p.read_text()
s = s.replace('    private static volatile int minY = -64;\n',
              '    private static volatile int minY = -64;\n    private static volatile boolean packReady;\n', 1)
old = '''        ensureSession(mc, level);
        minY = level.getMinY();
        RtCausticaLodImporter.tick(mc, level, root, identity);
        boolean ready = root != null && Files.isRegularFile(root.resolve("wynnlod-v2.complete"));
        if (ready && LOGGED_PACK_READY.compareAndSet(false, true)) {
            CausticaMod.LOGGER.info("CausticaLOD packed WynnLOD source is active; no DH/Voxy runtime is involved");
        }
        return ready;
'''
new = '''        ensureSession(mc, level);
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
'''
assert old in s, 'packed available block not found'
s = s.replace(old, new, 1)
needle = '''    static void invalidateTile(int chunkX, int chunkZ) {
        long key = packCoords(chunkX, chunkZ);
        MEMORY.remove(key);
        MISSING.remove(key);
    }

'''
insert = needle + '''    /** Called by the background importer only after every region and the completion marker are published. */
    static synchronized void markImportedPackReady(Path completedRoot) {
        if (completedRoot == null || root == null || !root.equals(completedRoot)) {
            return; // import finished for a session that is no longer active
        }
        MISSING.clear();
        RtCausticaLodRegionStore.reset();
        packReady = true;
    }

'''
assert needle in s, 'packed invalidateTile block not found'
s = s.replace(needle, insert, 1)
s = s.replace('        root = null;\n        LOGGED_FIRST_QUERY.set(false);',
              '        root = null;\n        packReady = false;\n        LOGGED_FIRST_QUERY.set(false);', 1)
old = '''        root = FabricLoader.getInstance().getGameDir()
                .resolve("caustica_lod")
                .resolve(hashIdentity(nextIdentity));
        LOGGED_FIRST_QUERY.set(false);
'''
new = '''        root = FabricLoader.getInstance().getGameDir()
                .resolve("caustica_lod")
                .resolve(hashIdentity(nextIdentity));
        // One metadata check per session handles packs completed by an earlier client run. A pack
        // completed in this process uses markImportedPackReady() and needs no polling.
        packReady = Files.isRegularFile(root.resolve("wynnlod-v2.complete"));
        LOGGED_FIRST_QUERY.set(false);
'''
assert old in s, 'packed ensureSession root block not found'
s = s.replace(old, new, 1)
p.write_text(s)

# Notify packed source when import finishes ----------------------------------
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtCausticaLodImporter.java')
s = p.read_text()
old = '''            RtCausticaLodRegionStore.reset();
            CausticaMod.LOGGER.info(
                    "CausticaLOD WynnLOD import complete: {} level-0 sections -> {} surface tiles in {} packed regions; DH/Voxy are not required for rendering",
'''
new = '''            RtCausticaLodPackedSource.markImportedPackReady(sessionRoot);
            CausticaMod.LOGGER.info(
                    "CausticaLOD WynnLOD import complete: {} level-0 sections -> {} surface tiles in {} packed regions; DH/Voxy are not required for rendering",
'''
assert old in s, 'import completion reset block not found'
s = s.replace(old, new, 1)
p.write_text(s)

# Keep region-reader lock around channel IO only, not decompression ----------
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtCausticaLodRegionStore.java')
s = p.read_text()
s = s.replace('    private static final int RAW_TILE_BYTES = TILE_COLUMNS * (2 + 2 + 4 + 4 + 4);\n',
              '    private static final int RAW_TILE_BYTES = TILE_COLUMNS * (2 + 2 + 4 + 4 + 4);\n'
              '    // DEFLATE overhead for a 4 KiB payload is tiny; leave generous headroom while rejecting\n'
              '    // damaged indexes that would otherwise request pathological heap allocations.\n'
              '    private static final int MAX_COMPRESSED_TILE_BYTES = RAW_TILE_BYTES + 1024;\n', 1)
old = '''    static TileData read(Path root, int chunkX, int chunkZ) {
        // Keep the cache lock through the small positional read/decompression. That makes eviction
        // unable to close a channel that another worker is actively using, without a ref-counted FD
        // wrapper. Region payloads are only ~4 KiB raw, so this lock is much cheaper than an OS-handle
        // leak and disk remains far outside the render thread.
        synchronized (READERS) {
            RegionReader reader = readerLocked(root, chunkX, chunkZ);
            return reader != null ? reader.read(slot(chunkX, chunkZ)) : null;
        }
    }
'''
new = '''    static TileData read(Path root, int chunkX, int chunkZ) {
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
'''
assert old in s, 'region read block not found'
s = s.replace(old, new, 1)
old = '                if (offset < HEADER_BYTES || length <= 0 || Integer.toUnsignedLong(offset) + length > size) {'
new = ('                if (offset < HEADER_BYTES || length <= 0 || length > MAX_COMPRESSED_TILE_BYTES\n'
       '                        || Integer.toUnsignedLong(offset) + length > size) {')
assert old in s, 'region index validation not found'
s = s.replace(old, new, 1)
old = '''        TileData read(int index) {
            int length = lengths[index];
            if (length <= 0) {
                return null;
            }
            byte[] bytes = new byte[length];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            try {
                readFully(channel, buffer, Integer.toUnsignedLong(offsets[index]));
                return decompress(bytes);
            } catch (IOException | RuntimeException e) {
                CausticaMod.LOGGER.debug("CausticaLOD packed tile read failed: {}", e.toString());
                return null;
            }
        }
'''
new = '''        byte[] readCompressed(int index) {
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
'''
assert old in s, 'RegionReader.read block not found'
s = s.replace(old, new, 1)
p.write_text(s)
