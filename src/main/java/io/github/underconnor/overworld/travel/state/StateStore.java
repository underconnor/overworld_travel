package io.github.underconnor.overworld.travel.state;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/** Stores only UUIDs, coordinates and cooldown deadlines. Pending requests are ephemeral. */
public final class StateStore {
    private static final String VERSION = "1";
    private final Path directory;
    private final Path file;
    private boolean loadFailed;

    public StateStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath();
        this.file = this.directory.resolve("state.properties");
    }

    public void load(TravelState state) throws IOException {
        Objects.requireNonNull(state, "state");
        if (Files.notExists(file)) {
            loadFailed = false;
            return;
        }
        loadFailed = true;
        Properties properties = new UniqueProperties();
        Map<UUID, Map<TravelKind, Long>> cooldowns = new HashMap<>();
        Map<UUID, DeathPoint> deaths = new HashMap<>();
        try {
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            }
            if (!VERSION.equals(properties.getProperty("version"))) {
                throw new IllegalArgumentException("Missing or unsupported state version");
            }
            for (String key : properties.stringPropertyNames()) {
                String value = properties.getProperty(key);
                if (key.equals("version")) {
                    continue;
                }
                if (key.startsWith("death.")) {
                    UUID player = parseUuid(key.substring("death.".length()));
                    String[] fields = value.split(",", -1);
                    if (fields.length != 6) {
                        throw new IllegalArgumentException("Death point must contain six fields");
                    }
                    deaths.put(player, new DeathPoint(parseUuid(fields[0]),
                            Double.parseDouble(fields[1]), Double.parseDouble(fields[2]), Double.parseDouble(fields[3]),
                            Float.parseFloat(fields[4]), Float.parseFloat(fields[5])));
                } else if (key.startsWith("cooldown.")) {
                    String[] fields = key.substring("cooldown.".length()).split("\\.", -1);
                    if (fields.length != 2) {
                        throw new IllegalArgumentException("Invalid cooldown key");
                    }
                    UUID player = parseUuid(fields[0]);
                    TravelKind kind = TravelKind.valueOf(fields[1]);
                    long deadline = Long.parseLong(value);
                    if (deadline < 0) {
                        throw new IllegalArgumentException("Cooldown deadline cannot be negative");
                    }
                    cooldowns.computeIfAbsent(player, ignored -> new EnumMap<>(TravelKind.class)).put(kind, deadline);
                } else {
                    throw new IllegalArgumentException("Unknown state entry: " + key);
                }
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid travel state in " + file + "; existing data was not changed", exception);
        }
        state.restore(cooldowns, deaths);
        loadFailed = false;
    }

    /** Failure before the atomic move leaves the previously saved file intact. */
    public void save(TravelState state) throws IOException {
        Objects.requireNonNull(state, "state");
        if (loadFailed) {
            throw new IOException("Refusing to overwrite travel state after an unsuccessful load: " + file);
        }
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        state.cooldownSnapshot().forEach((player, values) -> values.forEach((kind, deadline) ->
                properties.setProperty("cooldown." + player + "." + kind, Long.toString(deadline))));
        state.deathSnapshot().forEach((player, point) -> properties.setProperty("death." + player,
                point.worldId() + "," + point.x() + "," + point.y() + "," + point.z() + "," + point.yaw() + "," + point.pitch()));

        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "state-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Overworld Travel state; UUIDs only");
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // Do not fall back to a non-atomic overwrite on unsupported filesystems.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static UUID parseUuid(String value) {
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) {
            throw new IllegalArgumentException("UUID must use its canonical lowercase representation");
        }
        return uuid;
    }

    private static final class UniqueProperties extends Properties {
        @Override
        public synchronized Object put(Object key, Object value) {
            if (containsKey(key)) {
                throw new IllegalArgumentException("Duplicate state entry: " + key);
            }
            return super.put(key, value);
        }
    }
}
