package io.github.underconnor.overworld.travel;

import io.github.underconnor.overworld.travel.identity.PlayerDirectory;
import io.github.underconnor.overworld.travel.identity.PlayerDirectory.Entry;
import io.github.underconnor.overworld.travel.state.StateStore;
import io.github.underconnor.overworld.travel.state.TravelKind;
import io.github.underconnor.overworld.travel.state.TravelState;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Command/event orchestration against mocked Paper APIs, not a real gameplay test. */
@Execution(ExecutionMode.SAME_THREAD)
class TravelCommandsTest {
    private MockedStatic<Bukkit> bukkit;
    private OverworldTravel plugin;
    private TravelState state;
    private StateStore store;
    private PlayerDirectory directory;
    private Map<UUID, Object> journeys;
    private Map<UUID, Player> players;
    private World world;
    private Player sender;
    private Player target;
    private Player other;

    @BeforeEach
    void setup() throws Exception {
        bukkit = mockStatic(Bukkit.class);
        players = new HashMap<>();
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000100"));
        when(world.getName()).thenReturn("test_world");
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getSpawnLocation()).thenAnswer(invocation -> new Location(world, 8, 65, 8));
        WorldBorder border = mock(WorldBorder.class);
        when(world.getWorldBorder()).thenReturn(border);
        when(border.isInside(any(Location.class))).thenReturn(true);
        when(world.hasCollisionsIn(any(BoundingBox.class))).thenAnswer(invocation ->
                ((BoundingBox) invocation.getArgument(0)).getHeight() < 0.1);
        Block floor = mock(Block.class);
        when(floor.getType()).thenReturn(Material.STONE);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(floor);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(invocation -> players.get(invocation.getArgument(0)));
        bukkit.when(() -> Bukkit.getWorld(world.getUID())).thenReturn(world);
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));

        plugin = mock(OverworldTravel.class, CALLS_REAL_METHODS);
        doReturn(true).when(plugin).isEnabled();
        doReturn(Logger.getLogger("TravelCommandsTest")).when(plugin).getLogger();
        state = new TravelState(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        store = mock(StateStore.class);
        directory = mock(PlayerDirectory.class);
        journeys = new HashMap<>();
        set(plugin, "state", state);
        set(plugin, "store", store);
        set(plugin, "directory", directory);
        set(plugin, "settings", defaults());
        set(plugin, "journeys", journeys);
        set(plugin, "lastRequests", new HashMap<UUID, Long>());
        set(plugin, "stateLoaded", true);
        sender = player("ExampleSender", 1, 8);
        target = player("ExampleTarget", 2, 32);
        other = player("ExampleOther", 3, 48);
        when(directory.find(any(Player.class), any(UUID.class))).thenAnswer(invocation ->
                Optional.ofNullable(players.get(invocation.getArgument(1))).map(this::entry));
        when(directory.resolve(any(Player.class), anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(1);
            return players.values().stream().filter(player -> player.getName().equalsIgnoreCase(query))
                    .map(this::entry).toList();
        });
    }

    @AfterEach
    void closeBukkitScope() {
        if (bukkit != null) bukkit.close();
    }

    @Test
    void onlyTheRecipientCanAcceptARequest() throws Exception {
        command(sender, "tpa", target.getName());
        assertTrue(state.outgoing(sender.getUniqueId()).isPresent());
        command(other, "tpaccept", sender.getName());
        assertTrue(state.outgoing(sender.getUniqueId()).isPresent());
        assertTrue(journeys.isEmpty());
        command(target, "tpaccept", sender.getName());
        assertTrue(state.outgoing(sender.getUniqueId()).isEmpty());
        assertTrue(journeys.containsKey(sender.getUniqueId()));
        assertFalse(journeys.containsKey(target.getUniqueId()));
        assertEquals(0, state.cooldownRemainingSeconds(sender.getUniqueId(), TravelKind.TPA));
    }

    @Test
    void ambiguousRealNameDoesNotSendToAnArbitraryPlayer() {
        List<Entry> ambiguous = List.of(entry(target), entry(other));
        when(directory.resolve(sender, "테스트이름")).thenReturn(ambiguous);
        command(sender, "tpa", "테스트이름");
        assertTrue(state.outgoing(sender.getUniqueId()).isEmpty());
        assertTrue(journeys.isEmpty());
    }

    @Test
    void multipleIncomingRequestsNeedAnUnambiguousSender() {
        state.request(sender.getUniqueId(), target.getUniqueId(), 60);
        state.request(other.getUniqueId(), target.getUniqueId(), 60);
        command(target, "tpaccept");
        assertEquals(2, state.incoming(target.getUniqueId()).size());
        assertTrue(journeys.isEmpty());
        command(target, "tpaccept", sender.getName());
        assertEquals(1, state.incoming(target.getUniqueId()).size());
        assertTrue(journeys.containsKey(sender.getUniqueId()));
        assertFalse(journeys.containsKey(other.getUniqueId()));
    }

    @Test
    void senderCooldownPreventsRequestsButRecipientCooldownDoesNot() {
        state.setCooldown(sender.getUniqueId(), TravelKind.TPA, 120);
        command(sender, "tpa", target.getName());
        assertTrue(state.outgoing(sender.getUniqueId()).isEmpty());
        command(other, "tpa", sender.getName());
        command(sender, "tpaccept", other.getName());
        assertTrue(journeys.containsKey(other.getUniqueId()));
    }

    @Test
    void acceptanceRechecksSenderCooldownBeforeConsumingRequest() {
        state.request(sender.getUniqueId(), target.getUniqueId(), 60);
        state.setCooldown(sender.getUniqueId(), TravelKind.TPA, 120);
        command(target, "tpaccept", sender.getName());
        assertTrue(state.outgoing(sender.getUniqueId()).isPresent());
        assertTrue(journeys.isEmpty());
    }

    @Test
    void failedTeleportChargesNoCooldownAndDoesNotSave() throws Exception {
        Object journey = acceptedTpa();
        when(sender.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenReturn(false);
        finish(journey, target.getLocation(), null);
        assertTrue(journeys.isEmpty());
        assertEquals(0, state.cooldownRemainingSeconds(sender.getUniqueId(), TravelKind.TPA));
        verify(store, never()).save(any());
    }

    @Test
    void successfulTpaChargesOnlySenderTpaCooldownAndPersists() throws Exception {
        Object journey = acceptedTpa();
        when(sender.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenReturn(true);
        finish(journey, target.getLocation(), null);
        assertEquals(120, state.cooldownRemainingSeconds(sender.getUniqueId(), TravelKind.TPA));
        assertEquals(0, state.cooldownRemainingSeconds(target.getUniqueId(), TravelKind.TPA));
        assertEquals(0, state.cooldownRemainingSeconds(sender.getUniqueId(), TravelKind.HOME));
        assertEquals(0, state.cooldownRemainingSeconds(sender.getUniqueId(), TravelKind.BACK));
        verify(store).save(state);
    }

    @Test
    void backRequiresRecordedDeathAndUsesThatPosition() throws Exception {
        command(sender, "back");
        assertTrue(journeys.isEmpty());
        PlayerTeleportEvent arbitraryTeleport = mock(PlayerTeleportEvent.class);
        when(arbitraryTeleport.getPlayer()).thenReturn(sender);
        plugin.teleport(arbitraryTeleport);
        command(sender, "back");
        assertTrue(journeys.isEmpty());

        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(sender);
        Location diedAt = sender.getLocation().clone();
        plugin.death(death);
        when(sender.getLocation()).thenAnswer(invocation -> new Location(world, 100, 65, 100));
        command(sender, "back");
        Object journey = journeys.get(sender.getUniqueId());
        assertNotNull(journey);
        Location destination = (Location) invoke("destination", journey);
        assertEquals(diedAt, destination);
        when(sender.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenReturn(true);
        finish(journey, destination, null);
        assertEquals(300, state.cooldownRemainingSeconds(sender.getUniqueId(), TravelKind.BACK));
        assertEquals(0, state.cooldownRemainingSeconds(sender.getUniqueId(), TravelKind.TPA));
    }

    @Test
    void successfulHomeUsesWorldSpawnAndHomeCooldown() throws Exception {
        command(sender, "home");
        Object journey = journeys.get(sender.getUniqueId());
        assertNotNull(journey);
        Location destination = (Location) invoke("destination", journey);
        assertEquals(world.getSpawnLocation(), destination);
        when(sender.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenReturn(true);
        finish(journey, destination, null);
        assertEquals(300, state.cooldownRemainingSeconds(sender.getUniqueId(), TravelKind.HOME));
        assertEquals(0, state.cooldownRemainingSeconds(sender.getUniqueId(), TravelKind.BACK));
    }

    @Test
    void invalidStoredRespawnReroutesToValidatedWorldSpawnAfterLoading() throws Exception {
        Location brokenBed = new Location(world, 64, 65, 64);
        when(sender.getRespawnLocation(false)).thenReturn(brokenBed);
        when(sender.getRespawnLocation()).thenReturn(null);
        when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(mock(Chunk.class)));
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return mock(BukkitTask.class);
        });
        when(sender.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenReturn(true);
        command(sender, "home");
        Object journey = journeys.get(sender.getUniqueId());
        assertEquals(brokenBed, invoke("destination", journey));
        finish(journey, brokenBed, null);
        verify(sender).teleport(eq(world.getSpawnLocation()), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        verify(sender, never()).teleport(eq(brokenBed), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertTrue(journeys.isEmpty());
        assertEquals(300, state.cooldownRemainingSeconds(sender.getUniqueId(), TravelKind.HOME));
    }

    @Test
    void unsafeDestinationCancelsWithoutCooldown() throws Exception {
        Object journey = acceptedTpa();
        when(world.hasCollisionsIn(any(BoundingBox.class))).thenReturn(true);
        finish(journey, target.getLocation(), null);
        verify(sender, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        assertTrue(journeys.isEmpty());
        assertEquals(0, state.cooldownRemainingSeconds(sender.getUniqueId(), TravelKind.TPA));
    }

    @Test
    void rotationIsAllowedButMovementBeyondToleranceCancels() throws Exception {
        acceptedTpa();
        Location origin = sender.getLocation();
        Location rotated = origin.clone();
        rotated.setYaw(90);
        plugin.move(new PlayerMoveEvent(sender, origin, rotated));
        assertTrue(journeys.containsKey(sender.getUniqueId()));
        plugin.move(new PlayerMoveEvent(sender, origin, origin.clone().add(0.11, 0, 0)));
        assertTrue(journeys.isEmpty());
        assertEquals(0, state.cooldownRemainingSeconds(sender.getUniqueId(), TravelKind.TPA));
    }

    @Test
    void absorptionOrShieldDoesNotBypassDamageCancellation() throws Exception {
        acceptedTpa();
        EntityDamageEvent damage = mock(EntityDamageEvent.class);
        when(damage.getEntity()).thenReturn(sender);
        when(damage.getDamage()).thenReturn(4.0);
        when(damage.getFinalDamage()).thenReturn(0.0);
        plugin.damage(damage);
        assertTrue(journeys.isEmpty());
    }

    @Test
    void rangedAttackCancelsShooterWarmup() throws Exception {
        acceptedTpa();
        Projectile projectile = mock(Projectile.class);
        when(projectile.getShooter()).thenReturn(sender);
        EntityDamageByEntityEvent attack = mock(EntityDamageByEntityEvent.class);
        when(attack.getDamage()).thenReturn(2.0);
        when(attack.getDamager()).thenReturn(projectile);
        when(attack.getEntity()).thenReturn(target);
        plugin.damage(attack);
        assertTrue(journeys.isEmpty());
    }

    @Test
    void staleChunkCallbackCannotTeleportOrCancelANewerJourney() throws Exception {
        Object stale = acceptedTpa();
        command(sender, "tpacancel");
        command(sender, "home");
        Object current = journeys.get(sender.getUniqueId());
        assertNotNull(current);
        finish(stale, target.getLocation(), null);
        assertSame(current, journeys.get(sender.getUniqueId()));
        verify(sender, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        assertEquals(0, state.cooldownRemainingSeconds(sender.getUniqueId(), TravelKind.TPA));
    }

    @Test
    void chunkFailureCancelsWithoutTeleportOrCooldown() throws Exception {
        Object journey = acceptedTpa();
        finish(journey, target.getLocation(), new IllegalStateException("test chunk failure"));
        assertTrue(journeys.isEmpty());
        verify(sender, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        assertEquals(0, state.cooldownRemainingSeconds(sender.getUniqueId(), TravelKind.TPA));
    }

    @Test
    void targetMovementDuringChunkLoadingCancels() throws Exception {
        Object journey = acceptedTpa();
        Location capturedDestination = target.getLocation().clone();
        when(target.getLocation()).thenAnswer(invocation -> capturedDestination.clone().add(1, 0, 0));
        finish(journey, capturedDestination, null);
        assertTrue(journeys.isEmpty());
        verify(sender, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    @Test
    void lostPermissionIsRecheckedBeforeTeleport() throws Exception {
        Object journey = acceptedTpa();
        when(sender.hasPermission("overworld.travel.tpa")).thenReturn(false);
        finish(journey, target.getLocation(), null);
        assertTrue(journeys.isEmpty());
        verify(sender, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
    }

    private Player player(String name, int id, int x) {
        Player player = mock(Player.class);
        UUID uuid = new UUID(0, id);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.canSee(any(Player.class))).thenReturn(true);
        when(player.getPassengers()).thenReturn(List.of());
        when(player.getLocation()).thenAnswer(invocation -> new Location(world, x, 65, 8));
        players.put(uuid, player);
        return player;
    }

    private Entry entry(Player player) {
        return new Entry(player.getUniqueId(), player.getName(), "");
    }

    private Settings defaults() {
        return new Settings(120, 5, 300, 5, 300, 10, 60, 3,
                "respawn", "", 0.1, true, true, true, 15);
    }

    private void command(Player player, String name, String... args) {
        Command command = mock(Command.class);
        when(command.getName()).thenReturn(name);
        assertTrue(plugin.onCommand(player, command, name, args));
    }

    private Object acceptedTpa() {
        state.request(sender.getUniqueId(), target.getUniqueId(), 60);
        command(target, "tpaccept", sender.getName());
        Object journey = journeys.get(sender.getUniqueId());
        assertNotNull(journey);
        return journey;
    }

    private void finish(Object journey, Location destination, Throwable error) throws Exception {
        set(journey, "loadDeadline", System.nanoTime() + 15_000_000_000L);
        invoke("finish", journey, destination, error);
    }

    private Object invoke(String name, Object... arguments) throws Exception {
        for (Method method : OverworldTravel.class.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                method.setAccessible(true);
                return method.invoke(plugin, arguments);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }
}
