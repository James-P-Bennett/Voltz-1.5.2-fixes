import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - FML login-sequence crash guard (plain Forge only).
 *
 * UNTESTED. This is the one patch in the project that could not be exercised: reproducing it
 * needs a modified client sending an out-of-order login packet, which the server-side harness
 * (it injects an already-logged-in fake player) cannot do. It ships behind its own command,
 * is not built by ./build.sh, and is plain-Forge only. On an MCPC+ server use the LoginSeqFix +
 * ProtocolLib plugins instead (they ship with the Voltz server) - see the README.
 *
 * The exploit. During the FML login handshake the server routes a client packet-250 custom
 * payload through NetworkRegistry.handleCustomPacket(packet, netManager, handler). For a channel
 * that is not "FML"/"MC|" and not REGISTER/UNREGISTER, it dispatches to the owning mod's
 * IPacketHandler with handler.getPlayer(). During login there is no player entity yet, so
 * getPlayer() is null and the mod's handler dereferences null on the server thread - a crash a
 * client can trigger before it has logged in.
 *
 * The patch. A guard at the head of the default (mod-packet) dispatch:
 *
 *     if (handler.getPlayer() == null) return;
 *
 * Only that branch is guarded; the REGISTER/UNREGISTER branches - the legitimate channel
 * registration that happens during login, before the player spawns - run untouched.
 *
 * The class is Forge's own (cpw.mods.fml.common.network.NetworkRegistry) but it references
 * obfuscated vanilla types (ej = NetServerHandler, sq = EntityPlayer). A plain-Forge 1.5.2
 * server runs obfuscated, so those names match at runtime; the inserted getPlayer() call is
 * copied verbatim from the existing call site in the same method, so no mapping is needed. This
 * does NOT match MCPC+, which runs deobfuscated - another reason it is plain-Forge only.
 *
 * usage: PatchForgeLogin <forge-universal.zip> <out.zip>
 */
public class PatchForgeLogin {

    static final String REGISTRY = "cpw/mods/fml/common/network/NetworkRegistry";
    static final String HANDLE_PACKET_DESC =
            "(Ldk;Lcg;Lcpw/mods/fml/common/network/Player;)V";

    static boolean hit;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: PatchForgeLogin <forge-universal.zip> <out.zip>");
            System.exit(2);
        }
        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(args[0]);
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) { out.put(ze.getName(), null); continue; }
            byte[] d = readAll(zf.getInputStream(ze));
            if (ze.getName().equals(REGISTRY + ".class")) d = patch(d);
            out.put(ze.getName(), d);
        }
        zf.close();
        if (!hit) throw new IllegalStateException("login guard did not apply");

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])));
        for (Map.Entry<String, byte[]> en : out.entrySet()) {
            zos.putNextEntry(new ZipEntry(en.getKey()));
            if (en.getValue() != null) zos.write(en.getValue());
            zos.closeEntry();
        }
        zos.close();
        System.out.println("OK  wrote " + args[1] + "  [login guard]");
    }

    /**
     * Insert, at the start of the default mod-packet block of handleCustomPacket:
     *
     *     if (handler.getPlayer() == null) return;
     *
     * The block is located by its `handlePacket(dk, cg, Player)` INVOKESPECIAL; the guard is
     * inserted before the aload_0 that begins the block, so the UNREGISTER branch's jump into
     * that block lands on the guard.
     */
    static byte[] patch(byte[] in) {
        ClassNode cn = new ClassNode();
        new ClassReader(in).accept(cn, ClassReader.SKIP_FRAMES);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("handleCustomPacket")) continue;
            MethodInsnNode dispatch = null;
            MethodInsnNode getPlayer = null;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKESPECIAL) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (mi.owner.equals(REGISTRY) && mi.name.equals("handlePacket") && mi.desc.equals(HANDLE_PACKET_DESC)) {
                    dispatch = mi;
                }
            }
            if (dispatch == null) throw new IllegalStateException("handlePacket dispatch not found");
            // getPlayer() is called just before the checkcast/dispatch; copy that exact call.
            for (AbstractInsnNode i = dispatch; i != null; i = i.getPrevious()) {
                if (i.getOpcode() == Opcodes.INVOKEVIRTUAL && ((MethodInsnNode) i).name.equals("getPlayer")) {
                    getPlayer = (MethodInsnNode) i;
                    break;
                }
            }
            if (getPlayer == null) throw new IllegalStateException("getPlayer call not found");
            // walk back to the aload_0 that starts the block: aload_0, aload_1, aload_2, aload_3, getPlayer, checkcast, dispatch
            AbstractInsnNode blockStart = getPlayer;
            int aloads = 0;
            for (AbstractInsnNode i = getPlayer.getPrevious(); i != null; i = i.getPrevious()) {
                int op = i.getOpcode();
                if (op == Opcodes.ALOAD) { blockStart = i; if (++aloads == 4) break; }
                else if (op >= 0) break;
            }
            if (aloads != 4) throw new IllegalStateException("block start not found (aloads=" + aloads + ")");

            LabelNode cont = new LabelNode();
            InsnList g = new InsnList();
            g.add(new VarInsnNode(Opcodes.ALOAD, 3));                              // handler
            g.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, getPlayer.owner, "getPlayer", getPlayer.desc, false));
            g.add(new JumpInsnNode(Opcodes.IFNONNULL, cont));
            g.add(new InsnNode(Opcodes.RETURN));
            g.add(cont);
            m.instructions.insertBefore(blockStart, g);
            m.maxStack = Math.max(m.maxStack, 1);
            hit = true;
        }
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }
}
