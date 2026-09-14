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

    /** Inserted at the top of TYinXing.handlePacketData on the server. The packet is dropped. */
    public static void serverPacketRefused(Object tile, Object player) {
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
        System.out.println(TAG + "refused camouflage packet at " + where + " (only the server sends that packet) from " + name);
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
