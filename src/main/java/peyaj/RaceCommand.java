package peyaj;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public class RaceCommand implements CommandExecutor {

    private final IceBoatRacing plugin;

    public RaceCommand(IceBoatRacing plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command is for players only.", NamedTextColor.RED));
            return true;
        }

        if (label.equalsIgnoreCase("checkpoint") || label.equalsIgnoreCase("cp")) {
            handleCheckpoint(player);
            return true;
        }

        if (args.length == 0) {
            plugin.guiManager.openMainMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "start" -> handleStart(player, args);
            case "stop" -> handleStop(player, args);
            case "vote" -> {
                if (plugin.isVoting) plugin.guiManager.openVoteMenu(player);
                else player.sendMessage(Component.text("Voting is not active.", NamedTextColor.RED));
            }
            case "join" -> handleJoin(player, args);
            case "leave" -> handleLeave(player);
            case "cp", "checkpoint" -> handleCheckpoint(player);
            case "admin" -> handleAdmin(player, args);
            default -> plugin.guiManager.openMainMenu(player);
        }
        return true;
    }

    private void handleAdmin(Player player, String[] args) {
        if (!player.hasPermission("race.admin")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return;
        }

        if (args.length < 2) {
            plugin.guiManager.openAdminPanel(player);
            return;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "wand" -> giveWand(player);
            case "startvote" -> {
                plugin.startVotingRound(30);
                player.sendMessage(Component.text("Voting round started.", NamedTextColor.GREEN));
            }
            case "delete" -> {
                if (args.length < 3) { player.sendMessage(Component.text("Usage: /race admin delete <name>", NamedTextColor.RED)); return; }
                String name = args[2].toLowerCase();
                RaceArena a = plugin.getArena(name);
                if (a != null) {
                    a.stopRace();
                    plugin.removeArena(name);
                    plugin.getConfig().set("arenas." + name, null);
                    plugin.saveConfig();
                    player.sendMessage(Component.text("Deleted " + name, NamedTextColor.RED));
                } else {
                    player.sendMessage(Component.text("Arena not found.", NamedTextColor.RED));
                }
            }
            case "visualize" -> {
                if (args.length < 3) return;
                String arenaName = args[2];
                if (plugin.activeVisualizers.containsKey(player.getUniqueId())) {
                    plugin.activeVisualizers.remove(player.getUniqueId());
                    player.sendMessage(Component.text("Visualizer OFF", NamedTextColor.YELLOW));
                } else {
                    plugin.activeVisualizers.put(player.getUniqueId(), arenaName);
                    player.sendMessage(Component.text("Visualizer ON", NamedTextColor.GREEN));
                }
            }
            default -> plugin.guiManager.openAdminPanel(player);
        }
    }

    // --- START / STOP HANDLERS ---

    private void handleStart(Player player, String[] args) {
        if (!player.hasPermission("race.admin")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /race start <arena>", NamedTextColor.RED));
            return;
        }
        RaceArena arena = plugin.getArena(args[1]);
        if (arena != null) {
            arena.startRace();
            player.sendMessage(Component.text("Force started arena: " + arena.getName(), NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Arena not found.", NamedTextColor.RED));
        }
    }

    private void handleStop(Player player, String[] args) {
        if (!player.hasPermission("race.admin")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /race stop <arena>", NamedTextColor.RED));
            return;
        }
        RaceArena arena = plugin.getArena(args[1]);
        if (arena != null) {
            arena.stopRace();
            player.sendMessage(Component.text("Force stopped arena: " + arena.getName(), NamedTextColor.RED));
        } else {
            player.sendMessage(Component.text("Arena not found.", NamedTextColor.RED));
        }
    }

    private void handleJoin(Player player, String[] args) {
        if (plugin.isRacer(player.getUniqueId())) {
            player.sendMessage(Component.text("Already in race.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            plugin.guiManager.openArenaSelector(player, false);
            return;
        }
        RaceArena arena = plugin.getArena(args[1]);
        if (arena == null) {
            player.sendMessage(Component.text("Arena not found.", NamedTextColor.RED));
            return;
        }
        arena.addPlayer(player);
        plugin.setPlayerArena(player.getUniqueId(), arena.getName());
    }

    private void handleLeave(Player player) {
        RaceArena arena = plugin.getPlayerArena(player.getUniqueId());
        if (arena != null) {
            arena.removePlayer(player);
            plugin.removePlayerFromArenaMap(player.getUniqueId());
            player.sendMessage(Component.text("Left race.", NamedTextColor.YELLOW));
            if (arena.getMainLobby() != null) player.teleport(arena.getMainLobby());
        } else {
            player.sendMessage(Component.text("You are not in a race.", NamedTextColor.RED));
        }
    }

    private void handleCheckpoint(Player player) {
        RaceArena arena = plugin.getPlayerArena(player.getUniqueId());
        if (arena == null) {
            player.sendMessage(Component.text("You are not in a race!", NamedTextColor.RED));
            return;
        }
        if (arena.isSpectator(player.getUniqueId())) {
            player.sendMessage(Component.text("Spectators cannot use checkpoints.", NamedTextColor.RED));
            return;
        }
        arena.respawnPlayer(player);
        player.sendMessage(Component.text("Respawned at last checkpoint!", NamedTextColor.GREEN));
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
        player.sendMessage(Component.text("Received Race Wand.", NamedTextColor.GREEN));
    }
}
