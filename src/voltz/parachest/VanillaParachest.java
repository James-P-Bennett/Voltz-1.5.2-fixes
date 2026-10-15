package voltz.parachest;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Server side hooks that keep Galacticraft Parachest blocks out of the world, because a Parachest
 * next to a vanilla chest crashes every client in range.
 *
 * The crash. GCCoreBlockParachest extends the vanilla BlockChest. A vanilla chest pairs with any
 * neighbour whose block is a BlockChest of the same kind (TileEntityChest.func_94044_a), so it
 * pairs with a Parachest and casts the Parachest's tile entity to TileEntityChest. The cast throws
 * ClassCastException in the chest's tick ("Ticking tile entity") on the client of every player
 * who has that chunk loaded, again on every login, and on the server too unless it is guarded.
 *
 * The hooks (injected by ParachestTransformer):
 *  * place:      a landing parachest entity puts its cargo in vanilla chests (placeChest).
 *  * convert:    an existing Parachest block turns itself into vanilla chests on its first server
 *                tick, so worlds that already hold one are cleaned up (updateEntity).
 *  * canPlaceAt: a Parachest item cannot be placed beside any chest (canPlaceBlockAt), so other
 *                players never receive a Parachest next to a chest even for the one tick before
 *                convert runs.
 *
 * Placing cargo follows the vanilla chest rules: a chest goes where a player could put one. It
 * joins a lone single chest beside it into a double chest, but never makes a three wide chest and
 * never sits beside a non vanilla chest block. Cargo past one chest goes into a second chest beside
 * the first (its own double chest) or into further chests above; anything left drops as items.
 *
 * Minecraft is touched only by reflection on SRG names, which is what the classes carry at runtime
 * on both plain Forge and MCPC+. Each hook returns "not handled" (stock behaviour runs) only when a
 * lookup fails before anything in the world has changed. Once a block has changed, everything not
 * stored in a chest is dropped, so cargo is never lost or doubled.
 */
public class VanillaParachest {

    private static final String TAG = "[VoltzVanillaParachest] ";
    private static final String PARACHEST_BLOCK = "micdoodle8.mods.galacticraft.core.blocks.GCCoreBlockParachest";
    private static final int CHEST = 54;
    private static final int TRAPPED_CHEST = 146;
    private static final int SLOTS = 27;
    private static final int SEARCH_UP = 100;
    private static final int MAX_CHESTS = 6;
    private static final int[][] SIDES = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

    // ------------------------------------------------------------------------------- hooks

    /** Head of GCCoreEntityParaChest.placeChest(x, y, z). true = handled, the entity is removed. */
    public static boolean place(Object para, int x, int y, int z) {
        World w;
        Object[] cargo;
        try {
            w = new World(field(para, "field_70170_p"));                 // Entity.worldObj
            Object c = field(para, "cargo");
            cargo = c == null ? new Object[0] : (Object[]) c;
        } catch (Throwable t) {
            System.out.println(TAG + "lookup failed, landing a stock parachest: " + t);
            return false;
        }
        store(w, para, cargo, x, y, z);
        return true;
    }

    /** Head of GCCoreTileEntityParachest.updateEntity(). true = converted, skip the stock tick. */
    public static boolean convert(Object tile) {
        World w;
        int x, y, z;
        Object[] copy;
        try {
            Object world = field(tile, "field_70331_k");                  // TileEntity.worldObj
            if (world == null || ((Boolean) field(world, "field_72995_K")).booleanValue()) {
                return false;                                             // client side: leave it
            }
            w = new World(world);
            x = ((Integer) field(tile, "field_70329_l")).intValue();
            y = ((Integer) field(tile, "field_70330_m")).intValue();
            z = ((Integer) field(tile, "field_70327_n")).intValue();
            Object[] contents = (Object[]) field(tile, "chestContents");
            copy = contents.clone();
            // empty the Parachest first: its breakBlock drops whatever it still holds
            setField(tile, "chestContents", Array.newInstance(contents.getClass().getComponentType(), contents.length));
        } catch (Throwable t) {
            System.out.println(TAG + "lookup failed, leaving a parachest in place: " + t);
            return false;
        }
        try {
            w.set(x, y, z, 0);
        } catch (Throwable t) {
            System.out.println(TAG + "could not remove a parachest at " + x + "," + y + "," + z + ", leaving it: " + t);
            try {
                setField(tile, "chestContents", copy);                     // give it its items back
            } catch (Throwable t2) {
                store(w, tile, copy, x, y, z);                             // last resort: never lose them
                return true;
            }
            return false;
        }
        store(w, tile, copy, x, y, z);
        System.out.println(TAG + "converted a parachest at " + x + "," + y + "," + z + " to a vanilla chest");
        return true;
    }

    /** Head of GCCoreBlockParachest.canPlaceBlockAt(world, x, y, z). false = refuse placement. */
    public static boolean canPlaceAt(Object world, int x, int y, int z) {
        try {
            World w = new World(world);
            for (int[] s : SIDES) {
                if (w.isAnyChest(w.id(x + s[0], y, z + s[1]))) return false;
            }
        } catch (Throwable t) {
            System.out.println(TAG + "placement check failed, allowing: " + t);
        }
        return true;
    }

    // ----------------------------------------------------------------------- placing cargo

    /** Puts cargo into vanilla chests starting at (x,y,z) and drops whatever does not fit. */
    private static void store(World w, Object origin, Object[] cargo, int x, int y, int z) {
        int[] next = { 0 };   // first cargo index not yet stored, advanced per stack
        try {
            int chests = 0;
            int from = y;
            while ((chests == 0 || remaining(cargo, next[0])) && chests < MAX_CHESTS) {
                int[] a = findSpot(w, x, from, z);
                if (a == null) break;
                w.set(a[0], a[1], a[2], CHEST);
                chests++;
                fill(w.tile(a[0], a[1], a[2]), cargo, next);
                boolean joined = chestNeighbours(w, a[0], a[1], a[2], Integer.MIN_VALUE, 0) > 0;
                if (!joined && remaining(cargo, next[0]) && chests < MAX_CHESTS) {
                    for (int[] s : SIDES) {
                        int bx = a[0] + s[0], bz = a[2] + s[1];
                        if (w.id(bx, a[1], bz) != 0 || chestNeighbours(w, bx, a[1], bz, a[0], a[2]) != 0) continue;
                        w.set(bx, a[1], bz, CHEST);
                        chests++;
                        fill(w.tile(bx, a[1], bz), cargo, next);
                        break;
                    }
                }
                from = a[1] + 1;
            }
        } catch (Throwable t) {
            System.out.println(TAG + "chest placement failed, dropping the rest of the cargo: " + t);
        }
        drop(w, origin, cargo, next[0], x, y, z);
    }

    /** First air block from (x,y,z) upward where a vanilla chest may go, or null. */
    private static int[] findSpot(World w, int x, int y, int z) throws Exception {
        for (int yy = y; yy <= Math.min(y + SEARCH_UP, 255); yy++) {
            if (w.id(x, yy, z) != 0) continue;
            int n = chestNeighbours(w, x, yy, z, Integer.MIN_VALUE, 0);
            if (n == 0) return new int[] { x, yy, z };
            if (n == 1 && singleNeighbour(w, x, yy, z)) return new int[] { x, yy, z };
        }
        return null;
    }

    /**
     * Number of vanilla chests beside (x,y,z), ignoring the column at (ignoreX, ignoreZ), or 99 if
     * any neighbour is a non vanilla chest block (a vanilla chest next to one would crash).
     */
    private static int chestNeighbours(World w, int x, int y, int z, int ignoreX, int ignoreZ) throws Exception {
        int n = 0;
        for (int[] s : SIDES) {
            int nx = x + s[0], nz = z + s[1];
            if (nx == ignoreX && nz == ignoreZ) continue;
            int id = w.id(nx, y, nz);
            if (id == CHEST) n++;
            else if (w.isForeignChest(id)) return 99;
        }
        return n;
    }

    /** The one vanilla chest beside (x,y,z) is single, so joining it makes an ordinary double. */
    private static boolean singleNeighbour(World w, int x, int y, int z) throws Exception {
        for (int[] s : SIDES) {
            int nx = x + s[0], nz = z + s[1];
            if (w.id(nx, y, nz) == CHEST) return chestNeighbours(w, nx, y, nz, x, z) == 0;
        }
        return false;
    }

    private static boolean remaining(Object[] cargo, int from) {
        for (int i = from; i < cargo.length; i++) if (cargo[i] != null) return true;
        return false;
    }

    /**
     * Puts cargo[next[0]..] into the chest's 27 slots, advancing next[0] past each stack as soon
     * as it is stored, so a failure part way never drops a stack that is already in the chest.
     */
    private static void fill(Object chest, Object[] cargo, int[] next) throws Exception {
        if (chest == null) return;
        Method set = method(chest.getClass(), "func_70299_a", 2);       // IInventory.setInventorySlotContents
        int slot = 0;
        while (next[0] < cargo.length && slot < SLOTS) {
            if (cargo[next[0]] != null) set.invoke(chest, slot++, cargo[next[0]]);
            next[0]++;
        }
    }

    /** Drops cargo[from..] as item entities at (x,y,z). */
    private static void drop(World w, Object origin, Object[] cargo, int from, int x, int y, int z) {
        if (!remaining(cargo, from)) return;
        try {
            Class<?> itemCls = mcClass(origin, "net.minecraft.entity.item.EntityItem", "rh");
            Constructor<?> make = null;
            for (Constructor<?> c : itemCls.getConstructors()) {
                if (c.getParameterTypes().length == 5) { make = c; break; }
            }
            Method spawn = method(w.world.getClass(), "func_72838_d", 1);  // World.spawnEntityInWorld
            for (int i = from; i < cargo.length; i++) {
                if (cargo[i] == null) continue;
                spawn.invoke(w.world, make.newInstance(w.world, x + 0.5d, y + 0.5d, z + 0.5d, cargo[i]));
            }
        } catch (Throwable t) {
            System.out.println(TAG + "could not drop leftover cargo: " + t);
        }
    }

    // ------------------------------------------------------------------------- reflection

    /** The handful of World and Block lookups the hooks need, resolved once per call. */
    private static final class World {
        final Object world;
        final Method getId, setBlock, getTile;
        final Object[] blocks;
        final Class<?> blockChest, parachest;

        World(Object world) throws Exception {
            this.world = world;
            getId = method(world.getClass(), "func_72798_a", 3);          // World.getBlockId
            setBlock = method(world.getClass(), "func_72832_d", 6);       // World.setBlock(x,y,z,id,meta,flags)
            getTile = method(world.getClass(), "func_72796_p", 3);        // World.getBlockTileEntity
            Class<?> block = mcClass(world, "net.minecraft.block.Block", "apa");
            Field list = block.getDeclaredField("field_71973_m");         // Block.blocksList
            list.setAccessible(true);
            blocks = (Object[]) list.get(null);
            blockChest = mcClass(world, "net.minecraft.block.BlockChest", "aln");
            Class<?> pc = null;
            try {
                pc = Class.forName(PARACHEST_BLOCK, false, world.getClass().getClassLoader());
            } catch (ClassNotFoundException e) {
                // Galacticraft absent: nothing to guard against
            }
            parachest = pc;
        }

        int id(int x, int y, int z) throws Exception {
            return ((Integer) getId.invoke(world, x, y, z)).intValue();
        }

        void set(int x, int y, int z, int id) throws Exception {
            setBlock.invoke(world, x, y, z, id, 0, 3);
        }

        Object tile(int x, int y, int z) throws Exception {
            return getTile.invoke(world, x, y, z);
        }

        Object block(int id) {
            return id > 0 && id < blocks.length ? blocks[id] : null;
        }

        /** Any chest block at all: vanilla, trapped, Parachest or another mod's BlockChest. */
        boolean isAnyChest(int id) {
            Object b = block(id);
            return b != null && (blockChest.isInstance(b) || (parachest != null && parachest.isInstance(b)));
        }

        /** A chest block that is not vanilla: a vanilla chest beside it would crash. */
        boolean isForeignChest(int id) {
            return id != CHEST && id != TRAPPED_CHEST && isAnyChest(id);
        }
    }

    private static Class<?> mcClass(Object near, String deobf, String obf) throws ClassNotFoundException {
        ClassLoader cl = near.getClass().getClassLoader();
        try {
            return Class.forName(deobf, false, cl);
        } catch (ClassNotFoundException e) {
            return Class.forName(obf, false, cl);
        }
    }

    private static Field findField(Object o, String name) throws NoSuchFieldException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object field(Object o, String name) throws Exception {
        return findField(o, name).get(o);
    }

    private static void setField(Object o, String name, Object value) throws Exception {
        findField(o, name).set(o, value);
    }

    private static Method method(Class<?> cls, String name, int args) throws Exception {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == args) {
                    m.setAccessible(true);
                    return m;
                }
            }
            for (Class<?> i : c.getInterfaces()) {
                for (Method m : i.getMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == args) return m;
                }
            }
        }
        throw new NoSuchMethodException(name + "/" + args);
    }
}
