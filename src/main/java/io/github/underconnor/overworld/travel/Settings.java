package io.github.underconnor.overworld.travel;

import io.github.underconnor.overworld.travel.state.TravelKind;
import org.bukkit.configuration.file.FileConfiguration;

record Settings(int tpaCooldown, int tpaWarmup, int homeCooldown, int homeWarmup,
                int backCooldown, int backWarmup, int requestExpiry, int requestThrottle,
                String homeDestination, String spawnWorld, double movementTolerance,
                boolean cancelDamage, boolean cancelAttack, boolean checkDestination, int loadTimeout) {
    static Settings read(FileConfiguration c) {
        String mode = c.getString("home.destination", "respawn");
        if (!mode.equals("respawn") && !mode.equals("world-spawn"))
            throw new IllegalArgumentException("home.destination must be respawn or world-spawn");
        double tolerance = c.getDouble("safety.movement-tolerance", 0.1);
        if (!Double.isFinite(tolerance) || tolerance < 0 || tolerance > 10)
            throw new IllegalArgumentException("safety.movement-tolerance must be between 0 and 10");
        return new Settings(seconds(c,"tpa.cooldown-seconds",120,0), seconds(c,"tpa.warmup-seconds",5,0),
            seconds(c,"home.cooldown-seconds",300,0), seconds(c,"home.warmup-seconds",5,0),
            seconds(c,"back.cooldown-seconds",300,0), seconds(c,"back.warmup-seconds",10,0),
            seconds(c,"tpa.request-expiry-seconds",60,1), seconds(c,"tpa.request-throttle-seconds",3,1),
            mode, c.getString("home.spawn-world", "").strip(), tolerance,
            c.getBoolean("safety.cancel-on-damage",true), c.getBoolean("safety.cancel-on-attack",true),
            c.getBoolean("safety.check-destination",true), seconds(c,"safety.chunk-load-timeout-seconds",15,1));
    }
    private static int seconds(FileConfiguration c, String key, int fallback, int minimum) {
        if (c.contains(key) && !c.isInt(key)) throw new IllegalArgumentException(key + " must be an integer");
        int value = c.getInt(key, fallback);
        if (value < minimum || value > 86400) throw new IllegalArgumentException(key + " is out of range");
        return value;
    }
    int cooldown(TravelKind kind) {
        return switch (kind) { case TPA -> tpaCooldown; case HOME -> homeCooldown; case BACK -> backCooldown; };
    }
    int warmup(TravelKind kind) {
        return switch (kind) { case TPA -> tpaWarmup; case HOME -> homeWarmup; case BACK -> backWarmup; };
    }
}
