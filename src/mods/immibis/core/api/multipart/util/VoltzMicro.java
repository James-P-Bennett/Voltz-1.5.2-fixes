package mods.immibis.core.api.multipart.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exploit fixes injected by Voltz-1.5.2-fixes into immibis-microblocks 55.0.7 and
 * immibis-core 55.1.6 (PatchMicro). Both jars ship the same BlockMultipartBase.
 *
 * Minecraft objects arrive as Object and are read reflectively on SRG names, because the
 * vanilla classes cannot be compiled against.
 */
public class VoltzMicro {

    private static final String TAG = "[VoltzFixes] ";
    private static final long LOG_INTERVAL_MS = 10000L;
    private static final double REACH_SQ = 64.0d;

    private static final Map fieldCache = Collections.synchronizedMap(new HashMap());
    private static final Map methodCache = Collections.synchronizedMap(new HashMap());
    private static final Map lastLog = Collections.synchronizedMap(new HashMap());
    private static boolean warned = false;

    // -------------------------------------------------------------- part drops

    /**
     * Replaces every read and write of BlockMultipartBase.lastDrop.
     *
     * Breaking a multipart stashes the part's drop in one static field, which vanilla then
     * picks up from getBlockDropped a moment later. MCPC+ ticks each dimension on its own
     * thread, so two players breaking parts in different dimensions at the same moment could
     * have one hand-off overwrite the other: one player got both drops, the other got none.
     * The hand-off is per thread now, so the two never meet.
     */
    private static final ThreadLocal lastDrop = new ThreadLocal();

    public static List getLastDrop() {
        return (List) lastDrop.get();
    }

    public static void setLastDrop(List drop) {
        if (drop == null) {
            lastDrop.remove();
        } else {
            lastDrop.set(drop);
        }
    }

    // ------------------------------------------------------------ part placing

    /**
     * Replaces ItemMicroblock.placeInBlockWithBukkitEvent in PacketMicroblockPlace.onReceived.
     *
     * The packet's coordinates are never checked against the player: stock places the held
     * microblock at whatever position the client names, at any distance, and getBlockTileEntity
     * on the way in loads - or generates - the chunk it lands in. The placement now has to be
     * in a loaded chunk within the player's reach, which is all the real client ever sends.
     */
    public static boolean placeGuarded(Object item, Object world, int x, int y, int z,
                                       Object position, Object stack, Object player, int side) {
        try {
            Object[] xyz = { Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) };
            if (!((Boolean) call(world, "func_72899_e", xyz)).booleanValue()) {       // World.blockExists
                refused(player, "microblock placement in unloaded " + x + "," + y + "," + z);
                return false;
            }
            if (distanceSq(player, x + 0.5d, y + 0.5d, z + 0.5d) > REACH_SQ) {
                refused(player, "microblock placement at " + x + "," + y + "," + z + " (out of reach)");
                return false;
            }
            return ((Boolean) call(item, "placeInBlockWithBukkitEvent", new Object[] {
                    world, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z),
                    position, stack, player, Integer.valueOf(side) })).booleanValue();
        } catch (Throwable t) {
            warn("placeGuarded", t);
            return false;
        }
    }

    // ------------------------------------------------------------- reflection

    private static Object call(Object o, String name, Object[] args) throws Exception {
        String key = o.getClass().getName() + "#" + name + "/" + args.length;
        Method m = (Method) methodCache.get(key);
        if (m == null) {
            for (Class k = o.getClass(); k != null && m == null; k = k.getSuperclass()) {
                Method[] ms = k.getDeclaredMethods();
                for (int i = 0; i < ms.length; i++) {
                    if (ms[i].getName().equals(name) && ms[i].getParameterTypes().length == args.length) {
                        m = ms[i];
                        break;
                    }
                }
            }
            if (m == null) {
                throw new NoSuchMethodException(key);
            }
            m.setAccessible(true);
            methodCache.put(key, m);
        }
        return m.invoke(o, args);
    }

    private static Object field(Object o, String name) throws Exception {
        String key = o.getClass().getName() + "#" + name;
        Field f = (Field) fieldCache.get(key);
        if (f == null) {
            for (Class k = o.getClass(); k != null && f == null; k = k.getSuperclass()) {
                try {
                    f = k.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (f == null) {
                throw new NoSuchFieldException(key);
            }
            f.setAccessible(true);
            fieldCache.put(key, f);
        }
        return f.get(o);
    }

    private static double distanceSq(Object player, double x, double y, double z) throws Exception {
        return ((Double) call(player, "func_70092_e", new Object[] {
                Double.valueOf(x), Double.valueOf(y), Double.valueOf(z) })).doubleValue();
    }

    private static void warn(String where, Throwable t) {
        if (!warned) {
            warned = true;
            System.out.println(TAG + "microblocks " + where + " failed, refusing: " + t);
        }
    }

    private static void refused(Object player, String what) {
        String name;
        try {
            name = String.valueOf(field(player, "field_71092_bJ"));               // EntityPlayer.username
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
