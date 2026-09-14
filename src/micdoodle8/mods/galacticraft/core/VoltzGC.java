package micdoodle8.mods.galacticraft.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hooks injected by Voltz-1.5.2-fixes into the Galacticraft coremod jar.
 *
 * Galacticraft references Minecraft by its obfuscated names in bytecode; FML deobfuscates
 * those references to SRG names (func_/field_) at class load, exactly as it does for the
 * mod's own code and for the injected calls that reach this class. So Minecraft and GC
 * objects arrive here as Object and are touched reflectively by their SRG names, which is
 * what the classes actually carry at runtime on both plain Forge and MCPC+.
 *
 * Every hook fails open: if a lookup breaks, the stock behaviour runs and a throttled line
 * is logged, rather than breaking the mod.
 */
public class VoltzGC {

    private static final String TAG = "[VoltzFixes] ";
    private static final String WORLDUTIL = "micdoodle8.mods.galacticraft.core.util.WorldUtil";

    /** reach for opening or driving a machine, squared (8 blocks). Matches GC's own tiles. */
    private static final double REACH_SQ = 64.0d;

    // ----------------------------------------------------------- packet 2: teleport

    /**
     * Replaces both WorldUtil.transferEntityToDimension(entity, dim, world) calls in packet 2.
     * The stock handler resolves any dimension name a client sends and teleports there, so a
     * client can reach any planet or any player's space station for free. The destination is
     * now required to be one the player may actually reach - WorldUtil's own reachable-dimension
     * map, which already honours station permissions - before the transfer runs. Stations stay
     * open to anyone the map lists; only unreachable destinations are refused.
     */
    public static void transferToDimension(Object entity, int dim, Object world) {
        try {
            Class<?> wu = Class.forName(WORLDUTIL);
            boolean allowed = false;
            try {
                Object all = findStatic(wu, "getArrayOfPossibleDimensions", 0).invoke(null);
                Object map = findStatic(wu, "getArrayOfPossibleDimensions", 2).invoke(null, all, entity);
                if (map instanceof Map) {
                    allowed = ((Map) map).containsValue(Integer.valueOf(dim));
                }
            } catch (Throwable t) {
                allowed = true;   // fail open: never strand a legitimate launch
                System.out.println(TAG + "dimension whitelist check failed, allowing: " + t);
            }
            if (!allowed) {
                refuse(entity, "teleport to dimension " + dim + " (not reachable)");
                return;
            }
            findStatic(wu, "transferEntityToDimension", 3).invoke(null, entity, Integer.valueOf(dim), world);
        } catch (Throwable t) {
            System.out.println(TAG + "transferToDimension failed: " + t);
        }
    }

    // --------------------------------------------------- packet 14: entity hijack

    /**
     * Guards the controllable-entity update. The stock handler finds a GCCoreEntityControllable
     * by the id in the packet and moves it, with no check that the sender is riding it, so a
     * client can drag anyone's rocket or buggy around by id. Returns the found entity only when
     * the player is actually riding it, otherwise null, which makes the handler skip the move.
     */
    public static Object entityIfRider(Object player, Object found) {
        if (found == null) {
            return null;
        }
        try {
            if (field(player, "field_70154_o") == found) {   // Entity.ridingEntity
                return found;
            }
        } catch (Throwable t) {
            System.out.println(TAG + "rider check failed, refused: " + t);
            return null;
        }
        refuse(player, "controllable-entity update (not riding it)");
        return null;
    }

    // --------------------------------------------------- packet 15: space station

    /**
     * Replaces the packet-15 branch. The stock branch binds a new space-station dimension and
     * then calls recipe.matches(player, true) - which consumes the ingredients - but throws the
     * boolean result away, so a player gets a station whether or not they had the materials. Now
     * the recipe is checked without consuming first; only when it matches are the ingredients
     * taken and the station bound. The "already has a station" guard is preserved.
     */
    public static void createSpaceStation(Object player, Object[] data) {
        try {
            int existing = ((Integer) field(player, "spaceStationDimensionID")).intValue();
            if (existing != -1 && existing != 0) {
                return;
            }
            int recipeId = ((Integer) data[0]).intValue();
            Class<?> wu = Class.forName(WORLDUTIL);
            Object recipe = findStatic(wu, "getSpaceStationRecipe", 1).invoke(null, Integer.valueOf(recipeId));
            if (recipe == null) {
                return;
            }
            Method matches = findMethod(recipe.getClass(), "matches", 2);
            boolean has = ((Boolean) matches.invoke(recipe, player, Boolean.FALSE)).booleanValue();
            if (!has) {
                refuse(player, "space-station creation (missing materials)");
                return;
            }
            matches.invoke(recipe, player, Boolean.TRUE);                       // consume
            Object world = field(player, "field_70170_p");                     // Entity.worldObj
            findStatic(wu, "bindSpaceStationToNewDimension", 2).invoke(null, world, player);
        } catch (Throwable t) {
            System.out.println(TAG + "createSpaceStation failed: " + t);
        }
    }

    // --------------------------------------------- packets 11/17/22: client coords

    /**
     * Gate for a packet that acts on a tile at client-supplied coordinates (open the refinery
     * GUI, toggle a machine, read a parachest). The stock handler reaches the tile with
     * getBlockTileEntity at those coordinates, with no reach or chunk check, so it works from
     * any distance or dimension and forces the chunk to load. The action is dropped unless the
     * chunk is already loaded and the player is within reach.
     */
    public static boolean reachPacket(Object player, Object[] data) {
        try {
            int x = ((Integer) data[0]).intValue();
            int y = ((Integer) data[1]).intValue();
            int z = ((Integer) data[2]).intValue();
            Object world = field(player, "field_70170_p");                     // Entity.worldObj
            Method blockExists = null;
            for (Class<?> k = world.getClass(); k != null && blockExists == null; k = k.getSuperclass()) {
                try { blockExists = k.getDeclaredMethod("func_72899_e", int.class, int.class, int.class); }
                catch (NoSuchMethodException ignored) { }
            }
            if (blockExists != null) {
                blockExists.setAccessible(true);
                boolean loaded = ((Boolean) blockExists.invoke(world,
                        Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z))).booleanValue();
                if (!loaded) {
                    refuse(player, "tile packet at " + x + "," + y + "," + z + " (chunk not loaded)");
                    return false;
                }
            }
            double d = ((Double) findMethod(player.getClass(), "func_70092_e", 3).invoke(player,
                    Double.valueOf(x + 0.5d), Double.valueOf(y + 0.5d), Double.valueOf(z + 0.5d))).doubleValue();
            if (d > REACH_SQ) {
                refuse(player, "tile packet at " + x + "," + y + "," + z + " (out of reach)");
                return false;
            }
            return true;
        } catch (Throwable t) {
            System.out.println(TAG + "reach check failed, allowing: " + t);
            return true;
        }
    }

    // --------------------------------------- UE router: tile-packet chunk guard

    /**
     * Gate for the bundled Universal Electricity PacketManager.onPacketData. A TILEENTITY packet
     * carries client coordinates and the router looks the tile up with getBlockTileEntity, which
     * loads (or generates) the chunk - far-coordinate spam forces chunk generation. True only
     * when the chunk is already loaded, so the lookup is skipped otherwise.
     */
    public static boolean chunkLoaded(Object world, int x, int y, int z) {
        try {
            Method blockExists = null;
            for (Class<?> k = world.getClass(); k != null && blockExists == null; k = k.getSuperclass()) {
                try { blockExists = k.getDeclaredMethod("func_72899_e", int.class, int.class, int.class); }
                catch (NoSuchMethodException ignored) { }
            }
            if (blockExists == null) {
                return true;
            }
            blockExists.setAccessible(true);
            return ((Boolean) blockExists.invoke(world,
                    Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z))).booleanValue();
        } catch (Throwable t) {
            return true;
        }
    }

    // ------------------------------------------------------- container GUIs

    /**
     * Replaces canInteractWith on the machine containers whose stock version returns true, so
     * the GUI outlives the block and works from any distance - extract items from a machine
     * you have already broken and it dupes when the block drops its inventory. Delegates to the
     * backing machine tile's own canInteractWith, which does the correct
     * getBlockTileEntity == this && getDistanceSq <= 64 test.
     */
    public static boolean containerReach(Object container, Object player) {
        try {
            Object slotList = field(container, "field_75151_b");               // Container.inventorySlots
            if (slotList instanceof List) {
                List slots = (List) slotList;
                for (int i = 0; i < slots.size(); i++) {
                    Object inv = slotField(slots.get(i));
                    if (inv == null) {
                        continue;
                    }
                    if (hasField(inv, "field_70331_k")) {                      // TileEntity.worldObj -> it is a tile
                        return ((Boolean) findMethod(inv.getClass(), "func_70300_a", 1)
                                .invoke(inv, player)).booleanValue();
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println(TAG + "container reach check failed, allowing: " + t);
        }
        return true;
    }

    private static Object slotField(Object slot) {
        try {
            return field(slot, "field_75224_c");                              // Slot.inventory
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------- NBT length clamp

    /** Clamps a chest length read from NBT before it sizes an array: a corrupt or hand-edited
     *  save can store a negative or absurd count, which crashes on load. */
    public static int clampInv(int count) {
        return count < 0 ? 0 : (count > 256 ? 256 : count);
    }

    // ------------------------------------------------------------- reflection

    private static boolean hasField(Object o, String name) {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try { k.getDeclaredField(name); return true; }
            catch (NoSuchFieldException ignored) { }
        }
        return false;
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "#" + name);
    }

    private static Method findMethod(Class<?> c, String name, int argc) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
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

    private static Method findStatic(Class<?> c, String name, int argc) throws NoSuchMethodException {
        return findMethod(c, name, argc);
    }

    // ------------------------------------------------------------- logging

    private static final Map lastLog = new HashMap();
    private static final long LOG_INTERVAL_MS = 10000L;

    private static void refuse(Object player, String what) {
        String name;
        try {
            name = player == null ? "?" : String.valueOf(field(player, "field_71092_bJ"));   // EntityPlayer.username
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
