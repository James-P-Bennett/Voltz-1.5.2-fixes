package icbm.wanyi;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Exploit fixes injected by Voltz-1.5.2-fixes into ICBM Contraption 1.2.1 (PatchICBMContraption).
 *
 * Minecraft objects arrive as Object and are read reflectively on SRG names, because the
 * vanilla classes cannot be compiled against.
 */
public class VoltzContraption {

    private static final String TAG = "[VoltzFixes] ";
    private static final long LOG_INTERVAL_MS = 10000L;

    private static final Map lastLog = new HashMap();
    private static int blockIdLimit = -1;

    /**
     * Wraps the disguise block id read by TYinXing.readFromNBT. The client renderer indexes
     * Block.blocksList with it unchecked, so an id outside the array - saved by the stock
     * packet exploit - becomes 0, which renders the block's own texture.
     */
    public static int blockId(int id) {
        if (blockIdLimit < 0) {
            try {
                Object list = Class.forName("net.minecraft.block.Block").getField("field_71973_m").get(null);   // Block.blocksList
                blockIdLimit = ((Object[]) list).length;
            } catch (Throwable t) {
                blockIdLimit = 4096;
            }
        }
        return id < 0 || id >= blockIdLimit ? 0 : id;
    }

    /** 8 blocks, the reach vanilla containers allow */
    private static final double REACH_SQ = 64.0d;

    /**
     * Inserted into TYinGanQi.handlePacketData after the packet ID is read. On the client
     * everything passes. On the server packet 1 - the description packet - is refused, and the
     * GUI's packets need the sender within 8 blocks; packet -1 (the GUI opening or closing) always
     * passes, so a player out of reach can still unsubscribe.
     */
    public static boolean detectorPacketAllowed(Object tile, Object player, int id) {
        try {
            Object world = field(tile, "field_70331_k");                                          // TileEntity.worldObj
            if (((Boolean) field(world, "field_72995_K")).booleanValue() || id == -1) {           // World.isRemote
                return true;
            }
            if (id == 1) {
                refuse(tile, player, "proximity detector description packet", "only the server sends that packet");
                return false;
            }
            double dx = ((Double) field(player, "field_70165_t")).doubleValue() - (((Integer) field(tile, "field_70329_l")).intValue() + 0.5d);
            double dy = ((Double) field(player, "field_70163_u")).doubleValue() - (((Integer) field(tile, "field_70330_m")).intValue() + 0.5d);
            double dz = ((Double) field(player, "field_70161_v")).doubleValue() - (((Integer) field(tile, "field_70327_n")).intValue() + 0.5d);
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= REACH_SQ) {
                return true;
            }
            refuse(tile, player, "proximity detector setting", Math.round(Math.sqrt(d)) + " blocks away");
        } catch (Throwable t) {
            System.out.println(TAG + "proximity detector check failed, refused: " + t);
        }
        return false;
    }

    /** Inserted at the top of TYinXing.handlePacketData on the server. The packet is dropped. */
    public static void serverPacketRefused(Object tile, Object player) {
        refuse(tile, player, "camouflage packet", "only the server sends that packet");
    }

    private static void refuse(Object tile, Object player, String what, String why) {
        String where;
        try {
            where = field(tile, "field_70329_l") + "," + field(tile, "field_70330_m") + "," + field(tile, "field_70327_n");
        } catch (Throwable t) {
            where = "?";
        }
        String name;
        try {
            name = String.valueOf(field(player, "field_71092_bJ"));           // EntityPlayer.username
        } catch (Throwable t) {
            name = "?";
        }
        long now = System.currentTimeMillis();
        Long last = (Long) lastLog.get(name);
        if (last != null && now - last.longValue() < LOG_INTERVAL_MS) {
            return;
        }
        lastLog.put(name, Long.valueOf(now));
        System.out.println(TAG + "refused " + what + " at " + where + " (" + why + ") from " + name);
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "#" + name);
    }
}
