package peyaj;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer; // FIXED: Added missing import
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
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

    // Runtime Data
    private final Map<UUID, Integer> playerCheckpoints = new HashMap<>();
    private final Map<UUID, Integer> playerLaps = new HashMap<>();
    private final Map<UUID, Long> startTimes = new HashMap<>();
    private final Map<UUID, String> finishTimes = new HashMap<>();
    private final Map<UUID, Boat> playerBoats = new HashMap<>();
    private final Set<UUID> players = new HashSet<>();
    private final List<UUID> finishOrder = new ArrayList<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final List<Location> glassBlocks = new ArrayList<>();

    // Leaderboard Data
    public final Map<UUID, Long> bestTimes = new HashMap<>();
    private TextDisplay hologramEntity;

    private BukkitTask autoStartTask = null;
    private int startCountdown = -1;
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

    public Location getLeaderboardLocation() { return leaderboardLocation; }
    public void setLeaderboardLocation(Location loc) {
        this.leaderboardLocation = loc;
        updateLeaderboardHologram(); // Refresh instantly
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
        if (finishPos1 != null && finishPos2 != null && finishPos1.getWorld().equals(finishPos2.getWorld())) {
            finishBox = BoundingBox.of(finishPos1, finishPos2);
            finishCenter = finishBox.getCenter().toLocation(finishPos1.getWorld());
        }
    }

    // --- HOLOGRAMS ---
    public void updateLeaderboardHologram() {
        if (leaderboardLocation == null) return;

        // Kill old if exists (check nearby to be safe)
        if (hologramEntity != null && !hologramEntity.isDead()) hologramEntity.remove();

        for (Entity e : leaderboardLocation.getWorld().getNearbyEntities(leaderboardLocation, 2, 2, 2)) {
            if (e instanceof TextDisplay td && e.getScoreboardTags().contains("iceboat_holo_" + name)) {
                e.remove();
            }
        }

        List<Map.Entry<UUID, Long>> sorted = new ArrayList<>(bestTimes.entrySet());
        sorted.sort(Map.Entry.comparingByValue());

        StringBuilder text = new StringBuilder();
        text.append("§b§l❄ ").append(name.toUpperCase()).append(" LEADERBOARD ❄\n");
        text.append("§7------------------------\n");

        int limit = Math.min(sorted.size(), 10);
        for (int i = 0; i < limit; i++) {
            UUID uuid = sorted.get(i).getKey();
            long time = sorted.get(i).getValue();
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            String pName = (op.getName() != null) ? op.getName() : "Unknown";

            String color = (i == 0) ? "§e" : (i == 1) ? "§f" : (i == 2) ? "§6" : "§7";
            text.append(color).append(i + 1).append(". §f").append(pName)
                    .append(" §7- §b").append(Utils.formatTime(time)).append("\n");
        }

        if (limit == 0) text.append("§7No records yet!\n");
        text.append("§7------------------------");

        hologramEntity = (TextDisplay) leaderboardLocation.getWorld().spawnEntity(leaderboardLocation, EntityType.TEXT_DISPLAY);
        hologramEntity.text(LegacyComponentSerializer.legacyAmpersand().deserialize(text.toString()));
        hologramEntity.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        hologramEntity.addScoreboardTag("iceboat_holo_" + name);
    }

    // --- PLAYER MANAGEMENT ---

    public void addPlayer(Player p) {
        if (state != RaceState.LOBBY) {
            p.sendMessage(Component.text("Race is already running!", NamedTextColor.RED));
            return;
        }
        players.add(p.getUniqueId());
        if (lobby != null) {
            p.teleport(lobby);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        }

        // Init data
        playerCheckpoints.put(p.getUniqueId(), 0);
        playerLaps.put(p.getUniqueId(), 1);

        p.sendMessage(Component.text("Joined " + name, NamedTextColor.GREEN));

        checkAutoStart();
        updateLobbyScoreboard();
    }

    public void removePlayer(Player p) {
        players.remove(p.getUniqueId());
        if (playerBoats.containsKey(p.getUniqueId())) {
            Boat b = playerBoats.remove(p.getUniqueId());
            if (b != null) b.remove();
        }

        stopMusic(p);
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());

        // Cancel auto start if players drop below min
        checkAutoStart();
        updateLobbyScoreboard();
    }

    // --- GAME LOOP ---

    public void startRace() {
        if (spawns.isEmpty()) return;

        cancelAutoStart();
        state = RaceState.STARTING;
        removeCages();
        finishOrder.clear();
        finishTimes.clear();

        int spawnIndex = 0;
        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;

            Location spawn = spawns.get(spawnIndex % spawns.size());
            spawnIndex++;
            p.teleport(spawn);

            Boat boat = (Boat) p.getWorld().spawnEntity(spawn, EntityType.BOAT);
            Utils.assignRandomBoatType(boat);
            boat.addPassenger(p);
            boat.setInvulnerable(true);
            playerBoats.put(uuid, boat);

            createCage(spawn);
            playerCheckpoints.put(uuid, 0);
            playerLaps.put(uuid, 1);

            setupRaceScoreboard(p);
        }

        syncGhostMode();

        new BukkitRunnable() {
            int count = 5;
            @Override
            public void run() {
                if (state != RaceState.STARTING) { removeCages(); this.cancel(); return; }

                if (count == 3) startMusic();

                if (count > 0) {
                    Title title = Title.title(Component.text(count, NamedTextColor.RED), Component.empty());
                    for (UUID uuid : players) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) {
                            p.showTitle(title);
                            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.5f + ((5 - count) * 0.3f));
                            Boat boat = playerBoats.get(uuid);
                            if (boat != null) boat.setVelocity(new Vector(0,0,0));
                        }
                    }
                    count--;
                } else {
                    removeCages();
                    state = RaceState.ACTIVE;
                    long now = System.currentTimeMillis();
                    for (UUID uuid : players) {
                        startTimes.put(uuid, now);
                        Player p = Bukkit.getPlayer(uuid);
                        if(p!=null) {
                            p.showTitle(Title.title(Component.text("GO!", NamedTextColor.GREEN), Component.empty()));
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

        if (!finishOrder.isEmpty() && !plugin.discordWebhookUrl.isEmpty()) {
            sendDiscordResults();
        }

        state = RaceState.LOBBY;
        removeCages();

        for (Boat b : playerBoats.values()) b.remove();
        playerBoats.clear();
        finishOrder.clear();
        finishTimes.clear();

        for (UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                p.setGameMode(GameMode.SURVIVAL);
                if (mainLobby != null) p.teleport(mainLobby);
                else p.teleport(p.getWorld().getSpawnLocation());
                plugin.removePlayerFromArenaMap(uuid);
            }
        }
        players.clear();
        updateLeaderboardHologram(); // Update hologram at end
    }

    public void tick() {
        if (state == RaceState.LOBBY) return;

        if (state == RaceState.ACTIVE) {
            List<UUID> ranking = calculateRankings(); // FIXED: Method is now present below
            for (UUID uuid : players) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) continue;

                if (!finishOrder.contains(uuid) && playerBoats.containsKey(uuid)) {
                    Location currentLoc = p.getLocation();
                    Location lastLoc = lastLocations.getOrDefault(uuid, currentLoc);
                    double speedKmH = (lastLoc.getWorld() == currentLoc.getWorld()) ? currentLoc.distance(lastLoc) * 72.0 : 0;
                    lastLocations.put(uuid, currentLoc);

                    int safety = 0;
                    boolean keepChecking = true;
                    while(keepChecking && safety < 3) {
                        keepChecking = checkObjectivesAlongPath(p, uuid, lastLoc, currentLoc);
                        safety++;
                    }

                    Utils.spawnExhaustParticles(p, playerBoats.get(uuid));

                    long timeMs = System.currentTimeMillis() - startTimes.getOrDefault(uuid, System.currentTimeMillis());
                    String timeStr = Utils.formatTime(timeMs);

                    int displayLap = (type == RaceType.LAP) ? playerLaps.getOrDefault(uuid, 1) : 1;
                    int maxLap = (type == RaceType.LAP) ? totalLaps : 1;
                    int cp = playerCheckpoints.getOrDefault(uuid, 0);

                    updateRaceScoreboard(p, timeStr, speedKmH, cp, checkpoints.size(), displayLap, maxLap, ranking);

                    String abText = String.format("§b%.0f km/h  §7|  §aCP: %d/%d", speedKmH, cp, checkpoints.size());
                    if (type == RaceType.LAP) abText += String.format("  §7|  §6Lap: %d/%d", displayLap, maxLap);
                    p.sendActionBar(Component.text(abText));

                    highlightNextTarget(p, uuid);
                } else {
                    updateRaceScoreboard(p, "FINISHED", 0, 0, 0, 0, 0, ranking);
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
                playerCheckpoints.put(uuid, 0); // Reset CPs for new lap
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 2f);
                p.sendMessage(Component.text("Lap " + (lap + 1) + "/" + totalLaps, NamedTextColor.GOLD));
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

        if (!bestTimes.containsKey(p.getUniqueId()) || timeMs < bestTimes.get(p.getUniqueId())) {
            bestTimes.put(p.getUniqueId(), timeMs);
            p.sendMessage(Component.text("New Personal Best: " + timeStr, NamedTextColor.AQUA));
        }

        p.showTitle(Title.title(Component.text("FINISHED!", NamedTextColor.GOLD), Component.text("Time: " + timeStr, NamedTextColor.YELLOW)));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1f, 1f);

        Boat boat = playerBoats.remove(p.getUniqueId());
        if (boat != null) boat.remove();
        p.setGameMode(GameMode.SPECTATOR);

        Bukkit.broadcast(Component.text(p.getName() + " finished " + name + " in " + timeStr + "!", NamedTextColor.YELLOW));

        if (finishOrder.size() >= players.size()) {
            Bukkit.broadcast(Component.text("Arena " + name + " finished! Closing...", NamedTextColor.GREEN));
            new BukkitRunnable() { @Override public void run() { stopRace(); } }.runTaskLater(plugin, 100L);
        }
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
    }

    // --- FIXED: Added Missing Method calculateRankings ---
    private List<UUID> calculateRankings() {
        List<UUID> rankList = new ArrayList<>(players);
        rankList.sort((u1, u2) -> {
            // 1. Finished players first
            boolean f1 = finishOrder.contains(u1);
            boolean f2 = finishOrder.contains(u2);
            if (f1 != f2) return f1 ? -1 : 1;
            if (f1) return Integer.compare(finishOrder.indexOf(u1), finishOrder.indexOf(u2));

            // 2. Lap Count (Higher is better)
            int l1 = playerLaps.getOrDefault(u1, 1);
            int l2 = playerLaps.getOrDefault(u2, 1);
            if (l1 != l2) return Integer.compare(l2, l1);

            // 3. Checkpoint Index (Higher is better)
            int c1 = playerCheckpoints.getOrDefault(u1, 0);
            int c2 = playerCheckpoints.getOrDefault(u2, 0);
            if (c1 != c2) return Integer.compare(c2, c1);

            // 4. Distance to Next Objective (Lower is better)
            double d1 = getDistanceToTarget(u1, c1);
            double d2 = getDistanceToTarget(u2, c2);
            return Double.compare(d1, d2);
        });
        return rankList;
    }

    // --- FIXED: Added Missing Method getDistanceToTarget ---
    private double getDistanceToTarget(UUID uuid, int cpIndex) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return Double.MAX_VALUE;

        Location target;
        if (cpIndex < checkpoints.size()) {
            target = checkpoints.get(cpIndex);
        } else {
            // Aiming for finish line
            if (finishCenter != null) target = finishCenter;
            else target = finishPos1;
        }

        if (target == null || !p.getWorld().equals(target.getWorld())) return Double.MAX_VALUE;

        return p.getLocation().distanceSquared(target);
    }

    // --- AUTO START ---
    private void checkAutoStart() {
        if (state != RaceState.LOBBY) return;
        if (players.size() >= minPlayers) {
            if (autoStartTask == null) {
                startCountdown = autoStartDelay;
                autoStartTask = new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (state != RaceState.LOBBY || players.size() < minPlayers) {
                            cancelAutoStart();
                            return;
                        }
                        if (startCountdown <= 0) {
                            startRace();
                            cancel();
                            return;
                        }
                        if (startCountdown == 60 || startCountdown == 30 || startCountdown == 10 || startCountdown <= 5) {
                            for (UUID uuid : players) {
                                Player p = Bukkit.getPlayer(uuid);
                                if (p != null) {
                                    p.sendMessage(Component.text("Race starting in " + startCountdown + "s", NamedTextColor.YELLOW));
                                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                                }
                            }
                        }
                        updateLobbyScoreboard();
                        startCountdown--;
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
            startCountdown = -1;
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
        String statusTxt = (autoStartTask != null) ? "§eStart: " + startCountdown + "s" : "§fWaiting...";
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
            Utils.createTeam(b, "rank_" + (i + 1), "§7" + (i + 1) + ". ---");
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
            String statText = String.format("§f%s §7| §b%.0f km/h §7| §aCP: %d/%d", time, speed, cp, totalCps);
            if (type == RaceType.LAP) statText += String.format(" §7| §6L%d/%d", lap, maxLaps);
            stats.suffix(Component.text(statText));
        }

        for (int i = 0; i < 10; i++) {
            Team t = b.getTeam("rank_" + (i + 1));
            if (t != null) {
                if (i < ranking.size()) {
                    UUID uuid = ranking.get(i);
                    Player rp = Bukkit.getPlayer(uuid);
                    String n = (rp != null) ? rp.getName() : "Unknown";
                    String c = (uuid.equals(p.getUniqueId())) ? "§a" : "§f";
                    if (finishOrder.contains(uuid)) t.suffix(Component.text(c + n + " §a✔"));
                    else t.suffix(Component.text(c + n));
                } else t.suffix(Component.text("§7---"));
            }
        }
    }

    // --- OTHER HELPERS ---

    private void createCage(Location spawn) {
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 2; y++) {
            if (Math.abs(x) <= 1 && Math.abs(z) <= 1) continue;
            Location b = spawn.clone().add(x, y, z);
            if (b.getBlock().getType() == Material.AIR) { b.getBlock().setType(Material.GLASS); glassBlocks.add(b); }
        }
    }

    private void removeCages() {
        for (Location l : glassBlocks) if (l.getBlock().getType() == Material.GLASS) l.getBlock().setType(Material.AIR);
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
                    new BukkitRunnable() {
                        @Override public void run() { if (otherP.isOnline() && b.isValid()) otherP.showEntity(plugin, b); }
                    }.runTaskLater(plugin, 1L);
                }
            }
        }
    }

    // Music
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

                    String json = "{\"embeds\": [{"
                            + "  \"title\": \"🏆 Race Results: " + name + "\","
                            + "  \"color\": 5814783,"
                            + "  \"description\": \"" + description.toString() + "\","
                            + "  \"footer\": { \"text\": \"IceBoatRacing\" },"
                            + "  \"timestamp\": \"" + Instant.now().toString() + "\""
                            + "}]}";

                    HttpClient client = HttpClient.newHttpClient();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(plugin.discordWebhookUrl))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build();
                    client.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    plugin.getLogger().warning("Discord webhook failed: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void highlightNextTarget(Player p, UUID uuid) {
        int currentCpIndex = playerCheckpoints.getOrDefault(uuid, 0);
        Location target = null;
        if (currentCpIndex < checkpoints.size()) {
            target = checkpoints.get(currentCpIndex);
        } else if (finishCenter != null) {
            target = finishCenter;
        } else {
            target = finishPos1;
        }
        if (target != null) {
            p.spawnParticle(Particle.HAPPY_VILLAGER, target.clone().add(0, 1.5, 0), 2, 0.2, 0.2, 0.2, 0);
        }
    }
}