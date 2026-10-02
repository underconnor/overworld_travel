package io.github.underconnor.overworld.travel.state;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TravelStateTest {
    private final MutableClock clock = new MutableClock();
    private final TravelState state = new TravelState(clock);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID charlie = UUID.randomUUID();

    @Test
    void senderCanHaveOnlyOneRequestAndOnlyItsTargetCanAcceptIt() {
        state.request(alice, bob, 60);
        TravelState.Request replacement = state.request(alice, charlie, 60);

        assertTrue(state.incoming(bob).isEmpty());
        assertEquals(replacement, state.outgoing(alice).orElseThrow());
        assertTrue(state.removeRequest(alice, bob).isEmpty());
        assertEquals(replacement, state.removeRequest(alice, charlie).orElseThrow());
        assertTrue(state.outgoing(alice).isEmpty());
        assertTrue(state.removeRequest(alice, charlie).isEmpty());
    }

    @Test
    void requestExpiresAtExactDeadlineAndRemainsAvailableForExpiryNotification() {
        TravelState.Request request = state.request(alice, bob, 60);
        clock.advance(59_999);
        assertEquals(request, state.outgoing(alice).orElseThrow());
        assertEquals(1, state.incoming(bob).size());
        assertTrue(state.expireRequests().isEmpty());

        clock.advance(1);
        assertTrue(state.outgoing(alice).isEmpty());
        assertTrue(state.incoming(bob).isEmpty());
        assertTrue(state.removeRequest(alice, bob).isEmpty());
        assertEquals(java.util.List.of(request), state.expireRequests());
        assertTrue(state.expireRequests().isEmpty());
    }

    @Test
    void disconnectRemovesBothIncomingAndOutgoingRequestsOnlyForThatPlayer() {
        state.request(alice, bob, 60);
        state.request(bob, charlie, 60);
        state.request(charlie, alice, 60);

        assertEquals(2, state.removePlayerRequests(bob).size());
        assertTrue(state.outgoing(alice).isEmpty());
        assertTrue(state.outgoing(bob).isEmpty());
        assertTrue(state.outgoing(charlie).isPresent());
    }

    @Test
    void cooldownRoundsUpWithoutExpiringEarlyAndKindsAreIndependent() {
        state.setCooldown(alice, TravelKind.TPA, 120);
        state.setCooldown(alice, TravelKind.HOME, 300);
        assertEquals(120, state.cooldownRemainingSeconds(alice, TravelKind.TPA));
        assertEquals(0, state.cooldownRemainingSeconds(bob, TravelKind.TPA));
        assertEquals(0, state.cooldownRemainingSeconds(alice, TravelKind.BACK));

        clock.advance(119_001);
        assertEquals(1, state.cooldownRemainingSeconds(alice, TravelKind.TPA));
        clock.advance(999);
        assertEquals(0, state.cooldownRemainingSeconds(alice, TravelKind.TPA));
        assertEquals(180, state.cooldownRemainingSeconds(alice, TravelKind.HOME));
        state.setCooldown(alice, TravelKind.HOME, 0);
        assertEquals(0, state.cooldownRemainingSeconds(alice, TravelKind.HOME));
    }

    @Test
    void mostRecentDeathReplacesPreviousDeath() {
        UUID world = UUID.randomUUID();
        state.setDeath(alice, new DeathPoint(world, 1, 64, 2, 90, 0));
        DeathPoint latest = new DeathPoint(world, -100.25, 30, 250.125, -40, 45);
        state.setDeath(alice, latest);
        assertEquals(latest, state.death(alice).orElseThrow());
        assertTrue(state.death(bob).isEmpty());
    }

    @Test
    void invalidInputCannotReplaceValidState() {
        TravelState.Request valid = state.request(alice, bob, 60);
        assertThrows(IllegalArgumentException.class, () -> state.request(alice, alice, 60));
        assertThrows(IllegalArgumentException.class, () -> state.request(alice, bob, 0));
        assertThrows(IllegalArgumentException.class, () -> state.request(alice, bob, -1));
        assertThrows(IllegalArgumentException.class, () -> state.setCooldown(alice, TravelKind.HOME, -1));
        assertEquals(valid, state.outgoing(alice).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> new DeathPoint(UUID.randomUUID(), Double.NaN, 64, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DeathPoint(UUID.randomUUID(), 0, 64, 0, Float.POSITIVE_INFINITY, 0));
    }

    static final class MutableClock extends Clock {
        private long milliseconds = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli();

        void advance(long milliseconds) {
            this.milliseconds += milliseconds;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("Test clock uses UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(milliseconds);
        }

        @Override
        public long millis() {
            return milliseconds;
        }
    }
}
