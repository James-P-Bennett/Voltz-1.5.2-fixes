package voltz.chestguard;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

/**
 * Voltz 1.5.2 fixes: client side chest crash guard, shipped as a coremod.
 *
 * This one is for the CLIENT (the launcher instance's coremods/ folder), unlike everything else
 * in this repo. It protects a player from a crash that a server can trigger, so it is useful on
 * any Voltz server, patched or not. VoltzVanillaParachest runs the same transformer server side.
 *
 * The crash. A vanilla chest looks at its four neighbours every tick
 * (TileEntityChest.checkForAdjacentChests) and, wherever the neighbour is a chest block,
 * casts the neighbour's tile entity straight to TileEntityChest. If that neighbour is a chest block
 * whose tile entity is something else, the cast throws ClassCastException and the client
 * crashes with "Ticking tile entity", again on every login while that chunk is in range. The
 * common way in: Galacticraft's Parachest block extends the vanilla chest block, so a vanilla chest
 * pairs with a Parachest placed beside it and casts the Parachest's tile entity.
 *
 * The fix lives in ChestGuardTransformer: a neighbour that is not a TileEntityChest is treated
 * as no neighbour, so the chest stays single instead of crashing.
 */
@IFMLLoadingPlugin.MCVersion("1.5.2")
public class VoltzChestGuard implements IFMLLoadingPlugin {

    public String[] getASMTransformerClass() {
        return new String[] { "voltz.chestguard.ChestGuardTransformer" };
    }

    public String[] getLibraryRequestClass() { return null; }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public void injectData(Map<String, Object> data) { }
}
