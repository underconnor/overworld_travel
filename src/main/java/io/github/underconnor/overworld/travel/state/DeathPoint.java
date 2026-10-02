package io.github.underconnor.overworld.travel.state;

import java.util.Objects;
import java.util.UUID;

public record DeathPoint(UUID worldId, double x, double y, double z, float yaw, float pitch) {
    public DeathPoint {
        Objects.requireNonNull(worldId, "worldId");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Death point coordinates must be finite");
        }
    }
}
