package voltz.chestguard;

import cpw.mods.fml.relauncher.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Rewrites every cast of a neighbour's tile entity to TileEntityChest inside TileEntityChest
 * (only checkForAdjacentChests has any) from
 *
 *     (TileEntityChest) te
 * to
 *     te instanceof TileEntityChest ? (TileEntityChest) te : null
 *
 * checkForAdjacentChests already treats a null neighbour as "no chest there", so a mismatched
 * neighbour leaves the chest single instead of throwing ClassCastException.
 *
 * Naming agnostic: the class is matched by its obfuscated name (apy) or its deobfuscated name,
 * and the casts are matched against the class's own internal name, so it applies whether it runs
 * before or after FML's runtime deobfuscation. It never throws: any failure logs and returns the
 * class unchanged. Compiled against ASM 4.1 (FML 1.5.2's runtime ASM).
 */
public class ChestGuardTransformer implements IClassTransformer {

    static final String DEOBF = "net.minecraft.tileentity.TileEntityChest";
    static final String OBF = "apy";
    public static int guarded;

    public byte[] transform(String name, String transformedName, byte[] data) {
        if (data == null || !(DEOBF.equals(transformedName) || DEOBF.equals(name) || OBF.equals(name))) {
            return data;
        }
        try {
            byte[] out = patch(data);
            System.out.println("[VoltzChestGuard] guarded " + guarded + " neighbour cast(s) in TileEntityChest");
            return out;
        } catch (Throwable t) {
            System.out.println("[VoltzChestGuard] transform failed, leaving TileEntityChest unchanged: " + t);
            return data;
        }
    }

    /**
     * Rewrites TileEntityChest's class bytes. Sets guarded and returns the (possibly unchanged)
     * bytes. Public so a server coremod or a shading mod can call it from its own transformer.
     */
    public static byte[] patch(byte[] data) {
        guarded = 0;
        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, ClassReader.SKIP_FRAMES);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
                if (i.getOpcode() != Opcodes.CHECKCAST || !((TypeInsnNode) i).desc.equals(cn.name)) continue;
                // only casts of a value just returned by World.getBlockTileEntity(x, y, z)
                AbstractInsnNode prev = i.getPrevious();
                while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
                if (!(prev instanceof MethodInsnNode) || !((MethodInsnNode) prev).desc.startsWith("(III)")) continue;
                LabelNode ok = new LabelNode();
                InsnList g = new InsnList();
                g.add(new InsnNode(Opcodes.DUP));
                g.add(new TypeInsnNode(Opcodes.INSTANCEOF, cn.name));
                g.add(new JumpInsnNode(Opcodes.IFNE, ok));
                g.add(new InsnNode(Opcodes.POP));
                g.add(new InsnNode(Opcodes.ACONST_NULL));
                g.add(ok);
                m.instructions.insertBefore(i, g);
                m.maxStack += 1;
                guarded++;
            }
        }
        if (guarded == 0) return data;
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** offline check: java ... ChestGuardTransformer <minecraft client jar> <out.class> */
    public static void main(String[] args) throws Exception {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(args[0]);
        java.util.zip.ZipEntry ze = zf.getEntry(OBF + ".class");
        java.io.InputStream is = zf.getInputStream(ze);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        zf.close();
        byte[] out = patch(bos.toByteArray());
        System.out.println("guarded=" + guarded + " bytesIn=" + bos.size() + " bytesOut=" + out.length);
        java.io.FileOutputStream fo = new java.io.FileOutputStream(args[1]);
        fo.write(out);
        fo.close();
    }
}
