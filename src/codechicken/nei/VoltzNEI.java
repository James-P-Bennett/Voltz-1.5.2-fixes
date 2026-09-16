package codechicken.nei;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Exploit fixes injected by Voltz-1.5.2-fixes into NotEnoughItems 1.5.2.28 (PatchNEI).
 *
 * Minecraft and NEI objects arrive as Object and are read reflectively, because NEI ships
 * against obfuscated vanilla names that cannot be compiled against.
 */
public class VoltzNEI {

    private static final String TAG = "[VoltzFixes] ";
    private static final long LOG_INTERVAL_MS = 10000L;

    private static boolean warned = false;
    private static long lastLog = 0L;

    /**
     * Wraps NEIServerConfig.authenticatePacket.
     *
     * Stock lists a required permission for most of NEI's server packets - give, delete,
     * creative, enchant, potion, time, rain - and then falls through to `return true` for
     * everything it forgot. Packet 15 is one of those: it retunes the mob spawner at whatever
     * coordinates the client names, to whatever mob name it sends, for any player, at any
     * distance. Packet 25 writes a dummy slot in the sender's open container with no
     * permission check either. Both now need the same permission as NEI's other creative
     * tools, which defaults to OP.
     */
    public static boolean auth(boolean stock, Object sender, Object packet) {
        if (!stock) {
            return false;
        }
        try {
            int type = ((Integer) call(packet, "getType", new Object[0])).intValue();
            if (type != 15 && type != 25) {
                return true;
            }
            String name = String.valueOf(field(sender, "field_71092_bJ"));      // EntityPlayer.username
            Method m = Class.forName("codechicken.nei.NEIServerConfig").getMethod(
                    "canPlayerUseFeature", new Class[] { String.class, String.class });
            boolean allowed = ((Boolean) m.invoke(null, new Object[] { name, "creative" })).booleanValue();
            if (!allowed) {
                refused(name, "NEI packet " + type);
            }
            return allowed;
        } catch (Throwable t) {
            warn(t);
            return false;
        }
    }

    // ------------------------------------------------------------- reflection

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

    private static Object call(Object o, String name, Object[] args) throws Exception {
        for (Class k = o.getClass(); k != null; k = k.getSuperclass()) {
            Method[] ms = k.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                if (ms[i].getName().equals(name) && ms[i].getParameterTypes().length == args.length) {
                    ms[i].setAccessible(true);
                    return ms[i].invoke(o, args);
                }
            }
        }
        throw new NoSuchMethodException(o.getClass().getName() + "#" + name);
    }

    private static void warn(Throwable t) {
        if (!warned) {
            warned = true;
            System.out.println(TAG + "NEI packet check failed, packet refused: " + t);
        }
    }

    private static void refused(String name, String what) {
        long now = System.currentTimeMillis();
        if (now - lastLog < LOG_INTERVAL_MS) {
            return;
        }
        lastLog = now;
        System.out.println(TAG + "refused " + what + " from " + name);
    }
}
