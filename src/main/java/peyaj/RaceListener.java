package peyaj;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RaceListener implements Listener {

    private final IceBoatRacing plugin;
    private final Map<UUID, Long> jumpCooldowns = new HashMap<>();

    public RaceListener(IceBoatRacing plugin) {
        this.plugin = plugin;
    }

    // --- PREVENT MOVING RESET ITEM ---
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // If player is in a race, prevent moving hotbar items (Compass/Reset)
        if (event.getWhoClicked() instanceof Player p && plugin.isRacer(p.getUniqueId())) {
            // Simple check: prevent any movement in inventory while racing to keep items in place
            ItemStack item = event.getCurrentItem();
            if (item != null && item.hasItemMeta()) {
                String name = PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
                if (name.contains("Race Menu") || name.contains("Reset Run")) {
                    event.setCancelled(true);
                }
            }
        }
    }

    // --- Arena Creation ---
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        String mode = plugin.inputMode.get(p.getUniqueId());
        if (mode == null || !mode.equals("CREATE_ARENA")) return;

        event.setCancelled(true);
        String msg = event.getMessage().trim();
        plugin.inputMode.remove(p.getUniqueId());

        if (msg.equalsIgnoreCase("cancel")) {
            p.sendMessage(Component.text("Creation cancelled.", NamedTextColor.YELLOW));
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.guiManager.openAdminPanel(p));
            return;
        }

        if (msg.contains(" ") || plugin.getArena(msg) != null) {
            p.sendMessage(Component.text("Invalid name or already exists.", NamedTextColor.RED));
            return;
        }

        RaceArena newArena = new RaceArena(msg.toLowerCase(), plugin);
        plugin.addArena(newArena.getName(), newArena);
        plugin.saveArenas();

        p.sendMessage(Component.text("Arena '" + newArena.getName() + "' created!", NamedTextColor.GREEN));
        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.guiManager.openRaceTypeSelector(p, newArena.getName()));
    }

    @EventHandler
    public void onBoatMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Boat boat) || !(boat.getPassengers().getFirst() instanceof Player p)) return;
        if (!plugin.isRacer(p.getUniqueId())) return;

        if (boat.getVelocity().length() < 0.1) return;

        Block front = boat.getLocation().add(boat.getLocation().getDirection()).getBlock();
        if (!front.getType().isAir() && !front.isPassable()) {
            boat.setVelocity(boat.getVelocity().setY(0.5));
        }
    }

    @EventHandler
    public void onBoatCollision(VehicleEntityCollisionEvent event) {
        if (!(event.getVehicle() instanceof Boat boat) || !(boat.getPassengers().getFirst() instanceof Player p)) return;

        RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
        if (arena == null || arena.getState() != RaceArena.RaceState.ACTIVE) return;

        event.setCollisionCancelled(true);
        event.setCancelled(true);

        if (event.getEntity() instanceof Boat && System.currentTimeMillis() - jumpCooldowns.getOrDefault(p.getUniqueId(), 0L) > 500) {
            if (boat.getVelocity().length() > 0.2) {
                boat.setVelocity(boat.getVelocity().setY(0.6));
                jumpCooldowns.put(p.getUniqueId(), System.currentTimeMillis());
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 2f);
            }
        }
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player p && plugin.isRacer(p.getUniqueId())) {
            RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
            if (arena != null && arena.getState() == RaceArena.RaceState.ACTIVE) {
                event.setCancelled(true);
                p.sendMessage(Component.text("You cannot exit the boat!", NamedTextColor.RED));
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        plugin.inputMode.remove(p.getUniqueId());
        jumpCooldowns.remove(p.getUniqueId());

        RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
        if (arena != null) {
            arena.removePlayer(p);
            plugin.removePlayerFromArenaMap(p.getUniqueId());
        }
    }

    // --- ADMIN TOOLS & RESET ITEM ---
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;

        String name = PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());

        if (item.getType() == Material.BLAZE_ROD && name.contains("Race Wand")) {
            event.setCancelled(true);
            handleWand(p, event.getAction(), event.getClickedBlock() != null ? event.getClickedBlock().getLocation() : null);
        } else if (item.getType() == Material.COMPASS && name.contains("Race Menu")) {
            event.setCancelled(true);
            plugin.guiManager.openMainMenu(p);
        } else if (item.getType() == Material.RED_DYE && name.contains("Reset Run")) {
            event.setCancelled(true);
            RaceArena arena = plugin.getPlayerArena(p.getUniqueId());
            if (arena != null && arena.isTimeTrial()) { // Verify it's a time trial
                arena.resetTimeTrial(p);
            }
        }
    }

    private void handleWand(Player p, Action action, Location blockLoc) {
        if (!p.hasPermission("race.admin")) return;

        String arenaName = plugin.editorArena.get(p.getUniqueId());
        if (arenaName == null) { p.sendMessage(Component.text("Select arena in GUI first.", NamedTextColor.RED)); return; }

        RaceArena arena = plugin.getArena(arenaName);
        IceBoatRacing.EditMode mode = plugin.editorMode.getOrDefault(p.getUniqueId(), IceBoatRacing.EditMode.SPAWN);

        // Shift+Right Click: Cycle Mode
        if (p.isSneaking() && action.name().contains("RIGHT")) {
            plugin.editorMode.put(p.getUniqueId(), mode.next());
            p.sendActionBar(Component.text("Mode: " + mode.next().name, mode.next().color));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 2f);
            return;
        }

        if (blockLoc == null) return;
        Location loc = blockLoc.clone().add(0.5, 1, 0.5);
        loc.setYaw(p.getLocation().getYaw());

        // Right Click: Add Node
        if (action == Action.RIGHT_CLICK_BLOCK) {
            switch (mode) {
                case SPAWN -> { arena.addSpawn(loc); actionMsg(p, "Spawn added."); }
                case CHECKPOINT -> { arena.addCheckpoint(loc); actionMsg(p, "Checkpoint added."); }
                case FINISH_1 -> { arena.setFinishLine(loc, arena.getFinishPos2()); actionMsg(p, "Finish 1 Set."); }
                case FINISH_2 -> { arena.setFinishLine(arena.getFinishPos1(), loc); actionMsg(p, "Finish 2 Set."); }
                case LOBBY -> { arena.setLobby(loc); actionMsg(p, "Lobby Set."); }
                case MAIN_LOBBY -> { arena.setMainLobby(loc); actionMsg(p, "Main Lobby Set."); }
                case LEADERBOARD -> { arena.setLeaderboardLocation(loc.add(0, 1.5, 0)); actionMsg(p, "Leaderboard Set."); }
            }
            plugin.saveArenas();
        }
        // Left Click: Remove Node
        else if (action == Action.LEFT_CLICK_BLOCK) {
            boolean removed = (mode == IceBoatRacing.EditMode.SPAWN) ? arena.removeNodeAtBlock(arena.getSpawns(), blockLoc) :
                    (mode == IceBoatRacing.EditMode.CHECKPOINT) ? arena.removeNodeAtBlock(arena.getCheckpoints(), blockLoc) : false;

            if (removed) {
                plugin.saveArenas();
                p.playSound(p.getLocation(), Sound.BLOCK_CANDLE_EXTINGUISH, 1f, 1f);
                p.spawnParticle(Particle.SMOKE, blockLoc.add(0.5, 1, 0.5), 20, 0.2, 0.2, 0.2, 0.05);
                p.sendMessage(Component.text("Node removed.", NamedTextColor.RED));
            } else {
                p.sendMessage(Component.text("Can only delete Spawns/Checkpoints via click.", NamedTextColor.RED));
            }
        }
    }

    private void actionMsg(Player p, String msg) {
        p.sendMessage(Component.text(msg, NamedTextColor.GREEN));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
    }
}
