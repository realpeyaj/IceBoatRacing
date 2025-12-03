package peyaj;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class IceBoatRacing extends JavaPlugin {

    private final Map<String, RaceArena> arenas = new HashMap<>();
    private final Map<UUID, String> playerArenaMap = new ConcurrentHashMap<>();

    // --- EDITOR & INPUT DATA ---
    public final Map<UUID, String> editorArena = new HashMap<>();
    public final Map<UUID, EditMode> editorMode = new HashMap<>();
    public final Map<UUID, String> activeVisualizers = new HashMap<>();
    public final Map<UUID, String> inputMode = new HashMap<>();

    // --- COSMETICS DATA ---
    private final Map<UUID, Material> playerCagePreference = new HashMap<>();
    private final Map<UUID, TrailType> playerTrailPreference = new HashMap<>();

    // --- VOTING DATA (RESTORED) ---
    public boolean isVoting = false;
    public int votingTimeRemaining = 0;
    private BukkitTask votingTask;
    public final Map<UUID, String> playerVotes = new HashMap<>();

    // --- CONFIGS ---
    private File messagesFile;
    private FileConfiguration messagesConfig;
    private File statsFile;
    private FileConfiguration statsConfig;

    // NEW: Arenas File
    private File arenasFile;
    private FileConfiguration arenasConfig;

    public GUIManager guiManager;

    // --- SETTINGS ---
    public double checkpointRadius = 25.0;
    public String discordWebhookUrl = "";

    public boolean musicEnabled = true;
    public String musicSound = "minecraft:coconutmallmariokartwiiostfourone";
    public int musicDuration = 180;
    public float musicVolume = 10000.0f;
    public float musicPitch = 1.0f;

    // --- NEW: GHOST DATA ---
    public static class GhostData {
        public final List<Location> points = new ArrayList<>();
        public final String playerName;
        public final long timeMs;

        public GhostData(String playerName, long timeMs) {
            this.playerName = playerName;
            this.timeMs = timeMs;
        }
    }

    public enum EditMode {
        SPAWN("Spawn Points", NamedTextColor.GREEN),
        CHECKPOINT("Checkpoints", NamedTextColor.RED),
        FINISH_1("Finish Pos 1", NamedTextColor.AQUA),
        FINISH_2("Finish Pos 2", NamedTextColor.AQUA),
        LOBBY("Pre-Lobby", NamedTextColor.GOLD),
        MAIN_LOBBY("Main Lobby", NamedTextColor.YELLOW),
        LEADERBOARD("Leaderboard Holo", NamedTextColor.LIGHT_PURPLE);

        public final String name;
        public final NamedTextColor color;
        EditMode(String name, NamedTextColor color) { this.name = name; this.color = color; }

        public EditMode next() {
            int idx = this.ordinal() + 1;
            if (idx >= values().length) idx = 0;
            return values()[idx];
        }
    }

    public enum TrailType {
        NONE("None", Material.BARRIER, null, null),
        SMOKE("Exhaust", Material.CAMPFIRE, org.bukkit.Particle.CAMPFIRE_COSY_SMOKE, null),
        FLAME("Flame", Material.BLAZE_POWDER, org.bukkit.Particle.FLAME, "race.trail.flame"),
        HEARTS("Love", Material.POPPY, org.bukkit.Particle.HEART, "race.trail.hearts"),
        NOTES("Music", Material.NOTE_BLOCK, org.bukkit.Particle.NOTE, "race.trail.notes"),
        SPARKS("Sparks", Material.FIREWORK_ROCKET, org.bukkit.Particle.FIREWORK, "race.trail.sparks"),
        MAGIC("Magic", Material.ENCHANTING_TABLE, org.bukkit.Particle.ENCHANT, "race.trail.magic"),
        ENDER("Void", Material.ENDER_PEARL, org.bukkit.Particle.DRAGON_BREATH, "race.trail.ender"),
        RAINBOW("Rainbow", Material.NAME_TAG, org.bukkit.Particle.DUST, "race.trail.rainbow");

        public final String name;
        public final Material icon;
        public final org.bukkit.Particle particle;
        public final String permission;

        TrailType(String name, Material icon, org.bukkit.Particle particle, String permission) {
            this.name = name;
            this.icon = icon;
            this.particle = particle;
            this.permission = permission;
        }
    }

    @Override
    public void onEnable() {
        sendStartupBanner();

        saveDefaultConfig();
        loadConfigSettings();
        loadMessages();
        loadStats();

        // Load Arenas from dedicated file
        loadArenasConfig();

        // MIGRATION CHECK: Move arenas from config.yml to arenas.yml
        if (getConfig().contains("arenas")) {
            getLogger().info("Migrating arenas from config.yml to arenas.yml...");
            arenasConfig.set("arenas", getConfig().getConfigurationSection("arenas"));
            getConfig().set("arenas", null);
            saveConfig();
            saveArenasConfig();
            getLogger().info("Migration complete!");
        }

        loadArenas();

        guiManager = new GUIManager(this);
        getServer().getPluginManager().registerEvents(guiManager, this);

        RaceCommand cmd = new RaceCommand(this);
        if (getCommand("race") != null) Objects.requireNonNull(getCommand("race")).setExecutor(cmd);
        if (getCommand("iceboat") != null) Objects.requireNonNull(getCommand("iceboat")).setExecutor(cmd);
        if (getCommand("checkpoint") != null) Objects.requireNonNull(getCommand("checkpoint")).setExecutor(cmd);

        getServer().getPluginManager().registerEvents(new RaceListener(this), this);

        new BukkitRunnable() {
            @Override
            public void run() {
                for (RaceArena arena : arenas.values()) arena.tick();
            }
        }.runTaskTimer(this, 0L, 1L);

        new BukkitRunnable() {
            @Override
            public void run() {
                Utils.tickVisualizers(IceBoatRacing.this);
            }
        }.runTaskTimer(this, 0L, 10L);
    }

    @Override
    public void onDisable() {
        for (RaceArena arena : arenas.values()) {
            arena.stopRace();
        }
        saveArenas();
        saveStats();
    }

    public void reload() {
        reloadConfig();
        loadConfigSettings();
        loadMessages();
        loadArenasConfig();
        getLogger().info("Configuration reloaded.");
    }

    // --- VOTING LOGIC (RESTORED) ---

    public void startVotingRound(int durationSeconds) {
        if (isVoting) return;
        isVoting = true;
        votingTimeRemaining = durationSeconds;
        playerVotes.clear();

        Bukkit.broadcast(Component.text("---------------------------------------", NamedTextColor.GREEN));
        Bukkit.broadcast(Component.text(" 🗳️ Map Voting has started!", NamedTextColor.YELLOW));
        Bukkit.broadcast(Component.text(" Type /race vote to choose the next map!", NamedTextColor.AQUA));
        Bukkit.broadcast(Component.text("---------------------------------------", NamedTextColor.GREEN));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
        }

        votingTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (votingTimeRemaining <= 0) {
                    endVotingRound();
                    cancel();
                    return;
                }

                if (votingTimeRemaining == 30 || votingTimeRemaining == 10 || votingTimeRemaining <= 5) {
                    Bukkit.broadcast(Component.text("Voting ends in " + votingTimeRemaining + "s...", NamedTextColor.GRAY));
                }

                votingTimeRemaining--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    public void endVotingRound() {
        isVoting = false;
        if (votingTask != null) votingTask.cancel();

        Map<String, Integer> counts = new HashMap<>();
        for (String arena : playerVotes.values()) {
            counts.put(arena, counts.getOrDefault(arena, 0) + 1);
        }

        String winner = null;
        int max = -1;

        if (counts.isEmpty()) {
            List<String> keys = new ArrayList<>(arenas.keySet());
            if (!keys.isEmpty()) winner = keys.get(new Random().nextInt(keys.size()));
        } else {
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > max) {
                    max = entry.getValue();
                    winner = entry.getKey();
                }
            }
        }

        if (winner == null || !arenas.containsKey(winner)) {
            Bukkit.broadcast(Component.text("Voting ended. No arenas available.", NamedTextColor.RED));
            return;
        }

        RaceArena winningArena = arenas.get(winner);
        Bukkit.broadcast(Component.text("---------------------------------------", NamedTextColor.GREEN));
        Bukkit.broadcast(Component.text(" 🏆 Voting Finished!", NamedTextColor.GOLD));
        Bukkit.broadcast(Component.text(" Next Map: " + winningArena.getName(), NamedTextColor.AQUA));
        Bukkit.broadcast(Component.text("---------------------------------------", NamedTextColor.GREEN));

        for (UUID uuid : playerVotes.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline() && !isRacer(uuid)) {
                winningArena.addPlayer(p);
            }
        }
    }

    public void castVote(Player p, String arenaName) {
        if (!isVoting) {
            p.sendMessage(Component.text("No voting in progress.", NamedTextColor.RED));
            return;
        }
        playerVotes.put(p.getUniqueId(), arenaName);
        p.sendMessage(Component.text("You voted for " + arenaName, NamedTextColor.GREEN));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
    }

    public int getVoteCount(String arenaName) {
        int count = 0;
        for (String s : playerVotes.values()) {
            if (s.equals(arenaName)) count++;
        }
        return count;
    }

    // --- CONFIG HELPERS ---
    private void loadArenasConfig() {
        arenasFile = new File(getDataFolder(), "arenas.yml");
        if (!arenasFile.exists()) {
            try { arenasFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        arenasConfig = YamlConfiguration.loadConfiguration(arenasFile);
    }

    public void saveArenasConfig() {
        try { arenasConfig.save(arenasFile); } catch (IOException e) { e.printStackTrace(); }
    }

    private void loadMessages() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) saveResource("messages.yml", false);
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public Component getMessage(String key) {
        String prefix = messagesConfig.getString("prefix", "&b[IceBoat] ");
        String msg = messagesConfig.getString(key, "&cMissing message: " + key);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(prefix + msg);
    }

    public String getRawMessage(String key) {
        return messagesConfig.getString(key, key);
    }

    private void loadStats() {
        statsFile = new File(getDataFolder(), "stats.yml");
        if (!statsFile.exists()) {
            try { statsFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        statsConfig = YamlConfiguration.loadConfiguration(statsFile);
    }

    public void saveStats() {
        try { statsConfig.save(statsFile); } catch (IOException e) { e.printStackTrace(); }
    }

    public void incrementStat(UUID uuid, String stat) {
        String path = uuid.toString() + "." + stat;
        int current = statsConfig.getInt(path, 0);
        statsConfig.set(path, current + 1);
        saveStats();
    }

    public int getStat(UUID uuid, String stat) {
        return statsConfig.getInt(uuid.toString() + "." + stat, 0);
    }

    // --- COSMETICS ---
    public Material getPlayerCagePreference(UUID uuid) {
        return playerCagePreference.getOrDefault(uuid, Material.GLASS);
    }

    public void setPlayerCagePreference(UUID uuid, Material mat) {
        playerCagePreference.put(uuid, mat);
    }

    public TrailType getPlayerTrailPreference(UUID uuid) {
        return playerTrailPreference.getOrDefault(uuid, TrailType.SMOKE);
    }

    public void setPlayerTrailPreference(UUID uuid, TrailType trail) {
        playerTrailPreference.put(uuid, trail);
    }

    // --- ARENA MANAGEMENT ---
    public RaceArena getArena(String name) {
        return arenas.get(name.toLowerCase());
    }

    public Map<String, RaceArena> getArenas() {
        return arenas;
    }

    public void addArena(String name, RaceArena arena) {
        arenas.put(name.toLowerCase(), arena);
    }

    public void removeArena(String name) {
        arenas.remove(name.toLowerCase());
    }

    public RaceArena getPlayerArena(UUID uuid) {
        String name = playerArenaMap.get(uuid);
        return (name != null) ? arenas.get(name) : null;
    }

    public void setPlayerArena(UUID uuid, String arenaName) {
        playerArenaMap.put(uuid, arenaName);
    }

    public void removePlayerFromArenaMap(UUID uuid) {
        playerArenaMap.remove(uuid);
    }

    public boolean isRacer(UUID uuid) {
        return playerArenaMap.containsKey(uuid);
    }

    // --- SAVE LOGIC (ARENAS.YML) ---
    public void saveArenas() {
        getConfig().set("settings.checkpoint-radius", checkpointRadius);
        getConfig().set("settings.discord-webhook-url", discordWebhookUrl);

        getConfig().set("music.enabled", musicEnabled);
        getConfig().set("music.sound-name", musicSound);
        getConfig().set("music.loop-duration-seconds", musicDuration);
        getConfig().set("music.volume", musicVolume);
        getConfig().set("music.pitch", musicPitch);

        saveConfig();

        arenasConfig.set("arenas", null);
        for (RaceArena arena : arenas.values()) {
            String path = "arenas." + arena.getName();
            arenasConfig.set(path + ".type", arena.getType().name());
            arenasConfig.set(path + ".laps", arena.getTotalLaps());
            arenasConfig.set(path + ".min-players", arena.minPlayers);
            arenasConfig.set(path + ".auto-start-delay", arena.autoStartDelay);
            arenasConfig.set(path + ".void-y", arena.voidY);
            arenasConfig.set(path + ".lobby", arena.getLobby());
            arenasConfig.set(path + ".mainlobby", arena.getMainLobby());
            arenasConfig.set(path + ".finish1", arena.getFinishPos1());
            arenasConfig.set(path + ".finish2", arena.getFinishPos2());
            arenasConfig.set(path + ".leaderboard", arena.getLeaderboardLocation());
            arenasConfig.set(path + ".spawns", arena.getSpawns());
            arenasConfig.set(path + ".checkpoints", arena.getCheckpoints());

            if (!arena.bestTimes.isEmpty()) {
                for (Map.Entry<UUID, Long> entry : arena.bestTimes.entrySet()) {
                    arenasConfig.set(path + ".best_times." + entry.getKey().toString(), entry.getValue());
                }
            }
        }
        saveArenasConfig();
    }

    private void loadConfigSettings() {
        this.checkpointRadius = getConfig().getDouble("settings.checkpoint-radius", 25.0);
        this.discordWebhookUrl = getConfig().getString("settings.discord-webhook-url", "");

        this.musicEnabled = getConfig().getBoolean("music.enabled", true);
        this.musicSound = getConfig().getString("music.sound-name", "minecraft:coconutmallmariokartwiiostfourone");
        this.musicDuration = getConfig().getInt("music.loop-duration-seconds", 180);
        this.musicVolume = (float) getConfig().getDouble("music.volume", 10000.0);
        this.musicPitch = (float) getConfig().getDouble("music.pitch", 1.0);
    }

    private void loadArenas() {
        ConfigurationSection section = arenasConfig.getConfigurationSection("arenas");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            if (arenas.containsKey(key.toLowerCase())) continue;

            String path = "arenas." + key;
            RaceArena arena = new RaceArena(key, this);

            try {
                arena.setType(RaceArena.RaceType.valueOf(arenasConfig.getString(path + ".type", "DEFAULT")));
            } catch (Exception e) {
                arena.setType(RaceArena.RaceType.DEFAULT);
            }

            arena.setTotalLaps(arenasConfig.getInt(path + ".laps", 1));
            arena.minPlayers = arenasConfig.getInt(path + ".min-players", 2);
            arena.autoStartDelay = arenasConfig.getInt(path + ".auto-start-delay", 30);
            arena.voidY = arenasConfig.getInt(path + ".void-y", -64);

            arena.setLobby(arenasConfig.getLocation(path + ".lobby"));
            arena.setMainLobby(arenasConfig.getLocation(path + ".mainlobby"));
            arena.setLeaderboardLocation(arenasConfig.getLocation(path + ".leaderboard"));

            arena.setFinishLine(
                    arenasConfig.getLocation(path + ".finish1"),
                    arenasConfig.getLocation(path + ".finish2")
            );

            List<?> loadedSpawns = arenasConfig.getList(path + ".spawns");
            if (loadedSpawns != null) for (Object obj : loadedSpawns) if (obj instanceof Location) arena.addSpawn((Location) obj);

            List<?> loadedCheckpoints = arenasConfig.getList(path + ".checkpoints");
            if (loadedCheckpoints != null) for (Object obj : loadedCheckpoints) if (obj instanceof Location) arena.addCheckpoint((Location) obj);

            ConfigurationSection timeSection = arenasConfig.getConfigurationSection(path + ".best_times");
            if (timeSection != null) {
                for (String uuidStr : timeSection.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        long time = timeSection.getLong(uuidStr);
                        arena.bestTimes.put(uuid, time);
                    } catch (Exception ignored) {}
                }
            }

            arenas.put(key.toLowerCase(), arena);
            arena.updateLeaderboardHologram();
            getLogger().info("Loaded arena: " + key);
        }
    }

    private void sendStartupBanner() {
        Bukkit.getConsoleSender().sendMessage(Component.text("                                                 ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender().sendMessage(Component.text("  ___   ____  _____ ____   ___    _  _____ ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender().sendMessage(Component.text(" |_ _| / ___|| ____| __ ) / _ \\  / \\|_   _|", NamedTextColor.AQUA));
        Bukkit.getConsoleSender().sendMessage(Component.text("  | | | |    |  _| |  _ \\| | | |/ _ \\ | |  ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender().sendMessage(Component.text("  | | | |___ | |___| |_) | |_| / ___ \\| |  ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender().sendMessage(Component.text(" |___| \\____||_____|____/ \\___/_/   \\_\\_|  ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender().sendMessage(Component.text("                                                 ", NamedTextColor.AQUA));
        String versionInfo = "   v" + getPluginMeta().getVersion() + " by " + String.join(", ", getPluginMeta().getAuthors()) + " enabled!";
        Bukkit.getConsoleSender().sendMessage(Component.text(versionInfo, NamedTextColor.GREEN));
    }
}