from pathlib import Path

# Centralize full-resolution overlap test ------------------------------------
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtDhLodSource.java')
s = p.read_text()
old = '''        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            int fullRadius = (Math.max(1, mc.options.getEffectiveRenderDistance()) + 2) * 16;
            int px = mc.player.getBlockX();
            int pz = mc.player.getBlockZ();
            long minX = originBlockX;
            long minZ = originBlockZ;
            long maxX = minX + Math.max(1, footprintBlocks) - 1L;
            long maxZ = minZ + Math.max(1, footprintBlocks) - 1L;
            if (maxX >= (long) px - fullRadius && minX <= (long) px + fullRadius
                    && maxZ >= (long) pz - fullRadius && minZ <= (long) pz + fullRadius) {
                // Retry rather than mark empty: after the player moves this same persistent region may
                // become distant and should become eligible immediately, not after the empty cooldown.
                return new FetchResult(List.of(), false);
            }
        }
'''
new = '''        if (overlapsFullResolution(footprintBlocks, originBlockX, originBlockZ)) {
            // Retry rather than mark empty: after the player moves this same persistent region may
            // become distant and should become eligible immediately, not after the empty cooldown.
            return new FetchResult(List.of(), false);
        }
'''
assert old in s, 'inline full-resolution overlap test not found'
s = s.replace(old, new, 1)
needle = '''    public static FetchResult fetchArea(int footprintBlocks, int originBlockX, int originBlockZ) {
'''
helper = '''    /** True when this coarse page intersects the vanilla/full-resolution RT terrain window. */
    static boolean overlapsFullResolution(int footprintBlocks, int originBlockX, int originBlockZ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        int fullRadius = (Math.max(1, mc.options.getEffectiveRenderDistance()) + 2) * 16;
        int px = mc.player.getBlockX();
        int pz = mc.player.getBlockZ();
        long minX = originBlockX;
        long minZ = originBlockZ;
        long maxX = minX + Math.max(1, footprintBlocks) - 1L;
        long maxZ = minZ + Math.max(1, footprintBlocks) - 1L;
        return maxX >= (long) px - fullRadius && minX <= (long) px + fullRadius
                && maxZ >= (long) pz - fullRadius && minZ <= (long) pz + fullRadius;
    }

'''
assert needle in s, 'fetchArea declaration not found'
s = s.replace(needle, helper + needle, 1)
p.write_text(s)

# A ready immutable packed source can authoritatively answer "empty" --------
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtCausticaLodPackedSource.java')
s = p.read_text()
old = '''        if (resolvedCells == 0) {
            return new RtCausticaLodSource.FetchResult(List.of(), false);
        }
'''
new = '''        if (resolvedCells == 0) {
            // The imported pack is immutable once packReady is true. No covered cells is therefore a
            // confirmed empty/uncovered result, not a transient source failure that should stall other pages.
            return new RtCausticaLodSource.FetchResult(List.of(), true);
        }
'''
assert old in s, 'packed resolvedCells empty result not found'
s = s.replace(old, new, 1)
p.write_text(s)

# Streamer: skip near pages before dispatch, per-key transient retry, cull near resident/completions
p = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrain.java')
s = p.read_text()
s = s.replace(
    '    private static final long LOD_TRANSIENT_RETRY_NANOS = 1_000_000_000L;\n    private long lodSourceRetryAfterNanos;\n',
    '    /** Per-page retry for live-cache misses; never stalls unrelated LOD pages. */\n'
    '    private static final long LOD_TRANSIENT_RETRY_FRAMES = 60L;\n', 1)
s = s.replace('''        publishLodPrepared(ctx, pbx, pby, pbz);
        if (System.nanoTime() < lodSourceRetryAfterNanos) {
            return;
        }

''', '''        publishLodPrepared(ctx, pbx, pby, pbz);

''', 1)
old = '''                        long key = lodKey(lx, ly, lz, detail);
                        if (lodResident.containsKey(key) || lodInFlight.contains(key)) {
                            continue;
                        }
'''
new = '''                        int originX = lx * sectionBlocks;
                        int originZ = lz * sectionBlocks;
                        // The source rejects these too, but skipping here is essential: near pages must
                        // not consume the nearest-first dispatch budget and starve the actual distant ring.
                        if (RtDhLodSource.overlapsFullResolution(sectionBlocks, originX, originZ)) {
                            continue;
                        }
                        long key = lodKey(lx, ly, lz, detail);
                        if (lodResident.containsKey(key) || lodInFlight.contains(key)) {
                            continue;
                        }
'''
assert old in s, 'LOD dispatch key block not found'
s = s.replace(old, new, 1)
old = '''            int currentDeferred = 0;
            for (LodCompletion completion : retrySoon) {
                if (completion.generation() != currentGeneration) {
                    continue;
                }
                lodInFlight.remove(completion.key());
                currentDeferred++;
            }
            if (currentDeferred != 0) {
                lodSourceRetryAfterNanos = System.nanoTime() + LOD_TRANSIENT_RETRY_NANOS;
                CausticaMod.LOGGER.info(
                        "CausticaLOD source not query-ready; retrying in 1 second ({} regions deferred)",
                        currentDeferred);
            }
'''
new = '''            long retryAfter = RtComposite.frameCounter() + LOD_TRANSIENT_RETRY_FRAMES;
            for (LodCompletion completion : retrySoon) {
                if (completion.generation() != currentGeneration) {
                    continue;
                }
                lodInFlight.remove(completion.key());
                lodRetryAfterFrame.put(completion.key(), retryAfter);
            }
'''
assert old in s, 'global transient retry block not found'
s = s.replace(old, new, 1)
# Reject completed work that became near or fell outside the padded residency ring while async work ran.
old = '''        boolean changed = false;
        for (LodPrepared completion : batch) {
            PreparedSection ps = completion.prepared();
            if (completion.generation() != currentGeneration
                    || lodActiveDetail == Integer.MIN_VALUE
                    || !CausticaConfig.Rt.Lod.ENABLED.value()) {
                destroyPreparedSection(ps);
                continue;
            }

            lodInFlight.remove(ps.key());
            lodRetryAfterFrame.remove(ps.key());
'''
new = '''        boolean changed = false;
        int currentRadius = CausticaConfig.Rt.Lod.RADIUS.value();
        for (LodPrepared completion : batch) {
            PreparedSection ps = completion.prepared();
            if (completion.generation() != currentGeneration
                    || lodActiveDetail == Integer.MIN_VALUE
                    || !CausticaConfig.Rt.Lod.ENABLED.value()) {
                destroyPreparedSection(ps);
                continue;
            }

            lodInFlight.remove(ps.key());
            lodRetryAfterFrame.remove(ps.key());
            int sectionBlocks = RtDhLodRegion.SECTION_BLOCKS * completion.scale();
            int centreX = Math.floorDiv(pbx, sectionBlocks);
            int centreZ = Math.floorDiv(pbz, sectionBlocks);
            int lx = Math.floorDiv(ps.sx(), sectionBlocks);
            int lz = Math.floorDiv(ps.sz(), sectionBlocks);
            boolean outside = Math.max(Math.abs(lx - centreX), Math.abs(lz - centreZ)) > currentRadius + 2;
            boolean overlapsNear = RtDhLodSource.overlapsFullResolution(sectionBlocks, ps.sx(), ps.sz());
            if (outside || overlapsNear) {
                destroyPreparedSection(ps);
                continue;
            }
'''
assert old in s, 'LOD prepared publication block not found'
s = s.replace(old, new, 1)
# Existing resident pages also leave as soon as they enter the full-resolution window.
old = '''            if (Math.max(Math.abs(lx - centreX), Math.abs(lz - centreZ)) <= retainRadius) {
                continue;
            }
'''
new = '''            boolean insideRetainRing = Math.max(Math.abs(lx - centreX), Math.abs(lz - centreZ)) <= retainRadius;
            boolean overlapsNear = RtDhLodSource.overlapsFullResolution(sectionBlocks, g.sx, g.sz);
            if (insideRetainRing && !overlapsNear) {
                continue;
            }
'''
assert old in s, 'LOD eviction retain-ring test not found'
s = s.replace(old, new, 1)
s = s.replace('        lodSourceRetryAfterNanos = 0L;\n', '', 1)
p.write_text(s)
