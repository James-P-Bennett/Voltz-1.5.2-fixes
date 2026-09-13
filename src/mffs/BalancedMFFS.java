package mffs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * BalancedMFFS - zone flags and admin logging for MFFS interdiction matrices.
 *
 * Injected into the MFFS jar by PatchMFFS. Two jobs:
 *
 *  1. Zone flags. Every interdiction module acts through
 *     onDefend(IInterdictionMatrix, EntityLiving), and the base class's own body is
 *     `return false`. A guard at the head of each override returns false inside a denied
 *     zone, so the module behaves exactly like one that chose not to act. Admins can keep
 *     Anti-Personnel and Confiscate craftable and deny them around spawn and public areas
 *     instead of banning them outright.
 *
 *  2. Admin logging. Anti-Personnel strips a player's entire inventory into the matrix and
 *     then deals Integer.MAX_VALUE damage; Confiscate quietly moves filtered items out of
 *     player inventories. Neither leaves any trace, which makes them impossible to
 *     administrate. This announces a matrix once per chunk load and logs every kill and
 *     every confiscated stack as it happens.
 *
 * Minecraft objects arrive as Object and are read reflectively - the vanilla classes in
 * bin/minecraft.jar are obfuscated and cannot be compiled against. These are low-frequency
 * paths, so reflection costs nothing that matters.
 */
public class BalancedMFFS {

    private static final String FILE = "BalancedMFFS.txt";
    private static final long RECHECK_MS = 5000L;
    private static final String TAG = "[BalancedMFFS] ";

    private static List rules = new ArrayList();
    private static boolean logging = true;
    private static long lastStamp = -1L;
    private static long lastCheck = 0L;

    /** matrices already announced; a TileEntity is rebuilt on chunk load, so this is per load */
    private static final Map seenMatrices = Collections.synchronizedMap(new WeakHashMap());
    private static final Map fieldCache = new HashMap();

    private static class Rule {
        String flag;
        int dim;
        boolean whole;
        int x1, y1, z1, x2, y2, z2;
    }

    // ------------------------------------------------------------------ zones

    /** @return true if the module must not act on an entity at this position */
    public static boolean denied(String flag, int dim, double x, double y, double z) {
        reloadIfChanged();
        List snapshot = rules;
        for (int i = 0; i < snapshot.size(); i++) {
            Rule r = (Rule) snapshot.get(i);
            if (r.dim != dim) continue;
            if (!r.flag.equals("*") && !r.flag.equalsIgnoreCase(flag)) continue;
            if (r.whole) return true;
            if (x >= r.x1 && x <= r.x2 && y >= r.y1 && y <= r.y2 && z >= r.z1 && z <= r.z2) {
                return true;
            }
        }
        return false;
    }

    /** Called from ModularForceFieldSystem.preInit so the file exists at startup. */
    public static void init() {
        reloadIfChanged();
    }

    // ---------------------------------------------------------------- logging

    /**
     * Called every tick from TileEntityInterdictionMatrix. Announces the matrix once per
     * TileEntity instance - which is once per chunk load - and only if it carries a module
     * worth watching.
     */
    public static void matrixTick(Object tile) {
        if (!logging || tile == null) return;
        try {
            if (seenMatrices.put(tile, Boolean.TRUE) != null) return;
            String mods = moduleNames(tile);
            if (mods.indexOf("AntiPersonnel") < 0 && mods.indexOf("Confiscate") < 0) return;
            System.out.println(TAG + "matrix loaded at " + where(tile) + "  modules: " + mods);
        } catch (Throwable t) {
            // never let logging break the tick
        }
    }

    /** Called from ItemModuleAntiPersonnel immediately after the killing blow. */
    public static void logKill(Object entity, Object matrix) {
        if (!logging) return;
        try {
            System.out.println(TAG + "KILL  " + who(entity) + " at " + where(entity)
                    + "  by matrix " + where(matrix) + "  (inventory absorbed into matrix)");
        } catch (Throwable t) {
        }
    }

    /** Called from ItemModuleConfiscate for each stack as it is taken. */
    public static void logTaken(Object stack, Object entity, Object matrix) {
        if (!logging || stack == null) return;
        try {
            System.out.println(TAG + "CONFISCATE  " + who(entity) + " lost " + describe(stack)
                    + "  at " + where(entity) + "  to matrix " + where(matrix));
        } catch (Throwable t) {
        }
    }

    // ------------------------------------------------------------- reflection

    private static Field field(Class c, String name) {
        String key = c.getName() + "#" + name;
        Object hit = fieldCache.get(key);
        if (hit != null) return (Field) hit;
        for (Class k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                fieldCache.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Object get(Object o, String name) {
        if (o == null) return null;
        Field f = field(o.getClass(), name);
        if (f == null) return null;
        try {
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** player username, or the entity's class name for anything else */
    private static String who(Object entity) {
        Object name = get(entity, "field_71092_bJ");            // EntityPlayer.username
        if (name instanceof String) return (String) name;
        return entity == null ? "?" : entity.getClass().getSimpleName();
    }

    /** "dim0 123,64,-77" for either an entity or a tile entity */
    private static String where(Object o) {
        if (o == null) return "?";
        Object tx = get(o, "field_70329_l");                    // TileEntity.xCoord
        if (tx instanceof Integer) {
            return "dim" + dimOf(o) + " " + tx + "," + get(o, "field_70330_m")
                    + "," + get(o, "field_70327_n");
        }
        Object ex = get(o, "field_70165_t");                    // Entity.posX
        if (ex instanceof Double) {
            return "dim" + dimOf(o) + " " + fmt(ex) + "," + fmt(get(o, "field_70163_u"))
                    + "," + fmt(get(o, "field_70161_v"));
        }
        return o.getClass().getSimpleName();
    }

    private static String fmt(Object d) {
        if (!(d instanceof Double)) return "?";
        return Long.toString(Math.round(((Double) d).doubleValue()));
    }

    private static int dimOf(Object o) {
        Object world = get(o, "field_70170_p");                 // Entity.worldObj
        if (world == null) world = get(o, "field_70331_k");     // TileEntity.worldObj
        Object provider = get(world, "field_73011_w");
        Object dim = get(provider, "field_76574_g");
        return dim instanceof Integer ? ((Integer) dim).intValue() : 0;
    }

    /** "12x Iron Ingot" */
    private static String describe(Object stack) {
        String name;
        try {
            Method m = stack.getClass().getMethod("func_82833_r");   // ItemStack.getDisplayName
            name = String.valueOf(m.invoke(stack));
        } catch (Throwable t) {
            name = "item " + get(stack, "field_77993_c");
        }
        Object n = get(stack, "field_77994_a");                      // ItemStack.stackSize
        return (n == null ? "?" : n) + "x " + name;
    }

    private static String moduleNames(Object tile) {
        try {
            Method m = tile.getClass().getMethod("getModules", int[].class);
            Object res = m.invoke(tile, new Object[] { new int[0] });
            StringBuilder sb = new StringBuilder();
            if (res instanceof Set) {
                for (Object mod : (Set) res) {
                    if (mod == null) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(mod.getClass().getSimpleName().replace("ItemModule", ""));
                }
            }
            return sb.length() == 0 ? "none" : sb.toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ----------------------------------------------------------------- config

    private static synchronized void reloadIfChanged() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < RECHECK_MS) return;
        lastCheck = now;
        try {
            File dir = new File("config");
            File file = new File(dir, FILE);
            if (!file.exists()) {
                if (!dir.exists()) dir.mkdirs();
                writeTemplate(file);
            }
            long stamp = file.lastModified();
            if (stamp == lastStamp) return;
            lastStamp = stamp;
            parse(file);
            System.out.println(TAG + "loaded " + rules.size() + " rule(s), logging "
                    + (logging ? "on" : "off"));
        } catch (Throwable t) {
            System.out.println(TAG + "could not read " + FILE + ": " + t);
        }
    }

    private static void parse(File file) throws Exception {
        List found = new ArrayList();
        boolean log = true;
        BufferedReader in = new BufferedReader(new FileReader(file));
        try {
            String line;
            int lineNo = 0;
            while ((line = in.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                String[] p = line.split("\\s+");
                try {
                    if (p[0].equalsIgnoreCase("logging") && p.length >= 2) {
                        log = p[1].equalsIgnoreCase("on") || p[1].equalsIgnoreCase("true");
                        continue;
                    }
                    Rule r = new Rule();
                    if (p[0].equalsIgnoreCase("world") && p.length >= 3) {
                        r.whole = true;
                        r.dim = Integer.parseInt(p[1]);
                        r.flag = p[2];
                    } else if (p[0].equalsIgnoreCase("zone") && p.length >= 9) {
                        r.whole = false;
                        r.dim = Integer.parseInt(p[1]);
                        int ax = Integer.parseInt(p[2]), ay = Integer.parseInt(p[3]), az = Integer.parseInt(p[4]);
                        int bx = Integer.parseInt(p[5]), by = Integer.parseInt(p[6]), bz = Integer.parseInt(p[7]);
                        r.x1 = Math.min(ax, bx); r.x2 = Math.max(ax, bx);
                        r.y1 = Math.min(ay, by); r.y2 = Math.max(ay, by);
                        r.z1 = Math.min(az, bz); r.z2 = Math.max(az, bz);
                        r.flag = p[8];
                    } else {
                        System.out.println(TAG + "line " + lineNo + " ignored: " + line);
                        continue;
                    }
                    found.add(r);
                } catch (NumberFormatException nfe) {
                    System.out.println(TAG + "line " + lineNo + " has a bad number: " + line);
                }
            }
        } finally {
            in.close();
        }
        rules = found;
        logging = log;
    }

    private static void writeTemplate(File file) throws Exception {
        java.io.PrintWriter out = new java.io.PrintWriter(file);
        out.println("# BalancedMFFS - zone flags and admin logging for MFFS interdiction matrices");
        out.println("#");
        out.println("# Modules denied here do nothing to entities inside the area, so they can stay");
        out.println("# craftable instead of being banned outright.");
        out.println("#");
        out.println("# world <dim> <flag>");
        out.println("# zone  <dim> <x1> <y1> <z1> <x2> <y2> <z2> <flag>");
        out.println("#");
        out.println("# flags: mffs.antipersonnel  mffs.confiscate  mffs.antihostile");
        out.println("#        mffs.antifriendly   mffs.warn        *  (all of them)");
        out.println("#");
        out.println("# examples:");
        out.println("#   world 0 mffs.antipersonnel");
        out.println("#   zone  0 -200 0 -200 200 256 200 *");
        out.println("#");
        out.println("# Logging: a matrix carrying Anti-Personnel or Confiscate is announced once per");
        out.println("# chunk load, then every kill and every confiscated stack is logged.");
        out.println("logging on");
        out.println("#");
        out.println("# Edits are picked up within a few seconds. No restart needed.");
        out.close();
    }
}
