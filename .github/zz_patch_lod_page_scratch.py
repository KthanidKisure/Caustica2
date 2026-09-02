from pathlib import Path

FILES = [
    Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtCausticaLodSource.java'),
    Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtCausticaLodPackedSource.java'),
]

for p in FILES:
    s = p.read_text()

    old = '''    /** Per-worker primitive scratch: avoids thousands of short-lived sample arrays/records per LOD page. */
    private static final ThreadLocal<SampleScratch> SAMPLE_SCRATCH =
            ThreadLocal.withInitial(SampleScratch::new);
'''
    new = '''    private static final int GRID_EDGE = RtDhLodRegion.SECTION_BLOCKS + 2;
    private static final int GRID_CELLS = GRID_EDGE * GRID_EDGE;
    /** Per-worker reusable page + five-point reducer scratch; no per-page SurfaceColumn object burst. */
    private static final ThreadLocal<PageScratch> PAGE_SCRATCH =
            ThreadLocal.withInitial(PageScratch::new);
'''
    assert old in s, p
    s = s.replace(old, new, 1)

    old = '''        SurfaceColumn[] surfaceGrid = sampleSurfaceGrid(originBlockX, originBlockZ, scale);
'''
    new = '''        PageScratch surfaceGrid = sampleSurfaceGrid(originBlockX, originBlockZ, scale);
'''
    assert old in s, p
    s = s.replace(old, new, 1)

    old = '''                SurfaceColumn column = surfaceGrid[gridIndex(vx + 1, vz + 1)];
                if (column == null || column.groundY == NO_HEIGHT) {
                    continue;
                }
                resolvedCells++;

                int groundY = column.groundY;
                int surfaceY = Math.max(column.surfaceY, groundY);
                BlockState ground = stateById(column.groundStateId);
                BlockState body = stateById(column.bodyStateId);
                BlockState surface = stateById(column.surfaceStateId);
'''
    new = '''                int gridSlot = gridIndex(vx + 1, vz + 1);
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
'''
    assert old in s, p
    s = s.replace(old, new, 1)

    start = s.index('    /** Pre-sample the page plus a one-cell border so cliff skirts reuse exactly the same data. */')
    end = s.index('    private static boolean sampleColumn(SampleScratch scratch, int blockX, int blockZ) {', start)
    replacement = '''    /** Pre-sample the page plus a one-cell border so cliff skirts reuse exactly the same data. */
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

'''
    s = s[:start] + replacement + s[end:]

    old = '''    private static final class SampleScratch {
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

    private record SurfaceColumn(short groundY, short surfaceY,
                                 int groundStateId, int bodyStateId, int surfaceStateId) {
    }
'''
    new = '''    private static final class PageScratch {
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
'''
    assert old in s, p
    s = s.replace(old, new, 1)

    p.write_text(s)
