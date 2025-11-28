package peyaj;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent; // Added Import
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.ItemStack;

public class RaceListener implements Listener {

    private final IceBoatRacing plugin;

    public RaceListener(IceBoatRacing plugin) {
        this.plugin = plugin;
    }

    // FIX 3: Handle Disconnects
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (plugin.isRacer(p.getUniqueId())) {
            RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
            if (arena != null) {
                // Remove them properly so the race logic knows they are gone
                arena.removePlayer(p);
                // Also remove from global map immediately
                plugin.removePlayerFromArenaMap(p.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onBoatCollision(VehicleEntityCollisionEvent event) {
        if (event.getVehicle() instanceof Boat) {
            if (!event.getVehicle().getPassengers().isEmpty() && event.getVehicle().getPassengers().getFirst() instanceof Player p) {
                if (plugin.isRacer(p.getUniqueId())) {
                    RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
                    if (arena != null && arena.getState() == RaceArena.RaceState.ACTIVE) {
                        event.setCancelled(true);
                        event.setCollisionCancelled(true);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            if (plugin.isRacer(player.getUniqueId())) {
                RaceArena arena = plugin.getPlayerArena(player.getUniqueId());
                if (arena != null && arena.getState() == RaceArena.RaceState.ACTIVE) {
                    event.setCancelled(true);
                    player.sendMessage(Component.text("You cannot exit the boat during the race!", NamedTextColor.RED));
                }
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        ItemStack item = event.getItem();

        if (item != null && item.getType() == Material.BLAZE_ROD && item.hasItemMeta()) {
            Component displayName = item.getItemMeta().displayName();
            if (displayName != null && PlainTextComponentSerializer.plainText().serialize(displayName).contains("Race Wand")) {
                event.setCancelled(true);
                handleWand(p, event.getAction(), event.getClickedBlock() != null ? event.getClickedBlock().getLocation() : null);
            }
        }
    }

    private void handleWand(Player p, Action action, Location blockLoc) {
        if (!p.hasPermission("race.admin")) return;

        String arenaName = plugin.editorArena.get(p.getUniqueId());
        if (arenaName == null) {
            p.sendMessage(Component.text("Select an arena first: /race admin edit <arena>", NamedTextColor.RED));
            return;
        }
        RaceArena arena = plugin.getArena(arenaName);
        if (arena == null) return;

        IceBoatRacing.EditMode mode = plugin.editorMode.getOrDefault(p.getUniqueId(), IceBoatRacing.EditMode.SPAWN);

        if (p.isSneaking() && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {
            IceBoatRacing.EditMode next = mode.next();
            plugin.editorMode.put(p.getUniqueId(), next);
            p.sendActionBar(Component.text("Mode: " + next.name, next.color));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 2f);
            return;
        }

        if (blockLoc == null) return;

        if (action == Action.RIGHT_CLICK_BLOCK) {
            Location loc = blockLoc.clone().add(0.5, 1, 0.5);
            loc.setYaw(p.getLocation().getYaw());

            switch (mode) {
                case SPAWN -> {
                    arena.addSpawn(loc);
                    p.sendMessage(Component.text("Spawn added.", NamedTextColor.GREEN));
                    p.spawnParticle(Particle.HAPPY_VILLAGER, loc, 20);
                }
                case CHECKPOINT -> {
                    arena.addCheckpoint(loc);
                    p.sendMessage(Component.text("Checkpoint added.", NamedTextColor.GREEN));
                    p.spawnParticle(Particle.DUST, loc, 20, new Particle.DustOptions(Color.RED, 2));
                }
                case FINISH_1 -> {
                    arena.setFinishLine(loc, arena.getFinishPos2());
                    p.sendMessage(Component.text("Finish Pos 1 Set.", NamedTextColor.AQUA));
                }
                case FINISH_2 -> {
                    arena.setFinishLine(arena.getFinishPos1(), loc);
                    p.sendMessage(Component.text("Finish Pos 2 Set.", NamedTextColor.AQUA));
                }
                case LOBBY -> {
                    arena.setLobby(loc);
                    p.sendMessage(Component.text("Lobby Set.", NamedTextColor.GOLD));
                }
                case MAIN_LOBBY -> {
                    arena.setMainLobby(loc);
                    p.sendMessage(Component.text("Main Lobby Set.", NamedTextColor.YELLOW));
                }
                case LEADERBOARD -> {
                    arena.setLeaderboardLocation(loc.add(0, 1.5, 0));
                    p.sendMessage(Component.text("Leaderboard Location Set.", NamedTextColor.LIGHT_PURPLE));
                }
            }
            plugin.saveArenas();
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
        }

        if (action == Action.LEFT_CLICK_BLOCK) {
            boolean removed = false;
            switch (mode) {
                case SPAWN -> removed = arena.removeNodeAtBlock(arena.getSpawns(), blockLoc);
                case CHECKPOINT -> removed = arena.removeNodeAtBlock(arena.getCheckpoints(), blockLoc);
                default -> p.sendMessage(Component.text("Cannot delete " + mode.name + " with click.", NamedTextColor.RED));
            }

            if (removed) {
                plugin.saveArenas();
                p.playSound(p.getLocation(), Sound.BLOCK_CANDLE_EXTINGUISH, 1f, 1f);
                p.spawnParticle(Particle.SMOKE, blockLoc.clone().add(0.5, 1, 0.5), 20, 0.2, 0.2, 0.2, 0.05);
                p.sendMessage(Component.text("Node removed.", NamedTextColor.RED));
            }
        }
    }
}