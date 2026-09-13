package atomicscience.fanwusu;

import net.minecraftforge.common.Configuration;
import java.io.File;

/**
 * Config holder injected by Voltz-1.5.2-fixes.
 *
 * Lives in the mod's own package so FML's class loader picks it up with the rest
 * of Atomic Science. Loaded lazily on first access from EWuSu.explode().
 *
 * Writes config/VoltzFixes.cfg on first run.
 */
public class VoltzFixConfig {

    /**
     * Value pushed as the isSmoking argument of World.createExplosion in
     * EWuSu.explode(). false = no block damage, no block drops.
     * This is the INVERSE of the user-facing "Disable Explosion Block Damage".
     */
    public static final boolean blastDamage = load();

    /**
     * Called from ZhuYao.preInit so the .cfg is written at startup rather than
     * lazily on the first explosion. Invoking any static method forces <clinit>,
     * which runs load().
     */
    public static void init() {
    }

    private static boolean load() {
        boolean disable = true;   // default: damage OFF
        try {
            File file = new File("config", "VoltzFixes.cfg");
            Configuration cfg = new Configuration(file);
            cfg.load();
            disable = cfg.get("general",
                    "Disable Explosion Block Damage",
                    true,
                    "Particle accelerator explosions damage no blocks and drop no items.\n"
                  + "Entity knockback and damage are unaffected, so strange matter\n"
                  + "production is unchanged - the knock is what clears the 0.5 speed gate.\n"
                  + "A correctly synced accelerator detonates a survivor on its own\n"
                  + "electromagnet every cycle, so with this false the machine slowly\n"
                  + "eats itself. Set false only if a third injector suppresses the blast."
            ).getBoolean(true);
            if (cfg.hasChanged()) {
                cfg.save();
            }
        } catch (Throwable t) {
            // fail safe: if the config cannot be read, leave damage off
            System.out.println("[VoltzFixes] config unreadable, defaulting to no blast damage: " + t);
            disable = true;
        }
        System.out.println("[VoltzFixes] Disable Explosion Block Damage = " + disable);
        return !disable;
    }
}
