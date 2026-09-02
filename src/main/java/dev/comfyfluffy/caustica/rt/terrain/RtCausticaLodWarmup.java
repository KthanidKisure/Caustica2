package dev.comfyfluffy.caustica.rt.terrain;

import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Prevents transient proxy/HUB worlds from seeding the persistent CausticaLOD cache.
 *
 * <p>Wynncraft can replace the client level several times while connecting. Capturing immediately
 * would make short-lived HUB/interim geometry persistent under the same server/dimension identity as
 * the final gameplay world. A level therefore has to remain the same object for a short stability
 * window before native LOD capture starts. Full-resolution RT terrain is unaffected during warm-up.</p>
 */
public final class RtCausticaLodWarmup {
    private static final int STABLE_TICKS_REQUIRED = 100; // five seconds at 20 TPS

    private static ClientLevel lastLevel;
    private static int stableTicks;

    private RtCausticaLodWarmup() {
    }

    public static boolean ready(ClientLevel level) {
        if (level == null) {
            reset();
            return false;
        }
        if (level != lastLevel) {
            lastLevel = level;
            stableTicks = 0;
            return false;
        }
        if (stableTicks < STABLE_TICKS_REQUIRED) {
            stableTicks++;
        }
        return stableTicks >= STABLE_TICKS_REQUIRED;
    }

    public static void reset() {
        lastLevel = null;
        stableTicks = 0;
    }
}
