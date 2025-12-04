package peyaj;

import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class RaceArena {

    public enum RaceType { DEFAULT, LAP }
    public enum RaceState { LOBBY, STARTING, ACTIVE }

    private final String name;
    private final IceBoatRacing plugin;

    private RaceType type = RaceType.DEFAULT;
    private int totalLaps = 1;
    private RaceState state = RaceState.LOBBY;

    // Locations
    private final List<Location> spawns = new ArrayList<>();
    private final List<Location> checkpoints = new ArrayList<>();
    private Location lobby;
    private Location mainLobby;
    private Location leaderboardLocation;

    private Location finishPos1, finishPos2;
    private BoundingBox finishBox;
    private Location finishCenter;

    // Settings
    public int minPlayers = 2;
    public int autoStartDelay = 30;
    public int voidY = -64;

    // Runtime Data
    private final Map<UUID, Integer> playerCheckpoints = new HashMap<>();
    private final Map<UUID, Integer> playerLaps = new HashMap<>();
    private final Map<UUID, Long> startTimes = new HashMap<>();
    private final Map<UUID, String> finishTimes = new HashMap<>();
    private final Map<UUID, Boat> playerBoats = new HashMap<>();
    private final Set<UUID> players = new HashSet<>();
    private final Set<UUID> spectators = new HashSet<>();
    private final List<UUID> finishOrder = new ArrayList<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final List<Location> glassBlocks = new ArrayList<>();

    // Leaderboard Data
    public final Map<UUID, Long> bestTimes = new HashMap<>();

    // --- GHOST & SPLIT TIMING DATA ---
    private final Map<UUID, IceBoatRacing.GhostData> currentRecordings = new HashMap<>();
    private IceBoatRacing.GhostData bestGhost = null;
    private int ghostPlaybackTick = 0;
    private Boat visualGhostBoat = null;

    private final Map<UUID, Map<Integer, Long>> checkpointTimestamps = new HashMap<>();

    // --- UTILS ---
    private int tickCounter = 0;

    private BukkitTask autoStartTask = null;
    private int lobbyCountdown = -1;
    private int raceStartCountdown = -1;
    private BukkitTask musicTask = null;

    public RaceArena(String name, IceBoatRacing plugin) {
        this.name = name;
        this.plugin = plugin;
    }

    // --- GETTERS & SETTERS ---
    public String getName() { return name; }
    public RaceType getType() { return type; }
    public void setType(RaceType type) { this.type = type; }
    public int getTotalLaps() { return totalLaps; }
    public void setTotalLaps(int laps) { this.totalLaps = laps; }
    public Location getLobby() { return lobby; }
    public void setLobby(Location loc) { this.lobby = loc; }
    public Location getMainLobby() { return mainLobby; }
    public void setMainLobby(Location loc) { this.mainLobby = loc; }
    public List<Location> getSpawns() { return spawns; }
    public List<Location> getCheckpoints() { return checkpoints; }
    public Location getFinishPos1() { return finishPos1; }
    public Location getFinishPos2() { return finishPos2; }
    public BoundingBox getFinishBox() { return finishBox; }
    public RaceState getState() { return state; }
    public int getPlayerCount() { return players.size(); }
    public boolean isTimeTrial() { return isTimeTrialMode; }

    public Location getLeaderboardLocation() { return leaderboardLocation; }
    public void setLeaderboardLocation(Location loc) {
        this.leaderboardLocation = loc;
        updateLeaderboardHologram();
    }

    public boolean isSpectator(UUID uuid) {
        return spectators.contains(uuid);
    }

    public void addSpawn(Location loc) { spawns.add(loc); }
    public void addCheckpoint(Location loc) { checkpoints.add(loc); }

    public boolean removeNodeAtBlock(List<Location> list, Location clickedBlockLoc) {
        Iterator<Location> it = list.iterator();
        while(it.hasNext()) {
            Location nodeLoc = it.next();
            if (nodeLoc.getWorld().equals(clickedBlockLoc.getWorld()) &&
                    nodeLoc.getBlockX() == clickedBlockLoc.getBlockX() &&
                    nodeLoc.getBlockZ() == clickedBlockLoc.getBlockZ() &&
                    (nodeLoc.getBlockY() == clickedBlockLoc.getBlockY() || nodeLoc.getBlockY() == clickedBlockLoc.getBlockY() + 1)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public void setFinishLine(Location p1, Location p2) {
        this.finishPos1 = p1;
        this.finishPos2 = p2;
        recalculateFinishBox();
    }

    public void recalculateFinishBox() {
        if (finishPos1 != null && finishPos2 != null && finishPos1.getWorld() != null && finishPos1.getWorld().equals(finishPos2.getWorld())) {
            finishBox = BoundingBox.of(finishPos1, finishPos2).expand(0, 10.0, 0);
            finishCenter = finishBox.getCenter().toLocation(finishPos1.getWorld());
        }
    }

    // --- HOLOGRAMS ---
    public void updateLeaderboardHologram() {
        if (leaderboardLocation == null) return;
        try {
            String holoName = "race_lb_" + name;
            Hologram holo = DHAPI.getHologram(holoName);
            if (holo == null) {
                holo = DHAPI.createHologram(holoName, leaderboardLocation);
            } else {
                DHAPI.moveHologram(holo, leaderboardLocation);
            }
            List<String> lines = new ArrayList<>();
            lines.add("&b&l❄ " + name.toUpperCase() + " LEADERBOARD ❄");
            lines.add("&7------------------------");
            List<Map.Entry<UUID, Long>> sorted = new ArrayList<>(bestTimes.entrySet());
            sorted.sort(Map.Entry.comparingByValue());
            int limit = Math.min(sorted.size(), 10);
            for (int i = 0; i < limit; i++) {
                UUID uuid = sorted.get(i).getKey();
                long time = sorted.get(i).getValue();
                OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                String pName = (op.getName() != null) ? op.getName() : "Unknown";
                String color = (i == 0) ? "&e" : (i == 1) ? "&f" : (i == 2) ? "&6" : "&7";
                lines.add(color + (i + 1) + ". &f" + pName + " &7- &b" + Utils.formatTime(time));
            }
            if (limit == 0) lines.add("&7No records yet!");
            lines.add("&7------------------------");
            DHAPI.setHologramLines(holo, lines);
        } catch (NoClassDefFoundError e) {}
    }

    // --- PLAYER MANAGEMENT ---
    public void addPlayer(Player p) { addPlayer(p, false); }

    private boolean isTimeTrialMode = false;

    public void addPlayer(Player p, boolean timeTrial) {
        if (state != RaceState.LOBBY && !timeTrial) {
            addSpectator(p);
            return;
        }
        if (timeTrial && state == RaceState.ACTIVE) {
            p.sendMessage(plugin.getMessage("race-already-active"));
            return;
        }

        if (timeTrial && !players.isEmpty()) {
            p.sendMessage(Component.text("Lobby is not empty! Joining match instead of Time Trial.", NamedTextColor.YELLOW));
            timeTrial = false;
        }

        players.add(p.getUniqueId());
        if (lobby != null && lobby.getWorld() != null) {
            p.teleport(lobby);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        }
        giveLobbyItems(p, timeTrial);
        playerCheckpoints.put(p.getUniqueId(), 0);
        playerLaps.put(p.getUniqueId(), 1);
        p.sendMessage(plugin.getMessage("arena-joined").replaceText(b -> b.matchLiteral("{arena}").replacement(name)));

        if (timeTrial) {
            startRace(true);
        } else {
            checkAutoStart();
        }
        updateLobbyScoreboard();
    }

    public void addSpectator(Player p) {
        spectators.add(p.getUniqueId());
        p.setGameMode(GameMode.SPECTATOR);
        if (!spawns.isEmpty()) p.teleport(spawns.get(0));
        else if (lobby != null) p.teleport(lobby);
        p.sendMessage(plugin.getMessage("arena-spectating").replaceText(b -> b.matchLiteral("{arena}").replacement(name)));
        p.showTitle(Title.title(Component.text("SPECTATING", NamedTextColor.GREEN), Component.text("You are watching " + name, NamedTextColor.AQUA)));
        p.playSound(p.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 1f, 1f);
        setupRaceScoreboard(p);
        plugin.setPlayerArena(p.getUniqueId(), name);
    }

    private void giveLobbyItems(Player p, boolean isTimeTrial) {
        org.bukkit.inventory.ItemStack compass = new org.bukkit.inventory.ItemStack(Material.COMPASS);
        org.bukkit.inventory.meta.ItemMeta meta = compass.getItemMeta();
        meta.displayName(Component.text("Race Menu", NamedTextColor.AQUA));
        meta.lore(List.of(Component.text("Right Click to open", NamedTextColor.GRAY)));
        compass.setItemMeta(meta);
        p.getInventory().setItem(4, compass);

        if (isTimeTrial) {
            org.bukkit.inventory.ItemStack reset = new org.bukkit.inventory.ItemStack(Material.RED_DYE);
            org.bukkit.inventory.meta.ItemMeta rMeta = reset.getItemMeta();
            rMeta.displayName(Component.text("Reset Run", NamedTextColor.RED));
            rMeta.lore(List.of(Component.text("Right Click to restart", NamedTextColor.GRAY)));
            reset.setItemMeta(rMeta);
            p.getInventory().setItem(8, reset);
        }
    }

    public void resetTimeTrial(Player p) {
        if (!isTimeTrialMode || !players.contains(p.getUniqueId())) return;
        p.sendMessage(Component.text("↺ Run Reset!", NamedTextColor.YELLOW));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 2f);

        if (playerBoats.containsKey(p.getUniqueId())) {
            Boat b = playerBoats.remove(p.getUniqueId());
            if (b != null) b.remove();
        }

        if (visualGhostBoat != null) {
            visualGhostBoat.remove();
            visualGhostBoat = null;
        }
        ghostPlaybackTick = 0;

        currentRecordings.put(p.getUniqueId(), new IceBoatRacing.GhostData(p.getName(), 0));
        checkpointTimestamps.put(p.getUniqueId(), new HashMap<>());
        playerCheckpoints.put(p.getUniqueId(), 0);
        playerLaps.put(p.getUniqueId(), 1);
        finishOrder.remove(p.getUniqueId());
        finishTimes.remove(p.getUniqueId());

        startRace(true);
    }

    public void removePlayer(Player p) {
        if (spectators.contains(p.getUniqueId())) {
            spectators.remove(p.getUniqueId());
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().clear();
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            if (mainLobby != null && mainLobby.getWorld() != null) p.teleport(mainLobby);
            else if (p.getWorld() != null) p.teleport(p.getWorld().getSpawnLocation());
            plugin.removePlayerFromArenaMap(p.getUniqueId());
            p.sendMessage(plugin.getMessage("spectator-left"));
            return;
        }
        players.remove(p.getUniqueId());
        if (playerBoats.containsKey(p.getUniqueId())) {
            Boat b = playerBoats.remove(p.getUniqueId());
            if (b != null) b.remove();
        }
        currentRecordings.remove(p.getUniqueId());
        checkpointTimestamps.remove(p.getUniqueId());
        p.getInventory().clear();
        stopMusic(p);
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());

        if (players.isEmpty()) {
            if (state != RaceState.LOBBY) stopRace();
            cancelAutoStart();
        } else if (state == RaceState.LOBBY) {
            checkAutoStart();
        } else if (state == RaceState.ACTIVE) {
            checkFinishCondition();
        }
        updateLobbyScoreboard();
    }

    public void checkFinishCondition() {
        if (state != RaceState.ACTIVE) return;
        boolean allFinished = true;
        for (UUID uuid : players) {
            if (!finishOrder.contains(uuid)) {
                allFinished = false;
                break;
            }
        }
        if (players.isEmpty() || allFinished) {
            Bukkit.broadcast(plugin.getMessage("race-ended"));
            new BukkitRunnable() { @Override public void run() { stopRace(); } }.runTaskLater(plugin, 100L);
        }
    }

    // --- GAME LOOP ---
    public void startRace() { startRace(false); }

    public void startRace(boolean isTimeTrialSession) {
        if (spawns.isEmpty()) return;
        cancelAutoStart();

        this.isTimeTrialMode = isTimeTrialSession;

        state = RaceState.STARTING;
        removeCages();
        finishOrder.clear();
        finishTimes.clear();
        currentRecordings.clear();
        checkpointTimestamps.clear();
        ghostPlaybackTick = 0;
        tickCounter = 0;

        if (visualGhostBoat != null) { visualGhostBoat.remove(); visualGhostBoat = null; }

        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.getInventory().clear();
        }

        int spawnIndex = 0;
        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;

            Location spawn = spawns.get(spawnIndex % spawns.size());
            if (spawn.getWorld() == null) continue;

            spawnIndex++;
            p.teleport(spawn);
            Boat boat = (Boat) p.getWorld().spawnEntity(spawn, EntityType.BOAT);
            Utils.assignRandomBoatType(boat);
            boat.addPassenger(p);
            boat.setInvulnerable(true);
            playerBoats.put(uuid, boat);
            currentRecordings.put(uuid, new IceBoatRacing.GhostData(p.getName(), 0));
            checkpointTimestamps.put(uuid, new HashMap<>());
            createCage(spawn, uuid);
            playerCheckpoints.put(uuid, 0);
            playerLaps.put(uuid, 1);
            setupRaceScoreboard(p);
        }
        syncGhostMode();

        raceStartCountdown = 5;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (state != RaceState.STARTING) { removeCages(); this.cancel(); return; }
                if (raceStartCountdown == 3) startMusic();
                if (raceStartCountdown > 0) {
                    Title title = Title.title(Component.text(raceStartCountdown, NamedTextColor.RED), Component.empty());
                    for (UUID uuid : players) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) {
                            p.showTitle(title);
                            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.5f + ((5 - raceStartCountdown) * 0.3f));
                            Boat boat = playerBoats.get(uuid);
                            if (boat != null) boat.setVelocity(new Vector(0,0,0));
                        }
                    }
                    raceStartCountdown--;
                } else {
                    removeCages();
                    state = RaceState.ACTIVE;
                    long now = System.currentTimeMillis();
                    for (UUID uuid : players) {
                        startTimes.put(uuid, now);
                        Player p = Bukkit.getPlayer(uuid);
                        if(p!=null) {
                            Component goTitle = LegacyComponentSerializer.legacyAmpersand().deserialize(plugin.getRawMessage("race-started"));
                            p.showTitle(Title.title(goTitle, Component.empty()));
                            p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1f);
                            lastLocations.put(uuid, p.getLocation());
                        }
                    }
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    public void stopRace() {
        cancelAutoStart();
        stopAllMusic();
        if (!finishOrder.isEmpty() && !plugin.discordWebhookUrl.isEmpty()) { sendDiscordResults(); }

        state = RaceState.LOBBY;
        isTimeTrialMode = false;

        removeCages();
        for (Boat b : playerBoats.values()) b.remove();
        playerBoats.clear();
        finishOrder.clear();
        finishTimes.clear();
        currentRecordings.clear();
        checkpointTimestamps.clear();

        // Clean up fake entities
        for (UUID uuid : players) {
            if (ghostEntityIds.containsKey(uuid)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) PacketUtils.destroyFakeEntity(p, ghostEntityIds.get(uuid));
            }
        }
        ghostEntityIds.clear();

        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                p.setGameMode(GameMode.SURVIVAL);
                p.getInventory().clear();
                if (mainLobby != null && mainLobby.getWorld() != null) p.teleport(mainLobby);
                else if (p.getWorld() != null) p.teleport(p.getWorld().getSpawnLocation());
                plugin.removePlayerFromArenaMap(uuid);
            }
        }
        players.clear();
        for (UUID uuid : spectators) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                p.setGameMode(GameMode.SURVIVAL);
                p.getInventory().clear();
                if (mainLobby != null && mainLobby.getWorld() != null) p.teleport(mainLobby);
                else if (p.getWorld() != null) p.teleport(p.getWorld().getSpawnLocation());
                plugin.removePlayerFromArenaMap(uuid);
                p.sendMessage(plugin.getMessage("spectator-left"));
            }
        }
        spectators.clear();
        updateLeaderboardHologram();
    }

    private final Map<UUID, Integer> ghostEntityIds = new HashMap<>();

    public void tick() {
        if (state == RaceState.LOBBY) return;
        if (state == RaceState.ACTIVE) {
            tickCounter++;

            // GHOST PLAYBACK (Using Packets)
            if (isTimeTrialMode && bestGhost != null && ghostPlaybackTick < bestGhost.points.size()) {
                Location ghostLoc = bestGhost.points.get(ghostPlaybackTick);
                if (ghostLoc != null && ghostLoc.getWorld() != null) {
                    ghostLoc.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, ghostLoc.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
                    for (UUID uuid : players) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) {
                            if (!ghostEntityIds.containsKey(uuid)) {
                                int id = PacketUtils.spawnFakeBoat(p, ghostLoc);
                                ghostEntityIds.put(uuid, id);
                            } else {
                                PacketUtils.moveFakeBoat(p, ghostEntityIds.get(uuid), ghostLoc);
                            }
                        }
                    }
                }
            } else if (ghostPlaybackTick >= (bestGhost != null ? bestGhost.points.size() : 0)) {
                for (UUID uuid : ghostEntityIds.keySet()) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) PacketUtils.destroyFakeEntity(p, ghostEntityIds.get(uuid));
                }
                ghostEntityIds.clear();
            }
            ghostPlaybackTick++;

            List<UUID> ranking = calculateRankings();

            for (UUID uuid : players) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) continue;

                if (!finishOrder.contains(uuid) && playerBoats.containsKey(uuid)) {
                    Location currentLoc = p.getLocation();

                    if (currentLoc.getY() < voidY) {
                        respawnPlayer(p);
                        p.sendMessage(Component.text("§cYou fell! Respawning..."));
                        continue;
                    }

                    if (currentRecordings.containsKey(uuid)) {
                        currentRecordings.get(uuid).points.add(currentLoc);
                    }
                    Location lastLoc = lastLocations.getOrDefault(uuid, currentLoc);
                    double speedKmH = (lastLoc.getWorld() == currentLoc.getWorld()) ? currentLoc.distance(lastLoc) * 72.0 : 0;
                    lastLocations.put(uuid, currentLoc);
                    int safety = 0;
                    boolean keepChecking = true;
                    while(keepChecking && safety < 3) {
                        keepChecking = checkObjectivesAlongPath(p, uuid, lastLoc, currentLoc);
                        safety++;
                    }
                    Utils.spawnTrailParticles(p, playerBoats.get(uuid), plugin.getPlayerTrailPreference(uuid));
                    long timeMs = System.currentTimeMillis() - startTimes.getOrDefault(uuid, System.currentTimeMillis());
                    String timeStr = Utils.formatTime(timeMs);
                    int displayLap = (type == RaceType.LAP) ? playerLaps.getOrDefault(uuid, 1) : 1;
                    int maxLap = (type == RaceType.LAP) ? totalLaps : 1;
                    int cp = playerCheckpoints.getOrDefault(uuid, 0);
                    updateRaceScoreboard(p, timeStr, speedKmH, cp, checkpoints.size(), displayLap, maxLap, ranking);

                    if (tickCounter % 400 == 0) {
                        p.sendMessage(plugin.getMessage("stuck-tip"));
                    }
                    String abText = String.format("§b%.0f km/h  §7|  §aCP: %d/%d", speedKmH, cp, checkpoints.size());
                    if (type == RaceType.LAP) abText += String.format("  §7|  §6Lap: %d/%d", displayLap, maxLap);
                    p.sendActionBar(Component.text(abText));
                    highlightNextTarget(p, uuid);
                } else {
                    updateRaceScoreboard(p, "FINISHED", 0, 0, 0, 0, 0, ranking);
                }
            }
            for (UUID uuid : spectators) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    updateRaceScoreboard(p, "SPECTATING", 0, 0, checkpoints.size(), 0, totalLaps, ranking);
                }
            }
        }
    }

    // --- LOGIC HELPERS ---
    private boolean checkObjectivesAlongPath(Player p, UUID uuid, Location from, Location to) {
        int currentCpIndex = playerCheckpoints.getOrDefault(uuid, 0);
        Location cpTarget = null;
        boolean checkFinish = false;
        if (currentCpIndex < checkpoints.size()) {
            cpTarget = checkpoints.get(currentCpIndex);
        } else {
            checkFinish = true;
        }
        if (cpTarget != null) {
            if (Utils.lineSegmentIntersectsSphere(from, to, cpTarget, plugin.checkpointRadius)) {
                int totalCPs = checkpoints.size();
                int currentLap = playerLaps.getOrDefault(uuid, 1);
                int globalCPIndex = ((currentLap - 1) * totalCPs) + currentCpIndex;
                if (checkpointTimestamps.containsKey(uuid)) {
                    checkpointTimestamps.get(uuid).put(globalCPIndex, System.currentTimeMillis());
                }
                playerCheckpoints.put(uuid, currentCpIndex + 1);
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
                return true;
            }
        }
        if (checkFinish && finishBox != null) {
            Vector start = from.toVector();
            Vector direction = to.toVector().subtract(start);
            double maxDist = direction.length();
            if (maxDist > 0.01) {
                org.bukkit.util.RayTraceResult result = finishBox.rayTrace(start, direction.normalize(), maxDist);
                if (result != null) { handleFinishLineHit(p, uuid); return true; }
            }
            if (finishBox.contains(to.toVector())) { handleFinishLineHit(p, uuid); return true; }
        }
        return false;
    }

    private void handleFinishLineHit(Player p, UUID uuid) {
        if (type == RaceType.LAP) {
            int lap = playerLaps.getOrDefault(uuid, 1);
            if (lap < totalLaps) {
                playerLaps.put(uuid, lap + 1);
                playerCheckpoints.put(uuid, 0);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 2f);
                String msg = plugin.getRawMessage("lap-message")
                        .replace("{lap}", String.valueOf(lap + 1))
                        .replace("{total}", String.valueOf(totalLaps));
                p.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
            } else {
                finishPlayer(p);
            }
        } else {
            finishPlayer(p);
        }
    }

    private void finishPlayer(Player p) {
        stopMusic(p);
        if (finishOrder.contains(p.getUniqueId())) return;
        finishOrder.add(p.getUniqueId());
        long timeMs = System.currentTimeMillis() - startTimes.get(p.getUniqueId());
        String timeStr = Utils.formatTime(timeMs);
        finishTimes.put(p.getUniqueId(), timeStr);
        plugin.incrementStat(p.getUniqueId(), "races_played");
        if (finishOrder.size() == 1) { plugin.incrementStat(p.getUniqueId(), "wins"); }
        if (!bestTimes.containsKey(p.getUniqueId()) || timeMs < bestTimes.get(p.getUniqueId())) {
            bestTimes.put(p.getUniqueId(), timeMs);
            p.sendMessage(plugin.getMessage("new-pb").replaceText(b -> b.matchLiteral("{time}").replacement(timeStr)));
            if (currentRecordings.containsKey(p.getUniqueId())) {
                boolean isServerBest = true;
                for (Long t : bestTimes.values()) {
                    if (t < timeMs) { isServerBest = false; break; }
                }
                if (isServerBest) {
                    bestGhost = currentRecordings.get(p.getUniqueId());
                    p.sendMessage(plugin.getMessage("new-server-record"));
                }
            }
        }

        Component titleMain = LegacyComponentSerializer.legacyAmpersand().deserialize(plugin.getRawMessage("finished-title"));
        Component titleSub = LegacyComponentSerializer.legacyAmpersand().deserialize(plugin.getRawMessage("finished-subtitle").replace("{time}", timeStr));
        p.showTitle(Title.title(titleMain, titleSub));

        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1f, 1f);
        Boat boat = playerBoats.remove(p.getUniqueId());
        if (boat != null) boat.remove();
        p.setGameMode(GameMode.SPECTATOR);
        String broadcastMsg = plugin.getRawMessage("finish-broadcast").replace("{player}", p.getName()).replace("{arena}", name).replace("{time}", timeStr);
        Bukkit.broadcast(LegacyComponentSerializer.legacyAmpersand().deserialize(broadcastMsg));
        checkFinishCondition();
    }

    public void respawnPlayer(Player p) {
        if (state != RaceState.ACTIVE) return;
        int idx = playerCheckpoints.getOrDefault(p.getUniqueId(), 0);
        Location loc = (idx == 0) ? (!spawns.isEmpty() ? spawns.getFirst() : lobby) : checkpoints.get(idx - 1);
        if (loc == null) return;
        if (playerBoats.containsKey(p.getUniqueId())) playerBoats.get(p.getUniqueId()).remove();
        p.teleport(loc);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        lastLocations.put(p.getUniqueId(), loc);
        Boat boat = (Boat) p.getWorld().spawnEntity(loc, EntityType.BOAT);
        Utils.assignRandomBoatType(boat);
        boat.addPassenger(p);
        boat.setInvulnerable(true);
        playerBoats.put(p.getUniqueId(), boat);
        syncGhostModeForPlayer(p, boat);
        if (boat != null) {
            for (UUID otherUUID : players) {
                if (!otherUUID.equals(p.getUniqueId())) {
                    Player otherP = Bukkit.getPlayer(otherUUID);
                    if (otherP != null) {
                        otherP.hideEntity(plugin, boat);
                        new BukkitRunnable() { @Override public void run() { if (otherP.isOnline() && boat.isValid()) otherP.showEntity(plugin, boat); } }.runTaskLater(plugin, 1L);
                    }
                }
            }
        }
    }

    private List<UUID> calculateRankings() {
        List<UUID> rankList = new ArrayList<>(players);
        rankList.sort((u1, u2) -> {
            boolean f1 = finishOrder.contains(u1);
            boolean f2 = finishOrder.contains(u2);
            if (f1 != f2) return f1 ? -1 : 1;
            if (f1) return Integer.compare(finishOrder.indexOf(u1), finishOrder.indexOf(u2));
            int l1 = playerLaps.getOrDefault(u1, 1), l2 = playerLaps.getOrDefault(u2, 1);
            if (l1 != l2) return Integer.compare(l2, l1);
            int c1 = playerCheckpoints.getOrDefault(u1, 0), c2 = playerCheckpoints.getOrDefault(u2, 0);
            if (c1 != c2) return Integer.compare(c2, c1);
            double d1 = getDistanceToTarget(u1, c1);
            double d2 = getDistanceToTarget(u2, c2);
            return Double.compare(d1, d2);
        });
        return rankList;
    }

    private double getDistanceToTarget(UUID uuid, int cpIndex) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return Double.MAX_VALUE;
        Location target;
        if (cpIndex < checkpoints.size()) target = checkpoints.get(cpIndex);
        else if (finishCenter != null) target = finishCenter;
        else target = finishPos1;
        if (target == null || !p.getWorld().equals(target.getWorld())) return Double.MAX_VALUE;
        return p.getLocation().distanceSquared(target);
    }

    private void highlightNextTarget(Player p, UUID uuid) {
        int currentCpIndex = playerCheckpoints.getOrDefault(uuid, 0);
        Location target = null;
        if (currentCpIndex < checkpoints.size()) target = checkpoints.get(currentCpIndex);
        else if (finishCenter != null) target = finishCenter;
        else target = finishPos1;
        if (target != null) p.spawnParticle(Particle.HAPPY_VILLAGER, target.clone().add(0, 1.5, 0), 2, 0.2, 0.2, 0.2, 0);
    }

    private void createCage(Location spawn, UUID uuid) {
        Material cageMat = plugin.getPlayerCagePreference(uuid);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 2; y++) {
            if (Math.abs(x) <= 1 && Math.abs(z) <= 1) continue;
            Location b = spawn.clone().add(x, y, z);
            if (b.getBlock().getType() == Material.AIR) {
                b.getBlock().setType(cageMat);
                glassBlocks.add(b);
            }
        }
    }

    private void removeCages() {
        for (Location l : glassBlocks) if (l.getBlock().getType().name().contains("GLASS")) l.getBlock().setType(Material.AIR);
        glassBlocks.clear();
    }

    private void syncGhostMode() {
        for (UUID uuid : playerBoats.keySet()) {
            Boat b = playerBoats.get(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && b != null) syncGhostModeForPlayer(p, b);
        }
    }

    private void syncGhostModeForPlayer(Player p, Boat b) {
        for (UUID otherUUID : players) {
            Player otherP = Bukkit.getPlayer(otherUUID);
            if (otherP != null) {
                Team t = otherP.getScoreboard().getTeam("ghost");
                if (t != null) {
                    t.addEntry(p.getName());
                    t.addEntry(b.getUniqueId().toString());
                }
            }
        }
        for (UUID otherUUID : players) {
            if (!otherUUID.equals(p.getUniqueId())) {
                Player otherP = Bukkit.getPlayer(otherUUID);
                if (otherP != null) {
                    otherP.hideEntity(plugin, b);
                    new BukkitRunnable() { @Override public void run() { if (otherP.isOnline() && b.isValid()) otherP.showEntity(plugin, b); } }.runTaskLater(plugin, 1L);
                }
            }
        }
    }

    public void startMusic() {
        if (!plugin.musicEnabled) return;
        if (musicTask != null && !musicTask.isCancelled()) musicTask.cancel();
        musicTask = new BukkitRunnable() {
            @Override public void run() {
                if (state != RaceState.ACTIVE && state != RaceState.STARTING) { this.cancel(); return; }
                for (UUID uuid : players) {
                    if (!finishOrder.contains(uuid)) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null && p.isOnline()) p.playSound(p.getLocation(), plugin.musicSound, SoundCategory.MASTER, plugin.musicVolume, plugin.musicPitch);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, plugin.musicDuration * 20L);
    }
    public void stopMusic(Player p) { if (p != null) p.stopSound(plugin.musicSound, SoundCategory.MASTER); }
    public void stopAllMusic() {
        if (musicTask != null) { musicTask.cancel(); musicTask = null; }
        for (UUID uuid : players) stopMusic(Bukkit.getPlayer(uuid));
    }

    private void sendDiscordResults() {
        if (plugin.discordWebhookUrl == null || plugin.discordWebhookUrl.isEmpty()) return;
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    StringBuilder description = new StringBuilder();
                    int rank = 1;
                    String[] emojis = {"🥇", "🥈", "🥉"};
                    for (UUID uuid : finishOrder) {
                        if (rank > 10) break;
                        Player p = Bukkit.getPlayer(uuid);
                        String name = (p != null) ? p.getName() : Bukkit.getOfflinePlayer(uuid).getName();
                        String time = finishTimes.getOrDefault(uuid, "DNF");
                        String medal = (rank <= 3) ? emojis[rank-1] + " " : rank + ". ";
                        description.append(medal).append("**").append(name).append("** - `").append(time).append("`\\n");
                        rank++;
                    }
                    String json = "{\"embeds\": [{" + "  \"title\": \"🏆 Race Results: " + name + "\"," + "  \"color\": 5814783," + "  \"description\": \"" + description.toString() + "\"," + "  \"footer\": { \"text\": \"IceBoatRacing\" }," + "  \"timestamp\": \"" + Instant.now().toString() + "\"" + "}]}";
                    HttpClient client = HttpClient.newHttpClient();
                    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(plugin.discordWebhookUrl)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
                    client.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) { plugin.getLogger().warning("Discord webhook failed: " + e.getMessage()); }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void checkAutoStart() {
        if (state != RaceState.LOBBY) return;
        if (players.size() >= minPlayers) {
            if (autoStartTask == null) {
                lobbyCountdown = autoStartDelay;
                autoStartTask = new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (state != RaceState.LOBBY || players.size() < minPlayers) { cancelAutoStart(); return; }
                        if (lobbyCountdown <= 0) { startRace(); cancel(); return; }
                        if (lobbyCountdown == 60 || lobbyCountdown == 30 || lobbyCountdown == 10 || lobbyCountdown <= 5) {
                            for (UUID uuid : players) {
                                Player p = Bukkit.getPlayer(uuid);
                                if (p != null) {
                                    p.sendMessage(plugin.getMessage("race-starting").replaceText(b -> b.matchLiteral("{time}").replacement(String.valueOf(lobbyCountdown))));
                                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                                }
                            }
                        }
                        updateLobbyScoreboard();
                        lobbyCountdown--;
                    }
                }.runTaskTimer(plugin, 0L, 20L);
            }
        } else {
            cancelAutoStart();
        }
    }

    private void cancelAutoStart() {
        if (autoStartTask != null) {
            autoStartTask.cancel();
            autoStartTask = null;
            lobbyCountdown = -1;
            updateLobbyScoreboard();
        }
    }

    private void updateLobbyScoreboard() {
        if (state != RaceState.LOBBY) return;
        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) setupLobbyScoreboard(p);
        }
    }

    private void setupLobbyScoreboard(Player p) {
        ScoreboardManager m = Bukkit.getScoreboardManager();
        Scoreboard b = m.getNewScoreboard();
        Objective o = b.registerNewObjective("Lobby", Criteria.DUMMY, Component.text("§b§lICE BOAT"));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        Team statusTeam = b.registerNewTeam("status");
        String statusTxt = (autoStartTask != null && lobbyCountdown >= 0) ? "§eStart: " + lobbyCountdown + "s" : "§fWaiting...";
        statusTeam.addEntry("§7");
        statusTeam.suffix(Component.text(statusTxt));
        o.getScore("§7----------------").setScore(6);
        o.getScore("§eArena:").setScore(5);
        o.getScore("  §f" + name).setScore(4);
        o.getScore(" ").setScore(3);
        o.getScore("§aPlayers: §f" + players.size() + "/" + minPlayers).setScore(2);
        o.getScore("§7").setScore(1);
        o.getScore("§7---------------- ").setScore(0);
        p.setScoreboard(b);
    }

    private void setupRaceScoreboard(Player p) {
        ScoreboardManager m = Bukkit.getScoreboardManager();
        Scoreboard b = m.getNewScoreboard();
        Objective o = b.registerNewObjective("IceRace", Criteria.DUMMY, Component.text("§b§lICE BOAT"));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        Team ghost = b.registerNewTeam("ghost");
        ghost.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        Utils.createTeam(b, "stats", "§fTime: 00:00");
        o.getScore("§7----------------").setScore(15);
        o.getScore("§eStats:").setScore(14);
        o.getScore("§f").setScore(13);
        o.getScore(" ").setScore(12);
        o.getScore("§e§lTOP RACERS").setScore(11);
        String[] rankKeys = {"§1", "§2", "§3", "§4", "§5", "§6", "§7", "§8", "§9", "§a"};
        for (int i = 0; i < 10; i++) {
            Utils.createTeam(b, "rank_" + (i + 1), "");
            o.getScore(rankKeys[i]).setScore(10 - i);
            Team t = b.getTeam("rank_" + (i + 1));
            if (t != null) t.addEntry(rankKeys[i]);
        }
        Team stats = b.getTeam("stats");
        if (stats != null) stats.addEntry("§f");
        p.setScoreboard(b);
    }

    private void updateRaceScoreboard(Player p, String time, double speed, int cp, int totalCps, int lap, int maxLaps, List<UUID> ranking) {
        Scoreboard b = p.getScoreboard();
        Team stats = b.getTeam("stats");
        if (stats != null) {
            String statText;
            if (time.equals("SPECTATING")) {
                statText = "§bSPECTATING";
            } else {
                statText = String.format("§f%s §7| §b%.0f km/h §7| §aCP: %d/%d", time, speed, cp, totalCps);
                if (type == RaceType.LAP) statText += String.format(" §7| §6L%d/%d", lap, maxLaps);
            }
            stats.suffix(Component.text(statText));
        }
        UUID leaderUUID = (!ranking.isEmpty()) ? ranking.get(0) : null;
        for (int i = 0; i < 10; i++) {
            Team t = b.getTeam("rank_" + (i + 1));
            if (t != null) {
                if (i < ranking.size()) {
                    UUID uuid = ranking.get(i);
                    Player rp = Bukkit.getPlayer(uuid);
                    String name = (rp != null) ? rp.getName() : "Unknown";
                    int pLap = playerLaps.getOrDefault(uuid, 1);
                    String gapStr;
                    if (i == 0) { gapStr = "§e1st"; } else {
                        long gap = calculateGapToLeader(uuid, leaderUUID);
                        if (gap == 0) gapStr = "§7-.-";
                        else if (gap > 0) gapStr = "§c+" + String.format("%.1f", gap / 1000.0) + "s";
                        else gapStr = "§a" + String.format("%.1f", gap / 1000.0) + "s";
                    }
                    String entry;
                    if (uuid.equals(p.getUniqueId())) {
                        entry = String.format("§f%s §8// §aYou §eL%d", time, pLap);
                    } else {
                        entry = String.format("%s §8// §f%s §7L%d", gapStr, name, pLap);
                    }
                    if (finishOrder.contains(uuid)) entry = "§a✔ " + name + " §7(Finished)";
                    t.suffix(Component.text(entry));
                } else {
                    t.suffix(Component.text("§7---"));
                }
            }
        }
    }

    private long calculateGapToLeader(UUID playerUUID, UUID leaderUUID) {
        if (playerUUID == null || leaderUUID == null) return 0;
        if (playerUUID.equals(leaderUUID)) return 0;
        int pLap = playerLaps.getOrDefault(playerUUID, 1);
        int pCp = playerCheckpoints.getOrDefault(playerUUID, 0);
        int totalCPs = checkpoints.size();
        int globalIndex = ((pLap - 1) * totalCPs) + pCp - 1;
        if (globalIndex < 0) return 0;
        Map<Integer, Long> pTimes = checkpointTimestamps.get(playerUUID);
        Map<Integer, Long> lTimes = checkpointTimestamps.get(leaderUUID);
        if (pTimes == null || lTimes == null) return 0;
        if (!pTimes.containsKey(globalIndex) || !lTimes.containsKey(globalIndex)) return 0;
        return pTimes.get(globalIndex) - lTimes.get(globalIndex);
    }
}
