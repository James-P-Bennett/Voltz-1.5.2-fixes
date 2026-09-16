package mekanism.common;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Exploit and crash fixes injected by Voltz-1.5.2-fixes into Mekanism 5.5.6 (PatchMek).
 *
 *  - Electric Chest item: the open inventory is bound to the exact ItemStack it was opened
 *    from, instead of whatever the player happens to be holding when it saves.
 *  - Electric Chest packets: coordinates sent by the client must name a loaded Electric
 *    Chest within reach.
 *  - Electric Chests cannot be stored inside Electric Chests.
 *  - Machine and Robit GUIs close when the block or Robit is gone or out of reach.
 *
 * Minecraft and Mekanism objects arrive as Object and are read reflectively on SRG names,
 * because the vanilla classes cannot be compiled against. The hottest path is a reach
 * check once per tick per open GUI.
 */
public class VoltzMekanism {

    private static final String TAG = "[VoltzFixes] ";

    /** vanilla chest reach: squared distance from the player to the block centre */
    private static final double REACH_SQ = 64.0d;

    private static final long LOG_INTERVAL_MS = 10000L;

    private static final Map fieldCache = Collections.synchronizedMap(new HashMap());
    private static final Map methodCache = Collections.synchronizedMap(new HashMap());
    private static final Map warned = Collections.synchronizedMap(new HashMap());
    private static final Map lastLog = Collections.synchronizedMap(new HashMap());

    private static Class electricChestItem;
    private static Class electricChestTile;
    private static Class robitClass;
    private static Class playerInventory;

    // -------------------------------------------- Energy network reload heal

    private static final Map energyTicks = Collections.synchronizedMap(new WeakHashMap());

    /** ticks between forced acceptor re-scans of an energy network (~3s) */
    private static final int REFRESH_INTERVAL = 60;

    /**
     * Called from EnergyNetwork.tick(). The network's acceptor set (possibleAcceptors) is built
     * only by refresh(), cables never tick (canUpdate() is false), and after a chunk unload/
     * reload nothing reliably rebuilds it for acceptors that load on a different schedule than
     * the cable (e.g. an AE controller across a chunk boundary) - so a working cable link goes
     * dead until a neighbouring block is changed. This makes the network re-scan its acceptors
     * periodically so the link self-heals within a few seconds instead of staying broken.
     */
    public static boolean shouldRefreshNetwork(Object network) {
        try {
            int[] c = (int[]) energyTicks.get(network);
            if (c == null) {
                c = new int[] { 0 };
                energyTicks.put(network, c);
            }
            if (++c[0] >= REFRESH_INTERVAL) {
                c[0] = 0;
                return true;
            }
        } catch (Throwable t) {
            // never let the heal counter break the network tick
        }
        return false;
    }

    // ---------------------------------------------- BuildCraft power API bridge

    private static Boolean bcPower;

    /**
     * Replaces the MekanismHooks.BuildCraftLoaded gate on Mekanism's power-OUTPUT paths (the
     * energy cube's direct face output and the Universal Cable network). Stock Mekanism only
     * pushes MJ into a BuildCraft IPowerReceptor when the BuildCraft *mod* is installed, but
     * Applied Energistics implements IPowerReceptor using the bundled BuildCraft power *API*
     * with no BuildCraft mod present - so in a pack with AE and no BuildCraft (Voltz), Mekanism
     * silently refuses to power AE. This returns true whenever the BC power API classes are on
     * the classpath, which is the real precondition for using them safely.
     */
    public static boolean bcPowerAvailable() {
        if (bcPower == null) {
            boolean ok;
            try {
                Class.forName("buildcraft.api.power.IPowerReceptor");
                Class.forName("buildcraft.api.power.IPowerProvider");
                ok = true;
            } catch (Throwable t) {
                ok = false;
            }
            bcPower = Boolean.valueOf(ok);
            System.out.println(TAG + "BuildCraft power API "
                    + (ok ? "present - Mekanism will power IPowerReceptor tiles (e.g. AE)" : "absent"));
        }
        return bcPower.booleanValue();
    }

    // ------------------------------------------------------ Electric Chest item

    /** InventoryElectricChest -> the slot and stack it was opened from */
    private static final Map bindings = Collections.synchronizedMap(new WeakHashMap());

    private static class Binding {
        int slot;
        WeakReference player;
        WeakReference stack;
    }

    /**
     * Called from the InventoryElectricChest constructor, right after super().
     *
     * Stock getItemStack() returns the player's CURRENT held item every time the inventory
     * reads or saves. Swap the chest out of the held slot while its GUI is open and the
     * contents are written into whatever replaced it - another sustained-inventory item
     * keeps a full copy while the chest keeps its own. Binding to the opening slot and
     * stack closes that.
     */
    public static void bindChest(Object inventory, Object player) {
        try {
            Object inv = field(player, "field_71071_by");                     // EntityPlayer.inventory
            int slot = ((Integer) field(inv, "field_70461_c")).intValue();     // InventoryPlayer.currentItem
            Object stack = call(inv, "func_70301_a", new Object[] { Integer.valueOf(slot) });
            if (!isElectricChest(stack)) {
                return;
            }
            Binding b = new Binding();
            b.slot = slot;
            b.player = new WeakReference(player);
            b.stack = new WeakReference(stack);
            bindings.put(inventory, b);
        } catch (Throwable t) {
            warn("bindChest", t);
        }
    }

    /**
     * Replaces InventoryElectricChest.getItemStack(). The stack the GUI was opened from, or
     * null once that exact stack is no longer in its slot. read, write, openChest and
     * closeChest are patched to do nothing on null.
     */
    public static Object chestStack(Object inventory) {
        Binding b = (Binding) bindings.get(inventory);
        if (b == null) {
            return null;
        }
        Object player = b.player.get();
        Object stack = b.stack.get();
        if (player == null || stack == null) {
            return null;
        }
        try {
            Object inv = field(player, "field_71071_by");
            Object now = call(inv, "func_70301_a", new Object[] { Integer.valueOf(b.slot) });
            return now == stack ? stack : null;
        } catch (Throwable t) {
            warn("chestStack", t);
            return null;
        }
    }

    /** ContainerElectricChest.canInteractWith in item mode: close the GUI once unbound. */
    public static boolean chestOpen(Object inventory) {
        return chestStack(inventory) != null;
    }

    /**
     * SlotElectricChest.isItemValid and TileEntityElectricChest.isItemValidForSlot.
     *
     * Electric Chests nest: a chest item inside a chest item carries its whole inventory
     * in NBT, and so on down. The resulting item and chunk NBT grows until it no longer
     * fits in a packet or a region sector. Slots belonging to the player's own inventory
     * are left alone so players can still move their chests around.
     */
    public static boolean allowedInChest(Object stack, Object slotInventory) {
        try {
            if (stack == null) {
                return true;
            }
            if (slotInventory != null) {
                if (playerInventory == null) {
                    playerInventory = Class.forName("net.minecraft.entity.player.InventoryPlayer");
                }
                if (playerInventory.isInstance(slotInventory)) {
                    return true;
                }
            }
            return !isElectricChest(stack);
        } catch (Throwable t) {
            warn("allowedInChest", t);
            return true;
        }
    }

    // ---------------------------------------------------- Electric Chest block

    /**
     * Replaces world.getBlockTileEntity(x, y, z) in PacketElectricChest.read for the
     * SERVER_OPEN, PASSWORD and LOCK block paths.
     *
     * Stock trusts the coordinates completely: any client can open, re-password or unlock
     * any Electric Chest in the world by naming its position, and naming an unloaded one
     * loads (or generates) the chunk. Returns the chest only if it is loaded, really an
     * Electric Chest, and within reach; otherwise null, and the patched packet stops.
     */
    public static Object chestAt(Object world, int x, int y, int z, Object player) {
        try {
            Object[] xyz = { Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) };
            if (!((Boolean) call(world, "func_72899_e", xyz)).booleanValue()) {      // World.blockExists
                refused(player, "Electric Chest packet for unloaded " + x + "," + y + "," + z);
                return null;
            }
            Object te = call(world, "func_72796_p", xyz);                              // World.getBlockTileEntity
            if (electricChestTile == null) {
                electricChestTile = Class.forName("mekanism.common.TileEntityElectricChest");
            }
            if (te == null || !electricChestTile.isInstance(te)) {
                refused(player, "Electric Chest packet for " + x + "," + y + "," + z + " (no chest there)");
                return null;
            }
            if (distanceSq(player, x + 0.5d, y + 0.5d, z + 0.5d) > REACH_SQ) {
                refused(player, "Electric Chest packet for " + x + "," + y + "," + z + " (out of reach)");
                return null;
            }
            return te;
        } catch (Throwable t) {
            warn("chestAt", t);
            return null;
        }
    }

    // ------------------------------------------------------------ machine GUIs

    /**
     * Replaces TileEntityContainerBlock.isUseableByPlayer, which returns true
     * unconditionally. Mirrors vanilla TileEntityChest: the tile must still be the one at
     * its own position, and the player within reach. Without it a machine can be broken
     * or wrenched with its GUI still open and live.
     */
    public static boolean tileUsable(Object tile, Object player) {
        try {
            Object world = field(tile, "field_70331_k");                  // TileEntity.worldObj
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

    // ------------------------------------------------------------------ Robit

    /**
     * Replaces world.getEntityByID(x) in CommonProxy.getServerGui for the Robit GUIs.
     *
     * The id comes straight from a PacketRobit sent by the client, so stock opens the
     * inventory of any Robit anywhere in the world. Returns the Robit only if it is one,
     * alive and within reach; on null the patched getServerGui returns no container.
     */
    public static Object robitFor(Object world, int id, Object player) {
        try {
            Object e = call(world, "func_73045_a", new Object[] { Integer.valueOf(id) });   // World.getEntityByID
            if (robitClass == null) {
                robitClass = Class.forName("mekanism.common.EntityRobit");
            }
            if (e == null || !robitClass.isInstance(e)) {
                refused(player, "Robit GUI request for entity " + id + " (not a Robit)");
                return null;
            }
            if (!robitUsable(e, player)) {
                refused(player, "Robit GUI request for entity " + id + " (out of reach)");
                return null;
            }
            return e;
        } catch (Throwable t) {
            warn("robitFor", t);
            return null;
        }
    }

    /**
     * Replaces canInteractWith in the Robit main, inventory and smelting containers, which
     * return true unconditionally - a Robit picked up or killed with its GUI open stays
     * editable.
     */
    public static boolean robitUsable(Object robit, Object player) {
        try {
            if (robit == null || ((Boolean) field(robit, "field_70128_L")).booleanValue()) {   // Entity.isDead
                return false;
            }
            double x = ((Double) field(robit, "field_70165_t")).doubleValue();
            double y = ((Double) field(robit, "field_70163_u")).doubleValue();
            double z = ((Double) field(robit, "field_70161_v")).doubleValue();
            return distanceSq(player, x, y, z) <= REACH_SQ;
        } catch (Throwable t) {
            warn("robitUsable", t);
            return true;
        }
    }

    // ------------------------------------------------------------ Electric Pump

    /**
     * Inserted at the head of TileEntityElectricPump.suck's "this remembered node is not a
     * liquid source" path, and skips the node entirely when it returns false.
     *
     * Stock treats a node that is not a source block *right now* as exhausted, and immediately
     * either spreads to one of its neighbours - adding that to recurringNodes, so the pump
     * walks outward through the pool - or drops it into cleaningNodes, where clean() then
     * deletes the flowing water that was about to reform it. Either way the pump stops being a
     * pump sitting on one source block and starts eating everything within 80 blocks.
     *
     * The window is tiny in normal running, because suck() only fires every 20 ticks and an
     * infinite source reforms in a tick or two. A chunk reload lands squarely in it: the pump
     * resumes on the next world-time boundary with no relation to when it last took a block,
     * and water physics has not settled yet. That is why this shows up on unload/load and
     * almost never otherwise.
     *
     * Rather than guess from timing, this asks the question vanilla itself asks: a block with
     * two or more horizontally adjacent sources of the same liquid is going to become a source
     * again. A node like that is an infinite source the pump should keep drawing from forever,
     * so the node is left alone and stock's handling never runs. A node with fewer than two is
     * genuinely being drained, and the pump spreads exactly as it always did.
     */
    public static boolean pumpNodeReady(Object pump, Object node) {
        try {
            Object world = field(pump, "field_70331_k");                   // TileEntity.worldObj
            if (world == null) {
                return true;
            }
            int x = ((Integer) field(node, "xCoord")).intValue();
            int y = ((Integer) field(node, "yCoord")).intValue();
            int z = ((Integer) field(node, "zCoord")).intValue();
            int sources = 0;
            for (int i = 0; i < 4; i++) {
                int nx = x + (i == 0 ? 1 : i == 1 ? -1 : 0);
                int nz = z + (i == 2 ? 1 : i == 3 ? -1 : 0);
                if (isLiquidSource(world, nx, y, nz)) {
                    sources++;
                }
            }
            return sources < 2;
        } catch (Throwable t) {
            warn("pumpNodeReady", t);
            return true;
        }
    }

    /** Mekanism's own notion of a source block: the liquid's still form, metadata 0. */
    private static boolean isLiquidSource(Object world, int x, int y, int z) {
        try {
            if (mekUtils == null) {
                mekUtils = Class.forName("mekanism.common.MekanismUtils");
            }
            if (isLiquidMethod == null) {
                Method[] ms = mekUtils.getDeclaredMethods();
                for (int i = 0; i < ms.length; i++) {
                    if (ms[i].getName().equals("isLiquid") && ms[i].getParameterTypes().length == 4) {
                        isLiquidMethod = ms[i];
                        isLiquidMethod.setAccessible(true);
                        break;
                    }
                }
            }
            if (isLiquidMethod == null) {
                return false;
            }
            return ((Boolean) isLiquidMethod.invoke(null, new Object[] { world,
                    Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) })).booleanValue();
        } catch (Throwable t) {
            warn("isLiquidSource", t);
            return false;
        }
    }

    private static Class mekUtils;
    private static Method isLiquidMethod;

    // ------------------------------------------------------------- reflection

    private static boolean isElectricChest(Object stack) throws Exception {
        if (stack == null) {
            return false;
        }
        Object item = call(stack, "func_77973_b", new Object[0]);         // ItemStack.getItem
        if (electricChestItem == null) {
            electricChestItem = Class.forName("mekanism.common.IElectricChest");
        }
        if (item == null || !electricChestItem.isInstance(item)) {
            return false;
        }
        return ((Boolean) call(item, "isElectricChest", new Object[] { stack })).booleanValue();
    }

    private static double distanceSq(Object entity, double x, double y, double z) throws Exception {
        return ((Double) call(entity, "func_70092_e",                        // Entity.getDistanceSq
                new Object[] { Double.valueOf(x), Double.valueOf(y), Double.valueOf(z) })).doubleValue();
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

    private static void warn(String where, Throwable t) {
        if (warned.put(where, Boolean.TRUE) == null) {
            System.out.println(TAG + "Mekanism fix " + where + " failed, falling back: " + t);
        }
    }
}
