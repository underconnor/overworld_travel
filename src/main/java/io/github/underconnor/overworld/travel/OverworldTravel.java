package io.github.underconnor.overworld.travel;

import io.github.underconnor.overworld.travel.identity.PlayerDirectory;
import io.github.underconnor.overworld.travel.identity.PlayerDirectory.Entry;
import io.github.underconnor.overworld.travel.state.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class OverworldTravel extends JavaPlugin implements Listener, TabCompleter {
    private static final Component PREFIX = Component.text("[오버월드] ", NamedTextColor.GOLD);
    private final TravelState state = new TravelState();
    private final Map<UUID, Journey> journeys = new HashMap<>();
    private final Map<UUID, Long> lastRequests = new HashMap<>();
    private StateStore store;
    private Settings settings;
    private PlayerDirectory directory;
    private boolean stateLoaded;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            settings = Settings.read(getConfig());
            if (!settings.spawnWorld().isEmpty() && Bukkit.getWorld(settings.spawnWorld()) == null)
                throw new IllegalArgumentException("home.spawn-world does not exist");
            store = new StateStore(getDataFolder().toPath());
            store.load(state);
            stateLoaded = true;
            directory = new PlayerDirectory(this);
            for (String name : List.of("tpa", "tpaccept", "tpadeny", "tpacancel", "home", "back")) {
                PluginCommand command = Objects.requireNonNull(getCommand(name));
                command.setExecutor(this);
                command.setTabCompleter(this);
            }
            Bukkit.getPluginManager().registerEvents(this, this);
            Bukkit.getScheduler().runTaskTimer(this, this::tick, 1, 1);
            getLogger().info("OverworldTravel enabled for Paper 26.2; Passport real-name lookup is optional.");
        } catch (Exception e) {
            getLogger().severe("Cannot enable travel: " + e.getMessage() + ". Existing state has been preserved.");
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        for (UUID id : List.copyOf(journeys.keySet())) cancel(id, "플러그인이 종료되어 이동이 취소되었습니다.");
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof TravelMenu) p.closeInventory();
        if (stateLoaded) {
            try { store.save(state); }
            catch (IOException e) { getLogger().severe("Could not save travel state: " + e.getMessage()); }
        }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("게임에 접속한 플레이어만 사용할 수 있습니다."); return true;
        }
        if (!isEnabled() || !stateLoaded) { tell(player, "이동 기능을 사용할 수 없습니다."); return true; }
        String query = String.join(" ", args).strip();
        switch (command.getName()) {
            case "tpa" -> { if (query.isEmpty()) openMenu(player, 0); else requestByName(player, query); }
            case "tpaccept" -> answer(player, query, true);
            case "tpadeny" -> answer(player, query, false);
            case "tpacancel" -> {
                boolean pending = journeys.containsKey(player.getUniqueId());
                cancel(player.getUniqueId(), "이동을 취소했습니다.");
                Optional<TravelState.Request> request = state.outgoing(player.getUniqueId());
                request.ifPresent(r -> {
                    state.removeRequest(r.sender(), r.target());
                    tell(Bukkit.getPlayer(r.target()), player.getName() + "님이 이동 요청을 취소했습니다.");
                });
                if (request.isPresent()) tell(player, "보낸 이동 요청을 취소했습니다.");
                else if (!pending) tell(player, "취소할 요청이나 이동 대기가 없습니다.");
            }
            case "home" -> { if (query.isEmpty()) start(player, TravelKind.HOME, null); else tell(player, "사용법: /home"); }
            case "back" -> { if (query.isEmpty()) start(player, TravelKind.BACK, null); else tell(player, "사용법: /back"); }
            default -> { return false; }
        }
        return true;
    }
    private void requestByName(Player player, String query) {
        List<Entry> matches = directory.resolve(player, query);
        if (matches.isEmpty()) { tell(player, "접속 중인 플레이어를 찾을 수 없습니다. 닉네임 또는 Passport 실명을 입력하세요."); return; }
        if (matches.size() > 1) {
            tell(player, "같은 이름이 여러 명입니다. 닉네임으로 선택하세요: " + String.join(", ",matches.stream().map(Entry::minecraftName).toList())); return;
        }
        request(player, matches.getFirst().id());
    }
    private void request(Player player, UUID targetId) {
        if (!player.hasPermission("overworld.travel.tpa") || !ready(player, TravelKind.TPA)) return;
        if (targetId.equals(player.getUniqueId())) { tell(player, "자신에게 요청할 수 없습니다."); return; }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || target.isDead() || !target.canSee(player) || directory.find(player, targetId).isEmpty()) {
            tell(player, "현재 이 플레이어에게 요청할 수 없습니다."); return;
        }
        if (!target.hasPermission("overworld.travel.tpa")) { tell(player, "상대가 이동 요청을 사용할 수 없습니다."); return; }
        long now = System.nanoTime();
        Long last = lastRequests.get(player.getUniqueId());
        if (last != null && now-last < settings.requestThrottle()*1_000_000_000L) {
            tell(player, "잠시 기다린 뒤 다시 요청하세요."); return;
        }
        lastRequests.put(player.getUniqueId(), now);
        state.outgoing(player.getUniqueId()).ifPresent(old ->
            tell(Bukkit.getPlayer(old.target()), player.getName() + "님의 이전 이동 요청이 취소되었습니다."));
        state.request(player.getUniqueId(), targetId, settings.requestExpiry());
        tell(player, label(player, targetId) + "님에게 이동을 요청했습니다. " + settings.requestExpiry() + "초 동안 유효합니다.");
        tell(target, label(target, player.getUniqueId()) + "님이 내 위치로 이동을 요청했습니다.");
        target.sendMessage(PREFIX.append(Component.text("[수락]", NamedTextColor.GREEN)
            .clickEvent(ClickEvent.runCommand("/overworldtravel:tpaccept " + player.getName())))
            .append(Component.text("  "))
            .append(Component.text("[거절]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/overworldtravel:tpadeny " + player.getName()))));
    }
    private void answer(Player target, String query, boolean accept) {
        List<TravelState.Request> requests = state.incoming(target.getUniqueId());
        if (!query.isEmpty()) {
            Set<UUID> matches = new HashSet<>();
            directory.resolve(target, query).forEach(e -> matches.add(e.id()));
            requests = requests.stream().filter(r -> matches.contains(r.sender())).toList();
        }
        if (requests.isEmpty()) { tell(target, "해당하는 이동 요청이 없거나 만료되었습니다."); return; }
        if (requests.size() > 1) {
            tell(target, "요청이 여러 개입니다. /" + (accept ? "tpaccept" : "tpadeny") + " 닉네임 으로 선택하세요.");
            for (TravelState.Request request : requests) tell(target, "요청자: " + label(target, request.sender()));
            return;
        }
        TravelState.Request request = requests.getFirst();
        Player requester = Bukkit.getPlayer(request.sender());
        if (!accept) {
            state.removeRequest(request.sender(), request.target());
            tell(target, "이동 요청을 거절했습니다."); tell(requester, "상대가 이동 요청을 거절했습니다."); return;
        }
        if (requester == null || directory.find(target, request.sender()).isEmpty() || !requester.canSee(target)
                || target.isDead() || target.isInsideVehicle() || !ready(requester, TravelKind.TPA)) {
            tell(target, "지금은 요청자를 이동시킬 수 없습니다."); return;
        }
        if (state.removeRequest(request.sender(), request.target()).isEmpty()) {
            tell(target, "이동 요청이 만료되었습니다."); return;
        }
        tell(target, "이동 요청을 수락했습니다.");
        start(requester, TravelKind.TPA, target.getUniqueId());
    }
    private boolean ready(Player p, TravelKind kind) {
        if (!p.isOnline() || p.isDead() || p.isInsideVehicle() || !p.getPassengers().isEmpty() || p.isSleeping() || p.isGliding()) {
            tell(p, "현재 상태에서는 이동할 수 없습니다. 탈것에서 내리고 안전한 곳에 서 주세요."); return false;
        }
        if (!p.hasPermission(permission(kind))) { tell(p, "이동 권한이 없습니다."); return false; }
        if (journeys.containsKey(p.getUniqueId())) { tell(p, "이미 이동 대기 중입니다. /tpacancel 로 취소할 수 있습니다."); return false; }
        long remaining = state.cooldownRemainingSeconds(p.getUniqueId(), kind);
        if (remaining > 0) { tell(p, "/" + kind.name().toLowerCase(Locale.ROOT) + " 재사용까지 " + remaining + "초 남았습니다."); return false; }
        return true;
    }
    private void start(Player p, TravelKind kind, UUID target) {
        if (!ready(p, kind)) return;
        if (kind == TravelKind.BACK && state.death(p.getUniqueId()).isEmpty()) { tell(p, "기록된 사망 지점이 없습니다."); return; }
        Journey journey = new Journey(p, kind, target, settings.warmup(kind));
        journeys.put(p.getUniqueId(), journey);
        tell(p, settings.warmup(kind) + "초 후 이동합니다. 움직이거나 전투하면 취소됩니다.");
    }
    private void tick() {
        for (TravelState.Request request : state.expireRequests()) {
            tell(Bukkit.getPlayer(request.sender()), "보낸 이동 요청이 만료되었습니다.");
            tell(Bukkit.getPlayer(request.target()), "받은 이동 요청이 만료되었습니다.");
        }
        long now = System.nanoTime();
        for (Journey journey : List.copyOf(journeys.values())) {
            if (!valid(journey)) { cancel(journey.player, "플레이어 상태가 바뀌어 이동이 취소되었습니다."); continue; }
            if (journey.loading) {
                if (now >= journey.loadDeadline) cancel(journey.player, "목적지를 불러오지 못해 이동이 취소되었습니다.");
                continue;
            }
            long seconds = Math.max(0, (journey.deadline - now + 999_999_999L) / 1_000_000_000L);
            if (seconds == 0) loadDestination(journey);
            else if (seconds != journey.shownSeconds) {
                journey.shownSeconds = seconds;
                Bukkit.getPlayer(journey.player).sendActionBar(Component.text("이동까지 " + seconds + "초 · 움직이면 취소", NamedTextColor.YELLOW));
            }
        }
    }
    private boolean valid(Journey journey) {
        Player player = Bukkit.getPlayer(journey.player);
        if (player == null || !player.isOnline() || player.isDead() || player.isInsideVehicle()
            || !player.getPassengers().isEmpty() || player.isSleeping() || player.isGliding()
            || !player.hasPermission(permission(journey.kind)) || moved(journey.origin, player.getLocation())) return false;
        if (journey.target != null) {
            Player target = Bukkit.getPlayer(journey.target);
            return target != null && target.isOnline() && !target.isDead() && !target.isInsideVehicle()
                && player.canSee(target) && target.canSee(player) && target.hasPermission("overworld.travel.tpa");
        }
        return true;
    }
    private Location destination(Journey journey) {
        Player player = Bukkit.getPlayer(journey.player);
        if (journey.kind == TravelKind.TPA) return Bukkit.getPlayer(journey.target).getLocation().clone();
        if (journey.kind == TravelKind.HOME) {
            if (settings.homeDestination().equals("respawn")) {
                // Preload the stored point before asking Paper to validate the bed/anchor.
                Location respawn = player.getRespawnLocation(false);
                if (respawn != null) return respawn;
            }
            return worldSpawn();
        }
        return state.death(journey.player).map(point -> {
            World world = Bukkit.getWorld(point.worldId());
            return world == null ? null : new Location(world,point.x(),point.y(),point.z(),point.yaw(),point.pitch());
        }).orElse(null);
    }
    private Location worldSpawn() {
        World world = settings.spawnWorld().isEmpty() ? Bukkit.getWorlds().getFirst() : Bukkit.getWorld(settings.spawnWorld());
        return world == null ? null : world.getSpawnLocation();
    }
    private void loadDestination(Journey journey) { loadDestination(journey, destination(journey)); }
    private void loadDestination(Journey journey, Location destination) {
        if (destination == null || !DestinationSafety.withinWorld(destination)) {
            cancel(journey.player, "유효한 목적지를 찾을 수 없습니다. 사망 월드와 스폰 설정을 확인하세요."); return;
        }
        journey.loading = true;
        journey.loadDeadline = System.nanoTime() + settings.loadTimeout()*1_000_000_000L;
        List<CompletableFuture<Chunk>> chunks = new ArrayList<>();
        int cx = destination.getBlockX() >> 4, cz = destination.getBlockZ() >> 4;
        // Load neighbours for collision checks crossing a chunk border.
        for (int x=cx-1; x<=cx+1; x++) for (int z=cz-1; z<=cz+1; z++)
            chunks.add(destination.getWorld().getChunkAtAsync(x, z, true));
        CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            if (!isEnabled()) return;
            Bukkit.getScheduler().runTask(this, () -> finish(journey, destination, error));
        });
    }
    private void finish(Journey journey, Location destination, Throwable error) {
        if (journeys.get(journey.player) != journey) return;
        if (error != null || System.nanoTime() >= journey.loadDeadline || !valid(journey)) {
            cancel(journey.player, "이동 조건이 바뀌었거나 목적지를 불러오지 못했습니다."); return;
        }
        if (journey.kind == TravelKind.HOME) {
            Player player = Bukkit.getPlayer(journey.player);
            Location stored = settings.homeDestination().equals("respawn") ? player.getRespawnLocation(false) : null;
            if (stored != null && !stored.getWorld().isChunkLoaded(stored.getBlockX() >> 4, stored.getBlockZ() >> 4)) {
                if (++journey.reroutes > 3) cancel(journey.player, "리스폰 지점이 바뀌어 이동을 취소했습니다.");
                else loadDestination(journey, stored);
                return;
            }
            Location resolved = settings.homeDestination().equals("respawn") ? player.getRespawnLocation() : null;
            if (resolved == null) resolved = worldSpawn();
            if (resolved == null || !resolved.getWorld().equals(destination.getWorld())
                    || resolved.distanceSquared(destination) > 0.000001) {
                if (++journey.reroutes > 3) cancel(journey.player, "리스폰 지점을 확인할 수 없어 이동을 취소했습니다.");
                else loadDestination(journey, resolved);
                return;
            }
        }
        if (journey.target != null) {
            Location current = Bukkit.getPlayer(journey.target).getLocation();
            if (!current.getWorld().equals(destination.getWorld()) || current.distanceSquared(destination) > 0.01) {
                cancel(journey.player, "목적지를 준비하는 동안 상대가 움직여 이동이 취소되었습니다. 다시 요청하세요."); return;
            }
        }
        if (!DestinationSafety.withinWorld(destination) || settings.checkDestination() && !DestinationSafety.safe(destination)) {
            cancel(journey.player, "목적지가 막혀 있거나 위험해서 이동을 취소했습니다."); return;
        }
        Player player = Bukkit.getPlayer(journey.player);
        journey.executing = true;
        boolean success;
        try { success = player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN); }
        catch (RuntimeException e) { success = false; getLogger().warning("Teleport failed: " + e.getClass().getSimpleName()); }
        journey.executing = false;
        journeys.remove(journey.player, journey);
        player.sendActionBar(Component.empty());
        if (success) {
            state.setCooldown(journey.player, journey.kind, settings.cooldown(journey.kind));
            persist();
            tell(player, "이동했습니다. 재사용 대기 시간은 " + settings.cooldown(journey.kind) + "초입니다.");
        } else tell(player, "이동이 취소되었습니다. 재사용 대기 시간은 적용되지 않습니다.");
    }
    private void persist() {
        try { store.save(state); }
        catch (IOException e) {
            getLogger().severe("Travel state could not be saved; disabling to protect saved deaths and cooldowns: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    private boolean moved(Location origin, Location current) {
        return !Objects.equals(origin.getWorld(), current.getWorld())
            || origin.distanceSquared(current) > settings.movementTolerance()*settings.movementTolerance();
    }
    private void cancel(UUID id, String message) {
        if (journeys.remove(id) != null) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) { player.sendActionBar(Component.empty()); tell(player, message); }
        }
    }
    private void clearPlayer(UUID id, String message) {
        cancel(id, message);
        for (Journey journey : List.copyOf(journeys.values())) if (id.equals(journey.target)) cancel(journey.player, message);
        for (TravelState.Request request : state.removePlayerRequests(id)) {
            UUID other = request.sender().equals(id) ? request.target() : request.sender();
            tell(Bukkit.getPlayer(other), "상대의 접속/생존 상태가 바뀌어 이동 요청이 취소되었습니다.");
        }
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void move(PlayerMoveEvent event) {
        Journey journey = journeys.get(event.getPlayer().getUniqueId());
        if (journey != null && !journey.executing && moved(journey.origin, event.getTo())) cancel(journey.player, "움직여서 이동이 취소되었습니다.");
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void teleport(PlayerTeleportEvent event) {
        Journey journey = journeys.get(event.getPlayer().getUniqueId());
        if (journey != null && !journey.executing) cancel(journey.player, "다른 이동으로 인해 대기가 취소되었습니다.");
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true)
    public void damage(EntityDamageEvent event) {
        // Absorption or a shield must not let a player fight during the warmup.
        if (event.getDamage() <= 0) return;
        if (settings.cancelDamage() && event.getEntity() instanceof Player player) cancel(player.getUniqueId(), "피해를 받아 이동이 취소되었습니다.");
        if (settings.cancelAttack() && event instanceof EntityDamageByEntityEvent attack) {
            Object attacker = event.getDamageSource() == null ? null : event.getDamageSource().getCausingEntity();
            if (!(attacker instanceof Player)) attacker = attack.getDamager();
            if (attacker instanceof Projectile projectile) attacker = projectile.getShooter();
            if (attacker instanceof Player player) cancel(player.getUniqueId(), "공격해서 이동이 취소되었습니다.");
        }
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void death(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Location location = player.getLocation();
        state.setDeath(player.getUniqueId(), new DeathPoint(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()));
        clearPlayer(player.getUniqueId(), "사망으로 인해 이동이 취소되었습니다.");
        persist();
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer().getUniqueId(), "상대가 접속을 종료해 이동이 취소되었습니다.");
        lastRequests.remove(event.getPlayer().getUniqueId());
    }
    private void openMenu(Player player, int page) {
        List<Entry> entries = directory.online(player).stream().filter(e -> !e.id().equals(player.getUniqueId())).toList();
        int pages = Math.max(1, (entries.size()+44)/45);
        page = Math.max(0, Math.min(page, pages-1));
        TravelMenu menu = new TravelMenu(player.getUniqueId(), page);
        menu.inventory = Bukkit.createInventory(menu, 54, Component.text("이동 요청 · " + (page+1) + "/" + pages));
        for (int i=page*45; i<Math.min(entries.size(), (page+1)*45); i++) {
            Entry entry = entries.get(i); int slot = i-page*45;
            menu.targets.put(slot,entry.id());
            menu.inventory.setItem(slot, item(Material.NAME_TAG, entryLabel(entry), "클릭하여 이동 요청 보내기"));
        }
        if (page > 0) menu.inventory.setItem(45,item(Material.ARROW,"이전 페이지", ""));
        if (page+1 < pages) menu.inventory.setItem(53,item(Material.ARROW,"다음 페이지", ""));
        menu.inventory.setItem(49,item(Material.CLOCK,"닉네임 · Passport 실명", "수락 후 " + settings.tpaWarmup() + "초 대기 / 쿨타임 " + settings.tpaCooldown() + "초"));
        player.openInventory(menu.inventory);
    }
    private ItemStack item(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(name, NamedTextColor.WHITE));
            meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
        });
        return item;
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TravelMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !menu.owner.equals(player.getUniqueId())) return;
        int slot = event.getRawSlot(); UUID target = menu.targets.get(slot);
        if (target != null) Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) { player.closeInventory(); request(player,target); }
        });
        else if ((slot == 45 || slot == 53) && event.getCurrentItem() != null)
            Bukkit.getScheduler().runTask(this, () -> { if (player.isOnline()) openMenu(player, menu.page + (slot == 45 ? -1 : 1)); });
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TravelMenu) event.setCancelled(true);
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!isEnabled() || !stateLoaded || directory == null) return List.of();
        if (!(sender instanceof Player player) || args.length == 0 || !List.of("tpa","tpaccept","tpadeny").contains(command.getName())) return List.of();
        String query = String.join(" ",args).toLowerCase(Locale.ROOT);
        Set<UUID> incoming = new HashSet<>();
        if (!command.getName().equals("tpa")) state.incoming(player.getUniqueId()).forEach(r -> incoming.add(r.sender()));
        Set<String> names = new TreeSet<>();
        for (Entry entry : directory.online(player)) {
            if (entry.id().equals(player.getUniqueId()) || !command.getName().equals("tpa") && !incoming.contains(entry.id())) continue;
            if (entry.minecraftName().toLowerCase(Locale.ROOT).startsWith(query)) names.add(entry.minecraftName());
            if (!entry.realName().isBlank() && entry.realName().toLowerCase(Locale.ROOT).startsWith(query)) names.add(entry.realName());
        }
        return List.copyOf(names);
    }
    private String label(Player viewer, UUID id) { return directory.find(viewer,id).map(this::entryLabel).orElse("플레이어"); }
    private String entryLabel(Entry entry) { return entry.realName().isBlank() ? entry.minecraftName() : entry.realName() + " (" + entry.minecraftName() + ")"; }
    private static String permission(TravelKind kind) { return "overworld.travel." + kind.name().toLowerCase(Locale.ROOT); }
    private static void tell(Player player, String message) {
        if (player != null && player.isOnline()) player.sendMessage(PREFIX.append(Component.text(message,NamedTextColor.WHITE)));
    }
    private static final class Journey {
        final UUID player, target;
        final TravelKind kind;
        final Location origin;
        final long deadline;
        long shownSeconds = -1, loadDeadline;
        boolean loading, executing;
        int reroutes;
        Journey(Player player, TravelKind kind, UUID target, int seconds) {
            this.player=player.getUniqueId(); this.kind=kind; this.target=target;
            origin=player.getLocation().clone(); deadline=System.nanoTime() + seconds*1_000_000_000L;
        }
    }
    private static final class TravelMenu implements InventoryHolder {
        final UUID owner;
        final int page;
        final Map<Integer,UUID> targets = new HashMap<>();
        Inventory inventory;
        TravelMenu(UUID owner, int page) { this.owner=owner; this.page=page; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
