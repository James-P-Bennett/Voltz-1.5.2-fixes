package andrew.powersuits;

import net.minecraftforge.common.Configuration;
import java.io.File;

/**
 * Config holder injected by Voltz-1.5.2-fixes into the MPS Addons jar.
 *
 * Lives in the addon's own package so FML's class loader picks it up with the rest of
 * MPSA. Loaded from CommonProxy, so config/VoltzFixes-MPSA.cfg exists at startup.
 *
 * Only primitives and Forge types are referenced - the vanilla classes in
 * bin/minecraft.jar are obfuscated and cannot be compiled against, so anything needing a
 * Minecraft type is emitted as bytecode by PatchMPSA and handed here as plain values.
 */
public class VoltzMagnetConfig {

    /** true = the server pulls items itself instead of relying on client-side motion. */
    public static boolean serverSideMagnet = true;

    /** blocks per tick the item is pulled toward the player. */
    public static double magnetSpeed = 0.35d;

    private static boolean loaded = false;

    /** Called from CommonProxy. Safe to call more than once. */
    public static void init() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Configuration cfg = new Configuration(new File("config", "VoltzFixes-MPSA.cfg"));
            cfg.load();

            serverSideMagnet = cfg.get("general",
                    "Server Side Item Magnet", true,
                    "The Magnet module only moves items on the client, and item positions\n"
                  + "are server authoritative, so the pull is overwritten every tick and\n"
                  + "the module does nothing on a dedicated server while still draining\n"
                  + "power. True makes the server do the pulling, which vanilla's entity\n"
                  + "tracker then syncs to every client."
            ).getBoolean(true);

            magnetSpeed = cfg.get("general",
                    "Magnet Pull Speed", 0.35d,
                    "Blocks per tick an item is pulled toward the player."
            ).getDouble(0.35d);

            if (cfg.hasChanged()) {
                cfg.save();
            }
        } catch (Throwable t) {
            System.out.println("[VoltzFixes] MPSA config unreadable, using defaults: " + t);
        }
        System.out.println("[VoltzFixes] serverSideMagnet=" + serverSideMagnet
                + " magnetSpeed=" + magnetSpeed);
    }

    /**
     * One axis of the pull. PatchMPSA pushes the axis delta (player minus item) and the
     * distance already computed by updateMagneticPlayer, and stores the result straight
     * into the item's motion field.
     *
     * That distance is HORIZONTAL only - the stock method never computes a Y component -
     * so an item directly overhead would divide by something near zero. The divisor is
     * floored at 1.0 and every axis is clamped to magnetSpeed, which keeps the pull even
     * in all directions regardless.
     */
    public static double pull(double delta, double distance) {
        if (!serverSideMagnet) {
            return 0.0d;
        }
        double divisor = distance < 1.0d ? 1.0d : distance;
        double v = (delta / divisor) * magnetSpeed;
        if (v > magnetSpeed) {
            return magnetSpeed;
        }
        if (v < -magnetSpeed) {
            return -magnetSpeed;
        }
        return v;
    }
}
