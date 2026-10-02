package io.github.underconnor.overworld.travel.identity;

import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerNamesTest {
    private final PlayerDirectory.Entry first = entry("ExampleMiner", "테스트가");
    private final PlayerDirectory.Entry second = entry("ExampleBuilder", "테스트가");

    @Test
    void minecraftNamesMatchExactlyIgnoringCase() {
        assertEquals(List.of(first), PlayerNames.resolve(List.of(first, second), "exampleminer"));
        assertTrue(PlayerNames.resolve(List.of(first, second), "Example").isEmpty());
    }

    @Test
    void namesUseUnicodeCanonicalNormalizationAndTrimOuterWhitespace() {
        String decomposed = Normalizer.normalize("테스트가", Normalizer.Form.NFD);
        assertEquals(List.of(first), PlayerNames.resolve(List.of(first), "  " + decomposed + "  "));
    }

    @Test
    void duplicateRealNamesAreAllReturned() {
        assertEquals(List.of(first, second), PlayerNames.resolve(List.of(first, second), "테스트가"));
    }

    @Test
    void collisionsBetweenMinecraftAndRealNamesRemainAmbiguous() {
        var other = entry("SomeoneElse", "ExampleMiner");
        assertEquals(List.of(first, other), PlayerNames.resolve(List.of(first, other), "ExampleMiner"));
    }

    @Test
    void missingNamesDoNotMatchEmptyQueriesAndMinecraftNamesStillWork() {
        var unnamed = entry("OnlyMinecraft", "");
        assertTrue(PlayerNames.resolve(List.of(unnamed), " ").isEmpty());
        assertTrue(PlayerNames.resolve(List.of(unnamed), null).isEmpty());
        assertEquals(List.of(unnamed), PlayerNames.resolve(List.of(unnamed), "OnlyMinecraft"));
    }

    @Test
    void matchingDoesNotRemoveInternalSpacesOrParseDecoratedNames() {
        var spaced = entry("AnotherMiner", "테스트 가");
        assertTrue(PlayerNames.resolve(List.of(spaced), "테스트가").isEmpty());
        assertTrue(PlayerNames.resolve(List.of(first), "[회원] 테스트가").isEmpty());
    }

    private static PlayerDirectory.Entry entry(String minecraftName, String realName) {
        return new PlayerDirectory.Entry(UUID.randomUUID(), minecraftName, realName);
    }
}
