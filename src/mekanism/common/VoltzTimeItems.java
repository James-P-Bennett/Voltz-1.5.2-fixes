package mekanism.common;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Stopwatch and Weather Orb exploit fix, injected into Mekanism 5.5.6 by PatchMek and called
 * at the top of PacketTime.read and PacketWeather.read, before the stock code touches the
 * world.
 *
 * Stock trusts those packets completely: it damages whatever the sender happens to be holding
 * and then changes the time or weather, so any client can set the time or the weather at will
 * without ever owning either item, as often as it likes. The sender must now be holding the
 * real item, fully recharged, and the value has to be one the GUI can actually send.
 *
 * This is the bug fix only, and it is all the patched Mekanism jar carries. The optional
 * BalancedMekanismTimeItems mod adds the server policy on top - disable the items, put them on
 * a configurable cooldown, or turn them into a vote command - by answering the hook below. It
 * is looked up by name and never linked against, so the fix works exactly the same with the
 * feature mod absent.
 *
 * Minecraft objects arrive as Object and are handled reflectively on SRG names.
 */
public class VoltzTimeItems {

    public static final int STOPWATCH = 0;
    public static final int WEATHER_ORB = 1;

    private static final String TAG = "[VoltzFixes] ";
    private static final long LOG_INTERVAL_MS = 10000L;

    /** Both items take 4999 damage on use and recharge one point per tick. */
    private static final int STOCK_DAMAGE = 4999;

    private static final String[] NAMES = { "Stopwatch", "Weather Orb" };
    private static final String[] ITEM_FIELDS = { "Stopwatch", "WeatherOrb" };

    /** The optional feature mod; absent on a server that only wants the fix. */
    private static final String POLICY_CLASS = "voltz.timeitems.BalancedTimeItems";

    private static final Map methodCache = Collections.synchronizedMap(new HashMap());
    private static final Map lastLog = Collections.synchronizedMap(new HashMap());
    private static Method policy;
    private static boolean policyChecked;

    /**
     * Prepended to PacketTime.read (kind STOPWATCH, value = hour) and PacketWeather.read
     * (kind WEATHER_ORB, value = weather ordinal). The patch reads the int from the packet
     * first and hands it to the stock code afterwards. The stock damageItem(4999) call is
     * removed by the same patch, so the damage is applied here instead.
     *
     * @return true to let the stock time or weather change run
     */
    public static synchronized boolean use(Object player, Object world, int value, int kind) {
        String name = playerName(player);
        String item = NAMES[kind];
        try {
            Object held = call(player, "func_71045_bC", new Object[0]);                // getCurrentEquippedItem
            if (held == null || option(kind, value) < 0
                    || call(held, "func_77973_b", new Object[0]) != itemFor(kind)) {   // ItemStack.getItem
                logLimited(name, "refused " + item + " packet from " + name
                        + " (not holding one, or bad value " + value + ")");
                return false;
            }
            if (((Integer) call(held, "func_77960_j", new Object[0])).intValue() != 0) {  // getItemDamage
                tell(player, "The " + item + " is still recharging.");
                return false;
            }

            Method hook = policy();
            if (hook != null) {
                // the feature mod owns the damage, the cooldown and what actually happens
                return ((Boolean) hook.invoke(null, new Object[] { player, world,
                        Integer.valueOf(value), Integer.valueOf(kind) })).booleanValue();
            }
            call(held, "func_77964_b", new Object[] { Integer.valueOf(STOCK_DAMAGE) });   // setItemDamage
            return true;
        } catch (Throwable t) {
            System.out.println(TAG + "error handling " + item + " for " + name + ", refused: " + t);
            return false;
        }
    }

    /** The GUI only ever sends hour 0/6/12/18, or a weather ordinal 0-3. */
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

    private static Object itemFor(int kind) throws Exception {
        return Class.forName("mekanism.common.Mekanism").getField(ITEM_FIELDS[kind]).get(null);
    }

    /** Resolved once: null when BalancedMekanismTimeItems is not installed. */
    private static Method policy() {
        if (!policyChecked) {
            policyChecked = true;
            try {
                policy = Class.forName(POLICY_CLASS).getMethod("policy",
                        new Class[] { Object.class, Object.class, int.class, int.class });
                System.out.println(TAG + "BalancedMekanismTimeItems found; it governs the "
                        + "Stopwatch and Weather Orb");
            } catch (Throwable t) {
                policy = null;
            }
        }
        return policy;
    }

    // ------------------------------------------------------------- reflection

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

    private static String playerName(Object player) {
        try {
            java.lang.reflect.Field f = null;
            for (Class k = player.getClass(); k != null && f == null; k = k.getSuperclass()) {
                try {
                    f = k.getDeclaredField("field_71092_bJ");                      // EntityPlayer.username
                } catch (NoSuchFieldException ignored) {
                }
            }
            f.setAccessible(true);
            return String.valueOf(f.get(player));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void tell(Object player, String message) {
        try {
            call(player, "func_70006_a", new Object[] { "\u00a7e" + message });   // sendChatToPlayer
        } catch (Throwable t) {
            // a message is never worth failing the refusal over
        }
    }

    private static void logLimited(String name, String what) {
        long now = System.currentTimeMillis();
        Long last = (Long) lastLog.get(name);
        if (last != null && now - last.longValue() < LOG_INTERVAL_MS) {
            return;
        }
        lastLog.put(name, Long.valueOf(now));
        System.out.println(TAG + what);
    }
}
