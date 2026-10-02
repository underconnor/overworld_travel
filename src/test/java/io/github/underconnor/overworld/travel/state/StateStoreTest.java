package io.github.underconnor.overworld.travel.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StateStoreTest {
    @TempDir
    Path directory;

    private final TravelStateTest.MutableClock clock = new TravelStateTest.MutableClock();
    private final UUID alice = UUID.fromString("77ad71f4-80bc-4c30-af91-643199b32709");
    private final UUID world = UUID.fromString("0da3d908-60bc-4a5a-acdc-55aef3e6c2a7");
    private final DeathPoint death = new DeathPoint(world, -12.25, 64.5, 30.75, 180, -30);

    @Test
    void reloadPreservesCooldownDeadlinesAndDeathsButNotPendingRequests() throws IOException {
        TravelState original = new TravelState(clock);
        original.setCooldown(alice, TravelKind.HOME, 300);
        original.setCooldown(alice, TravelKind.BACK, 300);
        original.setDeath(alice, death);
        original.request(alice, UUID.randomUUID(), 60);
        StateStore store = new StateStore(directory);
        store.save(original);

        clock.advance(20_500);
        TravelState restored = new TravelState(clock);
        store.load(restored);
        assertEquals(280, restored.cooldownRemainingSeconds(alice, TravelKind.HOME));
        assertEquals(280, restored.cooldownRemainingSeconds(alice, TravelKind.BACK));
        assertEquals(death, restored.death(alice).orElseThrow());
        assertTrue(restored.outgoing(alice).isEmpty());
        String saved = Files.readString(directory.resolve("state.properties"));
        assertFalse(saved.contains("request"));
    }

    @Test
    void expiredCooldownsAreDiscardedOnReload() throws IOException {
        TravelState original = new TravelState(clock);
        original.setCooldown(alice, TravelKind.TPA, 120);
        StateStore store = new StateStore(directory);
        store.save(original);
        clock.advance(120_000);
        TravelState restored = new TravelState(clock);
        store.load(restored);
        assertEquals(0, restored.cooldownRemainingSeconds(alice, TravelKind.TPA));
        assertTrue(restored.cooldownSnapshot().isEmpty());
    }

    @Test
    void missingFileIsAValidNewInstallation() throws IOException {
        TravelState state = new TravelState(clock);
        new StateStore(directory.resolve("not-created-yet")).load(state);
        assertTrue(state.death(alice).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "version=2\n",
            "version=1\nversion=1\n",
            "version=1\nunknown=value\n",
            "version=1\ndeath.invalid=0,1,2,3,4,5\n",
            "version=1\ndeath.77ad71f4-80bc-4c30-af91-643199b32709=bad-world,1,2,3,4,5\n",
            "version=1\ndeath.77ad71f4-80bc-4c30-af91-643199b32709=0da3d908-60bc-4a5a-acdc-55aef3e6c2a7,NaN,2,3,4,5\n",
            "version=1\ndeath.77ad71f4-80bc-4c30-af91-643199b32709=0da3d908-60bc-4a5a-acdc-55aef3e6c2a7,1,2,3,4\n",
            "version=1\ncooldown.77ad71f4-80bc-4c30-af91-643199b32709.TPA=bad\n",
            "version=1\ncooldown.77ad71f4-80bc-4c30-af91-643199b32709.TPA=-1\n",
            "version=1\ncooldown.77ad71f4-80bc-4c30-af91-643199b32709.UNKNOWN=500\n",
            "version=1\ncooldown.77ad71f4-80bc-4c30-af91-643199b32709.TPA.extra=500\n"
    })
    void corruptInputNeitherMutatesMemoryNorCanBeOverwritten(String invalid) throws IOException {
        Path file = directory.resolve("state.properties");
        Files.writeString(file, invalid);
        TravelState state = new TravelState(clock);
        state.setDeath(alice, death);
        state.setCooldown(alice, TravelKind.HOME, 300);
        StateStore store = new StateStore(directory);

        assertThrows(IOException.class, () -> store.load(state));
        assertEquals(death, state.death(alice).orElseThrow());
        assertEquals(300, state.cooldownRemainingSeconds(alice, TravelKind.HOME));
        assertThrows(IOException.class, () -> store.save(state));
        assertEquals(invalid, Files.readString(file));
    }

    @Test
    void replacingSnapshotRemovesStaleEntriesAndCleansTemporaryFiles() throws IOException {
        StateStore store = new StateStore(directory);
        TravelState state = new TravelState(clock);
        state.setDeath(alice, death);
        state.setCooldown(alice, TravelKind.TPA, 120);
        store.save(state);

        clock.advance(120_000);
        DeathPoint latest = new DeathPoint(world, 10, 70, 20, 0, 0);
        state.setDeath(alice, latest);
        store.save(state);

        TravelState restored = new TravelState(clock);
        store.load(restored);
        assertEquals(latest, restored.death(alice).orElseThrow());
        assertEquals(0, restored.cooldownRemainingSeconds(alice, TravelKind.TPA));
        try (var files = Files.list(directory)) {
            assertEquals(java.util.List.of("state.properties"),
                    files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void failedAtomicReplacementPreservesDestinationAndCleansTemporaryFile() throws IOException {
        Path destination = Files.createDirectory(directory.resolve("state.properties"));
        Path existing = destination.resolve("existing");
        Files.writeString(existing, "previous data");
        StateStore store = new StateStore(directory);

        assertThrows(IOException.class, () -> store.save(new TravelState(clock)));
        assertEquals("previous data", Files.readString(existing));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }
}
