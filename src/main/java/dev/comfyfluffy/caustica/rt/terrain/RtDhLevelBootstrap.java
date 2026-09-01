package dev.comfyfluffy.caustica.rt.terrain;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures Distant Horizons has created its per-level database object before Caustica starts asking for
 * persistent LOD terrain.
 *
 * <p>DH 3.2 exposes {@code worldLoaded()} as soon as its {@code DhWorld} exists, but the actual
 * {@code IDhLevel} can still be absent. This happens on proxy-heavy multiplayer joins where Minecraft's
 * client level changes while DH is deciding whether the level is locally keyed or server-keyed. The
 * public terrain repo cannot return anything until {@code AbstractDhWorld.getOrLoadLevel(wrapper)} has
 * produced that {@code IDhLevel}.</p>
 *
 * <p>This adapter is reflection-only so DH remains optional. It runs from Caustica's client-tick hook,
 * i.e. on Minecraft's render/client thread, which is also where DH normally performs level creation and
 * fires its level-load events.</p>
 */
public final class RtDhLevelBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica/DhBootstrap");
    private static final String DH_MOD_ID = "distanthorizons";
    private static final long RETRY_NANOS = 1_000_000_000L;
    private static final long WAIT_LOG_NANOS = 10_000_000_000L;

    private enum State { UNRESOLVED, READY, UNAVAILABLE }

    private static State state = State.UNRESOLVED;
    private static Object worldProxy;
    private static Method worldLoaded;
    private static Method getSinglePlayerLevel;
    private static Method sharedGetAbstractDhWorld;
    private static Method worldGetLevel;
    private static Method worldGetOrLoadLevel;

    private static Object lastWorld;
    private static Object lastWrapper;
    private static long nextRetryNanos;
    private static long nextWaitLogNanos;
    private static final AtomicBoolean loggedReady = new AtomicBoolean();

    private RtDhLevelBootstrap() {
    }

    /** Called from START_CLIENT_TICK before RT terrain dispatch. */
    public static synchronized void tick() {
        resolve();
        if (state != State.READY) {
            return;
        }

        long now = System.nanoTime();
        if (now < nextRetryNanos) {
            return;
        }
        nextRetryNanos = now + RETRY_NANOS;

        try {
            if (!Boolean.TRUE.equals(worldLoaded.invoke(worldProxy))) {
                resetWorldTracking();
                return;
            }

            Object world = sharedGetAbstractDhWorld.invoke(null);
            Object wrapper = getSinglePlayerLevel.invoke(worldProxy);
            if (world == null || wrapper == null) {
                return;
            }

            if (world != lastWorld || wrapper != lastWrapper) {
                lastWorld = world;
                lastWrapper = wrapper;
                loggedReady.set(false);
                nextWaitLogNanos = 0L;
            }

            // If DH already owns the current wrapper, do not disturb its lifecycle.
            Object loaded = worldGetLevel.invoke(world, wrapper);
            if (loaded != null) {
                if (loggedReady.compareAndSet(false, true)) {
                    LOGGER.info("Distant Horizons current level is ready for Caustica LOD");
                }
                return;
            }

            // This is DH's own level-creation path. It handles its one-second first-load delay,
            // server-keyed levels, save-directory selection, database creation and level-load events.
            Object created = worldGetOrLoadLevel.invoke(world, wrapper);
            if (created != null) {
                if (loggedReady.compareAndSet(false, true)) {
                    LOGGER.info("Initialized Distant Horizons current level for Caustica LOD");
                }
                return;
            }

            if (now >= nextWaitLogNanos) {
                nextWaitLogNanos = now + WAIT_LOG_NANOS;
                LOGGER.info("Waiting for Distant Horizons to permit/create the current level before LOD streaming");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (now >= nextWaitLogNanos) {
                nextWaitLogNanos = now + WAIT_LOG_NANOS;
                LOGGER.info("Distant Horizons current-level bootstrap is not ready yet: {}", e.toString());
            }
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
            Class<?> delayed = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
            worldProxy = delayed.getField("worldProxy").get(null);
            if (worldProxy == null) {
                // DH publishes Delayed fields during its own initialization. Retry on a later tick.
                return;
            }

            Class<?> worldProxyInterface = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy");
            worldLoaded = worldProxyInterface.getMethod("worldLoaded");
            getSinglePlayerLevel = worldProxyInterface.getMethod("getSinglePlayerLevel");

            Class<?> sharedApi = Class.forName("com.seibel.distanthorizons.core.api.internal.SharedApi");
            sharedGetAbstractDhWorld = sharedApi.getMethod("getAbstractDhWorld");

            Class<?> coreLevelWrapper = Class.forName(
                    "com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper");
            Class<?> dhWorld = Class.forName("com.seibel.distanthorizons.core.world.IDhWorld");
            worldGetLevel = dhWorld.getMethod("getLevel", coreLevelWrapper);
            worldGetOrLoadLevel = dhWorld.getMethod("getOrLoadLevel", coreLevelWrapper);

            state = State.READY;
            LOGGER.info("Distant Horizons level bootstrap resolved");
        } catch (ReflectiveOperationException | RuntimeException e) {
            state = State.UNAVAILABLE;
            worldProxy = null;
            LOGGER.warn("Distant Horizons level bootstrap could not resolve; Caustica will use normal DH lifecycle only. {}",
                    e.toString());
        }
    }

    private static void resetWorldTracking() {
        lastWorld = null;
        lastWrapper = null;
        loggedReady.set(false);
        nextWaitLogNanos = 0L;
    }

    public static synchronized void invalidate() {
        resetWorldTracking();
        nextRetryNanos = 0L;
    }
}
