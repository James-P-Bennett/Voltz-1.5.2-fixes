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

    // ------------------------------------------------------- remote detonator

    /** Remote aim range on the client (ItYaoKong.BAN_JING), plus a little for eye height. */
    private static final double REMOTE_RANGE_SQ = 102.0d * 102.0d;
    private static final long LOG_INTERVAL_MS = 10000L;
    private static final Map lastLog = new java.util.HashMap();

    /**
     * Inserted into TZhaDan.handlePacketData just before a remote detonation. Stock checks
     * only that the sender holds a Remote; these are the Remote's own rules, which it
     * otherwise enforces on the client alone:
     *
     *  - the explosive is one a Remote can fire (condensed, breaching, S-mine: nengZha)
     *  - the Remote has more than 1,500 J
     *  - the explosive is the one linked to this Remote, or within its 100-block aim
     *
     * @return true to let the stock detonation run
     */
    public static boolean remoteAllowed(Object tile, Object player, boolean detonatable, double joules, Object linked) {
        try {
            int x = ((Integer) field(tile, "field_70329_l")).intValue();       // TileEntity.xCoord
            int y = ((Integer) field(tile, "field_70330_m")).intValue();
            int z = ((Integer) field(tile, "field_70327_n")).intValue();
            String where = x + "," + y + "," + z;
            if (!detonatable) {
                refused(player, "remote detonation of " + where + " (not a remote-detonatable explosive)");
                return false;
            }
            if (joules <= 1500.0d) {
                refused(player, "remote detonation of " + where + " (remote has no charge)");
                return false;
            }
            if (linked != null
                    && (int) Math.floor(linked.getClass().getField("x").getDouble(linked)) == x
                    && (int) Math.floor(linked.getClass().getField("y").getDouble(linked)) == y
                    && (int) Math.floor(linked.getClass().getField("z").getDouble(linked)) == z) {
                return true;
            }
            double d = ((Double) findMethod(player.getClass(), "func_70092_e", 3).invoke(player,      // Entity.getDistanceSq
                    new Object[] { Double.valueOf(x + 0.5d), Double.valueOf(y + 0.5d), Double.valueOf(z + 0.5d) })).doubleValue();
            if (d <= REMOTE_RANGE_SQ) {
                return true;
            }
            refused(player, "remote detonation of " + where + " (not linked, and out of range)");
            return false;
        } catch (Throwable t) {
            System.out.println(TAG + "remote detonation check failed, refused: " + t);
            return false;
        }
    }

    // --------------------------------------------------------- explosive type

    /**
     * Inserted into TZhaDan.handlePacketData when the server receives packet ID 1, which
     * sets the explosive id. Only the server sends that packet, so the change is dropped.
     */
    public static void typePacketRefused(Object tile, Object player) {
        String where;
        try {
            where = field(tile, "field_70329_l") + "," + field(tile, "field_70330_m") + "," + field(tile, "field_70327_n");
        } catch (Throwable t) {
            where = "?";
        }
        refused(player, "explosive type change at " + where + " (only the server sends that packet)");
    }

    // ------------------------------------------------------ server-only packets

    /** TDianCiQi.MAX_RADIUS, which the stock tower declares and never enforces. */
    public static int empRadius(int radius) {
        return radius < 0 ? 0 : (radius > 150 ? 150 : radius);
    }

    /**
     * Inserted where a machine's packet handler would apply, on the server, a packet that only
     * the server sends. The packet is dropped.
     */
    public static void serverPacketRefused(Object tile, Object player, String what) {
        String where;
        try {
            where = field(tile, "field_70329_l") + "," + field(tile, "field_70330_m") + "," + field(tile, "field_70327_n");
        } catch (Throwable t) {
            where = "?";
        }
        refused(player, what + " at " + where + " (only the server sends that packet)");
    }

    // ----------------------------------------------------------- multiblocks

    /** 10 blocks: a player clicking a dummy block, plus the size of the machine */
    private static final double MULTIBLOCK_REACH_SQ = 100.0d;

    /** Prepended to a multiblock owner's onActivated(player): false returns false unused. */
    public static boolean multiblockReach(Object tile, Object player) {
        try {
            double d = distanceSq(player, tile);
            if (d <= MULTIBLOCK_REACH_SQ) {
                return true;
            }
            refused(player, "use of the machine at " + where(tile) + " through a dummy block " + Math.round(Math.sqrt(d)) + " blocks away");
        } catch (Throwable t) {
            System.out.println(TAG + "multiblock reach check failed, refused: " + t);
        }
        return false;
    }

    /**
     * Prepended to a multiblock owner's onDestroy(callingBlock). A real dummy block is part of
     * the machine, within 2 blocks of its main block; anything further was pointed at it by a
     * packet, and breaking it must not delete the machine.
     */
    public static boolean multiblockPart(Object tile, Object callingBlock) {
        if (callingBlock == null || callingBlock == tile) {
            return true;
        }
        try {
            int dx = Math.abs(coord(callingBlock, "field_70329_l") - coord(tile, "field_70329_l"));
            int dy = Math.abs(coord(callingBlock, "field_70330_m") - coord(tile, "field_70330_m"));
            int dz = Math.abs(coord(callingBlock, "field_70327_n") - coord(tile, "field_70327_n"));
            if (dx <= 2 && dy <= 2 && dz <= 2) {
                return true;
            }
            refused(null, "removal of the machine at " + where(tile) + " by an unrelated block at " + where(callingBlock));
        } catch (Throwable t) {
            System.out.println(TAG + "multiblock part check failed, refused: " + t);
        }
        return false;
    }

    // ------------------------------------------------------- laser designator

    /**
     * Inserted into ZhaPinPacketGuanLi's LASER_DESIGNATOR branch before the strike starts. These
     * are the designator's own rules, which stock checks on the client only: a frequency, no
     * strike already counting down, more than 6,000 J, and a target inside its ray-trace range
     * (BAN_JING * 2).
     */
    public static boolean designatorAllowed(Object player, Object target, int frequency, int countdown, double joules, int range) {
        try {
            String what;
            if (frequency <= 0) {
                what = "no frequency set";
            } else if (countdown > 0) {
                what = "a strike is already counting down";
            } else if (joules <= 6000.0d) {
                what = "no charge";
            } else {
                double tx = target.getClass().getField("x").getDouble(target);
                double ty = target.getClass().getField("y").getDouble(target);
                double tz = target.getClass().getField("z").getDouble(target);
                double d = ((Double) findMethod(player.getClass(), "func_70092_e", 3).invoke(player,
                        new Object[] { Double.valueOf(tx), Double.valueOf(ty), Double.valueOf(tz) })).doubleValue();
                double max = range * 2.0d + 2.0d;
                if (d <= max * max) {
                    return true;
                }
                what = "target " + Math.round(Math.sqrt(d)) + " blocks away";
            }
            refused(player, "laser designator strike (" + what + ")");
        } catch (Throwable t) {
            System.out.println(TAG + "laser designator check failed, refused: " + t);
        }
        return false;
    }

    // ---------------------------------------------------------- missile stack

    /**
     * Replaces the `null` a launcher's onActivated writes into the player's held slot after
     * loading one missile: the rest of the held stack, or null once it is empty.
     */
    public static Object heldRemainder(Object player) {
        try {
            Object inventory = field(player, "field_71071_by");                                  // EntityPlayer.inventory
            Object held = findMethod(inventory.getClass(), "func_70448_g", 0).invoke(inventory, new Object[0]);   // getCurrentItem
            if (held != null && ((Integer) field(held, "field_77994_a")).intValue() > 0) {        // ItemStack.stackSize
                return held;
            }
        } catch (Throwable t) {
            System.out.println(TAG + "could not keep the rest of a missile stack: " + t);
        }
        return null;
    }

    // ------------------------------------------------------------- chunk guard

    /**
     * Gate for an ICBM packet-handler onPacketData override. The shared UE router looks up the
     * target tile with getBlockTileEntity, which loads (or generates) the chunk at the client's
     * coordinates - a far-coordinate spam can force chunk generation on Forge. A TILEENTITY packet
     * (type 1) is dropped when its chunk is not already loaded; other packets pass.
     */
    public static boolean chunkGuard(Object packet, Object player) {
        try {
            byte[] data = (byte[]) cgField(packet, "field_73629_c");                              // Packet250.data
            if (data == null || data.length < 16) {
                return true;
            }
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
            int type = in.readInt();
            if (type != 1) {                                                                      // only TILEENTITY carries coordinates
                return true;
            }
            int x = in.readInt(), y = in.readInt(), z = in.readInt();
            Object world = cgField(player, "field_70170_p");                                      // Entity.worldObj
            java.lang.reflect.Method blockExists = null;
            for (Class k = world.getClass(); k != null && blockExists == null; k = k.getSuperclass()) {
                try { blockExists = k.getDeclaredMethod("func_72899_e", int.class, int.class, int.class); }
                catch (NoSuchMethodException ignored) { }
            }
            if (blockExists == null) {
                return true;
            }
            blockExists.setAccessible(true);
            return ((Boolean) blockExists.invoke(world, new Object[] { Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) })).booleanValue();
        } catch (Throwable t) {
            return true;
        }
    }

    private static Object cgField(Object o, String name) throws Exception {
        for (Class k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "#" + name);
    }

    // ------------------------------------------------------------- listeners

    /** 10 blocks to open a machine GUI, 16 before a listener is dropped */
    private static final double LISTENER_JOIN_SQ = 100.0d;
    private static final double LISTENER_KEEP_SQ = 256.0d;

    /** Replaces listeners.add(player) where a GUI subscribes: only within reach. */
    public static boolean addListener(java.util.Set listeners, Object player, Object tile) {
        try {
            if (lDistanceSq(player, tile) <= LISTENER_JOIN_SQ) {
                return listeners.add(player);
            }
            lRefused(player, "GUI subscription at " + lWhere(tile) + " (out of reach)");
        } catch (Throwable t) {
            System.out.println(TAG + "listener check failed, refused: " + t);
        }
        return false;
    }

    /** Runs before a tick loop walks its listener set: drops dead, departed and distant players. */
    public static void pruneListeners(java.util.Set listeners, Object tile) {
        if (listeners.isEmpty()) {
            return;
        }
        try {
            Object world = lField(tile, "field_70331_k");                                       // TileEntity.worldObj
            for (java.util.Iterator it = listeners.iterator(); it.hasNext(); ) {
                Object p = it.next();
                if (p == null || ((Boolean) lField(p, "field_70128_L")).booleanValue()            // Entity.isDead
                        || lField(p, "field_70170_p") != world                                    // Entity.worldObj
                        || lDistanceSq(p, tile) > LISTENER_KEEP_SQ) {
                    it.remove();
                }
            }
        } catch (Throwable t) {
            System.out.println(TAG + "listener prune failed: " + t);
        }
    }

    private static double lDistanceSq(Object player, Object tile) throws Exception {
        double dx = ((Double) lField(player, "field_70165_t")).doubleValue() - (((Integer) lField(tile, "field_70329_l")).intValue() + 0.5d);
        double dy = ((Double) lField(player, "field_70163_u")).doubleValue() - (((Integer) lField(tile, "field_70330_m")).intValue() + 0.5d);
        double dz = ((Double) lField(player, "field_70161_v")).doubleValue() - (((Integer) lField(tile, "field_70327_n")).intValue() + 0.5d);
        return dx * dx + dy * dy + dz * dz;
    }

    private static String lWhere(Object tile) {
        try {
            return lField(tile, "field_70329_l") + "," + lField(tile, "field_70330_m") + "," + lField(tile, "field_70327_n");
        } catch (Throwable t) {
            return "?";
        }
    }

    private static Object lField(Object o, String name) throws Exception {
        for (Class k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "#" + name);
    }

    private static final java.util.Map lLastLog = new java.util.HashMap();

    private static void lRefused(Object player, String what) {
        String name;
        try {
            name = String.valueOf(lField(player, "field_71092_bJ"));                               // EntityPlayer.username
        } catch (Throwable t) {
            name = "?";
        }
        long now = System.currentTimeMillis();
        Long last = (Long) lLastLog.get(name);
        if (last != null && now - last.longValue() < 10000L) {
            return;
        }
        lLastLog.put(name, Long.valueOf(now));
        System.out.println(TAG + "refused " + what + " from " + name);
    }

    // ----------------------------------------------------------------- radar

    /** TLeiDaTai.MAX_BIAN_JING, which only the radar GUI enforces. */
    public static int radarRadius(int radius) {
        return radius < 0 ? 0 : (radius > 500 ? 500 : radius);
    }

    /** Inserted into the RADAR_GUN packet branch when the held radar gun has 1,000 J or less. */
    public static void radarGunRefused(Object player) {
        refused(player, "radar gun packet (no charge)");
    }

    private static int coord(Object tile, String name) throws Exception {
        return ((Integer) field(tile, name)).intValue();
    }

    private static String where(Object tile) {
        try {
            return field(tile, "field_70329_l") + "," + field(tile, "field_70330_m") + "," + field(tile, "field_70327_n");
        } catch (Throwable t) {
            return "?";
        }
    }

    private static double distanceSq(Object player, Object tile) throws Exception {
        return ((Double) findMethod(player.getClass(), "func_70092_e", 3).invoke(player, new Object[] {
                Double.valueOf(coord(tile, "field_70329_l") + 0.5d),
                Double.valueOf(coord(tile, "field_70330_m") + 0.5d),
                Double.valueOf(coord(tile, "field_70327_n") + 0.5d) })).doubleValue();
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "#" + name);
    }

    private static void refused(Object player, String what) {
        String name;
        try {
            name = player == null ? "a dummy block" : String.valueOf(field(player, "field_71092_bJ"));   // EntityPlayer.username
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
