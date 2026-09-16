package mffs;

/**
 * Bug-fix helper injected into the MFFS jar by PatchMFFS.
 *
 * Deliberately tiny and free of any dependency on BalancedMFFS: the dupe fixes ship in the
 * mod jar and the zone-flag/logging feature ships separately as the BalancedMFFS coremod,
 * so a server can take one without the other.
 */
public class VoltzMFFS {

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
}
