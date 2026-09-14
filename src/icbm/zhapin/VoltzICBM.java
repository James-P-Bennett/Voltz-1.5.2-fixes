package icbm.zhapin;

import net.minecraftforge.common.Configuration;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Config holder and hooks injected by Voltz-1.5.2-fixes into the ICBM Explosion jar.
 *
 * Loaded from ZhuYaoZhaPin.preInit, so config/VoltzFixes-ICBM.cfg exists at startup.
 *
 * Only primitives, java.* and Forge types are referenced. Minecraft and UE objects arrive
 * as Object and are touched reflectively: the vanilla classes cannot be compiled against.
 */
public class VoltzICBM {

    private static final String TAG = "[VoltzFixes] ";

    /** callCount at which a red matter black hole ends. One call per tick. 0 = never (stock). */
    public static int redMatterMaxTicks = 3000;

    /** flying block entities one sonic or hypersonic explosion may spawn. 0 = unlimited (stock). */
    public static int maxFlyingBlocks = 256;

    private static boolean loaded = false;

    /** Called from ZhuYaoZhaPin.preInit. Safe to call more than once. */
    public static void init() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Configuration cfg = new Configuration(new File("config", "VoltzFixes-ICBM.cfg"));
            cfg.load();

            redMatterMaxTicks = cfg.get("general",
                    "Red Matter Max Ticks", 3000,
                    "Red matter's explosion step never reports itself finished, so a\n"
                  + "black hole lives forever. It is saved with the chunk and rescans a\n"
                  + "radius-35 sphere every tick, even once nothing is left to eat.\n"
                  + "It now ends after this many ticks (3000 = 2.5 minutes).\n"
                  + "0 restores the stock behaviour: it never ends."
            ).getInt(3000);

            maxFlyingBlocks = cfg.get("general",
                    "Max Flying Blocks Per Sonic Explosion", 256,
                    "Sonic and hypersonic explosions turn nearly every block they break\n"
                  + "into a flying block entity - thousands for a hypersonic. Past this\n"
                  + "many, further blocks are still broken but do not become entities.\n"
                  + "0 restores the stock behaviour: unlimited."
            ).getInt(256);

            if (cfg.hasChanged()) {
                cfg.save();
            }
        } catch (Throwable t) {
            System.out.println(TAG + "ICBM config unreadable, using defaults: " + t);
        }
        System.out.println(TAG + "redMatterMaxTicks=" + redMatterMaxTicks
                + " maxFlyingBlocks=" + maxFlyingBlocks);
    }

    // ------------------------------------------------------------- red matter

    /** Prepended to ExHongSu.doBaoZha. true makes it return false, which ends the explosion. */
    public static boolean redMatterExpired(int callCount) {
        return redMatterMaxTicks > 0 && callCount >= redMatterMaxTicks;
    }

    // ------------------------------------------------------------ sonic dedupe

    private static WeakReference dedupeList = new WeakReference(null);
    private static final HashSet dedupe = new HashSet();
    private static Field vecX, vecY, vecZ;
    private static boolean dedupeBroken = false;

    /**
     * Replaces dataList1.add(position) in the sonic and hypersonic ray march, which steps
     * 0.3 blocks at a time and so queues the same block several times per ray, and again
     * for every ray crossing it. Only the first position per block is kept.
     *
     * baoZhaQian fills one list start to finish on the server thread, so a single set
     * reset whenever the list changes is enough.
     */
    public static boolean addUnique(Object list, Object vector) {
        if (!dedupeBroken) {
            try {
                if (dedupeList.get() != list) {
                    dedupeList = new WeakReference(list);
                    dedupe.clear();
                }
                if (vecX == null) {
                    Class c = vector.getClass();
                    vecX = c.getField("x");
                    vecY = c.getField("y");
                    vecZ = c.getField("z");
                }
                long key = pack((int) Math.floor(vecX.getDouble(vector)),
                        (int) Math.floor(vecY.getDouble(vector)),
                        (int) Math.floor(vecZ.getDouble(vector)));
                if (!dedupe.add(Long.valueOf(key))) {
                    return false;
                }
            } catch (Throwable t) {
                dedupeBroken = true;
                System.out.println(TAG + "sonic dedupe disabled, falling back to stock: " + t);
            }
        }
        return ((List) list).add(vector);
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (long) (z & 0x3FFFFFF);
    }

    // ------------------------------------------------------- flying block cap

    /** explosion source entity -> flying blocks spawned so far */
    private static final Map spawned = new WeakHashMap();
    private static Method spawnMethod;

    /**
     * Replaces world.spawnEntityInWorld(flyingBlock) in the sonic and hypersonic doBaoZha.
     * The block itself was already set to air by then, so a refused spawn simply means
     * that block does not fly.
     */
    public static boolean spawnFlyingBlock(Object world, Object entity, Object source) {
        if (maxFlyingBlocks > 0 && source != null) {
            int[] count = (int[]) spawned.get(source);
            if (count == null) {
                count = new int[1];
                spawned.put(source, count);
            }
            if (count[0] >= maxFlyingBlocks) {
                return false;
            }
            count[0]++;
        }
        try {
            if (spawnMethod == null) {
                spawnMethod = findMethod(world.getClass(), "func_72838_d", 1);
            }
            return ((Boolean) spawnMethod.invoke(world, new Object[] { entity })).booleanValue();
        } catch (Throwable t) {
            System.out.println(TAG + "could not spawn flying block: " + t);
            return false;
        }
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
}
