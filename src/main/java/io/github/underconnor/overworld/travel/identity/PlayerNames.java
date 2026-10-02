package io.github.underconnor.overworld.travel.identity;

import java.text.Normalizer;
import java.util.List;

/** Matching deliberately preserves internal whitespace and all name characters. */
final class PlayerNames {
    private PlayerNames() {}

    static List<PlayerDirectory.Entry> resolve(List<PlayerDirectory.Entry> entries, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String stripped = query.strip();
        String normalized = normalize(stripped);
        return entries.stream().filter(entry -> entry.minecraftName().equalsIgnoreCase(stripped)
                || (!entry.realName().isBlank() && normalize(entry.realName()).equals(normalized)))
                .toList();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    }
}
