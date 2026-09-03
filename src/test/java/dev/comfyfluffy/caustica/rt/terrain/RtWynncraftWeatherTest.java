package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtWynncraftWeatherTest {
    @Test
    void classifiesWynncraftWeatherRegions() {
        assertEquals(RtWynncraftWeather.Climate.SNOW, RtWynncraftWeather.climateAt(0, -750));
        assertEquals(RtWynncraftWeather.Climate.SAND, RtWynncraftWeather.climateAt(1000, -1800));
        assertEquals(RtWynncraftWeather.Climate.SMOG, RtWynncraftWeather.climateAt(900, -700));
        assertEquals(RtWynncraftWeather.Climate.CLEAR, RtWynncraftWeather.climateAt(1200, -5300));
        assertEquals(RtWynncraftWeather.Climate.RAIN, RtWynncraftWeather.climateAt(0, 0));
    }

    @Test
    void appliesRegionSpecificDailyChances() {
        assertEquals(0.95, RtWynncraftWeather.dailyChanceAt(0, -750), 0.0);
        assertEquals(0.75, RtWynncraftWeather.dailyChanceAt(200, -2000), 0.0);
        assertEquals(0.50, RtWynncraftWeather.dailyChanceAt(1000, -1800), 0.0);
        assertEquals(0.0, RtWynncraftWeather.dailyChanceAt(1200, -5300), 0.0);
        assertEquals(0.20, RtWynncraftWeather.dailyChanceAt(0, 0), 0.0);
    }

    @Test
    void convertsDailyChanceToElapsedTickHazard() {
        assertEquals(0.20, RtWynncraftWeather.startProbability(0.20, 24_000), 1.0e-12);
        assertEquals(0.0, RtWynncraftWeather.startProbability(0.75, 0));
        assertEquals(0.0, RtWynncraftWeather.startProbability(0.0, 24_000));
        assertEquals(1.0, RtWynncraftWeather.startProbability(1.0, 1));
        assertTrue(RtWynncraftWeather.startProbability(0.95, 1)
                > RtWynncraftWeather.startProbability(0.20, 1));
    }
}
