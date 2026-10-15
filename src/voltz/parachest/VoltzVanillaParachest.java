package voltz.parachest;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

/**
 * Voltz 1.5.2 fixes: Galacticraft Parachests become vanilla chests, shipped as a coremod.
 *
 * A server side option, separate from the patched Galacticraft jar so a server can take the bug
 * fixes without it. Drop the jar in a server's coremods/ next to Galacticraft (stock or patched).
 *
 * Galacticraft's Parachest block extends the vanilla chest block, so a vanilla chest beside a
 * Parachest pairs with it and casts its tile entity to TileEntityChest: ClassCastException, and
 * every client with that chunk loaded crashes with "Ticking tile entity" on every login. With this
 * coremod no Parachest block stays in the world:
 *  * a landing parachest puts its cargo in vanilla chests instead of placing a Parachest;
 *  * a Parachest already in the world turns into vanilla chests on its first server tick;
 *  * a Parachest item cannot be placed beside any chest;
 *  * the server's own TileEntityChest is guarded (ChestGuardTransformer), so a vanilla chest that
 *    meets a non chest tile entity stays single instead of crashing the server.
 *
 * Load time hooks: ParachestTransformer and ChestGuardTransformer. Placement: VanillaParachest.
 */
@IFMLLoadingPlugin.MCVersion("1.5.2")
public class VoltzVanillaParachest implements IFMLLoadingPlugin {

    public String[] getASMTransformerClass() {
        return new String[] { "voltz.parachest.ParachestTransformer", "voltz.chestguard.ChestGuardTransformer" };
    }

    public String[] getLibraryRequestClass() { return null; }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public void injectData(Map<String, Object> data) { }
}
