from pathlib import Path
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtCausticaLodImporter.java')
s = p.read_text()

old = '''      // Exactly four 16x16 chunks cover one 32x32 Voxy section footprint. Pre-seeding these
      // builders makes ingestSection allocation-free no matter how many vertical slabs exist.
      HashMap<Long, TileBuilder> tiles = new HashMap<>(8);
      for (int dx = 0; dx < 2; dx++) {
          for (int dz = 0; dz < 2; dz++) {
              int cx = baseChunkX + dx;
              int cz = baseChunkZ + dz;
              tiles.put(packCoords(cx, cz), new TileBuilder());
          }
      }
'''
new = '''      // Exactly four 16x16 chunks cover one 32x32 Voxy section footprint. Keep them in fixed
      // quadrant order instead of a boxed HashMap<Long,...>: ingestSection touches these builders for
      // every X/Z column of every vertical slab, so direct indexing removes a very hot hash/boxing path.
      TileBuilder[] tiles = {
              new TileBuilder(), new TileBuilder(), new TileBuilder(), new TileBuilder()
      };
'''
assert old in s
s = s.replace(old, new, 1)

old = '''      for (Map.Entry<Long, TileBuilder> entry : tiles.entrySet()) {
          RtCausticaLodRegionStore.TileData tile = entry.getValue().finish();
          if (tile == null) {
              continue;
          }
          int chunkX = (int) (entry.getKey() >> 32);
          int chunkZ = (int) (long) entry.getKey();
          spool.append(chunkX, chunkZ, tile);
          usableTiles++;
      }
'''
new = '''      for (int dx = 0; dx < 2; dx++) {
          for (int dz = 0; dz < 2; dz++) {
              int tileIndex = (dx << 1) | dz;
              RtCausticaLodRegionStore.TileData tile = tiles[tileIndex].finish();
              if (tile == null) {
                  continue;
              }
              spool.append(baseChunkX + dx, baseChunkZ + dz, tile);
              usableTiles++;
          }
      }
'''
assert old in s
s = s.replace(old, new, 1)

old = '''    private static boolean allGroundResolved(HashMap<Long, TileBuilder> tiles) {
        for (TileBuilder tile : tiles.values()) {
  if (!tile.complete()) {
      return false;
  }
        }
        return true;
    }
'''
new = '''    private static boolean allGroundResolved(TileBuilder[] tiles) {
        for (TileBuilder tile : tiles) {
            if (!tile.complete()) {
                return false;
            }
        }
        return true;
    }
'''
assert old in s
s = s.replace(old, new, 1)

old = '''    private static void ingestSection(long key, byte[] raw, BlockState[] states,
                                      HashMap<Long, TileBuilder> tiles) throws IOException {
'''
new = '''    private static void ingestSection(long key, byte[] raw, BlockState[] states,
                                      TileBuilder[] tiles) throws IOException {
'''
assert old in s
s = s.replace(old, new, 1)

old = '''        int sectionX = voxyX(key);
        int sectionY = voxyY(key);
        int sectionZ = voxyZ(key);
        int baseX = sectionX * VOXY_SECTION_EDGE;
        int baseY = sectionY * VOXY_SECTION_EDGE;
        int baseZ = sectionZ * VOXY_SECTION_EDGE;

        for (int lx = 0; lx < VOXY_SECTION_EDGE; lx++) {
            int worldX = baseX + lx;
            int chunkX = Math.floorDiv(worldX, RtCausticaLodRegionStore.TILE_EDGE);
            int tileX = Math.floorMod(worldX, RtCausticaLodRegionStore.TILE_EDGE);
            for (int lz = 0; lz < VOXY_SECTION_EDGE; lz++) {
                int worldZ = baseZ + lz;
                int chunkZ = Math.floorDiv(worldZ, RtCausticaLodRegionStore.TILE_EDGE);
                int tileZ = Math.floorMod(worldZ, RtCausticaLodRegionStore.TILE_EDGE);
                TileBuilder tile = tiles.computeIfAbsent(packCoords(chunkX, chunkZ), ignored -> new TileBuilder());
                int column = tileX * RtCausticaLodRegionStore.TILE_EDGE + tileZ;
'''
new = '''        int sectionY = voxyY(key);
        int baseY = sectionY * VOXY_SECTION_EDGE;

        for (int lx = 0; lx < VOXY_SECTION_EDGE; lx++) {
            int tileX = lx & (RtCausticaLodRegionStore.TILE_EDGE - 1);
            int tileDx = lx >>> 4;
            for (int lz = 0; lz < VOXY_SECTION_EDGE; lz++) {
                int tileZ = lz & (RtCausticaLodRegionStore.TILE_EDGE - 1);
                int tileDz = lz >>> 4;
                TileBuilder tile = tiles[(tileDx << 1) | tileDz];
                int column = tileX * RtCausticaLodRegionStore.TILE_EDGE + tileZ;
'''
assert old in s
s = s.replace(old, new, 1)

p.write_text(s)
