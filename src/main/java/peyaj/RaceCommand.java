package peyaj;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public class RaceCommand implements CommandExecutor, TabCompleter {

    private final IceBoatRacing plugin;

    public RaceCommand(IceBoatRacing plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 0) { sendHelp(player); return true; }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "admin" -> handleAdmin(player, args);
            case "list" -> handleList(player);
            case "join" -> handleJoin(player, args);
            case "leave" -> handleLeave(player);
            case "cp", "checkpoint" -> handleCheckpoint(player);
            case "start" -> handleStart(player, args);
            case "stop" -> handleStop(player, args);
            case "reload" -> handleReload(player);
            default -> sendHelp(player);
        }
        return true;
    }

    private void handleAdmin(Player player, String[] args) {
        if (!player.hasPermission("race.admin")) { msg(player, "&cNo permission."); return; }

        // GUI SHORTCUT: /race admin (no args) opens GUI
        if (args.length < 2) {
            plugin.guiManager.openMainMenu(player);
            return;
        }

        String action = args[1].toLowerCase();

        // Actions that don't need specific arena first
        if (action.equals("create")) {
            if (args.length < 3) { msg(player, "&cUsage: /race admin create <name> [DEFAULT|LAP]"); return; }
            String name = args[2].toLowerCase();
            if (plugin.getArenas().containsKey(name)) { msg(player, "&cArena exists."); return; }

            RaceArena newArena = new RaceArena(name, plugin);
            if (args.length > 3) {
                try {
                    newArena.setType(RaceArena.RaceType.valueOf(args[3].toUpperCase()));
                    if (newArena.getType() == RaceArena.RaceType.LAP) newArena.setTotalLaps(3);
                } catch (Exception e) {
                    msg(player, "&cInvalid type.");
                    return;
                }
            }
            plugin.addArena(name, newArena);
            plugin.saveArenas();
            msg(player, "&aCreated " + name);
            return;
        }

        if (action.equals("delete") || action.equals("remove")) {
            if (args.length < 3) { msg(player, "&cUsage: /race admin delete <name>"); return; }
            String name = args[2].toLowerCase();
            RaceArena a = plugin.getArena(name);
            if (a == null) { msg(player, "&cNot found."); return; }
            a.stopRace();
            plugin.removeArena(name);
            // Clear from config
            plugin.getConfig().set("arenas." + name, null);
            plugin.saveConfig();
            msg(player, "&cDeleted " + name);
            return;
        }

        if (action.equals("wand")) {
            giveWand(player);
            return;
        }

        if (action.equals("stopedit")) {
            plugin.editorArena.remove(player.getUniqueId());
            plugin.editorMode.remove(player.getUniqueId());
            plugin.activeVisualizers.remove(player.getUniqueId());
            msg(player, "&eExited editing mode.");
            return;
        }

        if (action.equals("setradius")) {
            if (args.length < 3) { msg(player, "&cUsage: setradius <#>"); return; }
            try {
                plugin.checkpointRadius = Double.parseDouble(args[2]);
                plugin.saveArenas();
                msg(player, "&aRadius set to " + plugin.checkpointRadius);
            } catch (NumberFormatException e) { msg(player, "&cInvalid number."); }
            return;
        }

        // Actions requiring Arena
        if (args.length < 3) { msg(player, "&cSpecify arena."); return; }
        RaceArena arena = plugin.getArena(args[2].toLowerCase());
        if (arena == null) { msg(player, "&cArena not found."); return; }

        switch (action) {
            case "edit" -> {
                plugin.editorArena.put(player.getUniqueId(), arena.getName());
                plugin.editorMode.put(player.getUniqueId(), IceBoatRacing.EditMode.SPAWN); // Uses public enum from Main
                plugin.activeVisualizers.put(player.getUniqueId(), arena.getName());
                // Open GUI instead of just msg
                plugin.guiManager.openArenaEditor(player, arena);
                msg(player, "&aEditing " + arena.getName() + ". Wand ON.");
            }
            case "visualize" -> {
                if (plugin.activeVisualizers.containsKey(player.getUniqueId())) {
                    plugin.activeVisualizers.remove(player.getUniqueId());
                    msg(player, "&eVisualizer OFF");
                } else {
                    plugin.activeVisualizers.put(player.getUniqueId(), arena.getName());
                    msg(player, "&aVisualizer ON");
                }
            }
            case "setlaps" -> {
                try {
                    arena.setTotalLaps(Integer.parseInt(args[3]));
                    msg(player, "&aLaps set to " + arena.getTotalLaps());
                } catch (Exception e) { msg(player, "&cInvalid number."); }
            }
            case "setminplayers" -> {
                try {
                    arena.minPlayers = Integer.parseInt(args[3]);
                    msg(player, "&aMin players: " + arena.minPlayers);
                } catch (Exception e) { msg(player, "&cInvalid number."); }
            }
            case "setautostart" -> {
                try {
                    arena.autoStartDelay = Integer.parseInt(args[3]);
                    msg(player, "&aAuto Start: " + arena.autoStartDelay + "s");
                } catch (Exception e) { msg(player, "&cInvalid number."); }
            }
            case "addspawn" -> {
                arena.addSpawn(player.getLocation());
                msg(player, "&aSpawn added.");
            }
            case "addcp" -> {
                arena.addCheckpoint(player.getLocation());
                msg(player, "&aCheckpoint added.");
            }
            case "setfinish" -> {
                if (args.length < 4) { msg(player, "&cUsage: setfinish <arena> <1|2>"); return; }
                Location loc = player.getLocation();
                if (args[3].equals("1")) {
                    arena.setFinishLine(loc, arena.getFinishPos2());
                    msg(player, "&aFinish Pos 1 set.");
                } else {
                    arena.setFinishLine(arena.getFinishPos1(), loc);
                    msg(player, "&aFinish Pos 2 set.");
                }
            }
            case "setlobby" -> {
                arena.setLobby(player.getLocation());
                msg(player, "&aLobby set.");
            }
            case "setmainlobby" -> {
                arena.setMainLobby(player.getLocation());
                msg(player, "&aMain Lobby set.");
            }
            // New Leaderboard Command (Backup for Wand)
            case "setleaderboard" -> {
                arena.setLeaderboardLocation(player.getLocation());
                msg(player, "&aLeaderboard Hologram location set.");
            }
        }
        plugin.saveArenas();
    }

    private void handleJoin(Player player, String[] args) {
        if (plugin.isRacer(player.getUniqueId())) { msg(player, "&cAlready in race."); return; }
        if (args.length < 2) { msg(player, "&cUsage: /race join <arena>"); return; }
        RaceArena arena = plugin.getArena(args[1]);
        if (arena == null) { msg(player, "&cNot found."); return; }

        arena.addPlayer(player);
        plugin.setPlayerArena(player.getUniqueId(), arena.getName());
    }

    private void handleLeave(Player player) {
        RaceArena arena = plugin.getPlayerArena(player.getUniqueId());
        if (arena != null) {
            arena.removePlayer(player);
            plugin.removePlayerFromArenaMap(player.getUniqueId());
            msg(player, "&eLeft race.");
            if (arena.getMainLobby() != null) player.teleport(arena.getMainLobby());
        }
    }

    private void handleStart(Player player, String[] args) {
        if (!player.hasPermission("race.admin")) { msg(player, "&cNo permission."); return; }
        if (args.length < 2) return;
        RaceArena arena = plugin.getArena(args[1]);
        if (arena != null) arena.startRace();
    }

    private void handleStop(Player player, String[] args) {
        if (!player.hasPermission("race.admin")) { msg(player, "&cNo permission."); return; }
        if (args.length < 2) return;
        RaceArena arena = plugin.getArena(args[1]);
        if (arena != null) {
            arena.stopRace();
            player.sendMessage(Component.text("Stopped " + arena.getName(), NamedTextColor.RED));
        }
    }

    private void handleReload(Player player) {
        if (!player.hasPermission("race.admin")) { msg(player, "&cNo permission."); return; }
        plugin.reload();
        msg(player, "&aConfiguration reloaded.");
    }

    private void handleCheckpoint(Player player) {
        RaceArena arena = plugin.getPlayerArena(player.getUniqueId());
        if (arena != null) arena.respawnPlayer(player);
    }

    private void handleList(Player player) {
        player.sendMessage(Component.text("Arenas:", NamedTextColor.AQUA));
        for (RaceArena a : plugin.getArenas().values()) {
            player.sendMessage(Component.text("- " + a.getName() + " (" + a.getState() + ")", NamedTextColor.WHITE));
        }
    }

    private void giveWand(Player player) {
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
        msg(player, "&aReceived Wand.");
    }

    private void msg(Player p, String m) {
        p.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(m));
    }

    private void sendHelp(Player p) {
        msg(p, "&b/race join|leave|list|cp");
        if (p.hasPermission("race.admin")) msg(p, "&e/race admin ...");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> cmds = new ArrayList<>(Arrays.asList("join", "leave", "list", "cp"));
            if (sender.hasPermission("race.admin")) {
                cmds.add("admin");
                cmds.add("start");
                cmds.add("stop");
                cmds.add("reload");
            }
            return filter(cmds, args[0]);
        }

        if (args.length == 2) {
            if (Arrays.asList("join", "start", "stop").contains(args[0].toLowerCase())) {
                return filter(new ArrayList<>(plugin.getArenas().keySet()), args[1]);
            }
            if (args[0].equalsIgnoreCase("admin")) {
                return filter(Arrays.asList("create", "delete", "wand", "edit", "stopedit", "visualize", "setlaps", "setradius", "addspawn", "addcp", "setfinish", "setlobby", "setmainlobby", "setminplayers", "setautostart", "setleaderboard"), args[1]);
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            // Suggest arenas for everything EXCEPT create/wand/stopedit/setradius
            List<String> noArenaArgs = Arrays.asList("create", "wand", "stopedit", "setradius");
            if (!noArenaArgs.contains(args[1].toLowerCase())) {
                return filter(new ArrayList<>(plugin.getArenas().keySet()), args[2]);
            }
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("create")) {
            return Arrays.asList("DEFAULT", "LAP");
        }

        return Collections.emptyList();
    }

    private List<String> filter(List<String> list, String input) {
        String i = input.toLowerCase();
        return list.stream().filter(s -> s.toLowerCase().startsWith(i)).collect(Collectors.toList());
    }
}