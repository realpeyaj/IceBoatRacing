package peyaj;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent; // Added Import
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class IceBoatRacing extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private final Map<String, RaceArena> arenas = new HashMap<>();
    private final Map<UUID, String> playerArenaMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeVisualizers = new HashMap<>();

    // --- EDITOR DATA ---
    private final Map<UUID, String> editorArena = new HashMap<>();
    private final Map<UUID, EditMode> editorMode = new HashMap<>();

    private double checkpointRadius = 25.0;

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
        // Startup Banner
        Bukkit.getConsoleSender().sendMessage(Component.text("                                                 ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender().sendMessage(Component.text("  ___   ____  _____ ____   ___    _  _____ ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender().sendMessage(Component.text(" |_ _| / ___|| ____| __ ) / _ \\  / \\|_   _|", NamedTextColor.AQUA));
        Bukkit.getConsoleSender().sendMessage(Component.text("  | | | |    |  _| |  _ \\| | | |/ _ \\ | |  ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender().sendMessage(Component.text("  | | | |___ | |___| |_) | |_| / ___ \\| |  ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender().sendMessage(Component.text(" |___| \\____||_____|____/ \\___/_/   \\_\\_|  ", NamedTextColor.AQUA));
        Bukkit.getConsoleSender().sendMessage(Component.text("                                                 ", NamedTextColor.AQUA));

        String versionInfo = "   v" + getPluginMeta().getVersion() + " by " + String.join(", ", getPluginMeta().getAuthors()) + " enabled!";
        Bukkit.getConsoleSender().sendMessage(Component.text(versionInfo, NamedTextColor.GREEN));

        saveDefaultConfig();
        loadArenas();

        Objects.requireNonNull(getCommand("race")).setExecutor(this);
        Objects.requireNonNull(getCommand("race")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override
            public void run() {
                for (RaceArena arena : arenas.values()) arena.tick();
            }
        }.runTaskTimer(this, 0L, 1L);

        new BukkitRunnable() {
            @Override
            public void run() {
                tickVisualizers();
            }
        }.runTaskTimer(this, 0L, 10L);
    }

    @Override
    public void onDisable() {
        for (RaceArena arena : arenas.values()) arena.stopRace();
        saveArenas();
    }

    // ================= EVENT LISTENERS =================

    // NEW: Server-side collision handling (Backup for Scoreboard Teams)
    @EventHandler
    public void onBoatCollision(VehicleEntityCollisionEvent event) {
        // If a boat hits another entity
        if (event.getVehicle() instanceof Boat) {
            // Check if the driver is a racer
            if (!event.getVehicle().getPassengers().isEmpty() && event.getVehicle().getPassengers().get(0) instanceof Player p) {
                if (isRacer(p)) {
                    RaceArena arena = getPlayerArena(p);
                    // If race is active, CANCEL ALL COLLISIONS
                    if (arena != null && arena.state == RaceState.ACTIVE) {
                        event.setCancelled(true);
                        event.setCollisionCancelled(true); // Extra safety for newer versions
                    }
                }
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        ItemStack item = event.getItem();

        if (item != null && item.getType() == Material.BLAZE_ROD && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();

            boolean isWand = false;
            if (meta.hasDisplayName()) {
                Component displayName = meta.displayName();
                if (displayName != null) {
                    String name = PlainTextComponentSerializer.plainText().serialize(displayName);
                    if (name.contains("Race Wand")) isWand = true;
                }
            }

            if (isWand) {
                event.setCancelled(true);

                if (!p.hasPermission("race.admin")) {
                    msg(p, "&cNo permission.");
                    return;
                }

                String arenaName = editorArena.get(p.getUniqueId());
                if (arenaName == null) {
                    msg(p, "&cSelect an arena first: /race admin edit <arena>");
                    return;
                }
                RaceArena arena = arenas.get(arenaName);
                if (arena == null) return;

                EditMode mode = editorMode.getOrDefault(p.getUniqueId(), EditMode.SPAWN);

                if (p.isSneaking() && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
                    EditMode next = mode.next();
                    editorMode.put(p.getUniqueId(), next);
                    p.sendActionBar(Component.text("Mode: " + next.name, next.color));
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 2f);
                    return;
                }

                if (event.getClickedBlock() == null) return;

                if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    Location loc = event.getClickedBlock().getLocation().add(0.5, 1, 0.5);
                    loc.setYaw(p.getLocation().getYaw());

                    switch (mode) {
                        case SPAWN -> {
                            arena.spawns.add(loc);
                            msg(p, "&aSpawn #" + arena.spawns.size() + " added.");
                            p.spawnParticle(Particle.HAPPY_VILLAGER, loc, 20);
                        }
                        case CHECKPOINT -> {
                            arena.checkpoints.add(loc);
                            msg(p, "&aCheckpoint #" + arena.checkpoints.size() + " added.");
                            p.spawnParticle(Particle.DUST, loc, 20, new Particle.DustOptions(Color.RED, 2));
                        }
                        case FINISH_1 -> {
                            arena.finishPos1 = loc;
                            arena.recalculateFinishBox();
                            msg(p, "&bFinish Pos 1 set.");
                        }
                        case FINISH_2 -> {
                            arena.finishPos2 = loc;
                            arena.recalculateFinishBox();
                            msg(p, "&bFinish Pos 2 set.");
                        }
                        case LOBBY -> {
                            arena.lobby = loc;
                            msg(p, "&6Lobby set.");
                        }
                        case MAIN_LOBBY -> {
                            arena.mainLobby = loc;
                            msg(p, "&eMain Lobby set.");
                        }
                    }
                    saveArenas();
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
                }

                if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                    Location clickedBlockLoc = event.getClickedBlock().getLocation();
                    boolean removed = false;

                    switch (mode) {
                        case SPAWN -> {
                            removed = arena.removeNodeAtBlock(arena.spawns, clickedBlockLoc);
                            if (removed) msg(p, "&cSpawn removed.");
                        }
                        case CHECKPOINT -> {
                            removed = arena.removeNodeAtBlock(arena.checkpoints, clickedBlockLoc);
                            if (removed) msg(p, "&cCheckpoint removed.");
                        }
                        default -> msg(p, "&cCannot delete " + mode.name + " with click. Overwrite it instead.");
                    }

                    if (removed) {
                        saveArenas();
                        p.playSound(p.getLocation(), Sound.BLOCK_CANDLE_EXTINGUISH, 1f, 1f);
                        p.spawnParticle(Particle.SMOKE, clickedBlockLoc.add(0.5, 1, 0.5), 20, 0.2, 0.2, 0.2, 0.05);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            if (isRacer(player)) {
                RaceArena arena = getPlayerArena(player);
                if (arena != null && arena.state == RaceState.ACTIVE) {
                    event.setCancelled(true);
                    player.sendMessage(Component.text("You cannot exit the boat during the race!", NamedTextColor.RED));
                }
            }
        }
    }

    // ================= DATA PERSISTENCE =================

    private void saveArenas() {
        getConfig().set("settings.checkpoint-radius", checkpointRadius);
        getConfig().set("arenas", null);

        for (RaceArena arena : arenas.values()) {
            String path = "arenas." + arena.name;
            getConfig().set(path + ".type", arena.type.name());
            getConfig().set(path + ".laps", arena.totalLaps);
            getConfig().set(path + ".lobby", arena.lobby);
            getConfig().set(path + ".mainlobby", arena.mainLobby);
            getConfig().set(path + ".finish1", arena.finishPos1);
            getConfig().set(path + ".finish2", arena.finishPos2);
            getConfig().set(path + ".spawns", arena.spawns);
            getConfig().set(path + ".checkpoints", arena.checkpoints);
        }
        saveConfig();
    }

    private void loadArenas() {
        this.checkpointRadius = getConfig().getDouble("settings.checkpoint-radius", 25.0);
        ConfigurationSection section = getConfig().getConfigurationSection("arenas");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            String path = "arenas." + key;
            RaceArena arena = new RaceArena(key);
            try { arena.type = RaceType.valueOf(getConfig().getString(path + ".type", "DEFAULT")); } catch (Exception e) { arena.type = RaceType.DEFAULT; }
            arena.totalLaps = getConfig().getInt(path + ".laps", 1);
            arena.lobby = getConfig().getLocation(path + ".lobby");
            arena.mainLobby = getConfig().getLocation(path + ".mainlobby");

            arena.finishPos1 = getConfig().getLocation(path + ".finish1");
            arena.finishPos2 = getConfig().getLocation(path + ".finish2");
            arena.recalculateFinishBox();

            List<?> loadedSpawns = getConfig().getList(path + ".spawns");
            if (loadedSpawns != null) for (Object obj : loadedSpawns) if (obj instanceof Location) arena.spawns.add((Location) obj);

            List<?> loadedCheckpoints = getConfig().getList(path + ".checkpoints");
            if (loadedCheckpoints != null) for (Object obj : loadedCheckpoints) if (obj instanceof Location) arena.checkpoints.add((Location) obj);

            arenas.put(key.toLowerCase(), arena);
            getLogger().info("Loaded arena: " + key);
        }
    }

    // ================= VISUALIZER LOGIC =================

    private void tickVisualizers() {
        for (Map.Entry<UUID, String> entry : activeVisualizers.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null || !p.isOnline()) { activeVisualizers.remove(entry.getKey()); continue; }

            RaceArena arena = arenas.get(entry.getValue());
            if (arena == null) continue;

            if (editorArena.containsKey(p.getUniqueId()) && editorArena.get(p.getUniqueId()).equals(arena.name)) {
                EditMode mode = editorMode.getOrDefault(p.getUniqueId(), EditMode.SPAWN);
                p.sendActionBar(Component.text("Editing: " + arena.name + " | Mode: " + mode.name + " (Shift+RC to cycle)", mode.color));
            }

            for (Location loc : arena.spawns) p.spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0, 1, 0), 5, 0.2, 0.2, 0.2, 0);

            Location prev = (arena.spawns.isEmpty()) ? null : arena.spawns.getFirst();
            for (Location loc : arena.checkpoints) {
                p.spawnParticle(Particle.DUST, loc.clone().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0, new Particle.DustOptions(Color.RED, 1.5f));
                drawCircle(p, loc, checkpointRadius, Color.RED);
                if (prev != null) drawLine(p, prev, loc, Color.GRAY);
                prev = loc;
            }

            if (arena.finishBox != null && arena.finishPos1 != null) {
                drawBox(p, arena.finishBox);
                Location center = arena.finishBox.getCenter().toLocation(arena.finishPos1.getWorld());

                if (prev != null) drawLine(p, prev, center, Color.YELLOW);
                if (arena.type == RaceType.LAP && !arena.spawns.isEmpty()) {
                    drawLine(p, center, arena.spawns.getFirst(), Color.TEAL);
                }
            } else if (arena.finishPos1 != null) {
                p.spawnParticle(Particle.END_ROD, arena.finishPos1, 5, 0, 0, 0, 0);
            }

            if (arena.lobby != null) p.spawnParticle(Particle.NOTE, arena.lobby.clone().add(0, 1, 0), 1, 0, 0, 0, 0);
            if (arena.mainLobby != null) p.spawnParticle(Particle.NOTE, arena.mainLobby.clone().add(0, 1, 0), 1, 0, 0, 0, 0);
        }
    }

    private void drawLine(Player p, Location p1, Location p2, Color color) {
        if (p1.getWorld() != p2.getWorld()) return;
        double dist = p1.distance(p2);
        Vector v = p2.toVector().subtract(p1.toVector()).normalize();
        Location curr = p1.clone();
        for (double d = 0; d < dist; d += 1.0) {
            p.spawnParticle(Particle.DUST, curr.add(0,1,0), 1, 0, 0, 0, 0, new Particle.DustOptions(color, 0.8f));
            curr.add(v);
        }
    }

    private void drawCircle(Player p, Location center, double radius, Color color) {
        for (int i = 0; i < 360; i += 15) {
            double rad = Math.toRadians(i);
            double x = radius * Math.cos(rad);
            double z = radius * Math.sin(rad);
            p.spawnParticle(Particle.DUST, center.clone().add(x, 1, z), 1, 0, 0, 0, 0, new Particle.DustOptions(color, 0.5f));
        }
    }

    private void drawBox(Player p, BoundingBox box) {
        Color color = Color.WHITE;
        double minX = box.getMinX(), minY = box.getMinY(), minZ = box.getMinZ();
        double maxX = box.getMaxX(), maxY = box.getMaxY(), maxZ = box.getMaxZ();

        drawLine(p, new Location(p.getWorld(), minX, minY, minZ), new Location(p.getWorld(), maxX, minY, minZ), color);
        drawLine(p, new Location(p.getWorld(), minX, minY, minZ), new Location(p.getWorld(), minX, minY, maxZ), color);
        drawLine(p, new Location(p.getWorld(), maxX, minY, maxZ), new Location(p.getWorld(), maxX, minY, minZ), color);
        drawLine(p, new Location(p.getWorld(), maxX, minY, maxZ), new Location(p.getWorld(), maxX, maxY, minZ), color);

        drawLine(p, new Location(p.getWorld(), minX, maxY, minZ), new Location(p.getWorld(), maxX, maxY, minZ), color);
        drawLine(p, new Location(p.getWorld(), minX, maxY, minZ), new Location(p.getWorld(), minX, maxY, maxZ), color);
        drawLine(p, new Location(p.getWorld(), maxX, maxY, maxZ), new Location(p.getWorld(), minX, maxY, maxZ), color);
        drawLine(p, new Location(p.getWorld(), maxX, maxY, maxZ), new Location(p.getWorld(), maxX, maxY, minZ), color);

        drawLine(p, new Location(p.getWorld(), minX, minY, minZ), new Location(p.getWorld(), minX, maxY, minZ), color);
        drawLine(p, new Location(p.getWorld(), maxX, minY, minZ), new Location(p.getWorld(), maxX, maxY, minZ), color);
        drawLine(p, new Location(p.getWorld(), minX, minY, maxZ), new Location(p.getWorld(), minX, maxY, maxZ), color);
        drawLine(p, new Location(p.getWorld(), maxX, minY, maxZ), new Location(p.getWorld(), maxX, maxY, maxZ), color);
    }

    // ================= COMMANDS =================

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 0) { sendHelp(player); return true; }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "admin" -> {
                if (!player.hasPermission("race.admin")) return noPerm(player);
                if (args.length < 2) {
                    player.sendMessage(Component.text("Usage: /race admin <action> <arena>", NamedTextColor.RED));
                    return true;
                }
                String action = args[1].toLowerCase();

                switch(action) {
                    case "create" -> {
                        if (args.length < 3) return msg(player, "&cUsage: /race admin create <name> [DEFAULT|LAP]");
                        String name = args[2].toLowerCase();
                        if (arenas.containsKey(name)) return msg(player, "&cArena exists.");

                        RaceArena newArena = new RaceArena(name);
                        if (args.length > 3) {
                            try {
                                newArena.type = RaceType.valueOf(args[3].toUpperCase());
                                if (newArena.type == RaceType.LAP) newArena.totalLaps = 3;
                            } catch (Exception e) {
                                return msg(player, "&cInvalid type.");
                            }
                        }
                        arenas.put(name, newArena);
                        saveArenas();
                        return msg(player, "&aCreated " + name);
                    }
                    case "delete", "remove" -> {
                        if (args.length < 3) return msg(player, "&cUsage: /race admin delete <arena>");
                        String name = args[2].toLowerCase();
                        if (!arenas.containsKey(name)) return msg(player, "&cArena not found.");

                        RaceArena toRemove = arenas.get(name);
                        toRemove.stopRace();
                        arenas.remove(name);

                        getConfig().set("arenas." + name, null);
                        saveConfig();

                        return msg(player, "&cDeleted arena " + name);
                    }
                    case "wand" -> {
                        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
                        ItemMeta meta = wand.getItemMeta();
                        meta.displayName(Component.text("Race Wand", NamedTextColor.GOLD));
                        meta.lore(Arrays.asList(
                                Component.text("Left Click: Remove Node", NamedTextColor.RED),
                                Component.text("Right Click: Add Node", NamedTextColor.GREEN),
                                Component.text("Shift+RC: Cycle Mode", NamedTextColor.YELLOW)
                        ));
                        wand.setItemMeta(meta);
                        player.getInventory().addItem(wand);
                        return msg(player, "&aGiven Race Wand. Use /race admin edit <arena> to start.");
                    }
                    case "edit" -> {
                        if (args.length < 3) return msg(player, "&cUsage: /race admin edit <arena>");
                        String name = args[2].toLowerCase();
                        if (!arenas.containsKey(name)) return msg(player, "&cArena not found.");

                        editorArena.put(player.getUniqueId(), name);
                        editorMode.put(player.getUniqueId(), EditMode.SPAWN);
                        activeVisualizers.put(player.getUniqueId(), name);
                        return msg(player, "&aEditing " + name + ". Wand Mode: SPAWN. Visualizer ON.");
                    }
                    case "stopedit" -> {
                        editorArena.remove(player.getUniqueId());
                        editorMode.remove(player.getUniqueId());
                        activeVisualizers.remove(player.getUniqueId());
                        return msg(player, "&eExited editing mode.");
                    }
                    default -> {
                        if (action.equals("setradius")) {
                            if (args.length < 3) return msg(player, "&cUsage: /race admin setradius <value>");
                            try {
                                this.checkpointRadius = Double.parseDouble(args[2]);
                                saveArenas(); // Saves settings
                                return msg(player, "&aGlobal Checkpoint Radius set to " + checkpointRadius);
                            } catch (NumberFormatException e) {
                                return msg(player, "&cInvalid number.");
                            }
                        }

                        // Handle other commands (setlaps, visualize, etc)
                        if (args.length < 3) return msg(player, "&cSpecify arena.");
                        RaceArena arena = arenas.get(args[2].toLowerCase());
                        if (arena == null) return msg(player, "&cArena not found.");

                        switch (action) {
                            case "visualize" -> {
                                if (activeVisualizers.containsKey(player.getUniqueId())) {
                                    activeVisualizers.remove(player.getUniqueId());
                                    msg(player, "&eVisualizer OFF");
                                } else {
                                    activeVisualizers.put(player.getUniqueId(), arena.name);
                                    msg(player, "&aVisualizer ON");
                                }
                            }
                            case "setlaps" -> {
                                try {
                                    arena.totalLaps = Integer.parseInt(args[3]);
                                    msg(player, "&aLaps set to " + arena.totalLaps);
                                } catch (Exception e) {
                                    msg(player, "&cInvalid number.");
                                }
                            }
                            case "addspawn" -> {
                                arena.spawns.add(player.getLocation());
                                msg(player, "&aSpawn added.");
                            }
                            case "addcp" -> {
                                arena.checkpoints.add(player.getLocation());
                                msg(player, "&aCheckpoint added.");
                            }
                            case "setfinish" -> {
                                if (args.length < 4) return msg(player, "&cUsage: setfinish <arena> <1|2>");
                                if (args[3].equals("1")) {
                                    arena.finishPos1 = player.getLocation();
                                    msg(player, "&aFinish Pos 1 set.");
                                } else if (args[3].equals("2")) {
                                    arena.finishPos2 = player.getLocation();
                                    msg(player, "&aFinish Pos 2 set.");
                                } else return msg(player, "&cUse 1 or 2.");
                                arena.recalculateFinishBox();
                            }
                            case "setlobby" -> {
                                arena.lobby = player.getLocation();
                                msg(player, "&aLobby set.");
                            }
                            case "setmainlobby" -> {
                                arena.mainLobby = player.getLocation();
                                msg(player, "&aMain Lobby set.");
                            }
                        }
                        saveArenas();
                        return true;
                    }
                }
            }
            case "list" -> {
                player.sendMessage(Component.text("Arenas:", NamedTextColor.AQUA));
                for (RaceArena a : arenas.values())
                    player.sendMessage(Component.text("- " + a.name + " (" + a.state + ")", NamedTextColor.WHITE));
                return true;
            }
            case "join" -> {
                if (playerArenaMap.containsKey(player.getUniqueId())) return msg(player, "&cAlready in race.");
                if (args.length < 2) return msg(player, "&cUsage: /race join <arena>");
                RaceArena a = arenas.get(args[1].toLowerCase());
                if (a == null) return msg(player, "&cNot found.");
                if (a.state != RaceState.LOBBY) return msg(player, "&cRunning.");
                a.addPlayer(player);
                playerArenaMap.put(player.getUniqueId(), a.name);
                return true;
            }
            case "leave" -> {
                RaceArena a = getPlayerArena(player);
                if (a != null) {
                    a.removePlayer(player);
                    playerArenaMap.remove(player.getUniqueId());
                    msg(player, "&eLeft race.");
                    if (a.mainLobby != null) player.teleport(a.mainLobby);
                }
                return true;
            }
            case "cp", "checkpoint" -> {
                RaceArena a = getPlayerArena(player);
                if (a != null) a.respawnPlayer(player);
                return true;
            }
            case "start" -> {
                if (!player.hasPermission("race.admin")) return noPerm(player);
                RaceArena a = arenas.get(args[1].toLowerCase());
                if (a != null) a.startRace();
                return true;
            }
            case "stop" -> {
                if (!player.hasPermission("race.admin")) return noPerm(player);
                RaceArena a = arenas.get(args[1].toLowerCase());
                if (a != null) {
                    a.stopRace();
                    Bukkit.broadcast(Component.text("Stopped " + a.name, NamedTextColor.RED));
                }
                return true;
            }
        }

        return true;
    }

    private boolean msg(Player p, String m) {
        p.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(m));
        return true;
    }
    private boolean noPerm(Player p) { return msg(p, "&cNo permission."); }
    private void sendHelp(Player p) { msg(p, "&b/race join|leave|list|admin"); }
    public RaceArena getPlayerArena(Player p) { String n = playerArenaMap.get(p.getUniqueId()); return (n==null)?null:arenas.get(n); }
    public boolean isRacer(Player p) { return playerArenaMap.containsKey(p.getUniqueId()); }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> cmds = new ArrayList<>(Arrays.asList("join", "leave", "list", "cp"));
            if (sender.hasPermission("race.admin")) {
                cmds.add("admin");
                cmds.add("start");
                cmds.add("stop");
            }
            String input = args[0].toLowerCase();
            return cmds.stream().filter(s -> s.startsWith(input)).collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("join") || sub.equals("start") || sub.equals("stop")) {
                String input = args[1].toLowerCase();
                return arenas.keySet().stream().filter(s -> s.startsWith(input)).collect(Collectors.toList());
            }
            if (sub.equals("admin") && sender.hasPermission("race.admin")) {
                List<String> admin = Arrays.asList("create", "delete", "wand", "edit", "stopedit", "visualize", "setlaps", "setradius", "addspawn", "addcp", "setfinish", "setlobby", "setmainlobby");
                String input = args[1].toLowerCase();
                return admin.stream().filter(s -> s.startsWith(input)).collect(Collectors.toList());
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            if (!args[1].equalsIgnoreCase("create") && !args[1].equalsIgnoreCase("wand") && !args[1].equalsIgnoreCase("stopedit") && !args[1].equalsIgnoreCase("setradius")) {
                String input = args[2].toLowerCase();
                return arenas.keySet().stream().filter(s -> s.startsWith(input)).collect(Collectors.toList());
            }
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("create")) {
            return Arrays.asList("DEFAULT", "LAP");
        }

        return Collections.emptyList();
    }

    // ==========================================================
    //                  ARENA CLASS
    // ==========================================================

    public enum RaceType { DEFAULT, LAP }
    public enum RaceState { LOBBY, STARTING, ACTIVE }

    public class RaceArena {
        public String name;
        public RaceType type = RaceType.DEFAULT;
        public int totalLaps = 1;
        public RaceState state = RaceState.LOBBY;

        public List<Location> spawns = new ArrayList<>();
        public List<Location> checkpoints = new ArrayList<>();
        public Location lobby, mainLobby;

        public Location finishPos1, finishPos2;
        public BoundingBox finishBox;
        public Location finishCenter;

        private final Map<UUID, Integer> playerCheckpoints = new HashMap<>();
        private final Map<UUID, Integer> playerLaps = new HashMap<>();
        private final Map<UUID, Long> startTimes = new HashMap<>();
        private final Map<UUID, Boat> playerBoats = new HashMap<>();
        private final Set<UUID> players = new HashSet<>();
        private final List<UUID> finishOrder = new ArrayList<>();
        private final Map<UUID, Location> lastLocations = new HashMap<>();
        private final List<Location> glassBlocks = new ArrayList<>();

        public RaceArena(String name) { this.name = name; }

        // FIXED: New method to check for exact block location
        public boolean removeNodeAtBlock(List<Location> list, Location clickedBlockLoc) {
            Iterator<Location> it = list.iterator();
            while(it.hasNext()) {
                Location nodeLoc = it.next();
                // Check if the node is in the same world and has the same block coordinates as the clicked block
                // (ignoring the +1 Y offset logic here, checking if the node is "above" or "at" the clicked block)
                if (nodeLoc.getWorld().equals(clickedBlockLoc.getWorld()) &&
                        nodeLoc.getBlockX() == clickedBlockLoc.getBlockX() &&
                        nodeLoc.getBlockZ() == clickedBlockLoc.getBlockZ() &&
                        // Check Y: Either exact match or node is 1 block above clicked block
                        (nodeLoc.getBlockY() == clickedBlockLoc.getBlockY() || nodeLoc.getBlockY() == clickedBlockLoc.getBlockY() + 1)) {
                    it.remove();
                    return true;
                }
            }
            return false;
        }

        public void recalculateFinishBox() {
            if (finishPos1 != null && finishPos2 != null && finishPos1.getWorld().equals(finishPos2.getWorld())) {
                finishBox = BoundingBox.of(finishPos1, finishPos2);
                finishCenter = finishBox.getCenter().toLocation(finishPos1.getWorld());
            }
        }

        public void addPlayer(Player p) {
            players.add(p.getUniqueId());
            if (lobby != null) { p.teleport(lobby); p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f); }
            playerCheckpoints.put(p.getUniqueId(), 0);
            playerLaps.put(p.getUniqueId(), 1);
            p.sendMessage(Component.text("Joined " + name, NamedTextColor.GREEN));
            updateLobbyScoreboard();
        }

        public void removePlayer(Player p) {
            players.remove(p.getUniqueId());
            if (playerBoats.containsKey(p.getUniqueId())) {
                Boat b = playerBoats.remove(p.getUniqueId());
                if (b != null) b.remove();
            }
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            updateLobbyScoreboard();
        }

        public void startRace() {
            if (spawns.isEmpty()) return;
            state = RaceState.STARTING;
            removeCages();
            finishOrder.clear();

            int spawnIndex = 0;
            for (UUID uuid : players) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;

                Location spawn = spawns.get(spawnIndex % spawns.size());
                spawnIndex++;
                p.teleport(spawn);

                Boat boat = (Boat) p.getWorld().spawnEntity(spawn, EntityType.BOAT);
                boat.addPassenger(p);
                boat.setInvulnerable(true); // COLLISION FIX: Prevents breaking
                playerBoats.put(uuid, boat);

                createCage(spawn);
                playerCheckpoints.put(uuid, 0);
                playerLaps.put(uuid, 1);

                setupRaceScoreboard(p);
            }

            // COLLISION FIX: Aggressively add everyone to everyone's ghost team
            for (UUID uuid : playerBoats.keySet()) {
                Boat b = playerBoats.get(uuid);
                Player p = Bukkit.getPlayer(uuid);

                // Add this boat/player to the ghost team of EVERY OTHER player in the race
                for (UUID otherUUID : players) {
                    Player otherP = Bukkit.getPlayer(otherUUID);
                    if (otherP != null) {
                        Team t = otherP.getScoreboard().getTeam("ghost");
                        if (t != null) {
                            if (p != null) t.addEntry(p.getName());
                            if (b != null) t.addEntry(b.getUniqueId().toString());
                        }
                    }
                }

                // --- VISIBILITY REFRESH TRICK ---
                // Force client to reload the entity AFTER it's been added to the team
                if (b != null) {
                    for (UUID otherUUID : players) {
                        if (!otherUUID.equals(uuid)) { // Don't hide from self
                            Player otherP = Bukkit.getPlayer(otherUUID);
                            if (otherP != null) {
                                otherP.hideEntity(IceBoatRacing.this, b);
                                otherP.showEntity(IceBoatRacing.this, b);
                            }
                        }
                    }
                }
            }

            new BukkitRunnable() {
                int count = 5;
                @Override
                public void run() {
                    if (state != RaceState.STARTING) { removeCages(); this.cancel(); return; }
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
            }.runTaskTimer(IceBoatRacing.this, 0L, 20L);
        }

        public void stopRace() {
            state = RaceState.LOBBY;
            removeCages();
            for (Boat b : playerBoats.values()) b.remove();
            playerBoats.clear();
            finishOrder.clear();

            for (UUID uuid : players) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                    p.setGameMode(GameMode.SURVIVAL);
                    if (mainLobby != null) p.teleport(mainLobby);
                    else p.teleport(p.getWorld().getSpawnLocation());
                    IceBoatRacing.this.playerArenaMap.remove(uuid);
                }
            }
            players.clear();
        }

        public void tick() {
            if (state == RaceState.LOBBY) return;

            if (state == RaceState.ACTIVE) {
                List<UUID> ranking = calculateRankings();
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

                        spawnExhaustParticles(p, playerBoats.get(uuid));

                        long timeMs = System.currentTimeMillis() - startTimes.getOrDefault(uuid, System.currentTimeMillis());
                        String timeStr = formatTime(timeMs);

                        int displayLap = (type == RaceType.LAP) ? playerLaps.getOrDefault(uuid, 1) : 1;
                        int maxLap = (type == RaceType.LAP) ? totalLaps : 1;
                        int cp = playerCheckpoints.getOrDefault(uuid, 0);

                        // FIX: Pass raw CP index (0, 1, 2...) directly, without +1 offset for display
                        updateRaceScoreboard(p, timeStr, speedKmH, cp, checkpoints.size(), displayLap, maxLap, ranking);

                        // FIX: Remove +1 offset for action bar as well
                        String abText = String.format("§b%.0f km/h  §7|  §aCP: %d/%d", speedKmH, cp, checkpoints.size());
                        if (type == RaceType.LAP) {
                            abText += String.format("  §7|  §6Lap: %d/%d", displayLap, maxLap);
                        }
                        p.sendActionBar(Component.text(abText));

                        highlightNextTarget(p, uuid);

                    } else {
                        updateRaceScoreboard(p, "FINISHED", 0, 0, 0, 0, 0, ranking);
                    }
                }
            }
        }

        private void highlightNextTarget(Player p, UUID uuid) {
            int currentCpIndex = playerCheckpoints.getOrDefault(uuid, 0);
            Location target = null;

            if (currentCpIndex < checkpoints.size()) {
                target = checkpoints.get(currentCpIndex);
            } else if (finishPos1 != null) {
                if (finishCenter != null) target = finishCenter;
                else target = finishPos1;
            }

            if (target != null) {
                p.spawnParticle(Particle.HAPPY_VILLAGER, target.clone().add(0, 1.5, 0), 2, 0.2, 0.2, 0.2, 0);
            }
        }

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
                if (lineSegmentIntersectsSphere(from, to, cpTarget, checkpointRadius)) {
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
                    if (result != null) {
                        handleFinishLineHit(p, uuid);
                        return true;
                    }
                }
                if (finishBox.contains(to.toVector())) {
                    handleFinishLineHit(p, uuid);
                    return true;
                }
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
                    p.sendMessage(Component.text("Lap " + (lap + 1) + "/" + totalLaps, NamedTextColor.GOLD));
                } else {
                    finishPlayer(p);
                }
            } else {
                finishPlayer(p);
            }
        }

        private boolean lineSegmentIntersectsSphere(Location p1, Location p2, Location sphereCenter, double radius) {
            if (p1.getWorld() != sphereCenter.getWorld()) return false;
            if (Math.abs(p1.getY() - sphereCenter.getY()) > 15 && Math.abs(p2.getY() - sphereCenter.getY()) > 15) return false;
            Vector d = p2.toVector().subtract(p1.toVector());
            Vector f = p1.toVector().subtract(sphereCenter.toVector());
            double a = d.dot(d);
            double b = 2 * f.dot(d);
            double c = f.dot(f) - radius * radius;
            double disc = b*b - 4*a*c;
            if (disc < 0) return false;
            disc = Math.sqrt(disc);
            double t1 = (-b - disc) / (2*a);
            double t2 = (-b + disc) / (2*a);
            return (t1 >= 0 && t1 <= 1) || (t2 >= 0 && t2 <= 1);
        }

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
                double d2 = getDistanceToTarget(u2, c2); // c1 == c2 here
                return Double.compare(d1, d2);
            });
            return rankList;
        }

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

        private void finishPlayer(Player p) {
            if (finishOrder.contains(p.getUniqueId())) return;
            finishOrder.add(p.getUniqueId());
            long timeMs = System.currentTimeMillis() - startTimes.get(p.getUniqueId());
            String timeStr = formatTime(timeMs);
            p.showTitle(Title.title(Component.text("FINISHED!", NamedTextColor.GOLD), Component.text("Time: " + timeStr, NamedTextColor.YELLOW)));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1f, 1f);
            Boat boat = playerBoats.remove(p.getUniqueId());
            if (boat != null) boat.remove();
            p.setGameMode(GameMode.SPECTATOR);
            Bukkit.broadcast(Component.text(p.getName() + " finished " + name + " in " + timeStr + "!", NamedTextColor.YELLOW));
            if (finishOrder.size() >= players.size()) {
                Bukkit.broadcast(Component.text("Arena " + name + " finished! Closing...", NamedTextColor.GREEN));
                new BukkitRunnable() { @Override public void run() { stopRace(); } }.runTaskLater(IceBoatRacing.this, 100L);
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
            boat.addPassenger(p);
            playerBoats.put(p.getUniqueId(), boat);
            updateCollision(boat);

            // --- REFRESH VISIBILITY ON RESPAWN TOO ---
            if (boat != null) {
                for (UUID otherUUID : players) {
                    if (!otherUUID.equals(p.getUniqueId())) {
                        Player otherP = Bukkit.getPlayer(otherUUID);
                        if (otherP != null) {
                            otherP.hideEntity(IceBoatRacing.this, boat);
                            otherP.showEntity(IceBoatRacing.this, boat);
                        }
                    }
                }
            }
        }

        private void createCage(Location spawn) {
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 2; y++) {
                if (Math.abs(x) <= 1 && Math.abs(z) <= 1) continue;
                Location b = spawn.clone().add(x, y, z);
                if (b.getBlock().getType() == Material.AIR) { b.getBlock().setType(Material.GLASS); glassBlocks.add(b); }
            }
        }
        private void removeCages() { for (Location l : glassBlocks) if (l.getBlock().getType() == Material.GLASS) l.getBlock().setType(Material.AIR); glassBlocks.clear(); }

        public void updateLobbyScoreboard() {
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
            // Removed NumberFormat for compatibility
            // o.numberFormat(NumberFormat.blank());
            o.getScore("§7----------------").setScore(6);
            o.getScore("§eArena:").setScore(5);
            o.getScore("  §f" + name).setScore(4);
            o.getScore(" ").setScore(3);
            o.getScore("§aPlayers: §f" + players.size()).setScore(2);
            o.getScore("§6Laps: §f" + totalLaps).setScore(1);
            o.getScore("§7---------------- ").setScore(0);
            p.setScoreboard(b);
        }

        private void setupRaceScoreboard(Player p) {
            ScoreboardManager m = Bukkit.getScoreboardManager();
            Scoreboard b = m.getNewScoreboard();
            Objective o = b.registerNewObjective("IceRace", Criteria.DUMMY, Component.text("§b§lICE BOAT"));
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            // Removed NumberFormat for compatibility
            // o.numberFormat(NumberFormat.blank());
            Team ghost = b.registerNewTeam("ghost");
            ghost.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);

            createTeam(b, "stats", "§fTime: 00:00");
            o.getScore("§7----------------").setScore(15);
            o.getScore("§eStats:").setScore(14);
            o.getScore("§f").setScore(13); // Holder for stats
            o.getScore(" ").setScore(12);
            o.getScore("§e§lTOP RACERS").setScore(11);

            String[] rankKeys = {"§1", "§2", "§3", "§4", "§5", "§6", "§7", "§8", "§9", "§a"};
            for (int i = 0; i < 10; i++) {
                String key = rankKeys[i];
                createTeam(b, "rank_" + (i + 1), "§7" + (i + 1) + ". ---");
                o.getScore(key).setScore(10 - i);
                Team t = b.getTeam("rank_" + (i + 1));
                if (t != null) t.addEntry(key);
            }
            Team stats = b.getTeam("stats");
            if (stats != null) stats.addEntry("§f");
            p.setScoreboard(b);
        }

        private void createTeam(Scoreboard b, String name, String suffix) { Team t = b.registerNewTeam(name); t.suffix(Component.text(suffix)); }

        private void updateRaceScoreboard(Player p, String time, double speed, int cp, int totalCps, int lap, int maxLaps, List<UUID> ranking) {
            Scoreboard b = p.getScoreboard();
            if (b.getObjective("IceRace") == null) return;
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

        private void updateCollision(Entity e) {
            String entry = (e instanceof Player) ? e.getName() : e.getUniqueId().toString();
            for (UUID uuid : players) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    Scoreboard board = p.getScoreboard();
                    Team t = board.getTeam("ghost");
                    if (t != null && !t.hasEntry(entry)) t.addEntry(entry);
                }
            }
        }
    }

    private void spawnExhaustParticles(Player p, Boat boat) {
        if (boat != null && !boat.isDead() && p.getVehicle() == boat) {
            Vector dir = boat.getLocation().getDirection().multiply(-1);
            p.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, boat.getLocation().add(dir.multiply(0.9)).add(0, 0.4, 0), 1, 0, 0, 0, 0);
        }
    }

    private String formatTime(long millis) {
        long min = (millis / 1000) / 60;
        long sec = (millis / 1000) % 60;
        long ms = millis % 1000;
        return String.format("%02d:%02d.%03d", min, sec, ms);
    }
}