import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - Atomic Science v0.6.2.117 patches.
 *
 * Every patch routes through atomicscience.fanwusu.VoltzFixConfig, which is injected into
 * the jar and loaded from ZhuYao.preInit, so all four can be toggled in
 * config/VoltzFixes.cfg without rebuilding.
 *
 *   assemblerwear   TGouCheng.yong()  wear loop bound 5 -> VoltzFixConfig.assemblerSlots
 *   syncspawn       TJiaSuQi          spawn gate ticks  -> VoltzFixConfig.spawnClock(world, ticks)
 *   noblastdamage   EWuSu.explode()   isSmoking arg     -> VoltzFixConfig.blastDamage
 *   plasma          BDengLiZiTi       setTickRandomly(VoltzFixConfig.plasmaDecay)
 *
 * usage: PatchAS <in.jar> <out.jar> <patch>[,<patch>...] <VoltzFixConfig.class>
 */
public class PatchAS {

    static final String PLASMA_CLASS    = "atomicscience/hecheng/BDengLiZiTi";
    static final String PARTICLE_CLASS  = "atomicscience/fanwusu/EWuSu";
    static final String ACCEL_CLASS     = "atomicscience/fanwusu/TJiaSuQi";
    static final String ASSEMBLER_CLASS = "atomicscience/TGouCheng";
    static final String MAIN_CLASS      = "atomicscience/ZhuYao";
    static final String CFG_CLASS       = "atomicscience/fanwusu/VoltzFixConfig";

    static final String SET_TICK_RANDOMLY = "func_71907_b";   // Block.setTickRandomly
    static final String STR_DESC          = "(Z)Lnet/minecraft/block/Block;";
    static final String CREATE_EXPLOSION  = "func_72876_a";   // World.createExplosion
    static final String CE_DESC = "(Lnet/minecraft/entity/Entity;DDDFZ)Lnet/minecraft/world/Explosion;";
    static final String WORLD_FIELD       = "field_70331_k";  // TileEntity.worldObj
    static final String WORLD_TYPE        = "Lnet/minecraft/world/World;";
    static final String TOTAL_WORLD_TIME  = "func_82737_E";   // World.getWorldTotalTime, monotonic

    static boolean doPlasma, doBlast, doSync, doWear;
    static boolean hitPlasma, hitBlast, hitSync, hitInit;
    static int wearHits;
    static String cfgClassFile;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchAS <in.jar> <out.jar> <patches> <VoltzFixConfig.class>");
            System.err.println("patches: assemblerwear,syncspawn,noblastdamage,plasma");
            System.exit(2);
        }
        for (String p : args[2].split(",")) {
            p = p.trim();
            if (p.equals("plasma")) doPlasma = true;
            else if (p.equals("noblastdamage")) doBlast = true;
            else if (p.equals("syncspawn")) doSync = true;
            else if (p.equals("assemblerwear")) doWear = true;
            else throw new IllegalArgumentException("unknown patch: " + p);
        }
        cfgClassFile = args[3];

        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(args[0]);
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) { out.put(ze.getName(), null); continue; }
            byte[] d = readAll(zf.getInputStream(ze));
            String n = ze.getName();
            if (doPlasma && n.equals(PLASMA_CLASS + ".class"))    d = patchPlasma(d);
            if (doBlast  && n.equals(PARTICLE_CLASS + ".class"))  d = patchBlast(d);
            if (doSync   && n.equals(ACCEL_CLASS + ".class"))     d = patchSync(d);
            if (doWear   && n.equals(ASSEMBLER_CLASS + ".class")) d = patchWear(d);
            if (n.equals(MAIN_CLASS + ".class"))                  d = patchInitHook(d);
            out.put(n, d);
        }
        zf.close();

        // the config holder is always injected - every patch reads it
        injectHelper(out, CFG_CLASS, cfgClassFile);

        if (doPlasma && !hitPlasma) throw new IllegalStateException("plasma patch did not apply");
        if (doBlast  && !hitBlast)  throw new IllegalStateException("noblastdamage patch did not apply");
        if (doSync   && !hitSync)   throw new IllegalStateException("syncspawn patch did not apply");
        if (doWear   && wearHits != 1)
            throw new IllegalStateException("assemblerwear expected exactly 1 site, found " + wearHits);
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
     * Atomic Assembler off-by-one: TGouCheng.yong() checks all six cell slots for presence
     * but its wear loop is `for (i = 0; i < 5; i++)`, so slot 5's cell is never damaged and
     * lasts forever.
     *
     * The bound becomes VoltzFixConfig.assemblerSlots (6 fixed, 5 stock). Targeted by the
     * ICONST_5 immediately followed by IF_ICMPGE inside yong(); the build fails unless
     * exactly one such site exists.
     */
    static byte[] patchWear(byte[] in) {
        ClassNode cn = read(in);
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (!m.name.equals("yong") || !m.desc.equals("()V")) continue;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.ICONST_5) continue;
                AbstractInsnNode nx = skip(insn.getNext());
                if (nx == null || nx.getOpcode() != Opcodes.IF_ICMPGE) continue;
                m.instructions.set(insn,
                        new FieldInsnNode(Opcodes.GETSTATIC, CFG_CLASS, "assemblerSlots", "I"));
                wearHits++;
            }
        }
        return write(cn);
    }

    /**
     * TJiaSuQi.ticks starts at 0 when the TileEntity is CONSTRUCTED and the spawn gate is
     * ticks % 20 == 0, so accelerators placed at different moments carry a permanent phase
     * offset and only a world reload resyncs them.
     *
     * Replaces the counter read with VoltzFixConfig.spawnClock(worldTotalTime, ticks),
     * which returns world time when enabled and the original counter when not.
     * getWorldTotalTime is monotonic and is NOT moved by /time set.
     *
     * Only the spawn gate is touched - identified by the LDC 20L that follows it - so the
     * unrelated `ticks % 5` packet-send gate is left alone.
     */
    static byte[] patchSync(byte[] in) {
        ClassNode cn = read(in);
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            String worldOwner = null;
            for (AbstractInsnNode i : m.instructions.toArray())
                if (i instanceof FieldInsnNode && ((FieldInsnNode) i).name.equals(WORLD_FIELD))
                    worldOwner = ((FieldInsnNode) i).owner;
            if (worldOwner == null) continue;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof FieldInsnNode)) continue;
                FieldInsnNode f = (FieldInsnNode) insn;
                if (f.getOpcode() != Opcodes.GETFIELD
                        || !f.name.equals("ticks") || !f.desc.equals("J")) continue;
                AbstractInsnNode nx = skip(f.getNext());
                if (!(nx instanceof LdcInsnNode) || !Long.valueOf(20L).equals(((LdcInsnNode) nx).cst))
                    continue;
                // the stack here holds `this`, from the aload_0 that preceded this getfield
                InsnList rep = new InsnList();
                rep.add(new FieldInsnNode(Opcodes.GETFIELD, worldOwner, WORLD_FIELD, WORLD_TYPE));
                rep.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/World",
                                           TOTAL_WORLD_TIME, "()J", false));
                rep.add(new VarInsnNode(Opcodes.ALOAD, 0));
                rep.add(new FieldInsnNode(Opcodes.GETFIELD, f.owner, "ticks", "J"));
                rep.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS,
                                           "spawnClock", "(JJ)J", false));
                m.instructions.insert(f, rep);
                m.instructions.remove(f);
                hitSync = true;
            }
            m.maxStack = Math.max(m.maxStack, 6);
        }
        return write(cn);
    }

    /**
     * World.createExplosion(e,x,y,z,size,flag) routes flag to isSmoking, and
     * Explosion.doExplosionB gates the entire block-destruction loop on it, while entity
     * damage and knockback happen in doExplosionA which runs regardless.
     *
     * The flag becomes VoltzFixConfig.blastDamage (false by default = no block damage,
     * knockback intact). There is exactly one createExplosion call site in EWuSu.
     */
    static byte[] patchBlast(byte[] in) {
        ClassNode cn = read(in);
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (!m.name.equals("explode") || !m.desc.equals("()V")) continue;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode mi = (MethodInsnNode) insn;
                if (!mi.name.equals(CREATE_EXPLOSION) || !mi.desc.equals(CE_DESC)) continue;
                // walk back to the ICONST_1 supplying the last (isSmoking) argument.
                // NOT the first ICONST_1 in the method - that one is `hitParticle = true`.
                AbstractInsnNode p = mi.getPrevious();
                while (p != null && p.getOpcode() != Opcodes.ICONST_1) p = p.getPrevious();
                if (p == null) throw new IllegalStateException("isSmoking ICONST_1 not found");
                m.instructions.set(p,
                        new FieldInsnNode(Opcodes.GETSTATIC, CFG_CLASS, "blastDamage", "Z"));
                hitBlast = true;
            }
        }
        return write(cn);
    }

    /**
     * Plasma gets exactly one decay tick, scheduled in onBlockAdded at metadata*5, and
     * nothing re-arms it if that entry is lost. Random ticks are re-derived from the chunk
     * every tick and cannot be lost, and updateTick unconditionally converts plasma to
     * fire, so an orphan still dies.
     *
     * Appends setTickRandomly(VoltzFixConfig.plasmaDecay) to the constructor.
     */
    static byte[] patchPlasma(byte[] in) {
        ClassNode cn = read(in);
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (!m.name.equals("<init>") || !m.desc.equals("(I)V")) continue;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.RETURN) continue;
                InsnList add = new InsnList();
                add.add(new VarInsnNode(Opcodes.ALOAD, 0));
                add.add(new FieldInsnNode(Opcodes.GETSTATIC, CFG_CLASS, "plasmaDecay", "Z"));
                // owner = the mod's own class, matching how the existing inherited
                // func_71900_a call in this same constructor is emitted
                add.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, PLASMA_CLASS,
                                           SET_TICK_RANDOMLY, STR_DESC, false));
                add.add(new InsnNode(Opcodes.POP));
                m.instructions.insertBefore(insn, add);
                hitPlasma = true;
            }
            m.maxStack = Math.max(m.maxStack, 2);
        }
        return write(cn);
    }

    /** Load the config at startup by calling VoltzFixConfig.init() from ZhuYao.preInit. */
    static byte[] patchInitHook(byte[] in) {
        ClassNode cn = read(in);
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (!m.name.equals("preInit")) continue;
            m.instructions.insert(
                    new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "init", "()V", false));
            m.maxStack = Math.max(m.maxStack, 1);
            hitInit = true;
        }
        return write(cn);
    }

    /** next real instruction, skipping labels / line numbers / frames */
    static AbstractInsnNode skip(AbstractInsnNode n) {
        while (n != null && (n instanceof LabelNode || n instanceof LineNumberNode
                || n instanceof FrameNode)) n = n.getNext();
        return n;
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

    static ClassNode read(byte[] b) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        return cn;
    }

    static byte[] write(ClassNode cn) {
        ClassWriter cw = new ClassWriter(0);   // classes are v50, no stack map frames needed
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
