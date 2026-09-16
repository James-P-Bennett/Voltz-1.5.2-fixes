package powercrystals.minefactoryreloaded;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exploit fixes injected by Voltz-1.5.2-fixes into MineFactoryReloaded 2.6.4 (PatchMFR).
 *
 * Minecraft and MFR objects arrive as Object and are read reflectively on SRG names,
 * because the vanilla classes cannot be compiled against.
 */
public class VoltzMFR {

    private static final String TAG = "[VoltzFixes] ";
    private static final long LOG_INTERVAL_MS = 10000L;

    private static final Map fieldCache = Collections.synchronizedMap(new HashMap());
    private static final Map methodCache = Collections.synchronizedMap(new HashMap());
    private static final Map lastLog = Collections.synchronizedMap(new HashMap());
    private static boolean warned = false;
    private static Class slotFake;

    /** Vanilla's own block-interaction reach, squared, plus the usual slack. */
    private static final double REACH_SQ = 64.0d;

    /** The only keys TileEntityHarvester ever defines; everything else is client-invented. */
    private static final Set HARVESTER_KEYS = Collections.unmodifiableSet(new HashSet(
            Arrays.asList(new String[] { "silkTouch", "harvestSmallMushrooms", "harvestJungleWood" })));

    /** Guards the mutual fill() recursion between adjacent Liquid Routers. */
    private static final ThreadLocal routeDepth = new ThreadLocal();
    private static final int MAX_ROUTE_DEPTH = 8;

    /** One pending Deep Storage Unit NBT hand-off per world thread. */
    private static final ThreadLocal pendingNbt = new ThreadLocal();
    private static final long NBT_MAX_AGE_MS = 1000L;

    /**
     * Replaces both inventory.setInventorySlotContents(slot, stack) calls in the packet 19
     * branch of ServerPacketHandler.onPacketData.
     *
     * Packet 19 is meant for MFR's ghost filter slots: clicking a SlotFake in a machine GUI
     * sends the machine's coordinates and the slot's number, and the server stores a size-1
     * copy of the cursor there. Stock trusts the packet completely - any coordinates, any
     * slot, any inventory - and never takes the item from the cursor. A client can write a
     * free copy of whatever it holds into every slot of any chest, or empty any slot in the
     * world with an empty cursor.
     *
     * The write now goes through only if the player's open container is still usable, the
     * slot at that number in it is a SlotFake, and that slot belongs to this inventory -
     * exactly what the real GUI sends.
     */
    public static void setGhostSlot(Object inventory, int slot, Object stack, Object player) {
        try {
            Object container = field(player, "field_71070_bA");               // EntityPlayer.openContainer
            if (container != null) {
                List slots = (List) field(container, "field_75151_b");         // Container.inventorySlots
                if (slot >= 0 && slot < slots.size()) {
                    Object s = slots.get(slot);
                    if (slotFake == null) {
                        slotFake = Class.forName("powercrystals.minefactoryreloaded.gui.slot.SlotFake");
                    }
                    if (slotFake.isInstance(s)
                            && field(s, "field_75224_c") == inventory               // Slot.inventory
                            && ((Integer) field(s, "field_75225_a")).intValue() == slot  // Slot.slotIndex
                            && ((Boolean) call(container, "func_75145_c", new Object[] { player })).booleanValue()) {
                        call(inventory, "func_70299_a", new Object[] { Integer.valueOf(slot), stack });
                        return;
                    }
                }
            }
            refused(player, "ghost slot write to " + inventory.getClass().getSimpleName() + " slot " + slot
                    + " (no matching ghost slot open)");
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                System.out.println(TAG + "MFR ghost slot check failed, write refused: " + t);
            }
        }
    }

    // ------------------------------------------------------- packet tile lookup

    /**
     * Replaces every player.worldObj.getBlockTileEntity(x, y, z) in
     * ServerPacketHandler.onPacketData - packets 2, 3, 4, 5, 9, 10, 13, 14, 15, 16, 17 and 19.
     *
     * Stock hands the client's coordinates straight to getBlockTileEntity, which loads (or
     * generates) the chunk they land in. Every one of those packets then acts on whatever tile
     * it finds: retune an Auto-Enchanter, flip a Deep Storage Unit's output sides, copy a
     * record in an Auto Jukebox, reprogram a RedNet Logic block - anywhere in the world, from
     * any distance. The lookup now only answers for a tile in a chunk that is already loaded
     * and within the player's reach; otherwise the packet's `instanceof` fails and it stops.
     */
    public static Object tileAt(Object world, int x, int y, int z, Object player) {
        try {
            Object[] xyz = { Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) };
            if (!((Boolean) call(world, "func_72899_e", xyz)).booleanValue()) {       // World.blockExists
                refused(player, "MFR packet for unloaded " + x + "," + y + "," + z);
                return null;
            }
            if (distanceSq(player, x + 0.5d, y + 0.5d, z + 0.5d) > REACH_SQ) {
                refused(player, "MFR packet for " + x + "," + y + "," + z + " (out of reach)");
                return null;
            }
            return call(world, "func_72796_p", xyz);                                 // World.getBlockTileEntity
        } catch (Throwable t) {
            warn("tileAt", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ packet 3

    /**
     * Replaces harvester.getSettings().put(key, value) in the packet 3 branch.
     *
     * The key is a raw client string and the map is saved to NBT verbatim on every world save,
     * so a client could grow a Harvester's settings - and the region file holding it - without
     * limit. Only the three keys the tile actually defines are accepted.
     */
    public static Object harvesterSetting(Object settings, Object key, Object value) {
        if (!HARVESTER_KEYS.contains(key)) {
            return null;
        }
        try {
            return call(settings, "put", new Object[] { key, value });
        } catch (Throwable t) {
            warn("harvesterSetting", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ packet 5

    /**
     * Replace the getIsSideOutput / setSideIsOutput pair in the packet 5 branch: the side index
     * is a client int used straight as an array subscript into a six-element array, so anything
     * outside 0..5 threw out of the packet handler.
     */
    public static boolean dsuGetSide(Object tile, int side) {
        if (side < 0 || side > 5) {
            return false;
        }
        try {
            return ((Boolean) call(tile, "getIsSideOutput", new Object[] { Integer.valueOf(side) })).booleanValue();
        } catch (Throwable t) {
            warn("dsuGetSide", t);
            return false;
        }
    }

    public static void dsuSide(Object tile, int side, boolean output) {
        if (side < 0 || side > 5) {
            return;
        }
        try {
            call(tile, "setSideIsOutput", new Object[] { Integer.valueOf(side), Boolean.valueOf(output) });
        } catch (Throwable t) {
            warn("dsuSide", t);
        }
    }

    // --------------------------------------------------------- packets 13/14/15

    /**
     * RedNet Logic's circuit, buffer and pin numbers all come off the wire and are used as raw
     * array subscripts, so any of packets 13, 14, 15 could throw straight out of the packet
     * handler. Each entry point now checks its indices first.
     */
    public static void rnSendDef(Object tile, int circuit) {
        if (!rnCircuitInRange(tile, circuit)) {
            return;
        }
        try {
            call(tile, "sendCircuitDefinition", new Object[] { Integer.valueOf(circuit) });
        } catch (Throwable t) {
            warn("rnSendDef", t);
        }
    }

    /**
     * initCircuit's class name is a client string that stock feeds to Class.forName().
     * newInstance() - the cast to IRedNetLogicCircuit only fails after the constructor has
     * already run, so a client could have the server instantiate any class on its classpath
     * that has a public no-argument constructor. Only the circuit classes MFR (or another mod)
     * actually registered are accepted now.
     */
    public static void rnInit(Object tile, int circuit, String className) {
        if (!rnCircuitInRange(tile, circuit) || !rnCircuitRegistered(className)) {
            return;
        }
        try {
            // TileEntityRedNetLogic declares initCircuit(int, String) next to a private
            // initCircuit(int, IRedNetLogicCircuit), so this one needs the exact signature.
            Method m = null;
            for (Class k = tile.getClass(); k != null && m == null; k = k.getSuperclass()) {
                try {
                    m = k.getDeclaredMethod("initCircuit", new Class[] { int.class, String.class });
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (m == null) {
                return;
            }
            m.setAccessible(true);
            m.invoke(tile, new Object[] { Integer.valueOf(circuit), className });
        } catch (Throwable t) {
            warn("rnInit", t);
        }
    }

    public static void rnPinIn(Object tile, int circuit, int pin, int buffer, int index) {
        rnPin(tile, "setInputPinMapping", "_pinMappingInputs", circuit, pin, buffer, index);
    }

    public static void rnPinOut(Object tile, int circuit, int pin, int buffer, int index) {
        rnPin(tile, "setOutputPinMapping", "_pinMappingOutputs", circuit, pin, buffer, index);
    }

    private static void rnPin(Object tile, String method, String field, int circuit, int pin,
                              int buffer, int index) {
        if (!rnCircuitInRange(tile, circuit)) {
            return;
        }
        try {
            Object[] mappings = (Object[]) field(tile, field);
            Object row = mappings[circuit];
            if (row == null || pin < 0 || pin >= java.lang.reflect.Array.getLength(row)) {
                return;
            }
            // buffer indexes _buffers, which is a fixed 15-row table on the tile
            Object[] buffers = (Object[]) field(tile, "_buffers");
            if (buffer < 0 || buffer >= buffers.length) {
                return;
            }
            if (index < 0 || index >= java.lang.reflect.Array.getLength(buffers[buffer])) {
                return;
            }
            call(tile, method, new Object[] { Integer.valueOf(circuit), Integer.valueOf(pin),
                    Integer.valueOf(buffer), Integer.valueOf(index) });
        } catch (Throwable t) {
            warn("rnPin", t);
        }
    }

    private static boolean rnCircuitInRange(Object tile, int circuit) {
        try {
            Object[] circuits = (Object[]) field(tile, "_circuits");
            return circuit >= 0 && circuit < circuits.length && circuits[circuit] != null;
        } catch (Throwable t) {
            warn("rnCircuitInRange", t);
            return false;
        }
    }

    private static boolean rnCircuitRegistered(String className) {
        if (className == null) {
            return false;
        }
        try {
            Class registry = Class.forName("powercrystals.minefactoryreloaded.MFRRegistry");
            Method m = registry.getMethod("getRedNetLogicCircuits", new Class[0]);
            List circuits = (List) m.invoke(null, new Object[0]);
            for (Iterator it = circuits.iterator(); it.hasNext(); ) {
                Object c = it.next();
                if (c != null && c.getClass().getName().equals(className)) {
                    return true;
                }
            }
            // Noop is the fallback stock installs itself and never registers
            return className.equals("powercrystals.minefactoryreloaded.circuits.Noop");
        } catch (Throwable t) {
            warn("rnCircuitRegistered", t);
            return false;
        }
    }

    // ------------------------------------------------------------- liquid router

    /**
     * Replaces the two ITankContainer.fill calls in TileEntityLiquidRouter.weightedRouteLiquid.
     *
     * A router's own fill() routes straight into its neighbours' fill(), so two routers aimed at
     * each other - or any ring of them - recurse until the server thread blows its stack. The
     * chain is now cut off after a handful of hops, which is deeper than any real routing setup.
     */
    public static int routeFill(Object target, Object direction, Object liquid, boolean doFill) {
        Integer depth = (Integer) routeDepth.get();
        int d = depth == null ? 0 : depth.intValue();
        if (d >= MAX_ROUTE_DEPTH) {
            return 0;
        }
        routeDepth.set(Integer.valueOf(d + 1));
        try {
            // ITankContainer declares fill(ForgeDirection, ...) and fill(int, ...) side by side,
            // so this one cannot be resolved on arity alone.
            Method m = null;
            for (Class k = target.getClass(); k != null && m == null; k = k.getSuperclass()) {
                Method[] ms = k.getDeclaredMethods();
                for (int i = 0; i < ms.length; i++) {
                    Class[] p = ms[i].getParameterTypes();
                    if (ms[i].getName().equals("fill") && p.length == 3 && !p[0].isPrimitive()) {
                        m = ms[i];
                        break;
                    }
                }
            }
            if (m == null) {
                return 0;
            }
            m.setAccessible(true);
            return ((Integer) m.invoke(target, new Object[] { direction, liquid, Boolean.valueOf(doFill) }))
                    .intValue();
        } catch (Throwable t) {
            warn("routeFill", t);
            return 0;
        } finally {
            if (d == 0) {
                routeDepth.remove();
            } else {
                routeDepth.set(Integer.valueOf(d));
            }
        }
    }

    // ------------------------------------------------- deep storage unit hand-off

    /**
     * Replace BlockNBTManager.setForBlock / getForBlock for the Deep Storage Unit hand-off.
     *
     * A broken DSU stashes its whole contents in a static map keyed by x/y/z alone - no
     * dimension, no expiry - and the block item picks it up again in getBlockDropped. Break one
     * in a way that never reaches getBlockDropped (creative, an explosion, a block breaker) and
     * the entry just sits there: the next DSU broken at those coordinates, in any dimension,
     * drops carrying the old one's contents. The hand-off is now a single slot per world
     * thread, only valid for the coordinates it was written for and only for the tick it was
     * written in, which is all the stock path ever needed.
     */
    public static void setBlockNbt(Object tile) {
        if (tile == null) {
            return;
        }
        try {
            Object nbt = Class.forName("net.minecraft.nbt.NBTTagCompound").newInstance();
            call(tile, "func_70310_b", new Object[] { nbt });                     // writeToNBT
            pendingNbt.set(new Object[] {
                    field(tile, "field_70329_l"), field(tile, "field_70330_m"), field(tile, "field_70327_n"),
                    nbt, Long.valueOf(System.currentTimeMillis()) });
        } catch (Throwable t) {
            warn("setBlockNbt", t);
        }
    }

    public static Object getBlockNbt(int x, int y, int z) {
        Object[] slot = (Object[]) pendingNbt.get();
        pendingNbt.remove();
        if (slot == null) {
            return null;
        }
        if (((Integer) slot[0]).intValue() != x || ((Integer) slot[1]).intValue() != y
                || ((Integer) slot[2]).intValue() != z) {
            return null;
        }
        if (System.currentTimeMillis() - ((Long) slot[4]).longValue() > NBT_MAX_AGE_MS) {
            return null;
        }
        return slot[3];
    }

    // ------------------------------------------------------------------ machines

    /**
     * Replaces isUseableByPlayer on the Deep Storage Unit, Laser Drill and LiquiCrafter, which
     * each override TileEntityFactoryInventory's version and drop its "am I still the tile at
     * my own position" half. Without it the machine can be broken - dropping its contents -
     * with its GUI still open and live, and the slots emptied a second time out of the orphan.
     */
    public static boolean tileUsable(Object tile, Object player) {
        try {
            Object world = field(tile, "field_70331_k");                          // TileEntity.worldObj
            if (world == null) {
                return false;
            }
            int x = ((Integer) field(tile, "field_70329_l")).intValue();
            int y = ((Integer) field(tile, "field_70330_m")).intValue();
            int z = ((Integer) field(tile, "field_70327_n")).intValue();
            Object here = call(world, "func_72796_p",
                    new Object[] { Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) });
            if (here != tile) {
                return false;
            }
            return distanceSq(player, x + 0.5d, y + 0.5d, z + 0.5d) <= REACH_SQ;
        } catch (Throwable t) {
            warn("tileUsable", t);
            return true;
        }
    }

    // ------------------------------------------------------------- reflection

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

    private static double distanceSq(Object player, double x, double y, double z) throws Exception {
        return ((Double) call(player, "func_70092_e", new Object[] {
                Double.valueOf(x), Double.valueOf(y), Double.valueOf(z) })).doubleValue();
    }

    private static void warn(String where, Throwable t) {
        if (!warned) {
            warned = true;
            System.out.println(TAG + "MFR " + where + " failed, refusing: " + t);
        }
    }

    private static void refused(Object player, String what) {
        String name;
        try {
            name = String.valueOf(field(player, "field_71092_bJ"));          // EntityPlayer.username
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
