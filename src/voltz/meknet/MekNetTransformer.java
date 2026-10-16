package voltz.meknet;

import cpw.mods.fml.relauncher.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Fixes the Mekanism 5.5.6 energy network crash described in MekNetFix by rewriting
 * mekanism.common.EnergyNetwork:
 *
 *   refresh(), at the head:
 *       MekNetFix.prune(this);
 *   tick(), every direct call this.refresh() becomes:
 *       MekNetFix.refresh(this);
 *
 * Stock tick() has no refresh() call, so the second edit only touches a tick() that a periodic
 * rescan (the cablereload fix) was added to. Either edit is skipped when it is already present,
 * so running this over an already fixed class changes nothing.
 *
 * Mekanism classes are never obfuscated, so the names hold on plain Forge and MCPC+. It never
 * throws: any failure logs and returns the class unchanged. Compiled against ASM 4.1 (FML 1.5.2's
 * runtime ASM); uses only the four argument MethodInsnNode constructor.
 *
 * Shading: another runtime patching mod can shade this class and MekNetFix under any package and
 * call patch(name, bytes) from its own transformer when handles(name) is true. The hook owner is
 * read from MekNetFix.class at runtime, so a relocated copy injects calls to the relocated helper.
 * If that mod already injects the periodic rescan, run this after it so the rescan's refresh()
 * call is covered too.
 */
public class MekNetTransformer implements IClassTransformer {

    public static final String TARGET = "mekanism.common.EnergyNetwork";
    static final String NET = "mekanism/common/EnergyNetwork";
    static final String HOOK = MekNetFix.class.getName().replace('.', '/');

    public byte[] transform(String name, String transformedName, byte[] data) {
        String target = handles(transformedName) ? transformedName : name;
        if (data == null || !handles(target)) {
            return data;
        }
        try {
            byte[] out = patch(target, data);
            System.out.println("[VoltzMekNet] " + (out != data ? "patched " : "nothing to patch in ") + target);
            return out;
        } catch (Throwable t) {
            System.out.println("[VoltzMekNet] transform failed, " + target + " stays stock: " + t);
            return data;
        }
    }

    /** True for the one class this transformer rewrites. */
    public static boolean handles(String className) {
        return TARGET.equals(className);
    }

    /**
     * Rewrites EnergyNetwork. Returns the same array, unchanged, when there is nothing to do.
     * Public so a shading mod can call it from its own transformer.
     */
    public static byte[] patch(String className, byte[] data) {
        if (!handles(className)) return data;
        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, 0);
        boolean changed = false;
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (m.name.equals("refresh") && m.desc.equals("()V") && !calls(m, HOOK, "prune")) {
                InsnList g = new InsnList();
                g.add(new VarInsnNode(Opcodes.ALOAD, 0));
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "prune", "(Ljava/lang/Object;)V"));
                m.instructions.insert(g);
                if (m.maxStack < 1) m.maxStack = 1;
                changed = true;
            } else if (m.name.equals("tick") && m.desc.equals("()V")) {
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                    MethodInsnNode mi = (MethodInsnNode) i;
                    if (!mi.owner.equals(NET) || !mi.name.equals("refresh") || !mi.desc.equals("()V")) continue;
                    // same stack shape: both consume the network reference and return nothing
                    m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "refresh", "(Ljava/lang/Object;)V"));
                    changed = true;
                }
            }
        }
        if (!changed) return data;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static boolean calls(MethodNode m, String owner, String name) {
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals(owner) && ((MethodInsnNode) i).name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** offline check: java ... MekNetTransformer <Mekanism jar> <out dir> */
    public static void main(String[] args) throws Exception {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(args[0]);
        java.io.InputStream is = zf.getInputStream(zf.getEntry(NET + ".class"));
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        zf.close();
        byte[] in = bos.toByteArray();
        byte[] out = patch(TARGET, in);
        byte[] again = patch(TARGET, out);
        System.out.println(TARGET + " applied=" + (out != in) + " idempotent=" + (again == out) + " bytesIn=" + in.length + " bytesOut=" + out.length);
        java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(args[1], "EnergyNetwork.class"));
        fo.write(out);
        fo.close();
    }
}
