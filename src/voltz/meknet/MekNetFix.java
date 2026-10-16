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
 */
public final class MekNetFix {

    private MekNetFix() {
    }

    private static final String TAG = "[VoltzMekNet] ";
    private static final long LOG_INTERVAL_MS = 10000L;
    private static long lastLog;
    private static Field cablesField, worldField;
    private static Method refreshMethod;

    /**
     * Injected at the head of EnergyNetwork.refresh(). Drops cables that have no world any more,
     * as refresh() already drops invalidated ones; a cable rejoins a network when its chunk loads
     * again. Never throws.
     */
    public static void prune(Object network) {
        try {
            if (cablesField == null) {
                cablesField = network.getClass().getField("cables");
            }
            Set cables = (Set) cablesField.get(network);
            if (cables == null) return;
            Iterator it = cables.iterator();
            while (it.hasNext()) {
                Object cable = it.next();
                if (cable == null || world(cable) == null) {
                    it.remove();
                }
            }
        } catch (Throwable t) {
            log("cable prune skipped: " + t);
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
            log("energy network refresh skipped: " + cause);
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

    private static synchronized void log(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastLog >= LOG_INTERVAL_MS) {
            lastLog = now;
            System.out.println(TAG + msg);
        }
    }
}
