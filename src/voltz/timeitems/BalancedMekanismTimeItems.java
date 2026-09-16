package voltz.timeitems;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * BalancedMekanismTimeItems - the optional server policy for the Mekanism Stopwatch and
 * Weather Orb.
 *
 * A feature, not a bug fix, so it is a separate jar: the exploit fix that makes the two items
 * safe to have on a server at all is in the patched Mekanism jar and works without this. This
 * adds the part a server operator chooses - disable the items outright, put them on a longer
 * cooldown, or turn each option into a command so a vote plugin handles it.
 *
 * It transforms nothing, so it is an ordinary mod rather than a coremod: drop the jar in the
 * server's mods/ folder next to the patched Mekanism jar. The patched Mekanism looks this mod
 * up by name and hands it the decision when it is present; take the jar out and the items go
 * back to stock behaviour, still exploit-proof. Config is config/BalancedTimeItems.cfg.
 */
@Mod(modid = "balancedmekanismtimeitems", name = "BalancedMekanismTimeItems", version = "1.0",
     dependencies = "required-after:Mekanism")
public class BalancedMekanismTimeItems {

    @Mod.PreInit
    public void preInit(FMLPreInitializationEvent event) {
        BalancedTimeItems.init();
    }
}
