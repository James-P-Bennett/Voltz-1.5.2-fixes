package powercrystals.minefactoryreloaded;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exploit fixes injected by Voltz-1.5.2-fixes into MineFactoryReloaded 2.6.4 (PatchMFR).
 *
 * Minecraft and MFR objects arrive as Object and are read reflectively on SRG names,
 * because the vanilla classes cannot be compiled against.
 */
public class VoltzMFR {

    private static final String TAG = "[VoltzFixes] ";
    private static final long LOG_INTERVAL_MS = 10000L;

    private static final Map fieldCache = Collections.synchronizedMap(new HashMap());
    private static final Map methodCache = Collections.synchronizedMap(new HashMap());
    private static final Map lastLog = Collections.synchronizedMap(new HashMap());
    private static boolean warned = false;
    private static Class slotFake;

    /**
     * Replaces both inventory.setInventorySlotContents(slot, stack) calls in the packet 19
     * branch of ServerPacketHandler.onPacketData.
     *
     * Packet 19 is meant for MFR's ghost filter slots: clicking a SlotFake in a machine GUI
     * sends the machine's coordinates and the slot's number, and the server stores a size-1
     * copy of the cursor there. Stock trusts the packet completely - any coordinates, any
     * slot, any inventory - and never takes the item from the cursor. A client can write a
     * free copy of whatever it holds into every slot of any chest, or empty any slot in the
     * world with an empty cursor.
     *
     * The write now goes through only if the player's open container is still usable, the
     * slot at that number in it is a SlotFake, and that slot belongs to this inventory -
     * exactly what the real GUI sends.
     */
    public static void setGhostSlot(Object inventory, int slot, Object stack, Object player) {
        try {
            Object container = field(player, "field_71070_bA");               // EntityPlayer.openContainer
            if (container != null) {
                List slots = (List) field(container, "field_75151_b");         // Container.inventorySlots
                if (slot >= 0 && slot < slots.size()) {
                    Object s = slots.get(slot);
                    if (slotFake == null) {
                        slotFake = Class.forName("powercrystals.minefactoryreloaded.gui.slot.SlotFake");
                    }
                    if (slotFake.isInstance(s)
                            && field(s, "field_75224_c") == inventory               // Slot.inventory
                            && ((Integer) field(s, "field_75225_a")).intValue() == slot  // Slot.slotIndex
                            && ((Boolean) call(container, "func_75145_c", new Object[] { player })).booleanValue()) {
                        call(inventory, "func_70299_a", new Object[] { Integer.valueOf(slot), stack });
                        return;
                    }
                }
            }
            refused(player, "ghost slot write to " + inventory.getClass().getSimpleName() + " slot " + slot
                    + " (no matching ghost slot open)");
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                System.out.println(TAG + "MFR ghost slot check failed, write refused: " + t);
            }
        }
    }

    // ------------------------------------------------------------- reflection

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
}
