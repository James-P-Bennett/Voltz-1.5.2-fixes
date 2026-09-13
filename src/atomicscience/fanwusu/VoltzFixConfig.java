package atomicscience.fanwusu;

import net.minecraftforge.common.Configuration;
import java.io.File;

/**
 * Config holder injected by Voltz-1.5.2-fixes.
 *
 * Lives in the mod's own package so FML's class loader picks it up with the rest of
 * Atomic Science. Loaded from ZhuYao.preInit, so config/VoltzFixes.cfg exists at startup
 * rather than appearing lazily later.
 *
 * Only primitives and Forge types are referenced here - no net.minecraft.* classes,
 * because the vanilla classes in bin/minecraft.jar are obfuscated and cannot be compiled
 * against. Anything needing a Minecraft type is done in bytecode by PatchAS and handed
 * to this class as plain values.
 */
public class VoltzFixConfig {

    /**
     * isSmoking argument of World.createExplosion in EWuSu.explode().
     * false = no block damage, no block drops. INVERSE of the user-facing
     * "Disable Explosion Block Damage".
     */
    public static boolean blastDamage = false;

    /** true = TJiaSuQi spawn gate uses world time instead of the per-TileEntity counter. */
    public static boolean syncSpawn = true;

    /** true = plasma blocks tick randomly, so an orphaned block still decays. */
    public static boolean plasmaDecay = true;

    /** Loop bound for the Assembler cell wear loop in TGouCheng.yong(). 6 = fixed, 5 = stock. */
    public static int assemblerSlots = 6;

    private static boolean loaded = false;

    /** Called from ZhuYao.preInit. Safe to call more than once. */
    public static void init() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Configuration cfg = new Configuration(new File("config", "VoltzFixes.cfg"));
            cfg.load();

            blastDamage = !cfg.get("general",
                    "Disable Explosion Block Damage", true,
                    "Particle accelerator explosions damage no blocks and drop no items.\n"
                  + "Entity knockback and damage are unaffected, so strange matter\n"
                  + "production is unchanged - the knock is what clears the 0.5 speed gate.\n"
                  + "A correctly synced accelerator detonates a survivor on its own\n"
                  + "electromagnet every cycle, so with this false the machine slowly\n"
                  + "eats itself. Set false only if a third injector suppresses the blast."
            ).getBoolean(true);

            syncSpawn = cfg.get("general",
                    "Sync Accelerator Spawns To World Time", true,
                    "Gate particle spawning on world time instead of the per-machine tick\n"
                  + "counter, which starts at zero when the TileEntity is constructed.\n"
                  + "With this false, two accelerators placed at different moments stay\n"
                  + "permanently out of phase and only a world reload resyncs them."
            ).getBoolean(true);

            plasmaDecay = cfg.get("general",
                    "Plasma Self Decay Backstop", true,
                    "Give plasma blocks a random tick so an orphaned one still decays.\n"
                  + "Plasma normally gets a single scheduled decay tick at placement and\n"
                  + "nothing ever re-arms it. Stranded plasma is instant unconditional\n"
                  + "death and deletes any item that touches it."
            ).getBoolean(true);

            assemblerSlots = cfg.get("general",
                    "Assembler Wears All Six Cells", true,
                    "The Atomic Assembler requires six strange matter cells but its wear\n"
                  + "loop stops at five, so the sixth never takes damage and lasts forever.\n"
                  + "True consumes all six. False restores the stock behaviour."
            ).getBoolean(true) ? 6 : 5;

            if (cfg.hasChanged()) {
                cfg.save();
            }
        } catch (Throwable t) {
            System.out.println("[VoltzFixes] config unreadable, using defaults: " + t);
        }
        System.out.println("[VoltzFixes] blastDamage=" + blastDamage
                + " syncSpawn=" + syncSpawn
                + " plasmaDecay=" + plasmaDecay
                + " assemblerSlots=" + assemblerSlots);
    }

    /**
     * Spawn clock selector for TJiaSuQi. PatchAS pushes world total time and the
     * TileEntity's own counter; this picks between them. Plain longs, so this class
     * never has to name a Minecraft type.
     */
    public static long spawnClock(long worldTotalTime, long tileEntityTicks) {
        return syncSpawn ? worldTotalTime : tileEntityTicks;
    }
}
