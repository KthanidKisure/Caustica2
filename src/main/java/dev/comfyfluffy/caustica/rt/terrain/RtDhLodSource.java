package dev.comfyfluffy.caustica.rt.terrain;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads Distant Horizons' persistent terrain database and turns it into axis-aligned boxes that can be
 * meshed and put in the ray-tracing acceleration structure.
 *
 * <p>DH remains an optional dependency: all API access is reflected so Caustica still starts when DH is
 * absent or moves its API. DH supplies persistent terrain data; Caustica owns the ray-traced rendering.
 */
public final class RtDhLodSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica/DhLod");
    private static final String DH_MOD_ID = "distanthorizons";

    private enum State { UNRESOLVED, READY, UNAVAILABLE }

    private static State state = State.UNRESOLVED;
    private static Object terrainRepo;
    private static Object worldProxy;
    private static Object softCache;

    private static Method getAllTerrainDataAtDetailLevelAndPos;
    private static Method createSoftCache;
    private static Method getSinglePlayerLevel;
    private static Method getAllLoadedLevelWrappers;
    private static Method worldLoaded;

    private static Field resultPayload;
    private static Field resultSuccess;
    private static Field resultMessage;
    private static Field pointBottomY;
    private static Field pointTopY;
    private static Field pointBlockLight;
    private static Field pointSkyLight;
    private static Field pointBlockState;
    private static Method wrapperGetMcObject;

    private static final AtomicBoolean loggedWorldNotReady = new AtomicBoolean();
    private static final AtomicBoolean loggedQueryFailure = new AtomicBoolean();
    private static final AtomicBoolean loggedQuerySuccess = new AtomicBoolean();
    private static final AtomicBoolean loggedNoLevel = new AtomicBoolean();
    private static final AtomicBoolean loggedMultipleLevels = new AtomicBoolean();

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
     * True only when the reflected API is present AND DH says its world is actually loaded.
     *
     * <p>This worldLoaded gate is important on multiplayer. Caustica can finish its expensive RT/material
     * startup while DH is still switching from its interim/hub world to the real server level. Querying
     * during that window returns "Unable to get terrain data before the world has loaded". Treating that
     * temporary result as an empty LOD section poisoned the whole ring's retry cache.
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
                if (result == null || !resultSuccess.getBoolean(result)) {
                    if (firstFailureMessage == null && result != null) {
                        firstFailureMessage = resultMessage.get(result);
                    }
                    continue;
                }

                hadSuccessfulQuery = true;
                Object grid = resultPayload.get(result);
                List<LodBox> boxes = grid == null
                        ? List.of()
                        : flatten(grid, footprintBlocks, originBlockX, originBlockZ);
                if (!boxes.isEmpty()) {
                    if (loggedQuerySuccess.compareAndSet(false, true)) {
                        LOGGER.info("DH query succeeded at detail {} pos {},{} with {} terrain boxes",
                                detailLevel, posX, posZ, boxes.size());
                    }
                    loggedQueryFailure.set(false);
                    return new FetchResult(boxes, true);
                }
                successfulEmpty = boxes;
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
                    if (point == null) {
                        continue;
                    }
                    int bottomY = pointBottomY.getInt(point);
                    int topY = pointTopY.getInt(point);
                    if (topY <= bottomY) {
                        continue;
                    }
                    Object wrapper = pointBlockState.get(point);
                    if (wrapper == null) {
                        continue;
                    }
                    Object mcObject = wrapperGetMcObject.invoke(wrapper);
                    if (!(mcObject instanceof BlockState blockState) || blockState.isAir()) {
                        continue;
                    }
                    boxes.add(new LodBox(
                            originX + ix * sizeXZ,
                            bottomY,
                            topY,
                            originZ + iz * sizeXZ,
                            sizeXZ,
                            blockState,
                            pointBlockLight.getInt(point),
                            pointSkyLight.getInt(point)));
                }
            }
        }
        return boxes;
    }

    /**
     * Returns every currently loaded candidate wrapper instead of blindly using the first one.
     * Multiplayer getSinglePlayerLevel() throws by design, so the iterable fallback is the normal path.
     */
    private static List<Object> levelWrappers() throws ReflectiveOperationException {
        List<Object> wrappers = new ArrayList<>();
        try {
            Object single = getSinglePlayerLevel.invoke(worldProxy);
            if (single != null) {
                wrappers.add(single);
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Expected on multiplayer.
        }

        Object loaded;
        try {
            loaded = getAllLoadedLevelWrappers.invoke(worldProxy);
        } catch (java.lang.reflect.InvocationTargetException e) {
            return wrappers;
        }
        if (loaded instanceof Iterable<?> iterable) {
            for (Object wrapper : iterable) {
                if (wrapper != null && !containsIdentity(wrappers, wrapper)) {
                    wrappers.add(wrapper);
                }
            }
        }
        return wrappers;
    }

    private static boolean containsIdentity(List<Object> values, Object candidate) {
        for (Object value : values) {
            if (value == candidate) {
                return true;
            }
        }
        return false;
    }

    /** Forgets the resolved API. Call on world unload so a DH reload is picked up. */
    public static synchronized void invalidate() {
        if (state == State.READY) {
            state = State.UNRESOLVED;
            terrainRepo = null;
            worldProxy = null;
            if (softCache instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // DH's cache close does not normally throw.
                }
            }
            softCache = null;
            loggedWorldNotReady.set(false);
            loggedQueryFailure.set(false);
            loggedQuerySuccess.set(false);
            loggedNoLevel.set(false);
            loggedMultipleLevels.set(false);
        }
    }
}
