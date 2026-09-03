package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Random;

/** Client-side Wynncraft weather state used by Caustica's atmosphere renderer. */
public final class RtWynncraftWeather {
    public static final int WEATHER_RAIN = 0;
    public static final int WEATHER_SNOW = 1;
    public static final int WEATHER_SAND = 2;
    public static final int WEATHER_SMOG = 3;

    private static final long DAY_TICKS = 24_000L;
    private static final int MIN_DURATION_TICKS = 12_000;
    private static final int DURATION_VARIATION_TICKS = 12_001;
    private static final float FADE_IN_TICKS = 200.0f;
    private static final float FADE_OUT_TICKS = 300.0f;
    private static final int MAX_TICK_CATCH_UP = 200;

    private static final Region[] REGIONS = {
            new Region(-475, -940, 310, -575, 0.95, Climate.SNOW),
            new Region(-475, -575, -85, -295, 0.95, Climate.SNOW),
            new Region(-30, -2300, 530, -1820, 0.75, Climate.RAIN),
            new Region(-800, -3600, -495, -3000, 0.75, Climate.RAIN),
            new Region(850, -2330, 1500, -1180, 0.50, Climate.SAND),
            new Region(-980, -925, -485, -249, 0.33, Climate.RAIN),
            new Region(-2275, -5600, -1450, -5070, 0.33, Climate.RAIN),
            new Region(400, -1150, 1500, -250, 0.25, Climate.SMOG),
            new Region(-2155, -3400, -1000, -2030, 0.0, Climate.CLEAR),
            new Region(-1190, -3875, -801, -3500, 0.0, Climate.CLEAR),
            new Region(950, -5650, 1580, -5000, 0.0, Climate.CLEAR),
            new Region(-1150, -6600, -615, -5775, 0.0, Climate.CLEAR)
    };

    private static String sessionIdentity = "";
    private static long lastGameTick = Long.MIN_VALUE;
    private static Event event = Event.CLEAR;
    private static int remainingTicks;
    private static float rainIntensity;
    private static float thunderIntensity;
    private static Random random = new Random(0L);

    private RtWynncraftWeather() {
    }

    public record Sample(boolean active, int weatherType, float rainLevel,
                         float thunderLevel, float intensity) {
        private static final Sample INACTIVE = new Sample(false, WEATHER_RAIN, 0f, 0f, 0f);
    }

    enum Climate {
        RAIN(WEATHER_RAIN),
        SNOW(WEATHER_SNOW),
        SAND(WEATHER_SAND),
        SMOG(WEATHER_SMOG),
        CLEAR(WEATHER_RAIN);

        final int weatherType;

        Climate(int weatherType) {
            this.weatherType = weatherType;
        }
    }

    private enum Event {
        CLEAR,
        RAIN,
        THUNDER
    }

    private record Region(int minX, int minZ, int maxX, int maxZ,
                          double dailyChance, Climate climate) {
        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /** Returns native weather only on Wynncraft's overworld; other worlds keep their reported weather. */
    public static synchronized Sample sample(Minecraft mc, ClientLevel level, BlockPos cameraPos) {
        if (!CausticaConfig.Rt.Weather.WYNNCRAFT_DYNAMIC.value()
                || level == null || cameraPos == null
                || !Level.OVERWORLD.equals(level.dimension()) || !RtLodSession.isWynncraft(mc)) {
            reset();
            return Sample.INACTIVE;
        }

        String identity = RtLodSession.identity(mc, level);
        if (!identity.equals(sessionIdentity)) {
            initializeSession(identity, level.getGameTime());
        }

        Climate climate = climateAt(cameraPos.getX(), cameraPos.getZ());
        double dailyChance = dailyChanceAt(cameraPos.getX(), cameraPos.getZ());
        long gameTick = level.getGameTime();
        int elapsedTicks = elapsedTicks(gameTick);
        lastGameTick = gameTick;

        if (climate == Climate.CLEAR) {
            event = Event.CLEAR;
            remainingTicks = 0;
        } else if (elapsedTicks > 0) {
            advanceEvent(dailyChance, elapsedTicks);
        }

        float rainTarget = event == Event.CLEAR ? 0f : 1f;
        float thunderTarget = event == Event.THUNDER ? 1f : 0f;
        rainIntensity = approach(rainIntensity, rainTarget, elapsedTicks,
                rainTarget > rainIntensity ? FADE_IN_TICKS : FADE_OUT_TICKS);
        thunderIntensity = approach(thunderIntensity, thunderTarget, elapsedTicks,
                thunderTarget > thunderIntensity ? FADE_IN_TICKS : FADE_OUT_TICKS);
        float intensity = Math.min(rainIntensity + thunderIntensity * 0.5f, 1f);
        return new Sample(true, climate.weatherType, rainIntensity, thunderIntensity, intensity);
    }

    public static synchronized void reset() {
        sessionIdentity = "";
        lastGameTick = Long.MIN_VALUE;
        event = Event.CLEAR;
        remainingTicks = 0;
        rainIntensity = 0f;
        thunderIntensity = 0f;
    }

    private static void initializeSession(String identity, long gameTick) {
        sessionIdentity = identity;
        lastGameTick = gameTick;
        event = Event.CLEAR;
        remainingTicks = 0;
        rainIntensity = 0f;
        thunderIntensity = 0f;
        random = newRandom(mix64(identity.hashCode() ^ System.nanoTime()));
    }

    private static int elapsedTicks(long gameTick) {
        if (lastGameTick == Long.MIN_VALUE || gameTick <= lastGameTick) {
            return 0;
        }
        return (int) Math.min(gameTick - lastGameTick, MAX_TICK_CATCH_UP);
    }

    private static void advanceEvent(double dailyChance, int elapsedTicks) {
        if (event != Event.CLEAR) {
            remainingTicks -= elapsedTicks;
            if (remainingTicks <= 0) {
                event = Event.CLEAR;
                remainingTicks = 0;
            }
            return;
        }
        if (random.nextDouble() >= startProbability(dailyChance, elapsedTicks)) {
            return;
        }
        event = random.nextBoolean() ? Event.RAIN : Event.THUNDER;
        remainingTicks = MIN_DURATION_TICKS + random.nextInt(DURATION_VARIATION_TICKS);
    }

    static Climate climateAt(int x, int z) {
        for (Region region : REGIONS) {
            if (region.contains(x, z)) {
                return region.climate;
            }
        }
        return Climate.RAIN;
    }

    static double dailyChanceAt(int x, int z) {
        for (Region region : REGIONS) {
            if (region.contains(x, z)) {
                return region.dailyChance;
            }
        }
        return 0.20;
    }

    static double startProbability(double dailyChance, int elapsedTicks) {
        double chance = Math.max(0.0, Math.min(dailyChance, 1.0));
        int ticks = Math.max(elapsedTicks, 0);
        if (chance <= 0.0 || ticks == 0) {
            return 0.0;
        }
        if (chance >= 1.0) {
            return 1.0;
        }
        return 1.0 - Math.pow(1.0 - chance, ticks / (double) DAY_TICKS);
    }

    private static float approach(float current, float target, int elapsedTicks, float transitionTicks) {
        if (elapsedTicks <= 0) {
            return current;
        }
        float step = elapsedTicks / transitionTicks;
        if (current < target) {
            return Math.min(current + step, target);
        }
        return Math.max(current - step, target);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static Random newRandom(long seed) {
        return new Random(seed);
    }
}
