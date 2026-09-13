import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - Atomic Science patches.
 *
 * Patch "plasma"  (atomicscience.hecheng.BDengLiZiTi)
 *   Adds setTickRandomly(true) to the constructor.
 *   Plasma only ever gets one decay tick, scheduled in onBlockAdded at
 *   metadata*5 (35 ticks for reactor-spawned plasma). Nothing re-arms it if
 *   that entry is ever lost, and a stranded plasma block is instant
 *   unconditional death (1,073,741,823 damage, then setDead).
 *   Random ticks are re-derived from the chunk every tick and cannot be lost,
 *   and updateTick unconditionally converts the block to fire, so any orphan
 *   dies on its next random tick. The normal scheduled decay still fires first.
 *
 * Patch "noblastdamage"  (atomicscience.fanwusu.EWuSu)
 *   Flips the last argument of the createExplosion call in explode() to false.
 *     World.createExplosion(e,x,y,z,size,flag)
 *        -> newExplosion(e,x,y,z,size, false /*isFlaming* /, flag /*isSmoking* /)
 *   Explosion.doExplosionB gates the ENTIRE block-destruction loop on isSmoking
 *   (getfield b:Z / ifeq), while entity damage and knockback happen in
 *   doExplosionA, which runs regardless. So this removes all block damage and
 *   block drops while leaving the knockback - which is the strange-matter
 *   production mechanism - completely intact.
 */
public class PatchAS {

    static final String PLASMA_CLASS = "atomicscience/hecheng/BDengLiZiTi";
    static final String PARTICLE_CLASS = "atomicscience/fanwusu/EWuSu";
    static final String SET_TICK_RANDOMLY = "func_71907_b";
    static final String STR_DESC = "(Z)Lnet/minecraft/block/Block;";
    static final String CREATE_EXPLOSION = "func_72876_a";
    static final String CE_DESC = "(Lnet/minecraft/entity/Entity;DDDFZ)Lnet/minecraft/world/Explosion;";
    static final String ACCEL_CLASS = "atomicscience/fanwusu/TJiaSuQi";
    static final String WORLD_FIELD = "field_70331_k";
    static final String WORLD_TYPE = "Lnet/minecraft/world/World;";
    static final String TOTAL_WORLD_TIME = "func_82737_E";   // World.getWorldTotalTime() - monotonic
    static final String CFG_CLASS = "atomicscience/fanwusu/VoltzFixConfig";
    static final String CFG_FIELD = "blastDamage";
    static final String MAIN_CLASS = "atomicscience/ZhuYao";
    static final String ASSEMBLER_CLASS = "atomicscience/TGouCheng";
    static String cfgClassFile;   // path to the compiled VoltzFixConfig.class to inject

    static boolean doPlasma, doBlast, doSync, doWear;
    static boolean hitPlasma, hitBlast, hitSync, hitInit;
    static int wearHits;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: PatchAS <in.jar> <out.jar> <patch>[,<patch>]   patches: plasma,noblastdamage");
            System.exit(2);
        }
        for (String p : args[2].split(",")) {
            if (p.trim().equals("plasma")) doPlasma = true;
            else if (p.trim().equals("noblastdamage")) doBlast = true;
            else if (p.trim().equals("syncspawn")) doSync = true;
            else if (p.trim().equals("assemblerwear")) doWear = true;
            else throw new IllegalArgumentException("unknown patch: " + p);
        }

        if (args.length > 3) cfgClassFile = args[3];
        if (doBlast && cfgClassFile == null)
            throw new IllegalArgumentException("noblastdamage needs the compiled VoltzFixConfig.class as arg 4");

        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(args[0]);
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) { out.put(ze.getName(), null); continue; }
            byte[] d = readAll(zf.getInputStream(ze));
            String n = ze.getName();
            if (doPlasma && n.equals(PLASMA_CLASS + ".class")) d = patchPlasma(d);
            if (doBlast  && n.equals(PARTICLE_CLASS + ".class")) d = patchBlast(d);
            if (doSync   && n.equals(ACCEL_CLASS + ".class")) d = patchSync(d);
            if (doBlast  && n.equals(MAIN_CLASS + ".class")) d = patchInitHook(d);
            if (doWear   && n.equals(ASSEMBLER_CLASS + ".class")) d = patchWear(d);
            out.put(n, d);
        }
        zf.close();

        if (doBlast) {
            FileInputStream fis = new FileInputStream(cfgClassFile);
            out.put(CFG_CLASS + ".class", readAll(fis));
        }

        if (doPlasma && !hitPlasma) throw new IllegalStateException("plasma patch did not apply");
        if (doBlast  && !hitBlast)  throw new IllegalStateException("noblastdamage patch did not apply");
        if (doSync   && !hitSync)   throw new IllegalStateException("syncspawn patch did not apply");
        if (doBlast  && !hitInit)   throw new IllegalStateException("config init hook did not apply");
        if (doWear   && wearHits != 1)
            throw new IllegalStateException("assemblerwear expected exactly 1 site, found " + wearHits);

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])));
        for (Map.Entry<String, byte[]> en : out.entrySet()) {
            zos.putNextEntry(new ZipEntry(en.getKey()));
            if (en.getValue() != null) zos.write(en.getValue());
            zos.closeEntry();
        }
        zos.close();
        System.out.println("OK  wrote " + args[1] + "  [" + args[2] + "]");
    }

    /** append this.setTickRandomly(true) before every RETURN in <init>(I)V */
    static byte[] patchPlasma(byte[] in) {
        ClassNode cn = read(in);
        for (Object _o : cn.methods) {
            MethodNode m = (MethodNode) _o;
            if (!m.name.equals("<init>") || !m.desc.equals("(I)V")) continue;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.RETURN) continue;
                InsnList add = new InsnList();
                add.add(new VarInsnNode(Opcodes.ALOAD, 0));
                add.add(new InsnNode(Opcodes.ICONST_1));
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

    /** flip the isSmoking argument of createExplosion in explode() to false */
    static byte[] patchBlast(byte[] in) {
        ClassNode cn = read(in);
        for (Object _o : cn.methods) {
            MethodNode m = (MethodNode) _o;
            if (!m.name.equals("explode") || !m.desc.equals("()V")) continue;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode mi = (MethodInsnNode) insn;
                if (!mi.name.equals(CREATE_EXPLOSION) || !mi.desc.equals(CE_DESC)) continue;
                // walk back to the ICONST_1 that supplies the last (isSmoking) arg
                AbstractInsnNode p = mi.getPrevious();
                while (p != null && p.getOpcode() != Opcodes.ICONST_1) p = p.getPrevious();
                if (p == null) throw new IllegalStateException("isSmoking ICONST_1 not found");
                // isSmoking = VoltzFixConfig.blastDamage  (false by default = no block damage)
                m.instructions.set(p, new FieldInsnNode(Opcodes.GETSTATIC, CFG_CLASS, CFG_FIELD, "Z"));
                hitBlast = true;
            }
        }
        return write(cn);
    }

    /**
     * Gate particle spawning on world time instead of the per-TileEntity counter.
     *
     * TJiaSuQi.ticks starts at 0 when the TE is CONSTRUCTED, and the spawn gate is
     * ticks % 20 == 0. Two accelerators placed at different moments therefore carry a
     * permanent phase offset, their particles never meet at the midpoint, and the pair
     * produces nothing until a world reload reconstructs both on the same tick.
     * Measured: re-placing one accelerator put it 5 ticks out and more than halved
     * output; a later re-place left them 9 ticks out and every cycle detonated a lone
     * particle with nothing within 4.28 blocks.
     *
     * Swapping the counter for World.getWorldTotalTime() makes every accelerator in the
     * world share one clock, so placement order stops mattering and no restart is ever
     * needed. getWorldTotalTime is monotonic and is NOT moved by /time set.
     *
     * Only the spawn gate is touched: it is identified by the LDC 20L that follows it.
     * The unrelated `ticks % 5` packet-send gate is left alone.
     */
    static byte[] patchSync(byte[] in) {
        ClassNode cn = read(in);
        for (Object _o : cn.methods) {
            MethodNode m = (MethodNode) _o;
            String worldOwner = null;
            for (AbstractInsnNode i : m.instructions.toArray())
                if (i instanceof FieldInsnNode && ((FieldInsnNode) i).name.equals(WORLD_FIELD))
                    worldOwner = ((FieldInsnNode) i).owner;
            if (worldOwner == null) continue;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof FieldInsnNode)) continue;
                FieldInsnNode f = (FieldInsnNode) insn;
                if (f.getOpcode() != Opcodes.GETFIELD || !f.name.equals("ticks") || !f.desc.equals("J")) continue;
                AbstractInsnNode nx = f.getNext();
                while (nx != null && (nx instanceof LabelNode || nx instanceof LineNumberNode || nx instanceof FrameNode))
                    nx = nx.getNext();
                if (!(nx instanceof LdcInsnNode) || !Long.valueOf(20L).equals(((LdcInsnNode) nx).cst)) continue;
                InsnList rep = new InsnList();
                rep.add(new FieldInsnNode(Opcodes.GETFIELD, worldOwner, WORLD_FIELD, WORLD_TYPE));
                rep.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/World",
                                           TOTAL_WORLD_TIME, "()J", false));
                m.instructions.insert(f, rep);
                m.instructions.remove(f);
                hitSync = true;
            }
            m.maxStack = Math.max(m.maxStack, 3);
        }
        return write(cn);
    }

    /** call VoltzFixConfig.init() at the top of ZhuYao.preInit so the .cfg is written at startup */
    static byte[] patchInitHook(byte[] in) {
        ClassNode cn = read(in);
        for (Object _o : cn.methods) {
            MethodNode m = (MethodNode) _o;
            if (!m.name.equals("preInit")) continue;
            m.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "init", "()V", false));
            m.maxStack = Math.max(m.maxStack, 1);
            hitInit = true;
        }
        return write(cn);
    }

    /**
     * Atomic Assembler off-by-one: TGouCheng.yong() checks all six cell slots for
     * presence but its wear loop is `for (i = 0; i < 5; i++)`, so slot 5's strange
     * matter cell is never damaged and lasts forever. Real cost is 5 cells per 64
     * duplications instead of 6.
     *
     * Fix: iconst_5 -> bipush 6 on the loop bound. Targeted by the ICONST_5 that is
     * immediately followed by IF_ICMPGE inside yong(); the build fails unless exactly
     * one such site exists.
     */
    static byte[] patchWear(byte[] in) {
        ClassNode cn = read(in);
        for (Object _o : cn.methods) {
            MethodNode m = (MethodNode) _o;
            if (!m.name.equals("yong") || !m.desc.equals("()V")) continue;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.ICONST_5) continue;
                AbstractInsnNode nx = insn.getNext();
                while (nx != null && (nx instanceof LabelNode || nx instanceof LineNumberNode || nx instanceof FrameNode))
                    nx = nx.getNext();
                if (nx == null || nx.getOpcode() != Opcodes.IF_ICMPGE) continue;
                m.instructions.set(insn, new IntInsnNode(Opcodes.BIPUSH, 6));
                wearHits++;
            }
        }
        return write(cn);
    }

    static ClassNode read(byte[] b) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        return cn;
    }

    static byte[] write(ClassNode cn) {
        ClassWriter cw = new ClassWriter(0);   // class is v50, no stack map frames needed
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
