package mekanism.generators.common;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Exploit fixes injected by Voltz-1.5.2-fixes into MekanismGenerators 5.5.6 (PatchMekGen).
 *
 * Minecraft and Mekanism objects arrive as Object and are read reflectively on SRG names,
 * because the vanilla classes cannot be compiled against.
 */
public class VoltzMekGen {

    private static final String TAG = "[VoltzFixes] ";

    private static final Map fieldCache = Collections.synchronizedMap(new HashMap());
    private static final Map methodCache = Collections.synchronizedMap(new HashMap());
    private static boolean warned = false;

    /** BlockGenerator metadata values; see BlockGenerator.GeneratorType. */
    private static final int META_ADVANCED_SOLAR = 5;
    private static final int META_WIND_TURBINE = 6;

    // ------------------------------------------------------------------ placing

    /**
     * Runs at the head of ItemBlockGenerator.placeBlockAt.
     *
     * Both tall generators claim more space than they occupy: the Advanced Solar Generator
     * turns the block above it and the 3x3 two above it into bounding blocks, the Wind Turbine
     * the four blocks above it. Stock checks the 3x3 but never the single block directly above
     * the Advanced Solar Generator, and onPlace() overwrites whatever is there with
     * world.setBlock - so placing one under a chest, a machine or anyone's build deleted that
     * block and everything inside it, with no drop and no warning. Every position the
     * multiblock is about to claim now has to be free first.
     */
    public static boolean canPlace(Object stack, Object world, int x, int y, int z) {
        try {
            int meta = ((Integer) call(stack, "func_77960_j", new Object[0])).intValue();   // getItemDamage
            if (meta == META_ADVANCED_SOLAR) {
                if (y + 2 > 255 || !free(world, x, y + 1, z)) {
                    return false;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (!free(world, x + dx, y + 2, z + dz)) {
                            return false;
                        }
                    }
                }
            } else if (meta == META_WIND_TURBINE) {
                if (y + 4 > 255) {
                    return false;
                }
                for (int dy = 1; dy <= 4; dy++) {
                    if (!free(world, x, y + dy, z)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            warn("canPlace", t);
            return true;
        }
    }

    private static boolean free(Object world, int x, int y, int z) throws Exception {
        if (y < 0 || y > 255) {
            return false;
        }
        return ((Integer) call(world, "func_72798_a",                              // World.getBlockId
                new Object[] { Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) })).intValue() == 0;
    }

    // ---------------------------------------------------------------- breaking

    /**
     * Replaces every world.setBlockToAir in the two onBreak() multiblock teardowns.
     *
     * Stock blanks all of the positions the multiblock claimed whether or not they still hold
     * its bounding blocks, so a teardown could delete a block that had since replaced one of
     * them. A position is only cleared now if it still holds a Mekanism bounding block or the
     * generator itself.
     */
    public static boolean clearBound(Object world, int x, int y, int z) {
        try {
            int id = ((Integer) call(world, "func_72798_a",
                    new Object[] { Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) })).intValue();
            if (id == 0 || (id != boundingBlockId() && id != generatorBlockId())) {
                return false;
            }
            return ((Boolean) call(world, "func_94571_i",                          // World.setBlockToAir
                    new Object[] { Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) })).booleanValue();
        } catch (Throwable t) {
            warn("clearBound", t);
            return false;
        }
    }

    private static int boundingBlockId() throws Exception {
        Object block = staticField("mekanism.common.Mekanism", "BoundingBlock");
        return block == null ? -1 : ((Integer) field(block, "field_71990_ca")).intValue();
    }

    private static int generatorBlockId() throws Exception {
        Object id = staticField("mekanism.generators.common.MekanismGenerators", "generatorID");
        return id == null ? -1 : ((Integer) id).intValue();
    }

    // -------------------------------------------------------------- metadata

    /**
     * Clamps the argument of BlockGenerator$GeneratorType.getFromMetadata, which indexes
     * values() directly. A generator block carrying a metadata above the last generator type -
     * from a hand-made item stack, or a world edited by something else - threw out of
     * createTileEntity while the chunk was loading, which fails the chunk every time it is
     * read.
     */
    public static int clampMeta(int meta) {
        try {
            int count = Class.forName("mekanism.generators.common.BlockGenerator$GeneratorType")
                    .getEnumConstants().length;
            return meta < 0 || meta >= count ? 0 : meta;
        } catch (Throwable t) {
            warn("clampMeta", t);
            return meta < 0 || meta > 6 ? 0 : meta;
        }
    }

    // ----------------------------------------------------------------- packets

    /**
     * Replaces world.getBlockTileEntity in PacketElectrolyticSeparatorParticle.read.
     *
     * The packet only spawns smoke, which is a no-op on a server, but its coordinates come off
     * the wire and getBlockTileEntity loads - or generates - whatever chunk they land in. It
     * now answers only for chunks that are already loaded.
     */
    public static Object loadedTile(Object world, int x, int y, int z) {
        try {
            Object[] xyz = { Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) };
            if (!((Boolean) call(world, "func_72899_e", xyz)).booleanValue()) {      // World.blockExists
                return null;
            }
            return call(world, "func_72796_p", xyz);                                // World.getBlockTileEntity
        } catch (Throwable t) {
            warn("loadedTile", t);
            return null;
        }
    }

    // ------------------------------------------------------------- reflection

    private static Object staticField(String className, String name) throws Exception {
        Field f = Class.forName(className).getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
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

    private static void warn(String where, Throwable t) {
        if (!warned) {
            warned = true;
            System.out.println(TAG + "MekanismGenerators " + where + " failed: " + t);
        }
    }
}
