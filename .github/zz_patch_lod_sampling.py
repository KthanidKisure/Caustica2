from pathlib import Path
import re

ROOT = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain')

ROBUST_METHOD = '''    private static SurfaceColumn representativeColumn(int cellX, int cellZ, int scale) {
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
'''

def replace_method(text: str) -> str:
    pattern = re.compile(r'    private static SurfaceColumn representativeColumn\(int cellX, int cellZ, int scale\) \{.*?\n    \}\n\n    private static SurfaceColumn columnAt', re.S)
    out, count = pattern.subn(ROBUST_METHOD + '\n    private static SurfaceColumn columnAt', text, count=1)
    assert count == 1, 'representativeColumn replacement failed'
    return out

# Imported/packed source -------------------------------------------------------
p = ROOT / 'RtCausticaLodPackedSource.java'
s = p.read_text()
s = s.replace('import java.util.concurrent.ConcurrentHashMap;\n', '')
s = s.replace(
    '    private static final RtLodTileCache<RtCausticaLodRegionStore.TileData> MEMORY = new RtLodTileCache<>(8192);\n',
    '    private static final RtLodTileCache<RtCausticaLodRegionStore.TileData> MEMORY = new RtLodTileCache<>(8192);\n'
    '    /** Negative cache for uncovered chunks; imported regions are immutable once the completion marker exists. */\n'
    '    private static final RtLodTileCache<Boolean> MISSING = new RtLodTileCache<>(32768);\n', 1)
s = s.replace(
    '    static void invalidateTile(int chunkX, int chunkZ) {\n'
    '        MEMORY.remove(packCoords(chunkX, chunkZ));\n'
    '    }',
    '    static void invalidateTile(int chunkX, int chunkZ) {\n'
    '        long key = packCoords(chunkX, chunkZ);\n'
    '        MEMORY.remove(key);\n'
    '        MISSING.remove(key);\n'
    '    }', 1)
s = s.replace('        MEMORY.clear();\n        identity = "";', '        MEMORY.clear();\n        MISSING.clear();\n        identity = "";', 1)
s = s.replace('        MEMORY.clear();\n        RtCausticaLodRegionStore.reset();', '        MEMORY.clear();\n        MISSING.clear();\n        RtCausticaLodRegionStore.reset();', 1)
s = replace_method(s)
old_tile = '''    private static RtCausticaLodRegionStore.TileData tile(int chunkX, int chunkZ) {
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
'''
new_tile = '''    private static RtCausticaLodRegionStore.TileData tile(int chunkX, int chunkZ) {
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
'''
assert old_tile in s, 'packed tile method not found'
s = s.replace(old_tile, new_tile, 1)
p.write_text(s)

# Live native source ----------------------------------------------------------
p = ROOT / 'RtCausticaLodSource.java'
s = p.read_text()
s = s.replace(
    '    private static final RtLodTileCache<Boolean> KNOWN_ON_DISK = new RtLodTileCache<>(32768);\n',
    '    private static final RtLodTileCache<Boolean> KNOWN_ON_DISK = new RtLodTileCache<>(32768);\n'
    '    /** Missing/corrupt files discovered by worker queries; cleared only after a successful rewrite. */\n'
    '    private static final RtLodTileCache<Boolean> UNAVAILABLE_ON_DISK = new RtLodTileCache<>(32768);\n', 1)
s = s.replace(
    '            Path path = tilePath(cx, cz);\n'
    '            if (path != null && Files.isRegularFile(path)) {\n'
    '                KNOWN_ON_DISK.put(key, Boolean.TRUE);\n'
    '                continue;\n'
    '            }',
    '            Path path = tilePath(cx, cz);\n'
    '            // A worker may already have proved this file missing/corrupt. In that case do not trust\n'
    '            // its directory entry again: recapture the currently loaded chunk and atomically replace it.\n'
    '            if (!UNAVAILABLE_ON_DISK.containsKey(key) && path != null && Files.isRegularFile(path)) {\n'
    '                KNOWN_ON_DISK.put(key, Boolean.TRUE);\n'
    '                continue;\n'
    '            }', 1)
s = s.replace('        MEMORY.clear();\n        KNOWN_ON_DISK.clear();\n        sessionIdentity = "";',
              '        MEMORY.clear();\n        KNOWN_ON_DISK.clear();\n        UNAVAILABLE_ON_DISK.clear();\n        sessionIdentity = "";', 1)
s = s.replace('        MEMORY.clear();\n        KNOWN_ON_DISK.clear();\n        sessionIdentity = identity;',
              '        MEMORY.clear();\n        KNOWN_ON_DISK.clear();\n        UNAVAILABLE_ON_DISK.clear();\n        sessionIdentity = identity;', 1)
s = replace_method(s)
old_live_tile = '''    private static SurfaceTile tile(int chunkX, int chunkZ) {
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
'''
new_live_tile = '''    private static SurfaceTile tile(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        SurfaceTile resident = MEMORY.get(key);
        if (resident != null) {
            return resident;
        }
        if (UNAVAILABLE_ON_DISK.containsKey(key)) {
            return null;
        }
        Path path = tilePath(chunkX, chunkZ);
        if (path == null) {
            return null;
        }
        if (!Files.isRegularFile(path)) {
            UNAVAILABLE_ON_DISK.put(key, Boolean.TRUE);
            return null;
        }
        SurfaceTile loaded = readTile(path, chunkX, chunkZ);
        if (loaded != null) {
            UNAVAILABLE_ON_DISK.remove(key);
            SurfaceTile raced = MEMORY.putIfAbsent(key, loaded);
            KNOWN_ON_DISK.put(key, Boolean.TRUE);
            if (LOGGED_FIRST_DISK_LOAD.compareAndSet(false, true)) {
                CausticaMod.LOGGER.info("CausticaLOD restored persistent native surface tile {},{}", chunkX, chunkZ);
            }
            return raced != null ? raced : loaded;
        }
        // A stale/truncated tile must not permanently suppress recapture merely because the file exists.
        KNOWN_ON_DISK.remove(key);
        UNAVAILABLE_ON_DISK.put(key, Boolean.TRUE);
        return null;
    }
'''
assert old_live_tile in s, 'live tile method not found'
s = s.replace(old_live_tile, new_live_tile, 1)
s = s.replace(
    '                    if (capturedRoot.equals(currentRoot)) {\n'
    '                        KNOWN_ON_DISK.put(key, Boolean.TRUE);',
    '                    if (capturedRoot.equals(currentRoot)) {\n'
    '                        UNAVAILABLE_ON_DISK.remove(key);\n'
    '                        KNOWN_ON_DISK.put(key, Boolean.TRUE);', 1)
p.write_text(s)
