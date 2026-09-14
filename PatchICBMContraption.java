import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - ICBM Contraption 1.2.1.172 patches.
 *
 *   camouflage   TYinXing.handlePacketData   ignored on the server
 *                TYinXing.readFromNBT        jiaHaoMa -> VoltzContraption.blockId(jiaHaoMa)
 *   detector     TYinGanQi.handlePacketData  packet refused unless VoltzContraption.detectorPacketAllowed
 *
 * The camouflage block's packet is the server's description packet - disguise block id and
 * metadata, see-through sides, solid - but the server applies it from any client. An id past
 * the end of Block.blocksList is saved and sent to every client, whose block renderer indexes
 * the array with it outside its try and crashes; setting "not solid" removes the wall's
 * collision. The NBT clamp repairs blocks saved with a bad id before the patch.
 *
 * usage: PatchICBMContraption <in.jar> <out.jar> <patch>[,<patch>...] <VoltzContraption.class>
 */
public class PatchICBMContraption {

    static final String CAMO   = "icbm/wanyi/b/TYinXing";
    static final String HELPER = "icbm/wanyi/VoltzContraption";
    static final String DETECTOR = "icbm/wanyi/b/TYinGanQi";
    static final String WORLD  = "net/minecraft/world/World";

    static boolean doCamo, doDetector;
    static boolean hitGuard, hitClamp, hitDetector;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchICBMContraption <in.jar> <out.jar> <patches> <VoltzContraption.class>");
            System.err.println("patches: camouflage,detector");
            System.exit(2);
        }
        for (String p : args[2].split(",")) {
            p = p.trim();
            if (p.equals("camouflage")) doCamo = true;
            else if (p.equals("detector")) doDetector = true;
            else throw new IllegalArgumentException("unknown patch: " + p);
        }

        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(args[0]);
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) { out.put(ze.getName(), null); continue; }
            byte[] d = readAll(zf.getInputStream(ze));
            String n = ze.getName();
            if (doCamo && n.equals(CAMO + ".class")) d = patchCamouflage(d);
            if (doDetector && n.equals(DETECTOR + ".class")) d = patchDetector(d);
            out.put(n, d);
        }
        zf.close();
        out.put(HELPER + ".class", readAll(new FileInputStream(args[3])));

        if (doCamo && !(hitGuard && hitClamp)) throw new IllegalStateException("camouflage patch did not apply");
        if (doDetector && !hitDetector) throw new IllegalStateException("detector patch did not apply");

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
     * handlePacketData gets, at its head,
     *
     *     if (!this.worldObj.isRemote) { VoltzContraption.serverPacketRefused(this, player); return; }
     *
     * and the single `putfield jiaHaoMa` in readFromNBT (func_70307_a) gets the id passed
     * through VoltzContraption.blockId first.
     */
    static byte[] patchCamouflage(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (m.name.equals("handlePacketData")) {
                LabelNode carryOn = new LabelNode();
                InsnList g = new InsnList();
                g.add(new VarInsnNode(Opcodes.ALOAD, 0));
                g.add(new FieldInsnNode(Opcodes.GETFIELD, CAMO, "field_70331_k", "L" + WORLD + ";"));
                g.add(new FieldInsnNode(Opcodes.GETFIELD, WORLD, "field_72995_K", "Z"));   // World.isRemote
                g.add(new JumpInsnNode(Opcodes.IFNE, carryOn));
                g.add(new VarInsnNode(Opcodes.ALOAD, 0));
                g.add(new VarInsnNode(Opcodes.ALOAD, 4));
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "serverPacketRefused",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                g.add(new InsnNode(Opcodes.RETURN));
                g.add(carryOn);
                m.instructions.insert(g);
                m.maxStack = Math.max(m.maxStack, 2);
                hitGuard = true;
            }
            if (m.name.equals("func_70307_a") && m.desc.equals("(Lnet/minecraft/nbt/NBTTagCompound;)V")) {
                int hits = 0;
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i.getOpcode() != Opcodes.PUTFIELD) continue;
                    FieldInsnNode f = (FieldInsnNode) i;
                    if (!f.owner.equals(CAMO) || !f.name.equals("jiaHaoMa")) continue;
                    m.instructions.insertBefore(i, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "blockId", "(I)I", false));
                    hits++;
                }
                if (hits != 1) throw new IllegalStateException("camouflage: expected 1 id load in readFromNBT, found " + hits);
                hitClamp = true;
            }
        }
        return write(cn);
    }

    /**
     * TYinGanQi (proximity detector). Packet 1 is the server's description packet - power,
     * frequency, mode, inversion and range - and the GUI's own packets (-1 open/close, 2 mode,
     * 3 frequency, 4 and 5 range) carry no reach check, so any client can power, retune or
     * invert any detector from anywhere. Right after the packet ID is read, insert
     *
     *     if (!VoltzContraption.detectorPacketAllowed(this, player, id)) return;
     */
    static byte[] patchDetector(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("handlePacketData")) continue;
            AbstractInsnNode store = null;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEINTERFACE || !((MethodInsnNode) i).name.equals("readInt")) continue;
                store = i.getNext();
                while (store != null && store.getOpcode() < 0) store = store.getNext();
                break;
            }
            if (store == null || store.getOpcode() != Opcodes.ISTORE)
                throw new IllegalStateException("detector: no packet ID read in TYinGanQi.handlePacketData");
            LabelNode carryOn = new LabelNode();
            InsnList g = new InsnList();
            g.add(new VarInsnNode(Opcodes.ALOAD, 0));
            g.add(new VarInsnNode(Opcodes.ALOAD, 4));
            g.add(new VarInsnNode(Opcodes.ILOAD, ((VarInsnNode) store).var));
            g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "detectorPacketAllowed",
                    "(Ljava/lang/Object;Ljava/lang/Object;I)Z", false));
            g.add(new JumpInsnNode(Opcodes.IFNE, carryOn));
            g.add(new InsnNode(Opcodes.RETURN));
            g.add(carryOn);
            m.instructions.insert(store, g);
            m.maxStack = Math.max(m.maxStack, 3);
            hitDetector = true;
        }
        return write(cn);
    }

    /** Frames are dropped on read; these are version 50 classes, verified by type inference. */
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
