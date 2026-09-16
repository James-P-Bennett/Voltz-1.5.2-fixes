package mffs;

/**
 * Bug-fix helper injected into the MFFS jar by PatchMFFS.
 *
 * Deliberately tiny and free of any dependency on BalancedMFFS: the dupe fixes ship in the
 * mod jar and the zone-flag/logging feature ships separately as the BalancedMFFS coremod,
 * so a server can take one without the other.
 */
public class VoltzMFFS {

    private static final String TAG = "[VoltzFixes] ";

    /**
     * Called from the patched ItemModuleStablize for each slot of a sided inventory next to
     * the projector: is the slot among those exposed on the face touching the projector?
     */
    public static boolean hasSlot(int[] slots, int slot) {
        if (slots == null) return false;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) return true;
        }
        return false;
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
