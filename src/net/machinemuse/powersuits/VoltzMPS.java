package net.machinemuse.powersuits;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Blink Drive fix injected by Voltz-1.5.2-fixes into Modular Powersuits 0.7.0 (PatchMPS).
 *
 * MusePlayerUtils.teleportEntity moves the player to the raw ray hit point with no check
 * that they fit there. An entity hit, or a block-top hit beside a wall, puts the player's
 * hitbox inside blocks. NetServerHandler only rejects a move when the player STARTED it
 * clear of blocks, so from inside a block a modified client can then walk through walls.
 *
 * This computes the same destination, and if the player's box collides there, walks back
 * toward the player in quarter-block steps to the first spot that is clear. If there is
 * none, the player stays put. A destination that was already clear is used unchanged.
 *
 * Minecraft objects arrive as Object and are handled reflectively on SRG names.
 */
public class VoltzMPS {

    private static final String TAG = "[VoltzFixes] ";
    private static final double STEP = 0.25d;

    private static final Map fieldCache = new HashMap();
    private static final Map methodCache = new HashMap();
    private static boolean warned = false;

    /** Replaces MusePlayerUtils.teleportEntity(player, hit) in BlinkDriveModule.onRightClick. */
    public static void blink(Object player, Object hit) {
        if (player == null || hit == null) {
            return;
        }
        try {
            // EntityPlayerMP.playerNetServerHandler: absent on the client player
            Object handler = fieldOrNull(player, "field_71135_a");
            if (handler == null || ((Boolean) field(handler, "field_72576_c")).booleanValue()) {
                return;
            }
            Object vec = field(hit, "field_72307_f");                           // hitVec
            double tx = ((Double) field(vec, "field_72450_a")).doubleValue();
            double ty = ((Double) field(vec, "field_72448_b")).doubleValue();
            double tz = ((Double) field(vec, "field_72449_c")).doubleValue();

            // same side offsets as stock, for a block hit (entityHit is null)
            if (fieldOrNull(hit, "field_72308_g") == null) {
                switch (((Integer) field(hit, "field_72310_e")).intValue()) {     // sideHit
                    case 0: ty -= 2.0d; break;
                    case 2: tz -= 0.5d; break;
                    case 3: tz += 0.5d; break;
                    case 4: tx -= 0.5d; break;
                    case 5: tx += 0.5d; break;
                    default: break;
                }
            }

            double px = ((Double) field(player, "field_70165_t")).doubleValue();
            double py = ((Double) field(player, "field_70163_u")).doubleValue();
            double pz = ((Double) field(player, "field_70161_v")).doubleValue();
            double dx = tx - px, dy = ty - py, dz = tz - pz;
            int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / STEP));

            for (int i = 0; i < steps; i++) {
                double f = 1.0d - (double) i / (double) steps;
                if (clear(player, dx * f, dy * f, dz * f)) {
                    double[] to = { px + dx * f, py + dy * f, pz + dz * f };
                    if (!teleportAllowed(player, to)) {
                        return;
                    }
                    call(player, "func_70634_a", new Object[] {                     // setPositionAndUpdate
                            Double.valueOf(to[0]), Double.valueOf(to[1]), Double.valueOf(to[2]) });
                    return;
                }
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                System.out.println(TAG + "Blink Drive fix failed, not teleporting: " + t);
            }
        }
    }

    // --------------------------------------------------------------- Bukkit

    /**
     * Modular Powersuits moves blocks and players through raw Minecraft calls, so under MCPC+
     * none of it reaches Bukkit and no protection plugin ever sees it. These fire the event a
     * plugin would already be listening for, and honour a cancellation.
     *
     * Everything here is reflective and every entry point falls back to stock behaviour when
     * Bukkit is absent, so the same jar is correct on a plain Forge server.
     */
    private static Boolean hasBukkit;

    private static boolean bukkit() {
        if (hasBukkit == null) {
            try {
                Class.forName("org.bukkit.Bukkit");
                hasBukkit = Boolean.TRUE;
            } catch (Throwable t) {
                hasBukkit = Boolean.FALSE;
            }
        }
        return hasBukkit.booleanValue();
    }

    /** Fires the event and returns true when a plugin cancelled it. */
    private static boolean cancelled(Object event) throws Exception {
        Class eventType = Class.forName("org.bukkit.event.Event");
        Object manager = Class.forName("org.bukkit.Bukkit").getMethod("getPluginManager", new Class[0])
                .invoke(null, new Object[0]);
        manager.getClass().getMethod("callEvent", new Class[] { eventType })
                .invoke(manager, new Object[] { event });
        return ((Boolean) Class.forName("org.bukkit.event.Cancellable")
                .getMethod("isCancelled", new Class[0]).invoke(event, new Object[0])).booleanValue();
    }

    private static Object construct(String className, int args, Object[] values) throws Exception {
        java.lang.reflect.Constructor[] ctors = Class.forName(className).getConstructors();
        for (int i = 0; i < ctors.length; i++) {
            if (ctors[i].getParameterTypes().length == args) {
                return ctors[i].newInstance(values);
            }
        }
        throw new NoSuchMethodException(className + "/" + args);
    }

    // ---------------------------------------------------------- Lux Capacitor

    /**
     * Replaces world.setBlock in EntityLuxCapacitor.onImpact.
     *
     * Stock drops the block straight into the world with no player reference at all, so on
     * MCPC+ nothing fires and WorldGuard never sees it - and the capacitor flies flat, with no
     * gravity, for 400 ticks, so this lands blocks hundreds of blocks inside a claim. The
     * thrower is recorded on the entity, it was simply never used; now it becomes the player
     * on a BlockPlaceEvent, and a cancellation means no block.
     */
    public static boolean luxPlace(Object entity, Object world, int x, int y, int z,
                                   int id, int meta, int flag) {
        try {
            if (bukkit()) {
                Object thrower = throwerOf(entity);
                Object bukkitPlayer = thrower == null ? null : bukkitEntity(thrower);
                if (bukkitPlayer != null) {
                    Object bukkitWorld = call(world, "getWorld", new Object[0]);
                    Object block = call(bukkitWorld, "getBlockAt", new Object[] {
                            Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) });
                    Object state = call(block, "getState", new Object[0]);
                    Object inHand = call(bukkitPlayer, "getItemInHand", new Object[0]);
                    // placedAgainst: the capacitor sticks to a face, but the placed block is
                    // what a region check looks at, so it stands in for both
                    Object event = construct("org.bukkit.event.block.BlockPlaceEvent", 6,
                            new Object[] { block, state, block, inHand, bukkitPlayer, Boolean.TRUE });
                    if (cancelled(event)) {
                        return false;
                    }
                    Boolean canBuild = (Boolean) event.getClass()
                            .getMethod("canBuild", new Class[0]).invoke(event, new Object[0]);
                    if (!canBuild.booleanValue()) {
                        return false;
                    }
                }
            }
        } catch (Throwable t) {
            warn("Lux Capacitor place event", t);
        }
        try {
            return ((Boolean) call(world, "func_72832_d", new Object[] {           // setBlock
                    Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z),
                    Integer.valueOf(id), Integer.valueOf(meta), Integer.valueOf(flag) })).booleanValue();
        } catch (Throwable t) {
            warn("Lux Capacitor place", t);
            return false;
        }
    }

    /**
     * Replaces world.setBlockTileEntity on the line after, so a refused placement does not
     * leave a tile entity behind with no block under it.
     */
    public static void luxTile(Object world, int x, int y, int z, Object tile) {
        try {
            int here = ((Integer) call(world, "func_72798_a", new Object[] {        // getBlockId
                    Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) })).intValue();
            Object assigned = Class.forName("net.machinemuse.powersuits.block.BlockLuxCapacitor")
                    .getField("assignedBlockID").get(null);
            if (here != ((Integer) assigned).intValue()) {
                return;
            }
            call(world, "func_72837_a", new Object[] {                              // setBlockTileEntity
                    Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z), tile });
        } catch (Throwable t) {
            warn("Lux Capacitor tile", t);
        }
    }

    // ---------------------------------------------------------- Blade Launcher

    /** One pending verdict per thread, so the shear and the break agree without asking twice. */
    private static final ThreadLocal bladeVerdict = new ThreadLocal();

    /**
     * Replaces IShearable.isShearable in the block branch of EntitySpinningBlade.onImpact.
     *
     * The blade shears leaves and vines and then calls world.destroyBlock, which is not the
     * player-break path, so no BlockBreakEvent fires and a claim does not stop it. Both halves
     * are decided here, once, because the drops are spawned before the block is destroyed -
     * refusing only the destroy would hand out the shears for free.
     */
    public static boolean bladeMayShear(Object blade, Object target, Object item, Object world,
                                        int x, int y, int z) {
        boolean shearable;
        try {
            shearable = ((Boolean) call(target, "isShearable", new Object[] {
                    item, world, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) })).booleanValue();
        } catch (Throwable t) {
            warn("Blade Launcher isShearable", t);
            return false;
        }
        boolean allowed = breakAllowed(blade, world, x, y, z);
        bladeVerdict.set(Boolean.valueOf(allowed));
        return shearable && allowed;
    }

    /** Replaces world.destroyBlock on the line after the shear. */
    public static boolean bladeBreak(Object blade, Object world, int x, int y, int z, boolean drop) {
        Boolean pending = (Boolean) bladeVerdict.get();
        bladeVerdict.remove();
        boolean allowed = pending != null ? pending.booleanValue() : breakAllowed(blade, world, x, y, z);
        if (!allowed) {
            return false;
        }
        try {
            return ((Boolean) call(world, "func_94578_a", new Object[] {            // destroyBlock
                    Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z),
                    Boolean.valueOf(drop) })).booleanValue();
        } catch (Throwable t) {
            warn("Blade Launcher destroyBlock", t);
            return false;
        }
    }

    private static boolean breakAllowed(Object blade, Object world, int x, int y, int z) {
        if (!bukkit()) {
            return true;
        }
        try {
            Object shooter = fieldOrNull(blade, "shootingEntity");
            Object bukkitPlayer = shooter == null ? null : bukkitEntity(shooter);
            if (bukkitPlayer == null) {
                return true;
            }
            Object bukkitWorld = call(world, "getWorld", new Object[0]);
            Object block = call(bukkitWorld, "getBlockAt", new Object[] {
                    Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z) });
            Object event = construct("org.bukkit.event.block.BlockBreakEvent", 2,
                    new Object[] { block, bukkitPlayer });
            return !cancelled(event);
        } catch (Throwable t) {
            warn("Blade Launcher break event", t);
            return true;
        }
    }

    // ------------------------------------------------------------ Blink Drive

    /**
     * Gates the Blink Drive's move on a PlayerTeleportEvent, which is what every other
     * teleport a plugin can police goes through. Stock calls setPositionAndUpdate directly,
     * which is not CraftBukkit's teleport path, so region entry rules never see it. A plugin
     * that redirects the destination is honoured too.
     */
    private static boolean teleportAllowed(Object player, double[] to) {
        if (!bukkit()) {
            return true;
        }
        try {
            Object bukkitPlayer = bukkitEntity(player);
            if (bukkitPlayer == null) {
                return true;
            }
            Object bukkitWorld = call(bukkitPlayer, "getWorld", new Object[0]);
            Float yaw = (Float) field(player, "field_70177_z");                     // rotationYaw
            Float pitch = (Float) field(player, "field_70125_A");                   // rotationPitch
            Object from = call(bukkitPlayer, "getLocation", new Object[0]);
            Object dest = construct("org.bukkit.Location", 6, new Object[] { bukkitWorld,
                    Double.valueOf(to[0]), Double.valueOf(to[1]), Double.valueOf(to[2]), yaw, pitch });
            Object event = teleportEvent(bukkitPlayer, from, dest);
            if (cancelled(event)) {
                return false;
            }
            Object after = event.getClass().getMethod("getTo", new Class[0]).invoke(event, new Object[0]);
            if (after != null) {
                to[0] = ((Double) call(after, "getX", new Object[0])).doubleValue();
                to[1] = ((Double) call(after, "getY", new Object[0])).doubleValue();
                to[2] = ((Double) call(after, "getZ", new Object[0])).doubleValue();
            }
            return true;
        } catch (Throwable t) {
            warn("Blink Drive teleport event", t);
            return true;
        }
    }

    private static Object teleportEvent(Object player, Object from, Object to) throws Exception {
        try {
            Class cause = Class.forName("org.bukkit.event.player.PlayerTeleportEvent$TeleportCause");
            Object plugin = Enum.valueOf(cause, "PLUGIN");
            return construct("org.bukkit.event.player.PlayerTeleportEvent", 4,
                    new Object[] { player, from, to, plugin });
        } catch (Throwable t) {
            return construct("org.bukkit.event.player.PlayerTeleportEvent", 3,
                    new Object[] { player, from, to });
        }
    }

    // ------------------------------------------------------------- reflection

    /** EntityThrowable's thrower, by accessor then by field. */
    private static Object throwerOf(Object entity) {
        try {
            return call(entity, "func_85052_h", new Object[0]);                     // getThrower
        } catch (Throwable t) {
            try {
                return fieldOrNull(entity, "field_70192_c");                        // thrower
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    private static Object bukkitEntity(Object entity) {
        try {
            Object b = call(entity, "getBukkitEntity", new Object[0]);
            return Class.forName("org.bukkit.entity.Player").isInstance(b) ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void warn(String where, Throwable t) {
        if (!warned) {
            warned = true;
            System.out.println(TAG + where + " failed: " + t);
        }
    }

    /** true if the player's bounding box, moved by (dx, dy, dz), hits no collision box */
    private static boolean clear(Object player, double dx, double dy, double dz) throws Exception {
        Object box = field(player, "field_70121_D");                            // boundingBox
        Object moved = call(box, "func_72325_c", new Object[] {                  // getOffsetBoundingBox
                Double.valueOf(dx), Double.valueOf(dy), Double.valueOf(dz) });
        Object world = field(player, "field_70170_p");                          // worldObj
        List hits = (List) call(world, "func_72945_a", new Object[] { player, moved });  // getCollidingBoundingBoxes
        return hits.isEmpty();
    }

    private static Object fieldOrNull(Object o, String name) throws Exception {
        Field f = findField(o.getClass(), name);
        return f == null ? null : f.get(o);
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = findField(o.getClass(), name);
        if (f == null) {
            throw new NoSuchFieldException(o.getClass().getName() + "#" + name);
        }
        return f.get(o);
    }

    private static Field findField(Class c, String name) {
        String key = c.getName() + "#" + name;
        if (fieldCache.containsKey(key)) {
            return (Field) fieldCache.get(key);
        }
        Field f = null;
        for (Class k = c; k != null && f == null; k = k.getSuperclass()) {
            try {
                f = k.getDeclaredField(name);
                f.setAccessible(true);
            } catch (NoSuchFieldException ignored) {
            }
        }
        fieldCache.put(key, f);
        return f;
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
                // interface methods - Bukkit's Player.getItemInHand and friends - are not
                // declared anywhere on the class chain above
                Method[] ms = o.getClass().getMethods();
                for (int i = 0; i < ms.length && m == null; i++) {
                    if (ms[i].getName().equals(name) && ms[i].getParameterTypes().length == args.length) {
                        m = ms[i];
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

    /**
     * Replaces an `SomeEnum.values()[index]` whose index comes from NBT or block metadata.
     *
     * An out-of-range value throws an ArrayIndexOutOfBoundsException out of readFromNBT or
     * createTileEntity - that is, out of chunk loading - and it throws again every single time
     * that chunk is read, so one bad value makes the chunk permanently unloadable. Falling back
     * to the first constant loses that one setting and keeps the world.
     */
    public static Object enumAt(Object[] values, int index) {
        if (values == null || values.length == 0) {
            return null;
        }
        if (index < 0 || index >= values.length) {
            System.out.println(TAG + "out-of-range enum ordinal " + index + " of "
                    + values.length + ", using " + values[0]);
            return values[0];
        }
        return values[index];
    }
}
