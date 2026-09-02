from pathlib import Path

ROOT = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain')
OLD = '''    private static int skirtBottom(SurfaceColumn[] grid, int gx, int gz, int groundY) {
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
'''
NEW = '''    private static int skirtBottom(SurfaceColumn[] grid, int gx, int gz, int groundY) {
        int bottom = groundY;
        SurfaceColumn neighbor = grid[gridIndex(gx - 1, gz)];
        if (neighbor != null && neighbor.groundY != NO_HEIGHT) {
            bottom = Math.min(bottom, neighbor.groundY);
        }
        neighbor = grid[gridIndex(gx + 1, gz)];
        if (neighbor != null && neighbor.groundY != NO_HEIGHT) {
            bottom = Math.min(bottom, neighbor.groundY);
        }
        neighbor = grid[gridIndex(gx, gz - 1)];
        if (neighbor != null && neighbor.groundY != NO_HEIGHT) {
            bottom = Math.min(bottom, neighbor.groundY);
        }
        neighbor = grid[gridIndex(gx, gz + 1)];
        if (neighbor != null && neighbor.groundY != NO_HEIGHT) {
            bottom = Math.min(bottom, neighbor.groundY);
        }
        return bottom;
    }
'''
for name in ('RtCausticaLodPackedSource.java', 'RtCausticaLodSource.java'):
    p = ROOT / name
    s = p.read_text()
    assert OLD in s, f'skirtBottom allocation block not found in {name}'
    p.write_text(s.replace(OLD, NEW, 1))
