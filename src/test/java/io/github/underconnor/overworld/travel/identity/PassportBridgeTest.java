package io.github.underconnor.overworld.travel.identity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PassportBridgeTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public interface TestIdentityService {
        Optional<TestIdentity> identity(UUID id);
    }

    public record TestIdentity(UUID minecraftUuid, String realName, Instant expiresAt) {}

    @Test
    void invokesPublicInterfaceOnAnAnonymousProvider() throws Exception {
        TestIdentityService service = new TestIdentityService() {
            @Override public Optional<TestIdentity> identity(UUID id) {
                return Optional.of(new TestIdentity(id, "테스트가", NOW.plusSeconds(60)));
            }
        };
        assertEquals(Optional.of("테스트가"), read(service, NOW));
    }

    @Test
    void expiresAtMustBeStrictlyInTheFuture() throws Exception {
        TestIdentityService service = id -> Optional.of(new TestIdentity(id, "테스트가", NOW));
        assertTrue(read(service, NOW).isEmpty());
        assertTrue(read(service, NOW.plusSeconds(1)).isEmpty());
        assertEquals(Optional.of("테스트가"), read(service, NOW.minusSeconds(1)));
    }

    @Test
    void rejectsIdentityForAnotherUuidAndEmptyName() throws Exception {
        assertTrue(read(id -> Optional.of(new TestIdentity(UUID.randomUUID(), "테스트가", NOW.plusSeconds(60))), NOW).isEmpty());
        assertTrue(read(id -> Optional.of(new TestIdentity(id, " ", NOW.plusSeconds(60))), NOW).isEmpty());
        assertTrue(read(id -> Optional.of(new TestIdentity(id, null, NOW.plusSeconds(60))), NOW).isEmpty());
    }

    @Test
    void removalAndReplacementAreVisibleWithoutCachedIdentities() throws Exception {
        var current = new AtomicReference<>(Optional.of(new TestIdentity(PLAYER, "테스트가", NOW.plusSeconds(60))));
        TestIdentityService service = id -> current.get();
        assertEquals(Optional.of("테스트가"), read(service, NOW));
        current.set(Optional.empty());
        assertTrue(read(service, NOW).isEmpty());
        current.set(Optional.of(new TestIdentity(PLAYER, "테스트나", NOW.plusSeconds(60))));
        assertEquals(Optional.of("테스트나"), read(service, NOW));
    }

    @Test
    void incompatibleAndFailingProvidersFailClosed() throws Exception {
        assertTrue(read(id -> { throw new IllegalStateException("Unavailable"); }, NOW).isEmpty());
        assertTrue(PassportBridge.readIdentity(String.class.getMethod("toString"), "invalid", PLAYER, NOW).isEmpty());
    }

    private static Optional<String> read(TestIdentityService provider, Instant now) throws Exception {
        Method method = TestIdentityService.class.getMethod("identity", UUID.class);
        return PassportBridge.readIdentity(method, provider, PLAYER, now);
    }
}
