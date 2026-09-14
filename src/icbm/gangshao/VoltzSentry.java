package icbm.gangshao;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Exploit fixes injected by Voltz-1.5.2-fixes into ICBM Sentry 1.2.1 (PatchICBMSentry).
 *
 * Minecraft objects arrive as Object and are read reflectively on SRG names, because the
 * vanilla classes cannot be compiled against.
 */
public class VoltzSentry {

    private static final String TAG = "[VoltzFixes] ";
    private static final long LOG_INTERVAL_MS = 10000L;
    /** 8 blocks, the reach vanilla containers allow */
    private static final double REACH_SQ = 64.0d;

    private static final Map lastLog = new HashMap();
    private static boolean warned = false;

    /**
     * Replaces world.getPlayerEntityByName(name) in TileEntityTerminal's command branch. Stock
     * runs the command as whoever the packet names. This ignores the name and returns the
     * player who actually sent the packet, or null - and the command is dropped - when that
     * player is out of reach of the terminal.
     */
    public static Object commandSender(Object world, String claimedName, Object tile, Object player) {
        try {
            int x = ((Integer) field(tile, "field_70329_l")).intValue();         // TileEntity.xCoord
            int y = ((Integer) field(tile, "field_70330_m")).intValue();
            int z = ((Integer) field(tile, "field_70327_n")).intValue();
            double d = ((Double) findMethod(player.getClass(), "func_70092_e", 3).invoke(player,   // getDistanceSq
                    new Object[] { Double.valueOf(x + 0.5d), Double.valueOf(y + 0.5d), Double.valueOf(z + 0.5d) })).doubleValue();
            if (d <= REACH_SQ) {
                return player;
            }
            refused(player, "terminal command at " + x + "," + y + "," + z + " sent as " + claimedName
                    + " (" + Math.round(Math.sqrt(d)) + " blocks away)");
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                System.out.println(TAG + "terminal command check failed, refused: " + t);
            }
        }
        return null;
    }

    /** Inserted at the top of TPaoDaiBase.handlePacketData on the server. The packet is dropped. */
    public static void serverPacketRefused(Object tile, Object player, String what) {
        String where;
        try {
            where = field(tile, "field_70329_l") + "," + field(tile, "field_70330_m") + "," + field(tile, "field_70327_n");
        } catch (Throwable t) {
            where = "?";
        }
        refused(player, what + " at " + where + " (only the server sends that packet)");
    }

    /** 10 blocks: a player clicking a dummy block, plus the size of the machine */
    private static final double MULTIBLOCK_REACH_SQ = 100.0d;

    /** Prepended to TPaoTaiQi.onActivated(player): false returns false, unused. */
    public static boolean multiblockReach(Object tile, Object player) {
        try {
            double d = distanceSq(player, tile);
            if (d <= MULTIBLOCK_REACH_SQ) {
                return true;
            }
            refused(player, "use of the turret at " + where(tile) + " through a dummy block " + Math.round(Math.sqrt(d)) + " blocks away");
        } catch (Throwable t) {
            System.out.println(TAG + "multiblock reach check failed, refused: " + t);
        }
        return false;
    }

    /**
     * Prepended to TCiGuiPao.onDestroy(callingBlock). The railgun's own dummy sits right above
     * it; a dummy further than 2 blocks away was pointed at it by a packet.
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
            refused(null, "removal of the turret at " + where(tile) + " by an unrelated block at " + where(callingBlock));
        } catch (Throwable t) {
            System.out.println(TAG + "multiblock part check failed, refused: " + t);
        }
        return false;
    }

    /**
     * Body of TPaoTaiZhan.isUseableByPlayer, which was `return true`: the platform must still be
     * the tile at its position, and the player within 8 blocks - the vanilla container rule, so
     * the GUI closes when the platform breaks or the player walks off.
     */
    public static boolean usableByPlayer(Object tile, Object player) {
        try {
            Object world = field(tile, "field_70331_k");                                          // TileEntity.worldObj
            Object there = findMethod(world.getClass(), "func_72796_p", 3).invoke(world, new Object[] {   // getBlockTileEntity
                    Integer.valueOf(coord(tile, "field_70329_l")), Integer.valueOf(coord(tile, "field_70330_m")),
                    Integer.valueOf(coord(tile, "field_70327_n")) });
            return there == tile && distanceSq(player, tile) <= REACH_SQ;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                System.out.println(TAG + "platform reach check failed, closing: " + t);
            }
            return false;
        }
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

    // ------------------------------------------------------------- terminal

    private static final int CONSOLE_LINES = 100;

    /** Prepended to TileEntityTerminal.addToConsole: keeps the newest lines so the add stays in bounds. */
    public static void trimConsole(java.util.List lines) {
        if (lines != null && lines.size() >= CONSOLE_LINES) {
            lines.subList(0, lines.size() - CONSOLE_LINES + 1).clear();
        }
    }

    /**
     * Prepended to CommandUser and CommandAccess processCommand; true ends the command. Stock
     * checks only that the sender is an admin:
     *
     *  - users add NAME: re-adding someone already listed replaced their entry with USER, so an
     *    admin could demote the owner. Now refused as "User already exists."
     *  - users remove NAME: needs a higher level than NAME's, or the owner (removing yourself is fine)
     *  - access set NAME LEVEL: LEVEL may not be above the sender's own level
     */
    public static boolean accessCommandBlocked(Object player, Object terminal, String[] args) {
        try {
            if (args == null || args.length < 3) {
                return false;
            }
            String sender = String.valueOf(field(player, "field_71092_bJ"));                    // EntityPlayer.username
            int senderLevel = accessLevel(terminal, sender);
            int owner = ((Enum) Class.forName("icbm.gangshao.access.AccessLevel").getField("OWNER").get(null)).ordinal();
            String target = args[2];
            if (args[0].equalsIgnoreCase("users") && args[1].equalsIgnoreCase("add")) {
                if (accessLevel(terminal, target) > 0) {
                    console(terminal, "User already exists.");
                    return true;
                }
            } else if (args[0].equalsIgnoreCase("users") && args[1].equalsIgnoreCase("remove")) {
                if (senderLevel != owner && !target.equalsIgnoreCase(sender) && accessLevel(terminal, target) >= senderLevel) {
                    console(terminal, "Access denied!");
                    return true;
                }
            } else if (args[0].equalsIgnoreCase("access") && args[1].equalsIgnoreCase("set") && args.length >= 4) {
                Object wanted = findMethod(Class.forName("icbm.gangshao.access.AccessLevel"), "get", 1).invoke(null, new Object[] { args[3] });
                if (wanted != null && ((Enum) wanted).ordinal() > senderLevel) {
                    console(terminal, "Access denied!");
                    return true;
                }
            }
        } catch (Throwable t) {
            System.out.println(TAG + "terminal access check failed: " + t);
        }
        return false;
    }

    private static int accessLevel(Object terminal, String name) throws Exception {
        Object level = findMethod(terminal.getClass(), "getUserAccess", 1).invoke(terminal, new Object[] { name });
        return level == null ? 0 : ((Enum) level).ordinal();
    }

    private static void console(Object terminal, String line) throws Exception {
        findMethod(terminal.getClass(), "addToConsole", 1).invoke(terminal, new Object[] { line });
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
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "#" + name);
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
}
