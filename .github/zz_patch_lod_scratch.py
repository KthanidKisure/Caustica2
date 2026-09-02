from pathlib import Path
import re

ROOT = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain')

COMMON_REPRESENTATIVE = '''    private static SurfaceColumn representativeColumn(int cellX, int cellZ, int scale) {
        int half = Math.max(0, scale / 2);
        int quarter = Math.max(0, scale / 4);
        int threeQuarter = Math.max(0, (scale * 3) / 4);
        int end = Math.max(0, scale - 1);

        SampleScratch scratch = SAMPLE_SCRATCH.get();
        scratch.count = 0;
        sampleColumn(scratch, cellX + half, cellZ + half);
        sampleColumn(scratch, cellX + quarter, cellZ + quarter);
        sampleColumn(scratch, cellX + threeQuarter, cellZ + quarter);
        sampleColumn(scratch, cellX + quarter, cellZ + threeQuarter);
        sampleColumn(scratch, cellX + end, cellZ + end);
        int sampleCount = scratch.count;
        if (sampleCount == 0) {
            return null;
        }

        int highest = 0;
        for (int i = 1; i < sampleCount; i++) {
            if (scratch.surfaceY[i] > scratch.surfaceY[highest]) {
                highest = i;
            }
        }
        if (scale <= 4 || sampleCount == 1) {
            return scratch.column(highest);
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
            return new SurfaceColumn(scratch.groundY[ground], scratch.groundY[ground],
                    scratch.groundStateId[ground], scratch.bodyStateId[ground], scratch.groundStateId[ground]);
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
            return new SurfaceColumn(scratch.groundY[ground], scratch.groundY[ground],
                    scratch.groundStateId[ground], scratch.bodyStateId[ground], scratch.groundStateId[ground]);
        }
        return new SurfaceColumn(scratch.groundY[ground], scratch.surfaceY[surface],
                scratch.groundStateId[ground], scratch.bodyStateId[ground], scratch.surfaceStateId[surface]);
    }

'''

SCRATCH_CLASS = '''    private static final class SampleScratch {
        int count;
        final short[] groundY = new short[5];
        final short[] surfaceY = new short[5];
        final int[] groundStateId = new int[5];
        final int[] bodyStateId = new int[5];
        final int[] surfaceStateId = new int[5];
        final int[] elevatedIndices = new int[5];
        final int[] sortHeights = new int[5];

        SurfaceColumn column(int index) {
            return new SurfaceColumn(groundY[index], surfaceY[index],
                    groundStateId[index], bodyStateId[index], surfaceStateId[index]);
        }
    }

'''

def patch(path: Path, packed: bool):
    s = path.read_text()
    cache_needle = ('    private static final RtLodTileCache<RtCausticaLodRegionStore.TileData> MEMORY = new RtLodTileCache<>(8192);\n'
                    if packed else
                    '    private static final RtLodTileCache<SurfaceTile> MEMORY = new RtLodTileCache<>(8192);\n')
    assert cache_needle in s
    s = s.replace(cache_needle, cache_needle +
                  '    /** Per-worker primitive scratch: avoids thousands of short-lived sample arrays/records per LOD page. */\n'
                  '    private static final ThreadLocal<SampleScratch> SAMPLE_SCRATCH =\n'
                  '            ThreadLocal.withInitial(SampleScratch::new);\n', 1)

    pattern = re.compile(r'    private static SurfaceColumn representativeColumn\(int cellX, int cellZ, int scale\) \{.*?\n    \}\n\n    private static SurfaceColumn columnAt\(int blockX, int blockZ\) \{.*?\n    \}\n', re.S)
    if packed:
        sampler = '''    private static boolean sampleColumn(SampleScratch scratch, int blockX, int blockZ) {
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
'''
    else:
        sampler = '''    private static boolean sampleColumn(SampleScratch scratch, int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, TILE_EDGE);
        int chunkZ = Math.floorDiv(blockZ, TILE_EDGE);
        SurfaceTile tile = tile(chunkX, chunkZ);
        if (tile == null) {
            return false;
        }
        int lx = Math.floorMod(blockX, TILE_EDGE);
        int lz = Math.floorMod(blockZ, TILE_EDGE);
        int index = lx * TILE_EDGE + lz;
        short groundY = tile.groundY[index];
        if (groundY == NO_HEIGHT) {
            return false;
        }
        int slot = scratch.count++;
        scratch.groundY[slot] = groundY;
        scratch.surfaceY[slot] = tile.surfaceY[index];
        scratch.groundStateId[slot] = tile.groundStateId[index];
        scratch.bodyStateId[slot] = tile.bodyStateId[index];
        scratch.surfaceStateId[slot] = tile.surfaceStateId[index];
        return true;
    }
'''
    replacement = COMMON_REPRESENTATIVE + sampler
    s, count = pattern.subn(replacement, s, count=1)
    assert count == 1, f'sample method replacement failed for {path}'

    record_needle = '    private record SurfaceColumn(short groundY, short surfaceY,\n'
    assert record_needle in s
    s = s.replace(record_needle, SCRATCH_CLASS + record_needle, 1)
    path.write_text(s)

patch(ROOT / 'RtCausticaLodPackedSource.java', True)
patch(ROOT / 'RtCausticaLodSource.java', False)
