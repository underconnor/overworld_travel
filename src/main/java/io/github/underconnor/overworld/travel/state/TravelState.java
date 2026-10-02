package io.github.underconnor.overworld.travel.state;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Mutable state owned exclusively by the server's main thread. */
public final class TravelState {
    private final Clock clock;
    private final Map<UUID, Request> requests = new LinkedHashMap<>();
    private final Map<UUID, EnumMap<TravelKind, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, DeathPoint> deaths = new HashMap<>();

    public TravelState() {
        this(Clock.systemUTC());
    }

    public TravelState(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record Request(UUID sender, UUID target, long expiresAt) {
        public Request {
            Objects.requireNonNull(sender, "sender");
            Objects.requireNonNull(target, "target");
            if (sender.equals(target)) {
                throw new IllegalArgumentException("Cannot request teleportation to yourself");
            }
        }
    }

    public Request request(UUID sender, UUID target, int lifetimeSeconds) {
        if (lifetimeSeconds <= 0) {
            throw new IllegalArgumentException("Request lifetime must be positive");
        }
        Request request = new Request(sender, target, deadline(lifetimeSeconds));
        requests.remove(sender);
        requests.put(sender, request);
        return request;
    }

    public List<Request> incoming(UUID target) {
        Objects.requireNonNull(target, "target");
        long now = clock.millis();
        return requests.values().stream()
                .filter(request -> request.target().equals(target) && request.expiresAt() > now)
                .toList();
    }

    public Optional<Request> outgoing(UUID sender) {
        Request request = requests.get(Objects.requireNonNull(sender, "sender"));
        return request != null && request.expiresAt() > clock.millis()
                ? Optional.of(request) : Optional.empty();
    }

    /** Only the named recipient can remove an active request through this method. */
    public Optional<Request> removeRequest(UUID sender, UUID target) {
        Objects.requireNonNull(target, "target");
        Optional<Request> request = outgoing(sender)
                .filter(candidate -> candidate.target().equals(target));
        request.ifPresent(candidate -> requests.remove(candidate.sender()));
        return request;
    }

    public List<Request> removePlayerRequests(UUID player) {
        Objects.requireNonNull(player, "player");
        List<Request> removed = new ArrayList<>();
        requests.values().removeIf(request -> {
            boolean matches = request.sender().equals(player) || request.target().equals(player);
            if (matches) {
                removed.add(request);
            }
            return matches;
        });
        return List.copyOf(removed);
    }

    public List<Request> expireRequests() {
        long now = clock.millis();
        List<Request> expired = new ArrayList<>();
        requests.values().removeIf(request -> {
            boolean isExpired = request.expiresAt() <= now;
            if (isExpired) {
                expired.add(request);
            }
            return isExpired;
        });
        return List.copyOf(expired);
    }

    public long cooldownRemainingSeconds(UUID player, TravelKind kind) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(kind, "kind");
        EnumMap<TravelKind, Long> playerCooldowns = cooldowns.get(player);
        if (playerCooldowns == null) {
            return 0;
        }
        Long expiresAt = playerCooldowns.get(kind);
        if (expiresAt == null) {
            return 0;
        }
        long now = clock.millis();
        if (expiresAt <= now) {
            playerCooldowns.remove(kind);
            if (playerCooldowns.isEmpty()) {
                cooldowns.remove(player);
            }
            return 0;
        }
        long remainingMillis = Math.subtractExact(expiresAt, now);
        return (remainingMillis - 1) / 1_000 + 1;
    }

    public void setCooldown(UUID player, TravelKind kind, int seconds) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(kind, "kind");
        if (seconds < 0) {
            throw new IllegalArgumentException("Cooldown cannot be negative");
        }
        if (seconds == 0) {
            EnumMap<TravelKind, Long> playerCooldowns = cooldowns.get(player);
            if (playerCooldowns != null) {
                playerCooldowns.remove(kind);
                if (playerCooldowns.isEmpty()) {
                    cooldowns.remove(player);
                }
            }
            return;
        }
        long expiresAt = deadline(seconds);
        cooldowns.computeIfAbsent(player, ignored -> new EnumMap<>(TravelKind.class))
                .put(kind, expiresAt);
    }

    public Optional<DeathPoint> death(UUID player) {
        return Optional.ofNullable(deaths.get(Objects.requireNonNull(player, "player")));
    }

    public void setDeath(UUID player, DeathPoint deathPoint) {
        deaths.put(Objects.requireNonNull(player, "player"), Objects.requireNonNull(deathPoint, "deathPoint"));
    }

    Map<UUID, Map<TravelKind, Long>> cooldownSnapshot() {
        long now = clock.millis();
        Map<UUID, Map<TravelKind, Long>> snapshot = new HashMap<>();
        cooldowns.forEach((player, values) -> {
            EnumMap<TravelKind, Long> active = new EnumMap<>(TravelKind.class);
            values.forEach((kind, deadline) -> {
                if (deadline > now) {
                    active.put(kind, deadline);
                }
            });
            if (!active.isEmpty()) {
                snapshot.put(player, Map.copyOf(active));
            }
        });
        return Map.copyOf(snapshot);
    }

    Map<UUID, DeathPoint> deathSnapshot() {
        return Map.copyOf(deaths);
    }

    void restore(Map<UUID, Map<TravelKind, Long>> savedCooldowns, Map<UUID, DeathPoint> savedDeaths) {
        cooldowns.clear();
        long now = clock.millis();
        savedCooldowns.forEach((player, values) -> {
            EnumMap<TravelKind, Long> active = new EnumMap<>(TravelKind.class);
            values.forEach((kind, deadline) -> {
                if (deadline > now) {
                    active.put(kind, deadline);
                }
            });
            if (!active.isEmpty()) {
                cooldowns.put(player, active);
            }
        });
        deaths.clear();
        deaths.putAll(savedDeaths);
    }

    private long deadline(int seconds) {
        return Math.addExact(clock.millis(), Math.multiplyExact((long) seconds, 1_000L));
    }
}
