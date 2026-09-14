package icbm.gangshao;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Exploit fixes injected by Voltz-1.5.2-fixes into ICBM Sentry 1.2.1 (PatchICBMSentry).
 *
 * Minecraft objects arrive as Object and are read reflectively on SRG names, because the
 * vanilla classes cannot be compiled against.
 */
public class VoltzSentry {

    private static final String TAG = "[VoltzFixes] ";
    private static final long LOG_INTERVAL_MS = 10000L;
    /** 8 blocks, the reach vanilla containers allow */
    private static final double REACH_SQ = 64.0d;

    private static final Map lastLog = new HashMap();
    private static boolean warned = false;

    /**
     * Replaces world.getPlayerEntityByName(name) in TileEntityTerminal's command branch. Stock
     * runs the command as whoever the packet names. This ignores the name and returns the
     * player who actually sent the packet, or null - and the command is dropped - when that
     * player is out of reach of the terminal.
     */
    public static Object commandSender(Object world, String claimedName, Object tile, Object player) {
        try {
            int x = ((Integer) field(tile, "field_70329_l")).intValue();         // TileEntity.xCoord
            int y = ((Integer) field(tile, "field_70330_m")).intValue();
            int z = ((Integer) field(tile, "field_70327_n")).intValue();
            double d = ((Double) findMethod(player.getClass(), "func_70092_e", 3).invoke(player,   // getDistanceSq
                    new Object[] { Double.valueOf(x + 0.5d), Double.valueOf(y + 0.5d), Double.valueOf(z + 0.5d) })).doubleValue();
            if (d <= REACH_SQ) {
                return player;
            }
            refused(player, "terminal command at " + x + "," + y + "," + z + " sent as " + claimedName
                    + " (" + Math.round(Math.sqrt(d)) + " blocks away)");
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                System.out.println(TAG + "terminal command check failed, refused: " + t);
            }
        }
        return null;
    }

    /** Inserted at the top of TPaoDaiBase.handlePacketData on the server. The packet is dropped. */
    public static void serverPacketRefused(Object tile, Object player, String what) {
        String where;
        try {
            where = field(tile, "field_70329_l") + "," + field(tile, "field_70330_m") + "," + field(tile, "field_70327_n");
        } catch (Throwable t) {
            where = "?";
        }
        refused(player, what + " at " + where + " (only the server sends that packet)");
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

    private static Method findMethod(Class c, String name, int argc) throws NoSuchMethodException {
        for (Class k = c; k != null; k = k.getSuperclass()) {
            Method[] ms = k.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                if (ms[i].getName().equals(name) && ms[i].getParameterTypes().length == argc) {
                    ms[i].setAccessible(true);
                    return ms[i];
                }
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }

    private static void refused(Object player, String what) {
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
        System.out.println(TAG + "refused " + what + " from " + name);
    }
}
