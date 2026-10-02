package io.github.underconnor.overworld.travel;

import io.github.underconnor.overworld.travel.state.TravelKind;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @Test
    void packagedDefaultsMatchRequestedTravelTimes() {
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));
        Settings settings = Settings.read(config);

        assertEquals(120, settings.cooldown(TravelKind.TPA));
        assertEquals(5, settings.warmup(TravelKind.TPA));
        assertEquals(300, settings.cooldown(TravelKind.HOME));
        assertEquals(5, settings.warmup(TravelKind.HOME));
        assertEquals(300, settings.cooldown(TravelKind.BACK));
        assertEquals(10, settings.warmup(TravelKind.BACK));
        assertTrue(settings.cancelAttack());
        assertTrue(settings.cancelDamage());
        assertTrue(settings.checkDestination());
    }

    @Test
    void missingValuesUseDefaultsAndZeroWarmupOrCooldownIsAllowed() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("home.warmup-seconds", 0);
        config.set("home.cooldown-seconds", 0);
        Settings settings = Settings.read(config);
        assertEquals(0, settings.homeWarmup());
        assertEquals(0, settings.homeCooldown());
        assertEquals(60, settings.requestExpiry());
        assertEquals(15, settings.loadTimeout());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tpa.cooldown-seconds", "tpa.warmup-seconds", "home.cooldown-seconds",
            "home.warmup-seconds", "back.cooldown-seconds", "back.warmup-seconds", "tpa.request-expiry-seconds",
            "tpa.request-throttle-seconds", "safety.chunk-load-timeout-seconds"})
    void durationsRejectWrongTypesNegativeAndExcessiveValues(String key) {
        YamlConfiguration config = new YamlConfiguration();
        for (Object invalid : new Object[]{"5", 1.5, -1, 86_401}) {
            config.set(key, invalid);
            assertThrows(IllegalArgumentException.class, () -> Settings.read(config), key + "=" + invalid);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tpa.request-expiry-seconds", "tpa.request-throttle-seconds", "safety.chunk-load-timeout-seconds"})
    void expiryThrottleAndLoadTimeoutMustBePositive(String key) {
        YamlConfiguration config = new YamlConfiguration();
        config.set(key, 0);
        assertThrows(IllegalArgumentException.class, () -> Settings.read(config));
    }

    @Test
    void rejectsUnknownHomeModeAndOutOfRangeMovementTolerance() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("home.destination", "somewhere");
        assertThrows(IllegalArgumentException.class, () -> Settings.read(config));
        config.set("home.destination", "world-spawn");
        config.set("home.spawn-world", "  overworld  ");
        assertEquals("overworld", Settings.read(config).spawnWorld());
        for (double invalid : new double[]{-0.1, 10.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            config.set("safety.movement-tolerance", invalid);
            assertThrows(IllegalArgumentException.class, () -> Settings.read(config));
        }
    }
}
