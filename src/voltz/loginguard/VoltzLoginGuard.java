package voltz.loginguard;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

/**
 * Voltz 1.5.2 fixes - FML login-sequence crash guard, shipped as a coremod.
 *
 * A drop-in for a server's coremods/ folder (plain Forge or MCPC+): it transforms one FML core
 * class at load time, so it needs no separate Forge build and no ProtocolLib/Bukkit plugin. This
 * is the project's own fix for the crash, for server owners to install directly.
 *
 * The exploit. During the FML login handshake the server routes a client packet-250 custom
 * payload through NetworkRegistry.handleCustomPacket(packet, netManager, handler). For a mod
 * channel (not FML or MC|, and not REGISTER / UNREGISTER) it dispatches to the owning mod's
 * packet handler with handler.getPlayer() - which is null during login, because no player entity
 * exists yet. A modified client that sends such a packet before it has logged in makes the mod
 * handler dereference the null player on the server thread, crashing the server.
 *
 * The fix lives in LoginGuardTransformer: the mod-packet branch returns when getPlayer() is null.
 */
public class VoltzLoginGuard implements IFMLLoadingPlugin {

    public String[] getASMTransformerClass() {
        return new String[] { "voltz.loginguard.LoginGuardTransformer" };
    }

    public String[] getLibraryRequestClass() { return null; }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public void injectData(Map<String, Object> data) { }
}
