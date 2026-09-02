from pathlib import Path

p = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrain.java')
s = p.read_text()
s = s.replace('    private long lodGeneration = 1L;\n',
              '    private volatile long lodGeneration = 1L;\n', 1)
old = '''            RtWorkerPool.INSTANCE.submit(() -> {
                try {
                    // One source query covers this virtual section's whole horizontal footprint. The source
'''
new = '''            RtWorkerPool.INSTANCE.submit(() -> {
                try {
                    if (generation != lodGeneration) {
                        finishLodStale();
                        return;
                    }
                    // One source query covers this virtual section's whole horizontal footprint. The source
'''
assert old in s, 'worker start block not found'
s = s.replace(old, new, 1)
old = '''                    RtDhLodSource.FetchResult fetched = RtDhLodSource.fetchArea(sectionBlocks, originX, originZ);
        if (!fetched.querySucceeded()) {
            finishLodRetrySoon(key, generation);
            return;
        }
        java.util.List<RtDhLodSource.LodBox> boxes = fetched.boxes();
                    region.fill(boxes);
'''
new = '''                    RtDhLodSource.FetchResult fetched = RtDhLodSource.fetchArea(sectionBlocks, originX, originZ);
                    if (generation != lodGeneration) {
                        finishLodStale();
                        return;
                    }
                    if (!fetched.querySucceeded()) {
                        finishLodRetrySoon(key, generation);
                        return;
                    }
                    java.util.List<RtDhLodSource.LodBox> boxes = fetched.boxes();
                    region.fill(boxes);
'''
assert old in s, 'fetch result block not found'
s = s.replace(old, new, 1)
old = '''                    PackedSection packed = cpu.packed();
                    if (packed == null) {
                        finishLodTask(key, null, generation, scale);
                        return;
                    }
                    PreparedSection ps = RtSectionBuilder.prepare(dispatch.ctx(), packed,
'''
new = '''                    PackedSection packed = cpu.packed();
                    if (packed == null) {
                        finishLodTask(key, null, generation, scale);
                        return;
                    }
                    if (generation != lodGeneration) {
                        finishLodStale();
                        return;
                    }
                    PreparedSection ps = RtSectionBuilder.prepare(dispatch.ctx(), packed,
'''
assert old in s, 'packed/prepared block not found'
s = s.replace(old, new, 1)
old = '''                            cpu.opacityMicromap(), CausticaConfig.Rt.Terrain.BLAS_COMPACTION.value(),
                            key, originX, originY, originZ);
                    submitLodBuild(dispatch.ctx(), key, ps, scale, generation);
'''
new = '''                            cpu.opacityMicromap(), CausticaConfig.Rt.Terrain.BLAS_COMPACTION.value(),
                            key, originX, originY, originZ);
                    if (generation != lodGeneration) {
                        destroyPreparedSection(ps);
                        finishLodStale();
                        return;
                    }
                    submitLodBuild(dispatch.ctx(), key, ps, scale, generation);
'''
assert old in s, 'prepare/submit block not found'
s = s.replace(old, new, 1)
s = s.replace('''        ctx.gpuExecutor().submit(
                () -> false,
''', '''        ctx.gpuExecutor().submit(
                () -> generation != lodGeneration,
''', 1)
needle = '''    private void finishLodRetrySoon(long key, long generation) {
        lodRetrySoon.add(new LodCompletion(key, generation));
        finishActiveTask();
    }

'''
insert = needle + '''    /** Old-generation work needs no retry bookkeeping: releaseLod already cleared its render-owned key. */
    private void finishLodStale() {
        finishActiveTask();
    }

'''
assert needle in s, 'finishLodRetrySoon block not found'
s = s.replace(needle, insert, 1)
p.write_text(s)

# Remove a packed-source field that is written every query but never read.
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtCausticaLodPackedSource.java')
s = p.read_text()
s = s.replace('    private static volatile int minY = -64;\n', '', 1)
s = s.replace('        minY = level.getMinY();\n', '', 1)
p.write_text(s)
