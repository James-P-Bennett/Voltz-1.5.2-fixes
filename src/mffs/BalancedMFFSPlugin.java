package mffs;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

/**
 * BalancedMFFS - zone flags and admin logging for MFFS interdiction matrixes, shipped as a
 * coremod.
 *
 * This is a feature, not a bug fix, so it is deliberately a separate jar from
 * MFFS_v3.1.0.175-patched.jar: that one carries only the merge and Stabilize dupe fixes, and
 * a server that wants the dupe fixes without the zone system simply does not install this.
 *
 * Drop BalancedMFFS.jar in the server's coremods/ folder. It transforms MFFS's own classes at
 * load time rather than being baked into the mod jar, so it works against either the stock or
 * the patched MFFS. Remove the jar and MFFS is back to stock behaviour.
 *
 * The hooks live in BalancedMFFSTransformer; the zone rules, config file and logging live in
 * BalancedMFFS.
 */
@IFMLLoadingPlugin.MCVersion("1.5.2")
public class BalancedMFFSPlugin implements IFMLLoadingPlugin {

    public String[] getASMTransformerClass() {
        return new String[] { "mffs.BalancedMFFSTransformer" };
    }

    public String[] getLibraryRequestClass() { return null; }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public void injectData(Map<String, Object> data) { }
}
