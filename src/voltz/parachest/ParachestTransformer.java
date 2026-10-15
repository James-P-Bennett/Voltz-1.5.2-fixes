package voltz.parachest;

import cpw.mods.fml.relauncher.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Hooks three Galacticraft classes so no Parachest block ever sits in the world (see
 * VanillaParachest for the crash this prevents):
 *
 *   GCCoreEntityParaChest.placeChest(x, y, z), at the head:
 *       if (VanillaParachest.place(this, x, y, z)) return true;
 *   GCCoreTileEntityParachest.updateEntity(), at the head:
 *       if (VanillaParachest.convert(this)) return;
 *   GCCoreBlockParachest.canPlaceBlockAt(world, x, y, z), at the head:
 *       if (!VanillaParachest.canPlaceAt(world, x, y, z)) return false;
 *
 * Each hook falls through to the stock body when it reports "not handled".
 *
 * Naming agnostic: the classes are Galacticraft's own (never obfuscated); placeChest is GC's own
 * name, and the two overridden Minecraft methods are matched by obfuscated, SRG or MCP name with
 * the matching descriptor shape, so it applies before or after FML's runtime deobfuscation and on
 * MCPC+. The hooks take only Object and int, so their descriptors never change. It never throws:
 * any failure logs and returns the class unchanged. Compiled against ASM 4.1 (FML 1.5.2's runtime
 * ASM); uses only the four argument MethodInsnNode constructor.
 *
 * Shading: another runtime patching mod can shade this class and VanillaParachest under any
 * package and call patch(name, bytes) from its own transformer for every name handles() accepts.
 * The hook owner is read from VanillaParachest.class at runtime, so a relocated copy injects calls
 * to the relocated helper.
 */
public class ParachestTransformer implements IClassTransformer {

    static final String PKG = "micdoodle8.mods.galacticraft.core.";
    public static final String ENTITY = PKG + "entities.GCCoreEntityParaChest";
    public static final String TILE = PKG + "tile.GCCoreTileEntityParachest";
    public static final String BLOCK = PKG + "blocks.GCCoreBlockParachest";
    static final String HOOK = VanillaParachest.class.getName().replace('.', '/');

    public byte[] transform(String name, String transformedName, byte[] data) {
        String target = handles(transformedName) ? transformedName : name;
        if (data == null || !handles(target)) {
            return data;
        }
        try {
            byte[] out = patch(target, data);
            System.out.println("[VoltzVanillaParachest] " + (out != data ? "patched " : "could not patch ") + target);
            return out;
        } catch (Throwable t) {
            System.out.println("[VoltzVanillaParachest] transform failed, " + target + " stays stock: " + t);
            return data;
        }
    }

    /** True for the three Galacticraft classes this transformer rewrites. */
    public static boolean handles(String className) {
        return ENTITY.equals(className) || TILE.equals(className) || BLOCK.equals(className);
    }

    /**
     * Rewrites one of the classes handles() accepts. Returns the same array, unchanged, when the
     * target method is not found. Public so a shading mod can call it from its own transformer.
     */
    public static byte[] patch(String className, byte[] data) {
        ClassNode cn = new ClassNode();
        new ClassReader(data).accept(cn, ClassReader.SKIP_FRAMES);
        boolean applied = false;
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            InsnList g = new InsnList();
            LabelNode stock = new LabelNode();
            if (ENTITY.equals(className) && m.name.equals("placeChest") && m.desc.equals("(III)Z")) {
                g.add(new VarInsnNode(Opcodes.ALOAD, 0));
                g.add(new VarInsnNode(Opcodes.ILOAD, 1));
                g.add(new VarInsnNode(Opcodes.ILOAD, 2));
                g.add(new VarInsnNode(Opcodes.ILOAD, 3));
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "place", "(Ljava/lang/Object;III)Z"));
                g.add(new JumpInsnNode(Opcodes.IFEQ, stock));
                g.add(new InsnNode(Opcodes.ICONST_1));
                g.add(new InsnNode(Opcodes.IRETURN));
            } else if (TILE.equals(className) && m.desc.equals("()V") && isName(m.name, "h", "func_70316_g", "updateEntity")) {
                g.add(new VarInsnNode(Opcodes.ALOAD, 0));
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "convert", "(Ljava/lang/Object;)Z"));
                g.add(new JumpInsnNode(Opcodes.IFEQ, stock));
                g.add(new InsnNode(Opcodes.RETURN));
            } else if (BLOCK.equals(className) && m.desc.endsWith(";III)Z") && Type.getArgumentTypes(m.desc).length == 4
                    && isName(m.name, "c", "func_71930_b", "canPlaceBlockAt")) {
                g.add(new VarInsnNode(Opcodes.ALOAD, 1));
                g.add(new VarInsnNode(Opcodes.ILOAD, 2));
                g.add(new VarInsnNode(Opcodes.ILOAD, 3));
                g.add(new VarInsnNode(Opcodes.ILOAD, 4));
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "canPlaceAt", "(Ljava/lang/Object;III)Z"));
                g.add(new JumpInsnNode(Opcodes.IFNE, stock));
                g.add(new InsnNode(Opcodes.ICONST_0));
                g.add(new InsnNode(Opcodes.IRETURN));
            } else {
                continue;
            }
            g.add(stock);
            m.instructions.insert(g);
            if (m.maxStack < 4) m.maxStack = 4;
            applied = true;
        }
        if (!applied) return data;
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static boolean isName(String name, String obf, String srg, String mcp) {
        return name.equals(obf) || name.equals(srg) || name.equals(mcp);
    }

    /** offline check: java ... ParachestTransformer <Galacticraft jar> <out dir> */
    public static void main(String[] args) throws Exception {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(args[0]);
        for (String cls : new String[] { ENTITY, TILE, BLOCK }) {
            java.util.zip.ZipEntry ze = zf.getEntry(cls.replace('.', '/') + ".class");
            java.io.InputStream is = zf.getInputStream(ze);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] in = bos.toByteArray();
            byte[] out = patch(cls, in);
            System.out.println(cls + " applied=" + (out != in) + " bytesIn=" + in.length + " bytesOut=" + out.length);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(args[1], cls.substring(cls.lastIndexOf('.') + 1) + ".class"));
            fo.write(out);
            fo.close();
        }
        zf.close();
    }
}
