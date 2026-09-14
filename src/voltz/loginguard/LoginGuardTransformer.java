package voltz.loginguard;

import cpw.mods.fml.relauncher.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Inserts, at the head of the default (mod-packet) block of
 * cpw.mods.fml.common.network.NetworkRegistry.handleCustomPacket:
 *
 *     if (handler.getPlayer() == null) return;
 *
 * so a login-phase mod-channel packet-250 - which arrives before the player entity exists - is
 * dropped instead of being dispatched to a mod handler that would dereference the null player.
 * The REGISTER / UNREGISTER branches (the legitimate login channel registration) are untouched.
 *
 * Naming-agnostic: it matches FML's own stable names (handleCustomPacket, handlePacket) and copies
 * the getPlayer() call already present in the method, so it applies in both the obfuscated (plain
 * Forge) and deobfuscated (MCPC+) runtime environments. It never throws: any failure logs and
 * returns the class unchanged, so a mismatch can never stop the server from starting. Compiled
 * against ASM 4.1 (FML 1.5.2's runtime ASM); uses only the 4-argument MethodInsnNode constructor.
 */
public class LoginGuardTransformer implements IClassTransformer {

    static final String TARGET = "cpw.mods.fml.common.network.NetworkRegistry";
    static boolean applied;

    public byte[] transform(String name, String transformedName, byte[] data) {
        if (data == null || !TARGET.equals(transformedName)) {
            return data;
        }
        try {
            byte[] out = patch(data);
            System.out.println("[VoltzLoginGuard] " + (applied ? "patched" : "could not find insertion point in")
                    + " NetworkRegistry.handleCustomPacket");
            return out;
        } catch (Throwable t) {
            System.out.println("[VoltzLoginGuard] transform failed, leaving NetworkRegistry unchanged: " + t);
            return data;
        }
    }

    /** Shared with the offline verifier. Sets applied and returns the (possibly unchanged) bytes. */
    static byte[] patch(byte[] data) {
        applied = false;
        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, ClassReader.SKIP_FRAMES);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("handleCustomPacket")) continue;
            MethodInsnNode dispatch = null;
            for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
                if (i.getOpcode() == Opcodes.INVOKESPECIAL) {
                    MethodInsnNode mi = (MethodInsnNode) i;
                    if (mi.owner.equals(cn.name) && mi.name.equals("handlePacket")) dispatch = mi;
                }
            }
            if (dispatch == null) return data;
            MethodInsnNode getPlayer = null;
            for (AbstractInsnNode i = dispatch; i != null; i = i.getPrevious()) {
                if (i.getOpcode() == Opcodes.INVOKEVIRTUAL && ((MethodInsnNode) i).name.equals("getPlayer")) {
                    getPlayer = (MethodInsnNode) i;
                    break;
                }
            }
            if (getPlayer == null) return data;
            AbstractInsnNode blockStart = getPlayer;
            int aloads = 0;
            for (AbstractInsnNode i = getPlayer.getPrevious(); i != null; i = i.getPrevious()) {
                int op = i.getOpcode();
                if (op == Opcodes.ALOAD) { blockStart = i; if (++aloads == 4) break; }
                else if (op >= 0) break;
            }
            if (aloads != 4) return data;

            LabelNode cont = new LabelNode();
            InsnList g = new InsnList();
            g.add(new VarInsnNode(Opcodes.ALOAD, 3));
            g.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, getPlayer.owner, "getPlayer", getPlayer.desc));
            g.add(new JumpInsnNode(Opcodes.IFNONNULL, cont));
            g.add(new InsnNode(Opcodes.RETURN));
            g.add(cont);
            m.instructions.insertBefore(blockStart, g);
            if (m.maxStack < 1) m.maxStack = 1;
            applied = true;
        }
        if (!applied) return data;
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** offline check: java ... LoginGuardTransformer <forge-universal.zip> <out.class> */
    public static void main(String[] args) throws Exception {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(args[0]);
        java.util.zip.ZipEntry ze = zf.getEntry("cpw/mods/fml/common/network/NetworkRegistry.class");
        java.io.InputStream is = zf.getInputStream(ze);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        zf.close();
        byte[] out = patch(bos.toByteArray());
        System.out.println("applied=" + applied + " bytesIn=" + bos.size() + " bytesOut=" + out.length);
        java.io.FileOutputStream fo = new java.io.FileOutputStream(args[1]);
        fo.write(out);
        fo.close();
    }
}
