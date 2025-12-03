package peyaj;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class Utils {

    public static void tickVisualizers(IceBoatRacing plugin) {
        for (Map.Entry<UUID, String> entry : plugin.activeVisualizers.entrySet()) {
            Player p = plugin.getServer().getPlayer(entry.getKey());
            if (p == null || !p.isOnline()) {
                plugin.activeVisualizers.remove(entry.getKey());
                continue;
            }

            RaceArena arena = plugin.getArena(entry.getValue());
            if (arena == null) continue;

            if (plugin.editorArena.containsKey(p.getUniqueId()) && plugin.editorArena.get(p.getUniqueId()).equals(arena.getName())) {
                IceBoatRacing.EditMode mode = plugin.editorMode.getOrDefault(p.getUniqueId(), IceBoatRacing.EditMode.SPAWN);
                p.sendActionBar(Component.text("Editing: " + arena.getName() + " | Mode: " + mode.name + " (Shift+RC to cycle)", mode.color));
            }

            // Particles
            for (Location loc : arena.getSpawns()) p.spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0, 1, 0), 5, 0.2, 0.2, 0.2, 0);

            Location prev = (arena.getSpawns().isEmpty()) ? null : arena.getSpawns().getFirst();
            for (Location loc : arena.getCheckpoints()) {
                p.spawnParticle(Particle.DUST, loc.clone().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0, new Particle.DustOptions(Color.RED, 1.5f));
                drawCircle(p, loc, plugin.checkpointRadius, Color.RED);
                if (prev != null) drawLine(p, prev, loc, Color.GRAY);
                prev = loc;
            }

            if (arena.getFinishBox() != null && arena.getFinishPos1() != null) {
                drawBox(p, arena.getFinishBox());
                Location center = arena.getFinishBox().getCenter().toLocation(arena.getFinishPos1().getWorld());
                if (prev != null) drawLine(p, prev, center, Color.YELLOW);
                if (arena.getType() == RaceArena.RaceType.LAP && !arena.getSpawns().isEmpty()) {
                    drawLine(p, center, arena.getSpawns().getFirst(), Color.TEAL);
                }
            } else if (arena.getFinishPos1() != null) {
                p.spawnParticle(Particle.END_ROD, arena.getFinishPos1(), 5, 0, 0, 0, 0);
            }

            if (arena.getLobby() != null) p.spawnParticle(Particle.NOTE, arena.getLobby().clone().add(0, 1, 0), 1, 0, 0, 0, 0);
            if (arena.getMainLobby() != null) p.spawnParticle(Particle.NOTE, arena.getMainLobby().clone().add(0, 1, 0), 1, 0, 0, 0, 0);
        }
    }

    public static void drawLine(Player p, Location p1, Location p2, Color color) {
        if (p1.getWorld() != p2.getWorld()) return;
        double dist = p1.distance(p2);
        Vector v = p2.toVector().subtract(p1.toVector()).normalize();
        Location curr = p1.clone();
        for (double d = 0; d < dist; d += 1.0) {
            p.spawnParticle(Particle.DUST, curr.add(0,1,0), 1, 0, 0, 0, 0, new Particle.DustOptions(color, 0.8f));
            curr.add(v);
        }
    }

    public static void drawCircle(Player p, Location center, double radius, Color color) {
        for (int i = 0; i < 360; i += 15) {
            double rad = Math.toRadians(i);
            double x = radius * Math.cos(rad);
            double z = radius * Math.sin(rad);
            p.spawnParticle(Particle.DUST, center.clone().add(x, 1, z), 1, 0, 0, 0, 0, new Particle.DustOptions(color, 0.5f));
        }
    }

    public static void drawBox(Player p, BoundingBox box) {
        Color color = Color.WHITE;
        double minX = box.getMinX(), minY = box.getMinY(), minZ = box.getMinZ();
        double maxX = box.getMaxX(), maxY = box.getMaxY(), maxZ = box.getMaxZ();
        Location minMin = new Location(p.getWorld(), minX, minY, minZ);

        // Simplified box drawing for brevity (corners to corners)
        drawLine(p, minMin, new Location(p.getWorld(), maxX, minY, minZ), color);
        drawLine(p, minMin, new Location(p.getWorld(), minX, minY, maxZ), color);
        drawLine(p, minMin, new Location(p.getWorld(), minX, maxY, minZ), color);

        Location maxMax = new Location(p.getWorld(), maxX, maxY, maxZ);
        drawLine(p, maxMax, new Location(p.getWorld(), minX, maxY, maxZ), color);
        drawLine(p, maxMax, new Location(p.getWorld(), maxX, minY, maxZ), color);
        drawLine(p, maxMax, new Location(p.getWorld(), maxX, maxY, minZ), color);
    }

    public static void createTeam(Scoreboard b, String name, String suffix) {
        Team t = b.registerNewTeam(name);
        t.suffix(Component.text(suffix));
    }

    public static void assignRandomBoatType(Boat boat) {
        Boat.Type[] allTypes = Boat.Type.values();
        List<Boat.Type> valid = new ArrayList<>();
        for (Boat.Type t : allTypes) {
            if (t != Boat.Type.BAMBOO) valid.add(t);
        }
        if (!valid.isEmpty()) {
            boat.setBoatType(valid.get(ThreadLocalRandom.current().nextInt(valid.size())));
        }
    }

    // --- RESTORED: Formatting & Math ---

    public static String formatTime(long millis) {
        long min = (millis / 1000) / 60;
        long sec = (millis / 1000) % 60;
        long ms = millis % 1000;
        return String.format("%02d:%02d.%03d", min, sec, ms);
    }

    public static boolean lineSegmentIntersectsSphere(Location p1, Location p2, Location sphereCenter, double radius) {
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

    // --- UPDATED: TRAIL LOGIC WITH DELAY ---
    public static void spawnTrailParticles(Player p, Boat boat, IceBoatRacing.TrailType trail) {
        if (boat == null || boat.isDead() || p.getVehicle() != boat) return;
        if (trail == null || trail == IceBoatRacing.TrailType.NONE) return;

        // Calculate location NOW
        Vector dir = boat.getLocation().getDirection().multiply(-1); // Behind boat
        Location trailLoc = boat.getLocation().add(dir.multiply(1.0)).add(0, 0.4, 0);

        // Spawn LATER (2 ticks / 100ms) to sync with client interpolation
        IceBoatRacing plugin = JavaPlugin.getPlugin(IceBoatRacing.class);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;

            if (trail == IceBoatRacing.TrailType.RAINBOW) {
                int r = ThreadLocalRandom.current().nextInt(255);
                int g = ThreadLocalRandom.current().nextInt(255);
                int b = ThreadLocalRandom.current().nextInt(255);
                trailLoc.getWorld().spawnParticle(Particle.DUST, trailLoc, 2, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(Color.fromRGB(r, g, b), 1.0f));
            }
            else if (trail == IceBoatRacing.TrailType.NOTES) {
                trailLoc.getWorld().spawnParticle(Particle.NOTE, trailLoc, 1, 0.2, 0.2, 0.2, ThreadLocalRandom.current().nextDouble());
            }
            else {
                trailLoc.getWorld().spawnParticle(trail.particle, trailLoc, 2, 0.1, 0.1, 0.1, 0.02);
            }
        }, 2L);
    }
}