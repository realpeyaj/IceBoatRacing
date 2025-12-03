package peyaj;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class PacketUtils {

    private static final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

    // Spawn a fake boat for a specific player
    public static int spawnFakeBoat(Player observer, Location loc) {
        int entityId = ThreadLocalRandom.current().nextInt(100000, 999999); // Random ID
        UUID uuid = UUID.randomUUID();

        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().write(0, entityId);
        packet.getUUIDs().write(0, uuid);
        packet.getEntityTypeModifier().write(0, EntityType.BOAT);

        packet.getDoubles()
                .write(0, loc.getX())
                .write(1, loc.getY())
                .write(2, loc.getZ());

        // Pitch/Yaw are bytes in some versions, integers/floats in others depending on wrapper.
        // ProtocolLib handles this mostly, but standard is (byte) (angle * 256 / 360)
        packet.getBytes()
                .write(0, (byte) (loc.getPitch() * 256.0F / 360.0F))
                .write(1, (byte) (loc.getYaw() * 256.0F / 360.0F));

        try {
            protocolManager.sendServerPacket(observer, packet);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return entityId;
    }

    // Move the fake boat (Teleport is safest for now)
    public static void moveFakeBoat(Player observer, int entityId, Location loc) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
        packet.getIntegers().write(0, entityId);
        packet.getDoubles()
                .write(0, loc.getX())
                .write(1, loc.getY())
                .write(2, loc.getZ());
        packet.getBytes()
                .write(0, (byte) (loc.getYaw() * 256.0F / 360.0F))
                .write(1, (byte) (loc.getPitch() * 256.0F / 360.0F));
        packet.getBooleans().write(0, true); // On ground

        try {
            protocolManager.sendServerPacket(observer, packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Destroy the fake boat
    public static void destroyFakeEntity(Player observer, int entityId) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        packet.getIntLists().write(0, java.util.Collections.singletonList(entityId));

        try {
            protocolManager.sendServerPacket(observer, packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}