package dev.comfyfluffy.caustica.rt.terrain;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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
 * means this survives DH changing its rendering backend — which it has done twice recently.
 *
 * <h2>Why reflection</h2>
 * DH is optional. A hard compile-time dependency would make Caustica refuse to load without it, and
 * would pin a DH version. Everything here resolves lazily and degrades to "no LOD" if DH is absent, is
 * a version whose API moved, or has not finished loading a world. The API surface used is small and
 * documented — {@code DhApi.Delayed.terrainRepo}, {@code DhApi.Delayed.worldProxy}, and
 * {@code IDhApiTerrainDataRepo.getAllTerrainDataAtDetailLevelAndPos} — and it is a versioned public
 * API rather than internals, so it is a reasonable thing to bind to loosely.
 *
 * <h2>Data shape</h2>
 * DH returns {@code DhApiTerrainDataPoint[][][]} indexed [x][z][column entry]. Each entry is already a
 * vertical RUN — a {@code bottomYBlockPos}..{@code topYBlockPos} span of one block state, with baked
 * block and sky light. That is run-length encoding done for us: a 200-block stone column is one entry,
 * not 200. Emitting one box per entry is therefore already a large win before any greedy merging, and
 * it is why this does not need a general voxel mesher.
 *
 * <p>Crucially the entry carries a {@code blockStateWrapper} whose {@code getWrappedMcObject()} is a real
 * Minecraft {@link BlockState}. That means distant terrain can resolve to the SAME material table entries
 * as near terrain — roughness, metalness, emission, LabPBR maps — rather than the baked vertex colour a
 * renderer-interception approach would give you. Distant lava glowing correctly is downstream of this
 * one method call.
 */
public final class RtDhLodSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica/DhLod");
    private static final String DH_MOD_ID = "distanthorizons";

    /** Resolution states. Resolved once; a failure is remembered so a broken API is not retried per frame. */
    private enum State { UNRESOLVED, READY, UNAVAILABLE }

    private static State state = State.UNRESOLVED;
    private static Object terrainRepo;
    private static Object worldProxy;
    private static Method getAllTerrainDataAtDetailLevelAndPos;
    private static Method getSinglePlayerLevel;
    private static Field resultPayload;
    private static Field resultSuccess;
    private static Field resultMessage;
    private static Field pointBottomY;
    private static Field pointTopY;
    private static Field pointBlockLight;
    private static Field pointSkyLight;
    private static Field pointBlockState;
    private static Method wrapperGetMcObject;

    private RtDhLodSource() {
    }

    /**
     * One vertical run of a single block state, in world coordinates. {@code topY} is exclusive, matching
     * DH's convention, so an empty run is representable and callers do not need an off-by-one guard.
     */
    public record LodBox(int blockX, int bottomY, int topY, int blockZ, int sizeXZ,
                         BlockState blockState, int blockLight, int skyLight) {
        public int heightBlocks() {
            return topY - bottomY;
        }
    }

    public static synchronized boolean available() {
        resolve();
        return state == State.READY;
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
                // DH is present but has not finished initialising. Stay UNRESOLVED so this retries:
                // the fields are populated after world load, not at mod init.
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

            // getSinglePlayerLevel, not a "current level" accessor: DH has no notion of one. On a
            // multiplayer server this throws IllegalStateException and distant terrain is simply
            // absent — correct behaviour, since picking an arbitrary entry from
            // getAllLoadedLevelWrappers() would happily hand back the Nether's terrain while you
            // stand in the Overworld.
            Class<?> worldProxyInterface = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy");
            getSinglePlayerLevel = worldProxyInterface.getMethod("getSinglePlayerLevel");

            // DhApiResult exposes success/message/payload as public FIELDS, not getters.
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

            state = State.READY;
            LOGGER.info("Distant Horizons LOD source resolved ({})", dhApi.getName());
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A moved API is expected across DH majors and is not an error worth spamming: log once and
            // run without distant terrain, exactly as if DH were not installed.
            state = State.UNAVAILABLE;
            terrainRepo = null;
            worldProxy = null;
            LOGGER.warn("Distant Horizons is installed but its API did not resolve; "
                    + "distant LOD terrain disabled. {}", e.toString());
        }
    }

    /**
     * Fetches one DH region at {@code detailLevel} and flattens it into boxes.
     *
     * <p>{@code detailLevel} is DH's own exponent: 0 is one data point per block, 1 is one per 2x2
     * column, and so on. The horizontal footprint of each returned box is {@code 1 << detailLevel}
     * blocks square, which is why {@link LodBox#sizeXZ} is carried rather than assumed — a caller
     * meshing these must scale the quad, not emit a unit cube.
     *
     * <p><b>Call this off the render thread.</b> DH may hit disk. It is a database query, not a
     * memory read.
     *
     * @return the boxes, or an empty list if DH is unavailable or has nothing at this position.
     */
    public static List<LodBox> fetchRegion(byte detailLevel, int posX, int posZ) {
        synchronized (RtDhLodSource.class) {
            resolve();
            if (state != State.READY) {
                return List.of();
            }
        }
        try {
            Object levelWrapper = getSinglePlayerLevel.invoke(worldProxy);
            if (levelWrapper == null) {
                return List.of(); // between worlds
            }
            Object result = getAllTerrainDataAtDetailLevelAndPos.invoke(
                    terrainRepo, levelWrapper, detailLevel, posX, posZ, null);
            if (result == null || !resultSuccess.getBoolean(result)) {
                // A failed query is routine — the region may simply not be generated yet — so this is
                // debug, not a warning, and the message is DH's own explanation.
                LOGGER.debug("DH region {} {} unavailable at detail {}: {}", posX, posZ, detailLevel,
                        result == null ? "null result" : resultMessage.get(result));
                return List.of();
            }
            Object grid = resultPayload.get(result);
            if (grid == null) {
                return List.of();
            }
            return flatten(grid, detailLevel, posX, posZ);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("DH region fetch failed at detail {} ({}, {}): {}",
                    detailLevel, posX, posZ, e.toString());
            return List.of();
        }
    }

    /**
     * Walks the [x][z][column] array. Reflection on the array rather than a cast because the element
     * type lives in DH's classloader-visible API and casting it here would reintroduce the hard
     * dependency this class exists to avoid.
     */
    private static List<LodBox> flatten(Object grid, byte detailLevel, int posX, int posZ)
            throws ReflectiveOperationException {
        int sizeXZ = 1 << detailLevel;
        // DH positions are in detail-level units; the world origin of this region is the position
        // scaled by the level's block footprint.
        int originX = posX * sizeXZ;
        int originZ = posZ * sizeXZ;

        List<LodBox> boxes = new ArrayList<>();
        int lenX = Array.getLength(grid);
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
                        // Air runs are the majority of every column and carry no geometry. Skipping
                        // them here rather than in the mesher keeps the returned list proportional to
                        // the terrain rather than to the world height.
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

    /** Forgets the resolved API. Call on world unload so a DH reload is picked up. */
    public static synchronized void invalidate() {
        if (state == State.READY) {
            state = State.UNRESOLVED;
            terrainRepo = null;
            worldProxy = null;
        }
    }
}
