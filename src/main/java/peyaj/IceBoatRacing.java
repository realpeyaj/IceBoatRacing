package peyaj;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class IceBoatRacing extends JavaPlugin {

    private final Map<String, RaceArena> arenas = new HashMap<>();
    private final Map<UUID, String> playerArenaMap = new ConcurrentHashMap<>();

    // --- EDITOR DATA ---
    public final Map<UUID, String> editorArena = new HashMap<>();
    public final Map<UUID, EditMode> editorMode = new HashMap<>();
    public final Map<UUID, String> activeVisualizers = new HashMap<>();

    public GUIManager guiManager; // New GUI Manager

    // --- SETTINGS ---
    public double checkpointRadius = 25.0;
    public String discordWebhookUrl = "";

    public boolean musicEnabled = true;
    public String musicSound = "minecraft:coconutmallmariokartwiiostfourone";
    public int musicDuration = 180;
    public float musicVolume = 10000.0f;
    public float musicPitch = 1.0f;

    public enum EditMode {
        SPAWN("Spawn Points", NamedTextColor.GREEN),
        CHECKPOINT("Checkpoints", NamedTextColor.RED),
        FINISH_1("Finish Pos 1", NamedTextColor.AQUA),
        FINISH_2("Finish Pos 2", NamedTextColor.AQUA),
        LOBBY("Pre-Lobby", NamedTextColor.GOLD),
        MAIN_LOBBY("Main Lobby", NamedTextColor.YELLOW);

        public final String name;
        public final NamedTextColor color;
        EditMode(String name, NamedTextColor color) { this.name = name; this.color = color; }

        public EditMode next() {
            int idx = this.ordinal() + 1;
            if (idx >= values().length) idx = 0;
            return values()[idx];
        }
    }

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public void onEnable() {
        sendStartupBanner();

        saveDefaultConfig();
        loadConfigSettings();
        loadArenas();

        // Initialize GUI
        guiManager = new GUIManager(this);
        getServer().getPluginManager().registerEvents(guiManager, this);

        // Register Commands & Events
        Objects.requireNonNull(getCommand("race")).setExecutor(new RaceCommand(this));
        getServer().getPluginManager().registerEvents(new RaceListener(this), this);

        // 1. Game Loop (1 Tick)
        new BukkitRunnable() {
            @Override
            public void run() {
                for (RaceArena arena : arenas.values()) {
                    arena.tick();
                }
            }
        }.runTaskTimer(this, 0L, 1L);

        // 2. Visualizer Loop (10 Ticks)
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
    }

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

    // --- CONFIG LOADING/SAVING ---

    public void saveArenas() {
        getConfig().set("settings.checkpoint-radius", checkpointRadius);
        getConfig().set("settings.discord-webhook-url", discordWebhookUrl);

        getConfig().set("music.enabled", musicEnabled);
        getConfig().set("music.sound-name", musicSound);
        getConfig().set("music.loop-duration-seconds", musicDuration);
        getConfig().set("music.volume", musicVolume);
        getConfig().set("music.pitch", musicPitch);

        getConfig().set("arenas", null); // Clear old

        for (RaceArena arena : arenas.values()) {
            String path = "arenas." + arena.getName();
            getConfig().set(path + ".type", arena.getType().name());
            getConfig().set(path + ".laps", arena.getTotalLaps());
            getConfig().set(path + ".min-players", arena.minPlayers);
            getConfig().set(path + ".auto-start-delay", arena.autoStartDelay);
            getConfig().set(path + ".lobby", arena.getLobby());
            getConfig().set(path + ".mainlobby", arena.getMainLobby());
            getConfig().set(path + ".finish1", arena.getFinishPos1());
            getConfig().set(path + ".finish2", arena.getFinishPos2());
            getConfig().set(path + ".spawns", arena.getSpawns());
            getConfig().set(path + ".checkpoints", arena.getCheckpoints());
        }
        saveConfig();
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
        ConfigurationSection section = getConfig().getConfigurationSection("arenas");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            String path = "arenas." + key;
            RaceArena arena = new RaceArena(key, this);

            try {
                arena.setType(RaceArena.RaceType.valueOf(getConfig().getString(path + ".type", "DEFAULT")));
            } catch (Exception e) {
                arena.setType(RaceArena.RaceType.DEFAULT);
            }

            arena.setTotalLaps(getConfig().getInt(path + ".laps", 1));
            arena.minPlayers = getConfig().getInt(path + ".min-players", 2);
            arena.autoStartDelay = getConfig().getInt(path + ".auto-start-delay", 30);

            arena.setLobby(getConfig().getLocation(path + ".lobby"));
            arena.setMainLobby(getConfig().getLocation(path + ".mainlobby"));

            arena.setFinishLine(
                    getConfig().getLocation(path + ".finish1"),
                    getConfig().getLocation(path + ".finish2")
            );

            List<?> loadedSpawns = getConfig().getList(path + ".spawns");
            if (loadedSpawns != null) for (Object obj : loadedSpawns) if (obj instanceof Location) arena.addSpawn((Location) obj);

            List<?> loadedCheckpoints = getConfig().getList(path + ".checkpoints");
            if (loadedCheckpoints != null) for (Object obj : loadedCheckpoints) if (obj instanceof Location) arena.addCheckpoint((Location) obj);

            arenas.put(key.toLowerCase(), arena);
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