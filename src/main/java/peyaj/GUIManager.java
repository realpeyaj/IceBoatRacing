package peyaj;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.util.Arrays;
import java.util.List;

public class GUIManager implements Listener {

    private final IceBoatRacing plugin;
    private final NamespacedKey arenaKey;
    private final NamespacedKey trailKey;

    public GUIManager(IceBoatRacing plugin) {
        this.plugin = plugin;
        this.arenaKey = new NamespacedKey(plugin, "arena_key");
        this.trailKey = new NamespacedKey(plugin, "trail_key");
    }

    // --- MENUS ---

    public void openMainMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("IceBoat Racing", NamedTextColor.AQUA));

        // Play Button
        inv.setItem(11, createItem(Material.OAK_BOAT, "&b&lPlay", "&7Join a race!"));

        // Cosmetics Button
        inv.setItem(13, createItem(Material.DIAMOND, "&d&lCosmetics", "&7Cages & Trails"));

        // Vote Button (Dynamic)
        if (plugin.isVoting) {
            inv.setItem(22, createItem(Material.PAPER, "&a&lVOTE NOW!", "&7Click to vote for map", "&eEnds in: " + plugin.votingTimeRemaining + "s"));
        }

        // Stats Button
        int wins = plugin.getStat(p.getUniqueId(), "wins");
        int played = plugin.getStat(p.getUniqueId(), "races_played");
        inv.setItem(15, createItem(Material.PAPER, "&e&lYour Stats",
                "&7Wins: &a" + wins,
                "&7Races: &f" + played,
                "",
                "&7Win Rate: &b" + (played > 0 ? (wins * 100 / played) : 0) + "%"
        ));

        // Admin Button
        if (p.hasPermission("race.admin")) {
            inv.setItem(26, createItem(Material.COMMAND_BLOCK, "&c&lAdmin Panel", "&7Manage tracks & Settings"));
        }

        fillGlass(inv);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
    }

    public void openVoteMenu(Player p) {
        if (!plugin.isVoting) {
            p.sendMessage(Component.text("No voting is currently active.", NamedTextColor.RED));
            return;
        }

        int size = 27;
        Inventory inv = Bukkit.createInventory(null, size, Component.text("Vote for Map", NamedTextColor.DARK_GREEN));

        for (RaceArena arena : plugin.getArenas().values()) {
            if (arena.getState() != RaceArena.RaceState.LOBBY) continue;

            int votes = plugin.getVoteCount(arena.getName());
            boolean playerVotedThis = plugin.playerVotes.containsKey(p.getUniqueId()) && plugin.playerVotes.get(p.getUniqueId()).equals(arena.getName());

            ItemStack item = createItem(Material.MAP, "&b&l" + arena.getName(),
                    "&7Type: &f" + arena.getType(),
                    "&7Laps: &f" + arena.getTotalLaps(),
                    "",
                    "&e&lVotes: " + votes,
                    playerVotedThis ? "&a&l[ YOUR VOTE ]" : "&7Click to Vote"
            );

            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(arenaKey, PersistentDataType.STRING, arena.getName());
            if (playerVotedThis) meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);

            inv.addItem(item);
        }

        fillGlass(inv);
        p.openInventory(inv);
    }

    public void openCosmeticsHub(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Cosmetics Hub", NamedTextColor.LIGHT_PURPLE));
        inv.setItem(11, createItem(Material.GLASS, "&d&lCage Colors", "&7Change your starting block color"));
        inv.setItem(15, createItem(Material.FIREWORK_ROCKET, "&6&lParticle Trails", "&7Change your driving particle"));
        inv.setItem(26, createItem(Material.ARROW, "&cBack", "&7Return to Main Menu"));
        fillGlass(inv);
        p.openInventory(inv);
    }

    public void openCageMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Select Cage Color", NamedTextColor.LIGHT_PURPLE));
        inv.setItem(10, createCosmeticItem(Material.GLASS, "&fDefault (Clear)"));
        inv.setItem(11, createCosmeticItem(Material.RED_STAINED_GLASS, "&cRed"));
        inv.setItem(12, createCosmeticItem(Material.ORANGE_STAINED_GLASS, "&6Orange"));
        inv.setItem(13, createCosmeticItem(Material.YELLOW_STAINED_GLASS, "&eYellow"));
        inv.setItem(14, createCosmeticItem(Material.LIME_STAINED_GLASS, "&aLime"));
        inv.setItem(15, createCosmeticItem(Material.LIGHT_BLUE_STAINED_GLASS, "&bLight Blue"));
        inv.setItem(16, createCosmeticItem(Material.MAGENTA_STAINED_GLASS, "&dMagenta"));
        inv.setItem(26, createItem(Material.ARROW, "&cBack", "&7Return to Hub"));
        fillGlass(inv);
        p.openInventory(inv);
    }

    public void openTrailMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 36, Component.text("Select Particle Trail", NamedTextColor.GOLD));
        IceBoatRacing.TrailType current = plugin.getPlayerTrailPreference(p.getUniqueId());
        int slot = 10;
        for (IceBoatRacing.TrailType trail : IceBoatRacing.TrailType.values()) {
            boolean hasPerm = trail.permission == null || p.hasPermission(trail.permission);
            boolean isSelected = (trail == current);
            String status = isSelected ? "&a&lSELECTED" : (hasPerm ? "&eClick to Select" : "&cLocked");
            Material mat = trail.icon;
            ItemStack item = createItem(mat, "&6&l" + trail.name, status);
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(trailKey, PersistentDataType.STRING, trail.name());
            if (isSelected) meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS, org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
            if ((slot + 1) % 9 == 0) slot += 2;
        }
        inv.setItem(31, createItem(Material.ARROW, "&cBack", "&7Return to Hub"));
        fillGlass(inv);
        p.openInventory(inv);
    }

    public void openAdminPanel(Player p) {
        if (!p.hasPermission("race.admin")) {
            p.sendMessage(Component.text("You do not have permission to access this menu.", NamedTextColor.RED));
            p.closeInventory();
            return;
        }

        Inventory inv = Bukkit.createInventory(null, 27, Component.text("IceBoat Admin Panel", NamedTextColor.DARK_AQUA));
        inv.setItem(10, createItem(Material.EMERALD_BLOCK, "&a&lCreate Arena", "&7Click to name new arena"));
        inv.setItem(12, createItem(Material.CHEST_MINECART, "&e&lEdit Arenas", "&7Configure existing arenas"));
        inv.setItem(14, createItem(Material.BLAZE_ROD, "&6&lGet Wand", "&7Give setup wand"));
        inv.setItem(16, createItem(Material.REDSTONE_TORCH, "&c&lReload Plugin", "&7Reload config.yml"));

        // Admin Vote Button
        inv.setItem(22, createItem(Material.BEACON, "&e&lStart Vote", "&7Force start voting round"));

        // NEW: Setup Guide Book
        inv.setItem(8, createItem(Material.BOOK, "&b&lSetup Guide", "&7Get the instruction book"));

        inv.setItem(26, createItem(Material.ARROW, "&cBack", "&7Return to Main Menu"));
        fillGlass(inv);
        p.openInventory(inv);
    }

    public void openRaceTypeSelector(Player p, String arenaName) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text("Select Type: " + arenaName, NamedTextColor.BLUE));
        ItemStack defaultType = createItem(Material.ICE, "&b&lPoint-to-Point", "&7Classic race from Start to Finish.", "&7No Laps.");
        ItemMeta dm = defaultType.getItemMeta();
        dm.getPersistentDataContainer().set(arenaKey, PersistentDataType.STRING, arenaName);
        defaultType.setItemMeta(dm);

        ItemStack lapType = createItem(Material.CLOCK, "&e&lLap Race", "&7Multi-lap circuit.", "&7Start/Finish lines are the same.");
        ItemMeta lm = lapType.getItemMeta();
        lm.getPersistentDataContainer().set(arenaKey, PersistentDataType.STRING, arenaName);
        lapType.setItemMeta(lm);

        inv.setItem(11, defaultType);
        inv.setItem(15, lapType);
        fillGlass(inv);
        p.openInventory(inv);
    }

    public void openArenaSelector(Player p, boolean adminMode) {
        int size = 54;
        String title = adminMode ? "Edit Arena" : "Select Arena";
        Inventory inv = Bukkit.createInventory(null, size, Component.text(title, NamedTextColor.DARK_GRAY));
        for (RaceArena arena : plugin.getArenas().values()) {
            String status = (arena.getState() == RaceArena.RaceState.LOBBY) ? "&aOPEN" : "&cRUNNING";

            // --- UPDATED LORE WITH CLEAR INSTRUCTIONS ---
            String loreAction;
            if (adminMode) {
                loreAction = "&eClick to Edit";
            } else {
                if (arena.getState() == RaceArena.RaceState.LOBBY) {
                    // Check if lobby has players
                    if (arena.getPlayerCount() > 0) {
                        loreAction = "&eLeft-Click to Join Match &7(" + arena.getPlayerCount() + " waiting)";
                    } else {
                        loreAction = "&eLeft-Click to Join\n&dShift-Click for Time Trial";
                    }
                } else {
                    loreAction = "&bClick to Spectate";
                }
            }

            ItemStack item = createItem(Material.ICE, "&b&l" + arena.getName(), "&7Status: " + status, "&7Type: &f" + arena.getType(), "&7Laps: &f" + arena.getTotalLaps(), loreAction);
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(arenaKey, PersistentDataType.STRING, arena.getName());
            item.setItemMeta(meta);
            inv.addItem(item);
        }
        inv.setItem(49, createItem(Material.ARROW, "&cBack", "&7Return to Menu"));
        p.openInventory(inv);
    }

    public void openArenaEditor(Player p, RaceArena arena) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text("Editing: " + arena.getName(), NamedTextColor.BLUE));
        boolean isVis = plugin.activeVisualizers.containsKey(p.getUniqueId()) && plugin.activeVisualizers.get(p.getUniqueId()).equals(arena.getName());
        inv.setItem(10, createItem(isVis ? Material.ENDER_EYE : Material.ENDER_PEARL, "&b&lVisualizer: " + (isVis ? "&aON" : "&cOFF"), "&7Toggle particle preview"));

        IceBoatRacing.EditMode currentMode = plugin.editorMode.getOrDefault(p.getUniqueId(), IceBoatRacing.EditMode.SPAWN);
        inv.setItem(12, createItem(Material.BLAZE_POWDER, "&6&lWand Mode", "&7Current: " + currentMode.color + currentMode.name, "&eClick to cycle mode"));

        inv.setItem(14, createItem(Material.CLOCK, "&e&lLaps: &f" + arena.getTotalLaps(), "&aLeft-Click: +1", "&cRight-Click: -1"));
        inv.setItem(16, createItem(Material.PLAYER_HEAD, "&e&lMin Players: &f" + arena.minPlayers, "&aLeft-Click: +1", "&cRight-Click: -1"));

        inv.setItem(28, createItem(Material.COMPASS, "&aTeleport to Lobby", ""));
        inv.setItem(29, createItem(Material.BEACON, "&aTeleport to Start", ""));
        inv.setItem(30, createItem(Material.ARMOR_STAND, "&d&lSet Leaderboard", "&7Sets hologram at your feet"));
        inv.setItem(31, createItem(Material.REPEATER, "&aAuto-Start Delay: " + arena.autoStartDelay + "s", "&eClick to add 10s"));

        inv.setItem(32, createItem(Material.LEVER, "&a&lForce Start", "&7Start race immediately"));

        inv.setItem(33, createItem(Material.BLAZE_ROD, "&6Get Wand", "&7Equip the tool"));
        inv.setItem(34, createItem(Material.TNT, "&c&lDelete Arena", "&7Shift-Click to confirm"));
        inv.setItem(49, createItem(Material.ARROW, "&cBack to List", ""));

        fillGlass(inv);
        p.openInventory(inv);
        plugin.editorArena.put(p.getUniqueId(), arena.getName());
    }

    // --- HELPER: GIVE BOOK ---
    private void giveSetupBook(Player p) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();

        // Convert components to legacy strings for compatibility with BookMeta setTitle/setAuthor
        meta.setTitle(LegacyComponentSerializer.legacySection().serialize(Component.text("Arena Setup Guide", NamedTextColor.AQUA)));
        meta.setAuthor(LegacyComponentSerializer.legacySection().serialize(Component.text("IceBoatRacing", NamedTextColor.YELLOW)));

        Component p1 = Component.text("§lArena Setup Guide\n\n")
                .append(Component.text("1. Use the §lRace Wand§r\n   (/race admin wand)\n\n"))
                .append(Component.text("2. §lShift-Right Click§r\n   to cycle modes:\n   - Spawn\n   - Checkpoint\n   - Finish Line\n   - Lobby\n\n"));

        Component p2 = Component.text("§lKey Steps:\n\n")
                .append(Component.text("1. Place §aSpawns§r where boats start.\n\n"))
                .append(Component.text("2. Place §cCheckpoints§r along the track.\n\n"))
                .append(Component.text("3. Set §bFinish Line§r (2 points to make a box).\n"));

        // Page 3: Finalizing
        Component p3 = Component.text("§lFinalizing:\n\n")
                .append(Component.text("4. Set §6Lobby§r (waiting area).\n\n"))
                .append(Component.text("5. Click 'Visualizer' in GUI to see your nodes.\n\n"))
                .append(Component.text("6. Adjust Laps/Players in the GUI."));

        meta.addPages(p1, p2, p3);
        book.setItemMeta(meta);

        if (p.getInventory().firstEmpty() != -1) {
            p.getInventory().addItem(book);
        } else {
            p.getWorld().dropItem(p.getLocation(), book);
        }
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        p.sendMessage(Component.text("You received the Setup Guide!", NamedTextColor.GREEN));
    }

    // --- EVENT HANDLING ---

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Component titleComp = e.getView().title();
        String title = LegacyComponentSerializer.legacyAmpersand().serialize(titleComp);

        if (!title.contains("IceBoat") && !title.contains("Select Arena") && !title.contains("Edit Arena")
                && !title.contains("Editing:") && !title.contains("Select Cage") && !title.contains("Select Type")
                && !title.contains("Cosmetics Hub") && !title.contains("Select Particle") && !title.contains("Vote for Map")) return;

        e.setCancelled(true);
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

        // Main Menu
        if (title.contains("IceBoat Racing")) {
            if (clicked.getType() == Material.OAK_BOAT) openArenaSelector(p, false);
            else if (clicked.getType() == Material.DIAMOND) openCosmeticsHub(p);
            else if (clicked.getType() == Material.COMMAND_BLOCK) openAdminPanel(p);
            else if (clicked.getType() == Material.PAPER && plugin.isVoting) openVoteMenu(p);
            return;
        }

        // Vote Menu
        if (title.contains("Vote for Map")) {
            if (clicked.hasItemMeta() && clicked.getItemMeta().getPersistentDataContainer().has(arenaKey, PersistentDataType.STRING)) {
                String arenaName = clicked.getItemMeta().getPersistentDataContainer().get(arenaKey, PersistentDataType.STRING);
                plugin.castVote(p, arenaName);
                openVoteMenu(p);
            }
            return;
        }

        // Cosmetics Hub
        if (title.contains("Cosmetics Hub")) {
            if (clicked.getType() == Material.ARROW) { openMainMenu(p); return; }
            if (clicked.getType() == Material.GLASS) openCageMenu(p);
            if (clicked.getType() == Material.FIREWORK_ROCKET) openTrailMenu(p);
            return;
        }

        // Cosmetics: Cage
        if (title.contains("Select Cage Color")) {
            if (clicked.getType() == Material.ARROW) { openCosmeticsHub(p); return; }
            Material blockMat = convertPaneToBlock(clicked.getType());
            if (blockMat != null) {
                plugin.setPlayerCagePreference(p.getUniqueId(), blockMat);
                p.sendMessage(Component.text("Cage color set!", NamedTextColor.GREEN));
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                p.closeInventory();
            }
            return;
        }

        // Cosmetics: Trail
        if (title.contains("Select Particle")) {
            if (clicked.getType() == Material.ARROW) { openCosmeticsHub(p); return; }
            if (clicked.hasItemMeta() && clicked.getItemMeta().getPersistentDataContainer().has(trailKey, PersistentDataType.STRING)) {
                String trailName = clicked.getItemMeta().getPersistentDataContainer().get(trailKey, PersistentDataType.STRING);
                try {
                    IceBoatRacing.TrailType trail = IceBoatRacing.TrailType.valueOf(trailName);
                    if (trail.permission != null && !p.hasPermission(trail.permission)) {
                        p.sendMessage(Component.text("You don't have permission for this trail.", NamedTextColor.RED));
                        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 1f);
                        return;
                    }
                    plugin.setPlayerTrailPreference(p.getUniqueId(), trail);
                    p.sendMessage(Component.text("Trail set to " + trail.name, NamedTextColor.GREEN));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                    openTrailMenu(p);
                } catch (IllegalArgumentException ignored) {}
            }
            return;
        }

        // Admin Panel
        if (title.contains("Admin Panel")) {
            if (clicked.getType() == Material.ARROW) { openMainMenu(p); return; }
            if (clicked.getType() == Material.EMERALD_BLOCK) {
                p.closeInventory();
                plugin.inputMode.put(p.getUniqueId(), "CREATE_ARENA");
                p.sendMessage(Component.text("---------------------------------------", NamedTextColor.GREEN));
                p.sendMessage(Component.text("Please type the name of the new arena in chat.", NamedTextColor.YELLOW));
                p.sendMessage(Component.text("Type 'cancel' to abort.", NamedTextColor.GRAY));
                p.sendMessage(Component.text("---------------------------------------", NamedTextColor.GREEN));
            }
            else if (clicked.getType() == Material.CHEST_MINECART) openArenaSelector(p, true);
            else if (clicked.getType() == Material.BLAZE_ROD) {
                p.performCommand("race admin wand");
                p.closeInventory();
            }
            else if (clicked.getType() == Material.REDSTONE_TORCH) {
                plugin.reload();
                p.sendMessage(Component.text("Plugin reloaded!", NamedTextColor.GREEN));
                p.closeInventory();
            }
            else if (clicked.getType() == Material.BEACON) {
                p.performCommand("race admin startvote");
                p.closeInventory();
            }
            else if (clicked.getType() == Material.BOOK) {
                giveSetupBook(p);
                p.closeInventory();
            }
            return;
        }

        // Race Type Selector
        if (title.contains("Select Type:")) {
            if (clicked.hasItemMeta() && clicked.getItemMeta().getPersistentDataContainer().has(arenaKey, PersistentDataType.STRING)) {
                String arenaName = clicked.getItemMeta().getPersistentDataContainer().get(arenaKey, PersistentDataType.STRING);
                RaceArena arena = plugin.getArena(arenaName);
                if (arena != null) {
                    if (clicked.getType() == Material.ICE) {
                        arena.setType(RaceArena.RaceType.DEFAULT);
                        arena.setTotalLaps(1);
                    } else if (clicked.getType() == Material.CLOCK) {
                        arena.setType(RaceArena.RaceType.LAP);
                        arena.setTotalLaps(3);
                    }
                    plugin.saveArenas();
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
                    giveSetupBook(p);
                    openArenaEditor(p, arena);
                }
            }
            return;
        }

        // Arena Selector
        if (title.contains("Select Arena") || title.contains("Edit Arena")) {
            if (clicked.getType() == Material.ARROW) {
                if (title.contains("Edit")) openAdminPanel(p);
                else openMainMenu(p);
                return;
            }

            if (clicked.hasItemMeta() && clicked.getItemMeta().getPersistentDataContainer().has(arenaKey, PersistentDataType.STRING)) {
                String arenaName = clicked.getItemMeta().getPersistentDataContainer().get(arenaKey, PersistentDataType.STRING);

                // Logic: Handle Shift-Click (Time Trial) vs Left-Click (Join/Spectate)
                if (title.contains("Edit Arena")) {
                    RaceArena arena = plugin.getArena(arenaName);
                    if (arena != null) openArenaEditor(p, arena);
                } else {
                    // Check for active race conflict first
                    RaceArena arena = plugin.getArena(arenaName);

                    if (e.isShiftClick()) {
                        // Time Trial Request
                        if (arena != null) {
                            if (arena.getState() != RaceArena.RaceState.LOBBY) {
                                p.sendMessage(Component.text("Cannot start Time Trial while race is active.", NamedTextColor.RED));
                                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                            } else {
                                // Fallback handled inside addPlayer too, but we can do a soft check
                                arena.addPlayer(p, true); // true = Time Trial
                            }
                        }
                    } else {
                        // Regular Join / Spectate Request
                        if (arena != null) {
                            if (arena.getState() == RaceArena.RaceState.LOBBY) {
                                p.performCommand("race join " + arenaName);
                            } else {
                                // Auto-Spectate if running
                                arena.addSpectator(p);
                            }
                        }
                    }
                    p.closeInventory();
                }
            }
            return;
        }

        // Arena Editor
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
                arena.setTotalLaps(Math.max(1, arena.getTotalLaps() + change));
                plugin.saveArenas();
                openArenaEditor(p, arena);
            }
            else if (clicked.getType() == Material.PLAYER_HEAD) {
                int change = e.isLeftClick() ? 1 : -1;
                arena.minPlayers = Math.max(1, arena.minPlayers + change);
                plugin.saveArenas();
                openArenaEditor(p, arena);
            }
            else if (clicked.getType() == Material.COMPASS) {
                if (arena.getLobby() != null) p.teleport(arena.getLobby());
            }
            else if (clicked.getType() == Material.BEACON) {
                if (!arena.getSpawns().isEmpty()) p.teleport(arena.getSpawns().getFirst());
            }
            else if (clicked.getType() == Material.ARMOR_STAND) {
                arena.setLeaderboardLocation(p.getLocation().add(0, 1.5, 0));
                plugin.saveArenas();
                p.sendMessage(Component.text("Leaderboard updated.", NamedTextColor.LIGHT_PURPLE));
            }
            else if (clicked.getType() == Material.REPEATER) {
                arena.autoStartDelay += 10;
                if (arena.autoStartDelay > 60) arena.autoStartDelay = 10;
                plugin.saveArenas();
                openArenaEditor(p, arena);
            }
            else if (clicked.getType() == Material.LEVER) {
                p.performCommand("race start " + arenaName);
                p.closeInventory();
            }
            else if (clicked.getType() == Material.BLAZE_ROD) {
                p.performCommand("race admin wand");
                p.closeInventory();
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
                openArenaSelector(p, true);
            }
        }
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<String> loreStrings = Arrays.stream(lore)
                    .map(s -> LegacyComponentSerializer.legacyAmpersand().deserialize(s).decoration(TextDecoration.ITALIC, false))
                    .map(LegacyComponentSerializer.legacySection()::serialize)
                    .toList();
            meta.setLore(loreStrings);
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCosmeticItem(Material mat, String name) {
        return createItem(mat, name, "&7Click to select");
    }

    private Material convertPaneToBlock(Material pane) {
        String name = pane.name();
        if (name.contains("STAINED_GLASS_PANE")) {
            return Material.valueOf(name.replace("_PANE", ""));
        } else if (name.equals("GLASS_PANE")) {
            return Material.GLASS;
        }
        if (name.contains("GLASS")) return pane;
        return null;
    }

    private void fillGlass(Inventory inv) {
        ItemStack glass = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, glass);
        }
    }
}