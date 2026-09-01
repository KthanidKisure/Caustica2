from pathlib import Path

p = Path("src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrain.java")
s = p.read_text()

old_fields = '''    private final List<PreparedSection> lodPrepared =
            java.util.Collections.synchronizedList(new ArrayList<>());
    /** Keys whose dispatch ended without geometry (empty region, DH miss, or a build failure). */
    private final List<Long> lodFailed = java.util.Collections.synchronizedList(new ArrayList<>());
    /** Transient source misses: retry soon and never poison the long empty-region cooldown. */
    private final List<Long> lodRetrySoon = java.util.Collections.synchronizedList(new ArrayList<>());'''
new_fields = '''    /** Worker/GPU completions carry their dispatch generation so stale work can never publish after
     * a world/config transition. The lists are synchronized because completions arrive off-thread. */
    private final List<LodPrepared> lodPrepared =
            java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<LodCompletion> lodFailed =
            java.util.Collections.synchronizedList(new ArrayList<>());
    /** Transient source misses: retry soon and never poison the long empty-region cooldown. */
    private final List<LodCompletion> lodRetrySoon =
            java.util.Collections.synchronizedList(new ArrayList<>());'''
assert old_fields in s
s = s.replace(old_fields, new_fields, 1)

old_state = '''    private static final long LOD_TRANSIENT_RETRY_NANOS = 1_000_000_000L;
    private long lodSourceRetryAfterNanos;'''
new_state = '''    private static final long LOD_TRANSIENT_RETRY_NANOS = 1_000_000_000L;
    private long lodSourceRetryAfterNanos;
    /** Monotonic render-owned epoch for native LOD work. Every release/config change invalidates old callbacks. */
    private long lodGeneration = 1L;
    private int lodActiveDetail = Integer.MIN_VALUE;
    private int lodActiveHeightSections = Integer.MIN_VALUE;
    private int lodLoggedEmptyCount = -1;'''
assert old_state in s
s = s.replace(old_state, new_state, 1)

start = s.index('    private void streamLod(RtContext ctx, ClientLevel level, int pbx, int pby, int pbz) {')
end = s.index('    private void dispatchLodSection(', start)
new_stream = '''    private void streamLod(RtContext ctx, ClientLevel level, int pbx, int pby, int pbz) {
        boolean enabled = CausticaConfig.Rt.Lod.ENABLED.value();
        int requestedDetail = CausticaConfig.Rt.Lod.DETAIL.value();
        int requestedHeightSections = CausticaConfig.Rt.Lod.HEIGHT_SECTIONS.value();

        if (!enabled || !RtDhLodSource.available()) {
            if (enabled && !lodLoggedState) {
                lodLoggedState = true;
                CausticaMod.LOGGER.info(
                        "CausticaLOD enabled but its native source is not ready; no distant terrain yet");
            }
            releaseLod(ctx);
            return;
        }

        if (lodActiveDetail != requestedDetail || lodActiveHeightSections != requestedHeightSections) {
            releaseLod(ctx);
            lodActiveDetail = requestedDetail;
            lodActiveHeightSections = requestedHeightSections;
        }

        int empties = lodEmptyRegions.get();
        if (empties >= 64 && empties % 64 == 0 && lodResident.isEmpty()
                && empties != lodLoggedEmptyCount) {
            lodLoggedEmptyCount = empties;
            CausticaMod.LOGGER.info(
                    "CausticaLOD: {} empty regions, {} source boxes observed, none published (detail {})",
                    empties, lodBoxesSeen.get(), requestedDetail);
        }
        if (!lodLoggedState) {
            lodLoggedState = true;
            CausticaMod.LOGGER.info("CausticaLOD active: detail={}, radius={}, native source available",
                    requestedDetail, CausticaConfig.Rt.Lod.RADIUS.value());
        }

        publishLodPrepared(ctx, pbx, pby, pbz);
        if (System.nanoTime() < lodSourceRetryAfterNanos) {
            return;
        }

        int detail = requestedDetail;
        int scale = 1 << detail;
        int sectionBlocks = RtDhLodRegion.SECTION_BLOCKS * scale;
        int radius = CausticaConfig.Rt.Lod.RADIUS.value();
        int configuredHeightSections = requestedHeightSections;
        int budget = CausticaConfig.Rt.Lod.SECTIONS_PER_FRAME.value();

        int centreX = Math.floorDiv(pbx, sectionBlocks);
        int centreZ = Math.floorDiv(pbz, sectionBlocks);
        int minPageY = Math.floorDiv(level.getMinY(), sectionBlocks);
        int maxBlockY = level.getMinY() + level.getHeight() - 1;
        int maxPageY = Math.floorDiv(maxBlockY, sectionBlocks);
        int worldPageCount = Math.max(1, maxPageY - minPageY + 1);
        int heightSections = Math.min(configuredHeightSections, worldPageCount);
        int playerPageY = Math.floorDiv(pby, sectionBlocks);
        int maxStartY = maxPageY - heightSections + 1;
        int startPageY = Math.clamp(playerPageY - heightSections / 2, minPageY, maxStartY);
        evictLodOutside(ctx, centreX, centreZ, sectionBlocks, radius + 2);
        long frame = RtComposite.frameCounter();
        if ((frame & 127L) == 0L) {
            pruneExpiredLodRetries(frame);
        }

        outer:
        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    for (int dy = 0; dy < heightSections; dy++) {
                        if (budget <= 0) {
                            break outer;
                        }
                        int lx = centreX + dx;
                        int lz = centreZ + dz;
                        int ly = startPageY + dy;
                        long key = lodKey(lx, ly, lz, detail);
                        if (lodResident.containsKey(key) || lodInFlight.contains(key)) {
                            continue;
                        }
                        long retryAfter = lodRetryAfterFrame.get(key);
                        if (retryAfter > frame) {
                            continue;
                        }
                        lodRetryAfterFrame.remove(key);
                        dispatchLodSection(ctx, level, key, lx, ly, lz, detail, scale, sectionBlocks);
                        budget--;
                    }
                }
            }
        }
    }

'''
s = s[:start] + new_stream + s[end:]

s = s.replace('''        int originZ = lz * sectionBlocks;
        lodInFlight.add(key);
        beginActiveTask();''', '''        int originZ = lz * sectionBlocks;
        long generation = lodGeneration;
        lodInFlight.add(key);
        beginActiveTask();''', 1)
s = s.replace('finishLodRetrySoon(key);', 'finishLodRetrySoon(key, generation);')
s = s.replace('finishLodTask(key, null);', 'finishLodTask(key, null, generation, scale);')
s = s.replace('submitLodBuild(dispatch.ctx(), key, ps, scale);',
              'submitLodBuild(dispatch.ctx(), key, ps, scale, generation);', 1)

start = s.index('    private void submitLodBuild(')
end = s.index('    /** Publishes finished LOD sections into the shared section table. */', start)
new_submit_finish = '''    private void submitLodBuild(RtContext ctx, long key, PreparedSection prepared,
                                int scale, long generation) {
        ctx.gpuExecutor().submit(
                () -> false,
                cmd -> {
                    RtSectionBuilder.recordUpload(cmd, prepared);
                    RtAccel.recordBlasBuilds(ctx, cmd, List.of(prepared.blas()));
                },
                () -> {
                    RtAccel.freeBlasScratch(List.of(prepared.blas()));
                    prepared.releaseUpload();
                },
                (build, failure) -> {
                    if (failure != null) {
                        destroyPreparedSection(prepared);
                        finishLodTask(key, null, generation, scale);
                        return;
                    }
                    prepared.releaseBuildInputs();
                    finishLodTask(key, prepared, generation, scale);
                });
    }

    private void finishLodTask(long key, PreparedSection prepared, long generation, int scale) {
        if (prepared != null) {
            lodPrepared.add(new LodPrepared(prepared, generation, scale));
        } else {
            lodFailed.add(new LodCompletion(key, generation));
        }
        finishActiveTask();
    }

    private void finishLodRetrySoon(long key, long generation) {
        lodRetrySoon.add(new LodCompletion(key, generation));
        finishActiveTask();
    }

'''
s = s[:start] + new_submit_finish + s[end:]

start = s.index('    private void publishLodPrepared(RtContext ctx, int pbx, int pby, int pbz) {')
end = s.index('    private void evictLodOutside(', start)
new_publish = '''    private void publishLodPrepared(RtContext ctx, int pbx, int pby, int pbz) {
        long currentGeneration = lodGeneration;

        if (!lodRetrySoon.isEmpty()) {
            List<LodCompletion> retrySoon;
            synchronized (lodRetrySoon) {
                retrySoon = new ArrayList<>(lodRetrySoon);
                lodRetrySoon.clear();
            }
            int currentDeferred = 0;
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
        }

        if (!lodFailed.isEmpty()) {
            List<LodCompletion> failed;
            synchronized (lodFailed) {
                failed = new ArrayList<>(lodFailed);
                lodFailed.clear();
            }
            long retryAfter = RtComposite.frameCounter() + LOD_RETRY_COOLDOWN_FRAMES;
            for (LodCompletion completion : failed) {
                if (completion.generation() != currentGeneration) {
                    continue;
                }
                lodInFlight.remove(completion.key());
                lodRetryAfterFrame.put(completion.key(), retryAfter);
            }
        }

        if (lodPrepared.isEmpty()) {
            return;
        }
        List<LodPrepared> batch;
        synchronized (lodPrepared) {
            batch = new ArrayList<>(lodPrepared);
            lodPrepared.clear();
        }

        boolean changed = false;
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
            if (lodResident.containsKey(ps.key())) {
                destroyPreparedSection(ps);
                continue;
            }

            SectionGeom g = new SectionGeom(ps.key(), ps.uvs(), ps.material(),
                    ps.blas().accel, ps.triBase(), ps.sx(), ps.sy(), ps.sz(), ps.lights());
            g.lodScale = completion.scale();
            g.slot = table.allocateSlot();
            g.instanceIndex = table.instanceList.size();
            table.slots.set(g.slot, g);
            table.write(g);
            table.instanceList.add(table.instanceFor(g, blockX, blockY, blockZ));
            lodResident.put(ps.key(), g);
            changed = true;
        }

        if (!changed) {
            return;
        }
        int count = lodResident.size();
        if (count != lodLoggedPublished && (count == 1 || count == 8 || count == 32 || count == 128)) {
            lodLoggedPublished = count;
            CausticaMod.LOGGER.info("CausticaLOD sections published: {}", count);
        }
        table.flushWrites();
        table.instances = table.instanceList;
    }

    private void pruneExpiredLodRetries(long frame) {
        var iterator = lodRetryAfterFrame.long2LongEntrySet().fastIterator();
        while (iterator.hasNext()) {
            if (iterator.next().getLongValue() <= frame) {
                iterator.remove();
            }
        }
    }

'''
s = s[:start] + new_publish + s[end:]

start = s.index('    private void releaseLod(RtContext ctx) {')
end = s.index('    private record DispatchContext(', start)
new_release = '''    private void releaseLod(RtContext ctx) {
        boolean hadAsync = !lodInFlight.isEmpty() || !lodPrepared.isEmpty()
                || !lodFailed.isEmpty() || !lodRetrySoon.isEmpty();
        boolean hadState = lodActiveDetail != Integer.MIN_VALUE || !lodResident.isEmpty()
                || hadAsync || !lodRetryAfterFrame.isEmpty();

        if (hadState) {
            lodGeneration++;
        }
        lodActiveDetail = Integer.MIN_VALUE;
        lodActiveHeightSections = Integer.MIN_VALUE;
        lodSourceRetryAfterNanos = 0L;

        List<LodPrepared> completed;
        synchronized (lodPrepared) {
            completed = new ArrayList<>(lodPrepared);
            lodPrepared.clear();
        }
        for (LodPrepared completion : completed) {
            destroyPreparedSection(completion.prepared());
        }
        synchronized (lodFailed) {
            lodFailed.clear();
        }
        synchronized (lodRetrySoon) {
            lodRetrySoon.clear();
        }
        lodInFlight.clear();
        lodRetryAfterFrame.clear();

        if (!lodResident.isEmpty()) {
            List<SectionGeom> doomed = new ArrayList<>(lodResident.values());
            lodResident.clear();
            for (SectionGeom g : doomed) {
                if (g.slot >= 0 && g.slot < table.slots.size()) {
                    table.slots.set(g.slot, null);
                    table.freeSlots.add(g.slot);
                }
                g.slot = -1;
                g.instanceIndex = -1;
            }
            table.instanceList.clear();
            for (int i = 0; i < table.slots.size(); i++) {
                SectionGeom g = table.slots.get(i);
                if (g != null) {
                    g.instanceIndex = table.instanceList.size();
                    table.instanceList.add(table.instanceFor(g, blockX, blockY, blockZ));
                }
            }
            table.instances = table.instanceList;
            retire(ctx, ctx.gpuExecutor().latestGraphicsUse(), doomed);
        }

        lodEmptyRegions.set(0);
        lodBoxesSeen.set(0);
        lodLoggedEmptyCount = -1;
        lodLoggedPublished = -1;
        if (!CausticaConfig.Rt.Lod.ENABLED.value()) {
            lodLoggedState = false;
        }
    }

    private record LodPrepared(PreparedSection prepared, long generation, int scale) {
    }

    private record LodCompletion(long key, long generation) {
    }

'''
s = s[:start] + new_release + s[end:]

needle = '''        terrainEpoch++;
        lightGrid.cancelPending();
        drainTasksForClear(ctx);'''
replacement = '''        terrainEpoch++;
        lightGrid.cancelPending();
        releaseLod(ctx);
        drainTasksForClear(ctx);
        releaseLod(ctx);'''
assert needle in s
s = s.replace(needle, replacement, 1)

needle = '''        ctx.gpuExecutor().throwIfFailed();
        terrainEpoch++;

        // Token maps are render-thread ownership'''
replacement = '''        ctx.gpuExecutor().throwIfFailed();
        terrainEpoch++;
        releaseLod(ctx);

        // Token maps are render-thread ownership'''
assert needle in s
s = s.replace(needle, replacement, 1)

p.write_text(s)
