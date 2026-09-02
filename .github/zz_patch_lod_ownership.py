from pathlib import Path

p = Path('src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrain.java')
s = p.read_text()
# Add bounded diagnostics.
needle = '''    private final java.util.concurrent.atomic.AtomicInteger lodBoxesSeen =
            new java.util.concurrent.atomic.AtomicInteger();
'''
insert = needle + '''    /** Bounded worker/GPU failure diagnostics: enough to identify a real bug without log spam. */
    private final java.util.concurrent.atomic.AtomicInteger lodFailureLogs =
            new java.util.concurrent.atomic.AtomicInteger();
'''
assert needle in s
s = s.replace(needle, insert, 1)

# Harden post-prepare submit ownership.
old = '''                    if (generation != lodGeneration) {
                        destroyPreparedSection(ps);
                        finishLodStale();
                        return;
                    }
                    submitLodBuild(dispatch.ctx(), key, ps, scale, generation);
                } catch (Throwable t) {
                    finishLodTask(key, null, generation, scale);
                }
'''
new = '''                    if (generation != lodGeneration) {
                        destroyLodPreparedSafely(ps, null);
                        finishLodStale();
                        return;
                    }
                    try {
                        submitLodBuild(dispatch.ctx(), key, ps, scale, generation);
                    } catch (Throwable submitFailure) {
                        destroyLodPreparedSafely(ps, submitFailure);
                        logLodFailure("GPU submit", key, generation, submitFailure);
                        finishLodTask(key, null, generation, scale);
                    }
                } catch (Throwable t) {
                    logLodFailure("worker", key, generation, t);
                    finishLodTask(key, null, generation, scale);
                }
'''
assert old in s, 'worker ownership block not found'
s = s.replace(old, new, 1)

# Replace GPU callback with exception-safe ownership accounting.
old = '''                (build, failure) -> {
                    if (failure != null) {
                        destroyPreparedSection(prepared);
                        finishLodTask(key, null, generation, scale);
                        return;
                    }
                    prepared.releaseBuildInputs();
                    finishLodTask(key, prepared, generation, scale);
                });
'''
new = '''                (build, failure) -> {
                    if (failure != null) {
                        destroyLodPreparedSafely(prepared, failure);
                        if (generation != lodGeneration) {
                            finishLodStale();
                        } else {
                            if (!(failure instanceof java.util.concurrent.CancellationException)) {
                                logLodFailure("GPU build", key, generation, failure);
                            }
                            finishLodTask(key, null, generation, scale);
                        }
                        return;
                    }
                    try {
                        prepared.releaseBuildInputs();
                    } catch (Throwable releaseFailure) {
                        destroyLodPreparedSafely(prepared, releaseFailure);
                        logLodFailure("GPU build-input release", key, generation, releaseFailure);
                        if (generation != lodGeneration) {
                            finishLodStale();
                        } else {
                            finishLodTask(key, null, generation, scale);
                        }
                        throw releaseFailure;
                    }
                    if (generation != lodGeneration) {
                        destroyLodPreparedSafely(prepared, null);
                        finishLodStale();
                        return;
                    }
                    finishLodTask(key, prepared, generation, scale);
                });
'''
assert old in s, 'GPU completion block not found'
s = s.replace(old, new, 1)

# Add cleanup/log helpers after stale completion helper.
needle = '''    private void finishLodStale() {
        finishActiveTask();
    }

'''
insert = needle + '''    /** Preserve the original failure while making best-effort ownership cleanup non-fatal to task accounting. */
    private void destroyLodPreparedSafely(PreparedSection prepared, Throwable ownerFailure) {
        try {
            destroyPreparedSection(prepared);
        } catch (Throwable cleanupFailure) {
            if (ownerFailure != null && cleanupFailure != ownerFailure) {
                ownerFailure.addSuppressed(cleanupFailure);
            } else {
                logLodFailure("prepared cleanup", prepared.key(), lodGeneration, cleanupFailure);
            }
        }
    }

    private void logLodFailure(String phase, long key, long generation, Throwable failure) {
        if (generation != lodGeneration) {
            return; // expected cancellation from a released world/config generation
        }
        int index = lodFailureLogs.incrementAndGet();
        if (index <= 8) {
            CausticaMod.LOGGER.warn("CausticaLOD {} failed for key 0x{} (failure {}/8)",
                    phase, Long.toUnsignedString(key, 16), index, failure);
        }
    }

'''
assert needle in s, 'stale helper block not found'
s = s.replace(needle, insert, 1)

# Reset bounded diagnostic allowance when all LOD state is released for a new session/config.
old = '''        lodEmptyRegions.set(0);
        lodBoxesSeen.set(0);
        lodLoggedEmptyCount = -1;
'''
new = '''        lodEmptyRegions.set(0);
        lodBoxesSeen.set(0);
        lodFailureLogs.set(0);
        lodLoggedEmptyCount = -1;
'''
assert old in s
s = s.replace(old, new, 1)
p.write_text(s)
