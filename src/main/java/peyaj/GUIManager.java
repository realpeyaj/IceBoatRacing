package peyaj;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer; // FIXED: Added Import
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.util.Arrays;
import java.util.List; // FIXED: Added missing List import

public class GUIManager implements Listener {

    private final IceBoatRacing plugin;
    private final NamespacedKey arenaKey;

    public GUIManager(IceBoatRacing plugin) {
        this.plugin = plugin;
        this.arenaKey = new NamespacedKey(plugin, "arena_key");
    }

    // --- MENUS ---

    public void openMainMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("IceBoat Admin Panel", NamedTextColor.DARK_AQUA));

        inv.setItem(11, createItem(Material.EMERALD_BLOCK, "&a&lCreate Arena", "&7Click to start setup"));
        inv.setItem(13, createItem(Material.BLAZE_ROD, "&6&lGet Wand", "&7Give setup wand"));
        inv.setItem(15, createItem(Material.CHEST_MINECART, "&e&lEdit Arenas", "&7Manage existing tracks"));

        fillGlass(inv);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1f);
    }

    public void openArenaSelector(Player p) {
        int size = 54;
        Inventory inv = Bukkit.createInventory(null, size, Component.text("Select Arena", NamedTextColor.DARK_GRAY));

        for (RaceArena arena : plugin.getArenas().values()) {
            ItemStack item = createItem(Material.ICE, "&b&l" + arena.getName(),
                    "&7Type: &f" + arena.getType(),
                    "&7Laps: &f" + arena.getTotalLaps(),
                    "&eClick to Edit"
            );

            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(arenaKey, PersistentDataType.STRING, arena.getName());
            item.setItemMeta(meta);

            inv.addItem(item);
        }

        inv.setItem(49, createItem(Material.ARROW, "&cBack", "&7Return to Main Menu"));

        p.openInventory(inv);
    }

    public void openArenaEditor(Player p, RaceArena arena) {
        Inventory inv = Bukkit.createInventory(null, 45, Component.text("Editing: " + arena.getName(), NamedTextColor.BLUE));

        // 1. Visualizer Toggle
        boolean isVis = plugin.activeVisualizers.containsKey(p.getUniqueId()) && plugin.activeVisualizers.get(p.getUniqueId()).equals(arena.getName());
        inv.setItem(10, createItem(isVis ? Material.ENDER_EYE : Material.ENDER_PEARL,
                "&b&lVisualizer",
                isVis ? "&a&lENABLED" : "&c&lDISABLED",
                "&7Click to toggle particles"
        ));

        // 2. Wand Mode Selector
        IceBoatRacing.EditMode currentMode = plugin.editorMode.getOrDefault(p.getUniqueId(), IceBoatRacing.EditMode.SPAWN);
        inv.setItem(12, createItem(Material.BLAZE_POWDER,
                "&6&lWand Mode",
                "&7Current: " + currentMode.color + currentMode.name,
                "&eClick to cycle mode"
        ));

        // 3. Settings (Laps)
        inv.setItem(14, createItem(Material.CLOCK,
                "&e&lLaps: &f" + arena.getTotalLaps(),
                "&aLeft-Click: +1",
                "&cRight-Click: -1"
        ));

        // 4. Settings (Min Players)
        inv.setItem(16, createItem(Material.PLAYER_HEAD,
                "&e&lMin Players: &f" + arena.minPlayers,
                "&aLeft-Click: +1",
                "&cRight-Click: -1"
        ));

        // 5. Actions
        inv.setItem(30, createItem(Material.COMPASS, "&aTeleport to Lobby", ""));
        inv.setItem(31, createItem(Material.BEACON, "&aTeleport to Start", ""));
        inv.setItem(32, createItem(Material.TNT, "&c&lDelete Arena", "&7Shift-Click to confirm"));

        inv.setItem(40, createItem(Material.ARROW, "&cBack to Selector", ""));

        fillGlass(inv);
        p.openInventory(inv);

        plugin.editorArena.put(p.getUniqueId(), arena.getName());
    }

    // --- EVENT HANDLING ---

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Component titleComp = e.getView().title();
        String title = LegacyComponentSerializer.legacyAmpersand().serialize(titleComp);

        if (!title.contains("IceBoat") && !title.contains("Select Arena") && !title.contains("Editing:")) return;

        e.setCancelled(true);
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

        // --- MAIN MENU ---
        if (title.contains("Admin Panel")) {
            if (clicked.getType() == Material.EMERALD_BLOCK) {
                p.closeInventory();
                p.sendMessage(Component.text("Please type: /race admin create <name> [LAP|DEFAULT]", NamedTextColor.GREEN));
            }
            else if (clicked.getType() == Material.BLAZE_ROD) {
                p.performCommand("race admin wand");
                p.closeInventory();
            }
            else if (clicked.getType() == Material.CHEST_MINECART) {
                openArenaSelector(p);
            }
            return;
        }

        // --- SELECTOR ---
        if (title.contains("Select Arena")) {
            if (clicked.getType() == Material.ARROW) {
                openMainMenu(p);
                return;
            }

            if (clicked.hasItemMeta() && clicked.getItemMeta().getPersistentDataContainer().has(arenaKey, PersistentDataType.STRING)) {
                String arenaName = clicked.getItemMeta().getPersistentDataContainer().get(arenaKey, PersistentDataType.STRING);
                RaceArena arena = plugin.getArena(arenaName);
                if (arena != null) {
                    openArenaEditor(p, arena);
                }
            }
            return;
        }

        // --- EDITOR ---
        if (title.contains("Editing:")) {
            String arenaName = plugin.editorArena.get(p.getUniqueId());
            if (arenaName == null) { p.closeInventory(); return; }
            RaceArena arena = plugin.getArena(arenaName);
            if (arena == null) { p.closeInventory(); return; }

            if (clicked.getType() == Material.ENDER_EYE || clicked.getType() == Material.ENDER_PEARL) {
                p.performCommand("race admin visualize " + arenaName);
                openArenaEditor(p, arena);
            }
            else if (clicked.getType() == Material.BLAZE_POWDER) {
                IceBoatRacing.EditMode current = plugin.editorMode.getOrDefault(p.getUniqueId(), IceBoatRacing.EditMode.SPAWN);
                plugin.editorMode.put(p.getUniqueId(), current.next());
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 2f);
                openArenaEditor(p, arena);
            }
            else if (clicked.getType() == Material.CLOCK) {
                int change = e.isLeftClick() ? 1 : -1;
                int newLaps = Math.max(1, arena.getTotalLaps() + change);
                arena.setTotalLaps(newLaps);
                plugin.saveArenas();
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                openArenaEditor(p, arena);
            }
            else if (clicked.getType() == Material.PLAYER_HEAD) {
                int change = e.isLeftClick() ? 1 : -1;
                int newMin = Math.max(1, arena.minPlayers + change);
                arena.minPlayers = newMin;
                plugin.saveArenas();
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                openArenaEditor(p, arena);
            }
            else if (clicked.getType() == Material.COMPASS) {
                if (arena.getLobby() != null) {
                    p.teleport(arena.getLobby());
                    p.sendMessage(Component.text("Teleported to Lobby.", NamedTextColor.GREEN));
                } else p.sendMessage(Component.text("Lobby not set.", NamedTextColor.RED));
            }
            else if (clicked.getType() == Material.BEACON) {
                if (!arena.getSpawns().isEmpty()) {
                    p.teleport(arena.getSpawns().getFirst()); // Use getFirst() since it's a List
                    p.sendMessage(Component.text("Teleported to Start.", NamedTextColor.GREEN));
                } else p.sendMessage(Component.text("Spawns not set.", NamedTextColor.RED));
            }
            else if (clicked.getType() == Material.TNT) {
                if (e.isShiftClick()) {
                    p.performCommand("race admin delete " + arenaName);
                    p.closeInventory();
                } else {
                    p.sendMessage(Component.text("Shift-Click TNT to delete this arena.", NamedTextColor.RED));
                }
            }
            else if (clicked.getType() == Material.ARROW) {
                openArenaSelector(p);
            }
        }
    }

    // --- UTILS ---

    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        // Sets display name using Adventure
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name).decoration(TextDecoration.ITALIC, false));

        if (lore.length > 0) {
            // FIXED: Map Adventure Components to standard Strings using LegacyComponentSerializer
            List<String> loreStrings = Arrays.stream(lore)
                    .map(s -> LegacyComponentSerializer.legacyAmpersand().deserialize(s).decoration(TextDecoration.ITALIC, false))
                    .map(LegacyComponentSerializer.legacySection()::serialize) // Convert Component to String
                    .toList();

            meta.setLore(loreStrings); // Apply the List<String>
        }
        item.setItemMeta(meta);
        return item;
    }

    private void fillGlass(Inventory inv) {
        ItemStack glass = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, glass);
        }
    }
}