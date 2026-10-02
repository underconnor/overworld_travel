package io.github.underconnor.overworld.travel.identity;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A fresh view of visible players connected to this Paper server. */
public final class PlayerDirectory {
    private static final Comparator<Entry> ORDER = Comparator
            .comparing((Entry entry) -> entry.minecraftName().toLowerCase(Locale.ROOT))
            .thenComparing(Entry::id);
    private final JavaPlugin plugin;

    public PlayerDirectory(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** An empty real name means that no current Passport identity is available. */
    public record Entry(UUID id, String minecraftName, String realName) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(minecraftName, "minecraftName");
            Objects.requireNonNull(realName, "realName");
        }
    }

    /** Call from the server thread. This method never caches member identities. */
    public List<Entry> online(Player viewer) {
        Objects.requireNonNull(viewer, "viewer");
        var identities = PassportBridge.lookup(plugin.getServer().getServicesManager());
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(Player::isOnline)
                .filter(viewer::canSee)
                .map(player -> new Entry(player.getUniqueId(), player.getName(),
                        identities.realName(player.getUniqueId()).orElse("")))
                .sorted(ORDER)
                .toList();
    }

    /** Returns every exact match; callers must handle duplicate real names explicitly. */
    public List<Entry> resolve(Player viewer, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return PlayerNames.resolve(online(viewer), query);
    }

    public Optional<Entry> find(Player viewer, UUID id) {
        Objects.requireNonNull(id, "id");
        return online(viewer).stream().filter(entry -> entry.id().equals(id)).findFirst();
    }
}
