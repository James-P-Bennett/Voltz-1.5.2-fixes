package voltz.meknet;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Set;

/**
 * Runtime helper for MekNetTransformer. Self contained (nothing from this repo), so it can be
 * shaded and relocated into another runtime patching mod together with the transformer.
 *
 * The crash. A Mekanism 5.5.6 EnergyNetwork keeps its Universal Cables in a set. A chunk unload,
 * notably under TickThreading, can leave a cable in that set whose world is already gone
 * (worldObj null) without invalidating it. The next refresh() walks the set, reaches
 * Object3D.get(cable), dereferences the missing world and throws a NullPointerException out of
 * EnergyNetworkRegistry.tickEnd, which kills the server tick:
 *
 *   java.lang.NullPointerException
 *       at mekanism.api.Object3D.get(Object3D.java:75)
 *       at mekanism.common.CableUtils.getConnectedEnergyAcceptors(CableUtils.java:33)
 *       at mekanism.common.EnergyNetwork.refresh(EnergyNetwork.java:196)
 *       at mekanism.common.EnergyNetwork.tick(EnergyNetwork.java)
 *
 * A periodic acceptor rescan in tick() (the cablereload fix) makes it far more likely, because
 * refresh() then runs every few seconds on every network.
 *
 * The world can also be present but dead: TickThreading clears the fields of an unloaded world,
 * so a cable left behind in it still has a worldObj whose provider is null, and Object3D.get
 * throws at the same line. A throw part way through refresh() also leaves the network with only
 * some of its acceptors, so machines on it go unpowered until a refresh succeeds. prune() runs
 * the exact scan refresh() is about to run on each cable and drops any cable it throws on, so
 * the rest of the network still refreshes.
 */
public final class MekNetFix {

    private MekNetFix() {
    }

    private static final String TAG = "[VoltzMekNet] ";
    private static final long LOG_INTERVAL_MS = 10000L;
    private static long lastLog;
    private static Field cablesField, worldField;
    private static Method refreshMethod, scanMethod;
    private static boolean scanUnavailable;

    /**
     * Injected at the head of EnergyNetwork.refresh(). Drops cables that have no world, and cables
     * whose acceptor scan throws (a dead world), as refresh() already drops invalidated ones; a
     * cable rejoins a network when its chunk loads again. Never throws.
     */
    public static void prune(Object network) {
        try {
            if (cablesField == null) {
                cablesField = network.getClass().getField("cables");
            }
            Set cables = (Set) cablesField.get(network);
            if (cables == null) return;
            int dropped = 0;
            Throwable why = null;
            Iterator it = cables.iterator();
            while (it.hasNext()) {
                Object cable = it.next();
                Throwable t = cable == null || world(cable) == null ? NO_WORLD : scan(cable);
                if (t != null) {
                    it.remove();
                    dropped++;
                    if (why == null) why = t;
                }
            }
            if (dropped > 0) {
                log("dropped " + dropped + " dead cable(s) from an energy network: " + describe(why));
            }
        } catch (Throwable t) {
            log("cable prune skipped: " + describe(t));
        }
    }

    private static final Throwable NO_WORLD = new IllegalStateException("cable has no world");

    /**
     * Runs CableUtils.getConnectedEnergyAcceptors(cable), the call refresh() makes per cable.
     * Returns what it threw, or null when it ran (or cannot be found, so nothing is dropped).
     */
    private static Throwable scan(Object cable) {
        if (scanUnavailable) return null;
        try {
            if (scanMethod == null) {
                Class k = Class.forName("mekanism.common.CableUtils", false, cable.getClass().getClassLoader());
                Method[] ms = k.getMethods();
                for (int i = 0; i < ms.length; i++) {
                    if (ms[i].getName().equals("getConnectedEnergyAcceptors") && ms[i].getParameterTypes().length == 1) {
                        scanMethod = ms[i];
                    }
                }
                if (scanMethod == null) throw new NoSuchMethodException("CableUtils.getConnectedEnergyAcceptors");
            }
            scanMethod.invoke(null, cable);
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            return e.getCause();
        } catch (Throwable t) {
            scanUnavailable = true;
            log("cable scan check off: " + t);
            return null;
        }
    }

    /**
     * Replaces a direct refresh() call inside EnergyNetwork.tick(). The rescan is best effort, so
     * nothing it throws may reach the server tick.
     */
    public static void refresh(Object network) {
        try {
            if (refreshMethod == null) {
                refreshMethod = network.getClass().getMethod("refresh");
            }
            refreshMethod.invoke(network);
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ? t.getCause() : t;
            log("energy network refresh skipped: " + describe(cause));
        }
    }

    /** TileEntity.worldObj, by SRG, MCP or obfuscated name, so it works before or after FML deobfuscation. */
    private static Object world(Object tile) throws Exception {
        if (worldField == null) {
            String[] names = { "field_70331_k", "worldObj", "k" };
            for (Class k = tile.getClass(); k != null && worldField == null; k = k.getSuperclass()) {
                for (int i = 0; i < names.length && worldField == null; i++) {
                    try {
                        Field f = k.getDeclaredField(names[i]);
                        if (f.getType().isPrimitive()) continue;
                        f.setAccessible(true);
                        worldField = f;
                    } catch (NoSuchFieldException ignored) {
                        // try the next name
                    }
                }
            }
            if (worldField == null) throw new NoSuchFieldException("TileEntity.worldObj");
        }
        return worldField.get(tile);
    }

    /** the exception plus its top frames, so a log line says where it came from */
    static String describe(Throwable t) {
        if (t == null) return "null";
        if (t == NO_WORLD) return t.getMessage();
        StringBuilder sb = new StringBuilder(String.valueOf(t));
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 4; i++) {
            sb.append(i == 0 ? " at " : " < ").append(st[i]);
        }
        return sb.toString();
    }

    private static synchronized void log(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastLog >= LOG_INTERVAL_MS) {
            lastLog = now;
            System.out.println(TAG + msg);
        }
    }
}
