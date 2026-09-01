package dev.comfyfluffy.caustica.rt.terrain;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads Distant Horizons' persistent terrain database and turns it into axis-aligned boxes that can be
 * meshed and put in the ray-tracing acceleration structure.
 *
 * <h2>Why this exists</h2>
 * Caustica builds every section from {@code level.getChunk(...)}, the client chunk cache. Beyond the
 * server's view distance there is no block data in the process at all — so a Caustica-native LOD can
 * reduce detail on loaded terrain, but it can never show terrain that was never sent. DH already solves
 * that problem: it maintains a durable, generated, streamed world database far past the chunk cache.
 *
 * <h2>What this deliberately does NOT do</h2>
 * It does not touch DH's renderer, intercept its draw calls, or read its GPU buffers. DH's rendering
 * should be switched OFF when this is used. DH is a data source here and Caustica does all drawing, as
 * rays. That avoids the whole class of problems that come from two world renderers coexisting, and it
 * means this survives DH changing its rendering backend.
 *
 * <h2>Why reflection</h2>
 * DH is optional. A hard compile-time dependency would make Caustica refuse to load without it, and
 * would pin a DH version. Everything resolves lazily and degrades to "no LOD" if DH is absent or its
 * public API moved.
 */
public final class RtDhLodSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica/DhLod");
    private static final String DH_MOD_ID = "distanthorizons";

    private enum State { UNRESOLVED, READY, UNAVAILABLE }

    private static State state = State.UNRESOLVED;
    private static Object terrainRepo;
    private static Object worldProxy;
    private static Method getAllTerrainDataAtDetailLevelAndPos;
    private static Method createSoftCache;
    private static Method getSinglePlayerLevel;
    private static Method getAllLoadedLevelWrappers;
    private static Method worldLoaded;
    private static Method levelWrapperGetMinHeight;
    private static Object softCache;
    private static Field resultPayload;
    private static Field resultSuccess;
    private static Field resultMessage;
    private static Field pointBottomY;
    private static Field pointTopY;
    private static Field pointBlockLight;
    private static Field pointSkyLight;
    private static Field pointBlockState;
    private static Method wrapperGetMcObject;

    /*
     * DH 3.2 has a second, more specific readiness requirement than worldProxy.worldLoaded():
     * DhApiTerrainDataRepo resolves the supplied ILevelWrapper back through AbstractDhWorld.getLevel().
     * On proxy-heavy multiplayer servers that lookup can stay broken even though the wrapper came from
     * getAllLoadedLevelWrappers(). The optional adapter below bypasses only that lookup. It obtains the
     * IDhLevel already attached to DH's core wrapper and reads the same FullDataSourceProviderV2 that the
     * public terrain repo uses. All fields/methods remain reflected so DH stays an optional dependency.
     */
    private static boolean directCoreResolved;
    private static boolean directCoreAvailable;
    private static Method coreWrapperGetDhLevel;
    private static Method dhLevelGetFullDataProvider;
    private static Method providerGetAsync;
    private static Method sectionEncode;
    private static int sectionMinimumDetailLevel = 6;
    private static Field dataSourceMapping;
    private static Method dataSourceGetColumnAtRelPos;
    private static Method fullDataToApiPoint;
    private static Method longArrayListSize;
    private static Method longArrayListGetLong;

    private static final AtomicBoolean loggedWorldNotReady = new AtomicBoolean();
    private static final AtomicBoolean loggedQueryFailure = new AtomicBoolean();
    private static final AtomicBoolean loggedQuerySuccess = new AtomicBoolean();
    private static final AtomicBoolean loggedNoLevel = new AtomicBoolean();
    private static final AtomicBoolean loggedMultipleLevels = new AtomicBoolean();
    private static final AtomicBoolean loggedDirectFallback = new AtomicBoolean();
    private static final AtomicBoolean loggedDirectFallbackUnavailable = new AtomicBoolean();
    private static final AtomicBoolean loggedDirectNoLevel = new AtomicBoolean();

    private RtDhLodSource() {
    }

    /** One vertical run of one block state. topY is exclusive, matching DH. */
    public record LodBox(int blockX, int bottomY, int topY, int blockZ, int sizeXZ,
                         BlockState blockState, int blockLight, int skyLight) {
        public int heightBlocks() {
            return topY - bottomY;
        }
    }

    /**
     * Result of one DH terrain request. Successful queries may legitimately contain zero boxes;
     * failed/lifecycle queries are retryable and must never be cached as empty terrain.
     */
    public record FetchResult(List<LodBox> boxes, boolean querySucceeded) {
        public FetchResult {
            boxes = List.copyOf(boxes);
        }
    }

    /**
     * True only when the reflected API is present AND DH says a world object exists.
     *
     * <p>DH 3.2's worldLoaded() is only a coarse lifecycle gate; fetchRegion performs the stricter
     * per-level check and can fall back to DH's directly attached IDhLevel when the public repo's
     * AbstractDhWorld.getLevel(wrapper) lookup is stale.</p>
     */
    public static synchronized boolean available() {
        resolve();
        if (state != State.READY) {
            return false;
        }
        if (!worldReady()) {
            if (loggedWorldNotReady.compareAndSet(false, true)) {
                LOGGER.info("Distant Horizons API resolved, but its world is not query-ready yet; delaying LOD streaming");
            }
            return false;
        }
        loggedWorldNotReady.set(false);
        return true;
    }

    private static boolean worldReady() {
        if (state != State.READY || worldProxy == null || worldLoaded == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(worldLoaded.invoke(worldProxy));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("Unable to read Distant Horizons worldLoaded state: {}", e.toString());
            return false;
        }
    }

    private static void resolve() {
        if (state != State.UNRESOLVED) {
            return;
        }
        if (!FabricLoader.getInstance().isModLoaded(DH_MOD_ID)) {
            state = State.UNAVAILABLE;
            return;
        }
        try {
            Class<?> dhApi = Class.forName("com.seibel.distanthorizons.api.DhApi");
            Class<?> delayed = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
            terrainRepo = delayed.getField("terrainRepo").get(null);
            worldProxy = delayed.getField("worldProxy").get(null);
            if (terrainRepo == null || worldProxy == null) {
                terrainRepo = null;
                worldProxy = null;
                return;
            }

            Class<?> repoInterface = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo");
            Class<?> levelWrapper = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper");
            Class<?> cacheInterface = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache");
            getAllTerrainDataAtDetailLevelAndPos = repoInterface.getMethod(
                    "getAllTerrainDataAtDetailLevelAndPos",
                    levelWrapper, byte.class, int.class, int.class, cacheInterface);
            createSoftCache = repoInterface.getMethod("createSoftCache");
            levelWrapperGetMinHeight = levelWrapper.getMethod("getMinHeight");

            Class<?> worldProxyInterface = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy");
            getSinglePlayerLevel = worldProxyInterface.getMethod("getSinglePlayerLevel");
            getAllLoadedLevelWrappers = worldProxyInterface.getMethod("getAllLoadedLevelWrappers");
            worldLoaded = worldProxyInterface.getMethod("worldLoaded");

            Class<?> resultClass = Class.forName("com.seibel.distanthorizons.api.objects.DhApiResult");
            resultSuccess = resultClass.getField("success");
            resultMessage = resultClass.getField("message");
            resultPayload = resultClass.getField("payload");

            Class<?> pointClass = Class.forName(
                    "com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint");
            pointBottomY = pointClass.getField("bottomYBlockPos");
            pointTopY = pointClass.getField("topYBlockPos");
            pointBlockLight = pointClass.getField("blockLightLevel");
            pointSkyLight = pointClass.getField("skyLightLevel");
            pointBlockState = pointClass.getField("blockStateWrapper");

            Class<?> unsafeWrapper = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.IDhApiUnsafeWrapper");
            wrapperGetMcObject = unsafeWrapper.getMethod("getWrappedMcObject");

            softCache = createSoftCache.invoke(terrainRepo);
            resolveDirectCore();
            state = State.READY;
            LOGGER.info("Distant Horizons LOD source resolved ({})", dhApi.getName());
        } catch (ReflectiveOperationException | RuntimeException e) {
            state = State.UNAVAILABLE;
            terrainRepo = null;
            worldProxy = null;
            softCache = null;
            LOGGER.warn("Distant Horizons is installed but its API did not resolve; distant LOD terrain disabled. {}",
                    e.toString());
        }
    }

    /**
     * Best-effort adapter for the DH 3.2 core data path. Failure here never disables the public API path.
     */
    private static void resolveDirectCore() {
        if (directCoreResolved) {
            return;
        }
        directCoreResolved = true;
        try {
            Class<?> coreLevelWrapper = Class.forName(
                    "com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper");
            Class<?> dhLevel = Class.forName("com.seibel.distanthorizons.core.level.IDhLevel");
            Class<?> provider = Class.forName(
                    "com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2");
            Class<?> sectionPos = Class.forName("com.seibel.distanthorizons.core.pos.DhSectionPos");
            Class<?> dataSource = Class.forName(
                    "com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2");
            Class<?> idMap = Class.forName(
                    "com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap");
            Class<?> pointUtil = Class.forName(
                    "com.seibel.distanthorizons.core.util.DhApiTerrainDataPointUtil");
            Class<?> longArrayList = Class.forName("it.unimi.dsi.fastutil.longs.LongArrayList");

            coreWrapperGetDhLevel = coreLevelWrapper.getMethod("getDhLevel");
            dhLevelGetFullDataProvider = dhLevel.getMethod("getFullDataProvider");
            providerGetAsync = provider.getMethod("getAsync", long.class);
            sectionEncode = sectionPos.getMethod("encode", byte.class, int.class, int.class);
            sectionMinimumDetailLevel = sectionPos.getField("SECTION_MINIMUM_DETAIL_LEVEL").getByte(null);
            dataSourceMapping = dataSource.getField("mapping");
            dataSourceGetColumnAtRelPos = dataSource.getMethod("getColumnAtRelPos", int.class, int.class);
            fullDataToApiPoint = pointUtil.getMethod(
                    "createApiDatapoint", int.class, idMap, byte.class, long.class);
            longArrayListSize = longArrayList.getMethod("size");
            longArrayListGetLong = longArrayList.getMethod("getLong", int.class);

            directCoreAvailable = true;
            LOGGER.info("Distant Horizons direct FullDataProvider fallback resolved");
        } catch (ReflectiveOperationException | RuntimeException e) {
            directCoreAvailable = false;
            LOGGER.debug("Distant Horizons direct FullDataProvider fallback unavailable: {}", e.toString());
        }
    }

    /**
     * Fetches one square footprint and flattens DH's run-length terrain columns into boxes.
     * DH's API detailLevel is the size of the queried AREA: 0=1 block, 4=16 blocks, 6=64 blocks, etc.
     */
    public static FetchResult fetchArea(int footprintBlocks, int originBlockX, int originBlockZ) {
        byte detailLevel = (byte) Integer.numberOfTrailingZeros(Math.max(footprintBlocks, 1));
        int posX = Math.floorDiv(originBlockX, Math.max(footprintBlocks, 1));
        int posZ = Math.floorDiv(originBlockZ, Math.max(footprintBlocks, 1));
        return fetchRegion(detailLevel, posX, posZ, footprintBlocks, originBlockX, originBlockZ);
    }

    private static FetchResult fetchRegion(byte detailLevel, int posX, int posZ,
                                            int footprintBlocks, int originBlockX, int originBlockZ) {
        synchronized (RtDhLodSource.class) {
            resolve();
            if (state != State.READY || !worldReady()) {
                return new FetchResult(List.of(), false);
            }
        }

        try {
            List<Object> wrappers = levelWrappers();
            if (wrappers.isEmpty()) {
                if (loggedNoLevel.compareAndSet(false, true)) {
                    LOGGER.info("DH reports a loaded world but exposes no loaded level wrapper yet; delaying LOD queries");
                }
                return new FetchResult(List.of(), false);
            }
            loggedNoLevel.set(false);
            if (wrappers.size() > 1 && loggedMultipleLevels.compareAndSet(false, true)) {
                LOGGER.info("DH exposes {} loaded level wrappers; Caustica will query all candidates until one answers",
                        wrappers.size());
            }

            Object firstFailureMessage = null;
            boolean hadSuccessfulQuery = false;
            List<LodBox> successfulEmpty = List.of();

            for (Object levelWrapper : wrappers) {
                Object result = getAllTerrainDataAtDetailLevelAndPos.invoke(
                        terrainRepo, levelWrapper, detailLevel, posX, posZ, softCache);
                if (result != null && resultSuccess.getBoolean(result)) {
                    hadSuccessfulQuery = true;
                    Object grid = resultPayload.get(result);
                    List<LodBox> boxes = grid == null
                            ? List.of()
                            : flatten(grid, footprintBlocks, originBlockX, originBlockZ);
                    if (!boxes.isEmpty()) {
                        logSuccess(detailLevel, posX, posZ, boxes.size(), false);
                        return new FetchResult(boxes, true);
                    }
                    successfulEmpty = boxes;
                    continue;
                }

                if (firstFailureMessage == null && result != null) {
                    firstFailureMessage = resultMessage.get(result);
                }

                // DH 3.2 can expose a loaded wrapper that its own terrainRepo cannot map back to the
                // current world's level. Use the IDhLevel already attached to that wrapper instead.
                FetchResult direct = fetchDirectCore(levelWrapper, footprintBlocks, originBlockX, originBlockZ);
                if (direct.querySucceeded()) {
                    hadSuccessfulQuery = true;
                    if (!direct.boxes().isEmpty()) {
                        logSuccess(detailLevel, posX, posZ, direct.boxes().size(), true);
                        return direct;
                    }
                    successfulEmpty = direct.boxes();
                }
            }

            if (hadSuccessfulQuery) {
                if (loggedQuerySuccess.compareAndSet(false, true)) {
                    LOGGER.info("DH query succeeded at detail {} pos {},{} but returned no solid terrain",
                            detailLevel, posX, posZ);
                }
                loggedQueryFailure.set(false);
                return new FetchResult(successfulEmpty, true);
            }

            if (loggedQueryFailure.compareAndSet(false, true)) {
                LOGGER.info("DH query failed at detail {} pos {},{} across {} loaded level wrapper(s): {}",
                        detailLevel, posX, posZ, wrappers.size(),
                        firstFailureMessage == null ? "no successful result" : firstFailureMessage);
            }
            return new FetchResult(List.of(), false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("DH region fetch failed at detail {} ({}, {}): {}",
                    detailLevel, posX, posZ, e.toString());
            return new FetchResult(List.of(), false);
        }
    }

    private static void logSuccess(byte detailLevel, int posX, int posZ, int boxCount, boolean direct) {
        if (loggedQuerySuccess.compareAndSet(false, true)) {
            LOGGER.info("DH query succeeded at detail {} pos {},{} with {} terrain boxes{}",
                    detailLevel, posX, posZ, boxCount,
                    direct ? " via direct FullDataProvider fallback" : "");
        }
        loggedQueryFailure.set(false);
    }

    /**
     * Bypasses DhApiTerrainDataRepo's world.getLevel(wrapper) lookup, but still reads DH's own persistent
     * FullDataSourceProviderV2 and uses DH's own packed-data-to-API-point converter. This keeps the exact
     * block-state/light semantics of the public terrain API while avoiding the broken wrapper re-lookup.
     */
    private static FetchResult fetchDirectCore(Object levelWrapper,
                                                int footprintBlocks,
                                                int originBlockX,
                                                int originBlockZ) {
        if (!directCoreAvailable) {
            if (loggedDirectFallbackUnavailable.compareAndSet(false, true)) {
                LOGGER.info("DH public terrain lookup failed and direct FullDataProvider fallback is unavailable");
            }
            return new FetchResult(List.of(), false);
        }

        Map<Long, Object> dataSources = new HashMap<>();
        try {
            Object dhLevel = coreWrapperGetDhLevel.invoke(levelWrapper);
            if (dhLevel == null) {
                if (loggedDirectNoLevel.compareAndSet(false, true)) {
                    LOGGER.info("DH loaded wrapper has no attached IDhLevel; direct terrain fallback will retry");
                }
                return new FetchResult(List.of(), false);
            }

            Object provider = dhLevelGetFullDataProvider.invoke(dhLevel);
            if (provider == null) {
                return new FetchResult(List.of(), false);
            }

            if (loggedDirectFallback.compareAndSet(false, true)) {
                LOGGER.info("DH public terrain lookup rejected a loaded level; using direct FullDataProvider fallback");
            }

            int minHeight = ((Number) levelWrapperGetMinHeight.invoke(levelWrapper)).intValue();
            int sourceWidth = 1 << sectionMinimumDetailLevel;
            List<LodBox> boxes = new ArrayList<>();

            for (int ix = 0; ix < footprintBlocks; ix++) {
                int blockX = originBlockX + ix;
                int sectionX = Math.floorDiv(blockX, sourceWidth);
                int relX = Math.floorMod(blockX, sourceWidth);

                for (int iz = 0; iz < footprintBlocks; iz++) {
                    int blockZ = originBlockZ + iz;
                    int sectionZ = Math.floorDiv(blockZ, sourceWidth);
                    int relZ = Math.floorMod(blockZ, sourceWidth);

                    long sectionPos = ((Number) sectionEncode.invoke(
                            null, sectionMinimumDetailLevel, sectionX, sectionZ)).longValue();

                    Object dataSource = dataSources.get(sectionPos);
                    if (dataSource == null && !dataSources.containsKey(sectionPos)) {
                        Object future = providerGetAsync.invoke(provider, sectionPos);
                        if (!(future instanceof CompletableFuture<?> completable)) {
                            return new FetchResult(List.of(), false);
                        }
                        dataSource = completable.get();
                        if (dataSource == null) {
                            return new FetchResult(List.of(), false);
                        }
                        dataSources.put(sectionPos, dataSource);
                    }

                    if (dataSource == null) {
                        continue;
                    }
                    Object mapping = dataSourceMapping.get(dataSource);
                    Object column = dataSourceGetColumnAtRelPos.invoke(dataSource, relX, relZ);
                    if (mapping == null || column == null) {
                        continue;
                    }

                    int count = ((Number) longArrayListSize.invoke(column)).intValue();
                    for (int i = 0; i < count; i++) {
                        long packed = ((Number) longArrayListGetLong.invoke(column, i)).longValue();
                        Object point = fullDataToApiPoint.invoke(
                                null, minHeight, mapping, (byte) 0, packed);
                        appendPoint(boxes, point, blockX, blockZ, 1);
                    }
                }
            }

            return new FetchResult(boxes, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FetchResult(List.of(), false);
        } catch (ReflectiveOperationException | RuntimeException | java.util.concurrent.ExecutionException e) {
            LOGGER.debug("DH direct FullDataProvider query failed at ({}, {}), size {}: {}",
                    originBlockX, originBlockZ, footprintBlocks, e.toString());
            return new FetchResult(List.of(), false);
        } finally {
            for (Object dataSource : dataSources.values()) {
                if (dataSource instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Exception ignored) {
                        // FullDataSourceV2 releases pooled arrays here; failure should not break rendering.
                    }
                }
            }
        }
    }

    /** Walks the [x][z][column] array returned by DH. */
    private static List<LodBox> flatten(Object grid, int footprintBlocks, int originX, int originZ)
            throws ReflectiveOperationException {
        List<LodBox> boxes = new ArrayList<>();
        int lenX = Array.getLength(grid);
        if (lenX <= 0) {
            return boxes;
        }
        int sizeXZ = Math.max(footprintBlocks / lenX, 1);
        for (int ix = 0; ix < lenX; ix++) {
            Object column = Array.get(grid, ix);
            if (column == null) {
                continue;
            }
            int lenZ = Array.getLength(column);
            for (int iz = 0; iz < lenZ; iz++) {
                Object entries = Array.get(column, iz);
                if (entries == null) {
                    continue;
                }
                int lenY = Array.getLength(entries);
                for (int iy = 0; iy < lenY; iy++) {
                    Object point = Array.get(entries, iy);
                    if (point != null) {
                        appendPoint(boxes, point,
                                originX + ix * sizeXZ,
                                originZ + iz * sizeXZ,
                                sizeXZ);
                    }
                }
            }
        }
        return boxes;
    }

    private static void appendPoint(List<LodBox> boxes, Object point,
                                    int blockX, int blockZ, int sizeXZ)
            throws ReflectiveOperationException {
        int bottomY = pointBottomY.getInt(point);
        int topY = pointTopY.getInt(point);
        if (topY <= bottomY) {
            return;
        }
        Object wrapper = pointBlockState.get(point);
        if (wrapper == null) {
            return;
        }
        Object mcObject = wrapperGetMcObject.invoke(wrapper);
        if (!(mcObject instanceof BlockState blockState) || blockState.isAir()) {
            return;
        }
        boxes.add(new LodBox(
                blockX,
                bottomY,
                topY,
                blockZ,
                sizeXZ,
                blockState,
                pointBlockLight.getInt(point),
                pointSkyLight.getInt(point)));
    }

    private static List<Object> levelWrappers() throws ReflectiveOperationException {
        List<Object> wrappers = new ArrayList<>();

        // Prefer wrappers owned by the current DH world. On proxy servers the Minecraft client's
        // convenience wrapper can briefly point at a level that DH has not attached to the active world.
        Object loaded = getAllLoadedLevelWrappers.invoke(worldProxy);
        if (loaded instanceof Iterable<?> iterable) {
            for (Object wrapper : iterable) {
                if (wrapper != null && !containsIdentity(wrappers, wrapper)) {
                    wrappers.add(wrapper);
                }
            }
        }

        try {
            Object single = getSinglePlayerLevel.invoke(worldProxy);
            if (single != null && !containsIdentity(wrappers, single)) {
                wrappers.add(single);
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Some DH implementations can reject this outside true single-player; loaded wrappers suffice.
        }
        return wrappers;
    }

    private static boolean containsIdentity(List<Object> values, Object needle) {
        for (Object value : values) {
            if (value == needle) {
                return true;
            }
        }
        return false;
    }

    /** Forgets the reflected API on world unload so DH can publish a fresh world/cache. */
    public static synchronized void invalidate() {
        if (state == State.READY) {
            state = State.UNRESOLVED;
            terrainRepo = null;
            worldProxy = null;
            if (softCache instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // DH's cache close is not expected to fail.
                }
            }
            softCache = null;
        }
        directCoreResolved = false;
        directCoreAvailable = false;
        coreWrapperGetDhLevel = null;
        dhLevelGetFullDataProvider = null;
        providerGetAsync = null;
        sectionEncode = null;
        dataSourceMapping = null;
        dataSourceGetColumnAtRelPos = null;
        fullDataToApiPoint = null;
        longArrayListSize = null;
        longArrayListGetLong = null;

        loggedWorldNotReady.set(false);
        loggedQueryFailure.set(false);
        loggedQuerySuccess.set(false);
        loggedNoLevel.set(false);
        loggedMultipleLevels.set(false);
        loggedDirectFallback.set(false);
        loggedDirectFallbackUnavailable.set(false);
        loggedDirectNoLevel.set(false);
    }
}
