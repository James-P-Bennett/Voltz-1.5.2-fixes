package voltz.timeitems;

import net.minecraftforge.common.Configuration;
import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * BalancedTimeItems - server policy for the Mekanism Stopwatch and Weather Orb.
 *
 * This is the feature half, and it ships as its own mod: the exploit fix lives in the patched
 * Mekanism jar (mekanism.common.VoltzTimeItems) and stands on its own. That fix has already
 * established, before policy() is called, that the sender really is holding the real item and
 * that it is fully recharged. What is left is the server's choice of what the items do:
 *
 *   allow     stock behaviour, with a configurable cooldown
 *   disable   nothing happens; the player is told the item is disabled
 *   command   the change is cancelled and a command runs as the player instead,
 *             e.g. a vote plugin's /voteday
 *
 * Remove this mod and the Stopwatch and Weather Orb behave as stock, still exploit-proof.
 *
 * Minecraft objects arrive as Object and are handled reflectively on SRG names.
 */
public class BalancedTimeItems {

    public static final int STOPWATCH = 0;
    public static final int WEATHER_ORB = 1;

    private static final String TAG = "[BalancedTimeItems] ";

    /** Both items take 4999 damage on use and recharge one point per tick. */
    private static final int MAX_DAMAGE = 4999;

    private static final String[] NAMES = { "Stopwatch", "Weather Orb" };
    private static final String[] CATEGORIES = { "stopwatch", "weather orb" };

    /** GUI button order; the packet sends hour 0/6/12/18 or weather ordinal 0-3 */
    private static final String[][] OPTIONS = {
        { "Sunrise", "Noon", "Sunset", "Midnight" },
        { "Clear", "Storm", "Haze", "Rain" },
    };
    private static final String[][] DEFAULT_COMMANDS = {
        { "voteday", "voteday", "votenight", "votenight" },
        { "votesun", "voterain", "voterain", "voterain" },
    };

    private static final String[] mode = { "allow", "allow" };
    private static final int[] cooldownSeconds = { 250, 250 };
    private static final String[][] commands = new String[2][4];

    private static final Map lastUse = new HashMap();
    private static final Map methodCache = new HashMap();

    private static boolean loaded = false;

    /** Called from Mekanism.preInit. Safe to call more than once. */
    public static synchronized void init() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (int k = 0; k < 2; k++) {
            System.arraycopy(DEFAULT_COMMANDS[k], 0, commands[k], 0, 4);
        }
        try {
            Configuration cfg = new Configuration(new File("config", "BalancedTimeItems.cfg"));
            cfg.load();
            for (int k = 0; k < 2; k++) {
                String c = CATEGORIES[k];
                String name = NAMES[k];
                cfg.addCustomCategoryComment(c, "Mekanism " + name + ".");

                mode[k] = cfg.get(c, "Mode", "allow",
                        "allow    works as stock, subject to Cooldown Seconds.\n"
                      + "disable  does nothing; the player is told it is disabled.\n"
                      + "command  cancels the change and runs the matching <choice> Command\n"
                      + "         as the player instead. Cooldown Seconds still applies."
                ).getString().trim().toLowerCase();
                if (!mode[k].equals("allow") && !mode[k].equals("disable") && !mode[k].equals("command")) {
                    System.out.println(TAG + "unknown mode '" + mode[k] + "' for " + name + ", treating it as disable");
                    mode[k] = "disable";
                }

                cooldownSeconds[k] = cfg.get(c, "Cooldown Seconds", 250,
                        "Seconds a player must wait between uses. Stock is about 250: the item\n"
                      + "takes 4999 damage and recharges one point per tick. The item's damage\n"
                      + "is set to match (capped at 250 seconds) so its bar shows the wait."
                ).getInt(250);
                if (cooldownSeconds[k] < 0) {
                    cooldownSeconds[k] = 0;
                }

                for (int o = 0; o < 4; o++) {
                    commands[k][o] = cfg.get(c, OPTIONS[k][o] + " Command", DEFAULT_COMMANDS[k][o],
                            "Run as the player when they pick " + OPTIONS[k][o] + " in command mode.\n"
                          + "No leading slash needed. {player} is replaced with their name.\n"
                          + "Leave empty to make this choice unavailable."
                    ).getString().trim();
                }
            }
            if (cfg.hasChanged()) {
                cfg.save();
            }
        } catch (Throwable t) {
            System.out.println(TAG + "config unreadable, using defaults: " + t);
        }
        System.out.println(TAG + "stopwatch=" + mode[STOPWATCH] + " cooldown=" + cooldownSeconds[STOPWATCH]
                + "s  weatherorb=" + mode[WEATHER_ORB] + " cooldown=" + cooldownSeconds[WEATHER_ORB] + "s");
    }

    /**
     * Called by mekanism.common.VoltzTimeItems once it has established that the sender is
     * holding the real item, fully recharged, and sent a value the GUI can actually produce.
     *
     * @return true to let the stock time or weather change run
     */
    public static synchronized boolean policy(Object player, Object world, int value, int kind) {
        init();
        String name = playerName(player);
        String item = NAMES[kind];
        try {
            int option = option(kind, value);
            Object held = call(player, "func_71045_bC", new Object[0]);               // getCurrentEquippedItem
            if (held == null || option < 0) {
                return false;
            }
            if (mode[kind].equals("disable")) {
                tell(player, "The " + item + " is disabled on this server.");
                return false;
            }
            String command = commands[kind][option];
            if (mode[kind].equals("command") && command.length() == 0) {
                tell(player, OPTIONS[kind][option] + " is not available on this server.");
                return false;
            }

            String key = name + "#" + kind;
            long now = System.currentTimeMillis();
            Long last = (Long) lastUse.get(key);
            long waitMs = cooldownSeconds[kind] * 1000L;
            if (last != null && now - last.longValue() < waitMs) {
                long left = (waitMs - (now - last.longValue()) + 999L) / 1000L;
                tell(player, "The " + item + " is recharging: " + left + "s left.");
                return false;
            }
            lastUse.put(key, Long.valueOf(now));
            int damage = (int) Math.min((long) cooldownSeconds[kind] * 20L, (long) MAX_DAMAGE);
            call(held, "func_77964_b", new Object[] { Integer.valueOf(damage) });          // setItemDamage

            if (mode[kind].equals("command")) {
                runCommand(player, command.replace("{player}", name));
                System.out.println(TAG + name + " used the " + item + " (" + OPTIONS[kind][option]
                        + "), ran: " + command);
                return false;
            }
            System.out.println(TAG + name + " used the " + item + " (" + OPTIONS[kind][option] + ")");
            return true;
        } catch (Throwable t) {
            System.out.println(TAG + "error handling " + item + " for " + name + ", refused: " + t);
            return false;
        }
    }

    private static int option(int kind, int value) {
        if (kind == WEATHER_ORB) {
            return value >= 0 && value <= 3 ? value : -1;
        }
        switch (value) {
            case 0:  return 0;
            case 6:  return 1;
            case 12: return 2;
            case 18: return 3;
            default: return -1;
        }
    }

    /**
     * Runs the command as though the player typed it. On MCPC+ that goes through Bukkit's
     * dispatcher, which is where plugin commands such as vote commands live; on plain
     * Forge it goes through the vanilla command manager.
     */
    private static void runCommand(Object player, String command) throws Exception {
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        Class bukkit = null;
        try {
            bukkit = Class.forName("org.bukkit.Bukkit");
        } catch (ClassNotFoundException notMcpc) {
        }
        if (bukkit != null) {
            Object sender = player.getClass().getMethod("getBukkitEntity").invoke(player);
            Class senderType = Class.forName("org.bukkit.command.CommandSender");
            bukkit.getMethod("dispatchCommand", new Class[] { senderType, String.class })
                    .invoke(null, new Object[] { sender, command });
            return;
        }
        Object server = Class.forName("net.minecraft.server.MinecraftServer")
                .getMethod("func_71276_C").invoke(null);                             // getServer
        Object manager = call(server, "func_71187_D", new Object[0]);                // getCommandManager
        call(manager, "func_71556_a", new Object[] { player, command });              // executeCommand
    }

    private static void tell(Object player, String message) {
        try {
            call(player, "func_70006_a", new Object[] { "§e" + message });      // sendChatToPlayer
        } catch (Throwable t) {
        }
    }

    private static String playerName(Object player) {
        try {
            java.lang.reflect.Field f = null;
            for (Class k = player.getClass(); k != null && f == null; k = k.getSuperclass()) {
                try {
                    f = k.getDeclaredField("field_71092_bJ");                        // EntityPlayer.username
                } catch (NoSuchFieldException ignored) {
                }
            }
            f.setAccessible(true);
            return String.valueOf(f.get(player));
        } catch (Throwable t) {
            return "?";
        }
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
                // interface methods (ICommandManager) are not declared on the class chain above
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
}
