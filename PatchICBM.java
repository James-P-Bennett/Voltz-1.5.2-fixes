import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - ICBM Explosion 1.2.1.172 patches.
 *
 *   redmatter   ExHongSu.doBaoZha         return false once callCount reaches
 *                                         VoltzICBM.redMatterMaxTicks
 *   sonic       ExShengBuo / ExChaoShengBuo
 *                 baoZhaQian              dataList1.add(v)  -> VoltzICBM.addUnique(list, v)
 *                 doBaoZha                world.spawnEntityInWorld(EFeiBlock)
 *                                           -> VoltzICBM.spawnFlyingBlock(world, e, source)
 *
 * Red matter's doBaoZha returns true unconditionally, so the black hole never ends. It is
 * saved with the chunk and rescans a radius-35 sphere every tick forever.
 *
 * The sonic and hypersonic ray march adds a position every 0.3 blocks, so each block
 * lands in dataList1 several times over, and every block within about 7 of the centre
 * becomes a flying block entity.
 *
 * usage: PatchICBM <in.jar> <out.jar> <patch>[,<patch>...] <VoltzICBM.class>
 */
public class PatchICBM {

    static final String EX         = "icbm/zhapin/zhapin/ex/";
    static final String RED_CLASS  = EX + "ExHongSu";
    static final String SONIC      = EX + "ExShengBuo";
    static final String HYPERSONIC = EX + "ExChaoShengBuo";
    static final String MAIN_CLASS = "icbm/zhapin/ZhuYaoZhaPin";
    static final String CFG_CLASS  = "icbm/zhapin/VoltzICBM";

    static final String WORLD      = "net/minecraft/world/World";
    static final String SPAWN      = "func_72838_d";   // World.spawnEntityInWorld
    static final String PRE_DESC   =
            "(Lnet/minecraft/world/World;Luniversalelectricity/core/vector/Vector3;Lnet/minecraft/entity/Entity;)V";
    static final String DO_DESC    =
            "(Lnet/minecraft/world/World;Luniversalelectricity/core/vector/Vector3;Lnet/minecraft/entity/Entity;II)Z";

    // locals of doBaoZha(World, Vector3, Entity explosionSource, int metadata, int callCount)
    static final int L_SOURCE    = 3;
    static final int L_CALLCOUNT = 5;

    static boolean doRed, doSonic;
    static boolean hitRed, hitInit;
    static final Set<String> sonicAdds = new HashSet<String>();
    static final Set<String> sonicSpawns = new HashSet<String>();

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchICBM <in.jar> <out.jar> <patches> <VoltzICBM.class>");
            System.err.println("patches: redmatter,sonic");
            System.exit(2);
        }
        for (String p : args[2].split(",")) {
            p = p.trim();
            if (p.equals("redmatter")) doRed = true;
            else if (p.equals("sonic")) doSonic = true;
            else throw new IllegalArgumentException("unknown patch: " + p);
        }

        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(args[0]);
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) { out.put(ze.getName(), null); continue; }
            byte[] d = readAll(zf.getInputStream(ze));
            String n = ze.getName();
            if (doRed && n.equals(RED_CLASS + ".class"))    d = patchRedMatter(d);
            if (doSonic && n.equals(SONIC + ".class"))      d = patchSonic(d, SONIC);
            if (doSonic && n.equals(HYPERSONIC + ".class")) d = patchSonic(d, HYPERSONIC);
            if (n.equals(MAIN_CLASS + ".class"))            d = patchInitHook(d);
            out.put(n, d);
        }
        zf.close();
        injectHelper(out, CFG_CLASS, args[3]);

        if (doRed && !hitRed) throw new IllegalStateException("redmatter patch did not apply");
        if (doSonic) {
            for (String c : new String[] { SONIC, HYPERSONIC }) {
                if (!sonicAdds.contains(c))   throw new IllegalStateException("sonic dedupe did not apply to " + c);
                if (!sonicSpawns.contains(c)) throw new IllegalStateException("sonic spawn cap did not apply to " + c);
            }
        }
        if (!hitInit) throw new IllegalStateException("config init hook did not apply");

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])));
        for (Map.Entry<String, byte[]> en : out.entrySet()) {
            zos.putNextEntry(new ZipEntry(en.getKey()));
            if (en.getValue() != null) zos.write(en.getValue());
            zos.closeEntry();
        }
        zos.close();
        System.out.println("OK  wrote " + args[1] + "  [" + args[2] + "]");
    }

    /**
     * Prepend to ExHongSu.doBaoZha:
     *
     *     if (VoltzICBM.redMatterExpired(callCount)) return false;
     *
     * EZhaPin treats false as "finished": it calls baoZhaHou and kills itself. callCount is
     * EZhaPin.jiaoShuMu, which is written to NBT, so a black hole already older than the
     * limit dies on its first tick after the chunk loads.
     */
    static byte[] patchRedMatter(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("doBaoZha") || !m.desc.equals(DO_DESC)) continue;
            LabelNode carryOn = new LabelNode();
            InsnList g = new InsnList();
            g.add(new VarInsnNode(Opcodes.ILOAD, L_CALLCOUNT));
            g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "redMatterExpired", "(I)Z", false));
            g.add(new JumpInsnNode(Opcodes.IFEQ, carryOn));
            g.add(new InsnNode(Opcodes.ICONST_0));
            g.add(new InsnNode(Opcodes.IRETURN));
            g.add(carryOn);
            m.instructions.insert(g);
            m.maxStack = Math.max(m.maxStack, 2);
            hitRed = true;
        }
        return write(cn);
    }

    /**
     * baoZhaQian: the single List.add into dataList1 becomes VoltzICBM.addUnique, which
     * drops repeat entries for a block already queued. Same stack shape, same boolean.
     *
     * doBaoZha: the single spawnEntityInWorld call (the flying block) gets the explosion
     * source pushed as a third argument and becomes VoltzICBM.spawnFlyingBlock, which
     * enforces the per-explosion cap and otherwise spawns exactly as before.
     */
    static byte[] patchSonic(byte[] in, String owner) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (m.name.equals("baoZhaQian") && m.desc.equals(PRE_DESC)) {
                int hits = 0;
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
                    MethodInsnNode mi = (MethodInsnNode) i;
                    if (!mi.owner.equals("java/util/List") || !mi.name.equals("add")
                            || !mi.desc.equals("(Ljava/lang/Object;)Z")) continue;
                    m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS,
                            "addUnique", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
                    hits++;
                }
                if (hits != 1) throw new IllegalStateException(owner + ".baoZhaQian: expected 1 List.add, found " + hits);
                sonicAdds.add(owner);
            }
            if (m.name.equals("doBaoZha") && m.desc.equals(DO_DESC)) {
                int hits = 0;
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                    MethodInsnNode mi = (MethodInsnNode) i;
                    if (!mi.owner.equals(WORLD) || !mi.name.equals(SPAWN)) continue;
                    m.instructions.insertBefore(mi, new VarInsnNode(Opcodes.ALOAD, L_SOURCE));
                    m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS,
                            "spawnFlyingBlock",
                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z", false));
                    hits++;
                }
                if (hits != 1) throw new IllegalStateException(owner + ".doBaoZha: expected 1 spawn, found " + hits);
                m.maxStack = m.maxStack + 1;
                sonicSpawns.add(owner);
            }
        }
        return write(cn);
    }

    /** Load config/VoltzFixes-ICBM.cfg at startup by calling VoltzICBM.init() from preInit. */
    static byte[] patchInitHook(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("preInit")) continue;
            m.instructions.insert(
                    new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "init", "()V", false));
            m.maxStack = Math.max(m.maxStack, 1);
            hitInit = true;
        }
        return write(cn);
    }

    /** inject a helper class plus any inner classes compiled alongside it */
    static void injectHelper(Map<String, byte[]> out, String internalName, String classFile)
            throws IOException {
        out.put(internalName + ".class", readAll(new FileInputStream(classFile)));
        File f = new File(classFile);
        File dir = f.getParentFile();
        String base = f.getName().substring(0, f.getName().length() - ".class".length());
        File[] siblings = dir == null ? null : dir.listFiles();
        if (siblings == null) return;
        String pkg = internalName.contains("/")
                ? internalName.substring(0, internalName.lastIndexOf('/') + 1) : "";
        for (File s : siblings) {
            String n = s.getName();
            if (!n.startsWith(base + "$") || !n.endsWith(".class")) continue;
            out.put(pkg + n, readAll(new FileInputStream(s)));
            System.out.println("    + inner class " + pkg + n);
        }
    }

    /**
     * Frames are dropped on read. These are version 50 classes, so the JVM verifies them
     * by type inference when there is no StackMapTable, and the inserted jumps never have
     * to be described by a stale one.
     */
    static ClassNode read(byte[] b) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, ClassReader.SKIP_FRAMES);
        return cn;
    }

    static byte[] write(ClassNode cn) {
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
