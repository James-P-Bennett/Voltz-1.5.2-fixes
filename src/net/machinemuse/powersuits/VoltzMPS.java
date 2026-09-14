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
                    call(player, "func_70634_a", new Object[] {                     // setPositionAndUpdate
                            Double.valueOf(px + dx * f), Double.valueOf(py + dy * f), Double.valueOf(pz + dz * f) });
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
                throw new NoSuchMethodException(key);
            }
            m.setAccessible(true);
            methodCache.put(key, m);
        }
        return m.invoke(o, args);
    }
}
