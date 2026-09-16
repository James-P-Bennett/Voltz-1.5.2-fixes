import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - MekanismGenerators 5.5.6 patches.
 *
 *   solarspace   ItemBlockGenerator.placeBlockAt   every position a tall generator's
 *                multiblock is about to claim has to be free first - stock never checked the
 *                block directly above an Advanced Solar Generator and onPlace() overwrote it
 *   boundclear   TileEntityAdvancedSolarGenerator / TileEntityWindTurbine .onBreak
 *                only clear a position that still holds this multiblock's own blocks
 *   metaclamp    BlockGenerator$GeneratorType.getFromMetadata   values()[meta] with an
 *                out-of-range metadata threw while a chunk was loading
 *   particlepkt  PacketElectrolyticSeparatorParticle.read   client coordinates no longer
 *                load the chunk they name
 *
 * usage: PatchMekGen <in.jar> <out.jar> <patch>[,<patch>...] <VoltzMekGen.class>
 */
public class PatchMekGen {

    static final String G = "mekanism/generators/common/";
    static final String HELPER    = G + "VoltzMekGen";
    static final String ITEMBLOCK = G + "ItemBlockGenerator";
    static final String GEN_TYPE  = G + "BlockGenerator$GeneratorType";
    static final String PARTICLE  = G + "network/PacketElectrolyticSeparatorParticle";
    static final String[] BOUNDING_TILES = {
        G + "TileEntityAdvancedSolarGenerator", G + "TileEntityWindTurbine",
    };

    static final String WORLD = "net/minecraft/world/World";
    static final String TILE  = "net/minecraft/tileentity/TileEntity";
    static final String OBJ   = "Ljava/lang/Object;";
    static final String PLACE_DESC =
            "(Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;IIIIFFFI)Z";
    static final String READ_DESC =
            "(Lcom/google/common/io/ByteArrayDataInput;Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;)V";

    static final List<String> KNOWN =
            Arrays.asList("solarspace", "boundclear", "metaclamp", "particlepkt");

    static final Set<String> selected = new HashSet<String>();
    static final Map<String, Integer> hits = new LinkedHashMap<String, Integer>();

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchMekGen <in.jar> <out.jar> <patches> <VoltzMekGen.class>");
            System.err.println("patches: " + KNOWN);
            System.exit(2);
        }
        for (String p : args[2].split(",")) {
            p = p.trim();
            if (!KNOWN.contains(p)) throw new IllegalArgumentException("unknown patch: " + p);
            selected.add(p);
        }

        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(args[0]);
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) { out.put(ze.getName(), null); continue; }
            byte[] d = readAll(zf.getInputStream(ze));
            String n = ze.getName();
            String cls = n.endsWith(".class") ? n.substring(0, n.length() - 6) : null;
            if (cls != null) d = patchClass(cls, d);
            out.put(n, d);
        }
        zf.close();
        out.put(HELPER + ".class", readAll(new FileInputStream(args[3])));

        Map<String, Integer> expected = new LinkedHashMap<String, Integer>();
        expected.put("solarspace:placeBlockAt", 1);
        expected.put("boundclear:setBlockToAir", 8);
        expected.put("metaclamp:getFromMetadata", 1);
        expected.put("particlepkt:getBlockTileEntity", 1);
        for (Map.Entry<String, Integer> en : expected.entrySet()) {
            String patch = en.getKey().substring(0, en.getKey().indexOf(':'));
            if (!selected.contains(patch)) continue;
            int got = hits.containsKey(en.getKey()) ? hits.get(en.getKey()) : 0;
            if (got != en.getValue())
                throw new IllegalStateException(en.getKey() + ": expected " + en.getValue()
                        + " site(s), found " + got);
        }

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])));
        for (Map.Entry<String, byte[]> en : out.entrySet()) {
            zos.putNextEntry(new ZipEntry(en.getKey()));
            if (en.getValue() != null) zos.write(en.getValue());
            zos.closeEntry();
        }
        zos.close();
        System.out.println("OK  wrote " + args[1] + "  [" + args[2] + "]");
    }

    static byte[] patchClass(String cls, byte[] d) {
        if (cls.equals(ITEMBLOCK) && selected.contains("solarspace")) return patchPlace(d);
        if (cls.equals(GEN_TYPE) && selected.contains("metaclamp")) return patchMetaClamp(d);
        if (cls.equals(PARTICLE) && selected.contains("particlepkt")) return patchParticle(d);
        if (selected.contains("boundclear")) {
            for (String t : BOUNDING_TILES) if (cls.equals(t)) return patchOnBreak(d);
        }
        return d;
    }

    /** placeBlockAt gains a leading `if (!VoltzMekGen.canPlace(stack, world, x, y, z)) return false;`. */
    static byte[] patchPlace(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("placeBlockAt") || !m.desc.equals(PLACE_DESC)) continue;
            LabelNode ok = new LabelNode();
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ALOAD, 1));      // ItemStack stack
            pre.add(new VarInsnNode(Opcodes.ALOAD, 3));      // World world
            pre.add(new VarInsnNode(Opcodes.ILOAD, 4));      // x
            pre.add(new VarInsnNode(Opcodes.ILOAD, 5));      // y
            pre.add(new VarInsnNode(Opcodes.ILOAD, 6));      // z
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "canPlace",
                    "(" + OBJ + OBJ + "III)Z", false));
            pre.add(new JumpInsnNode(Opcodes.IFNE, ok));
            pre.add(new InsnNode(Opcodes.ICONST_0));
            pre.add(new InsnNode(Opcodes.IRETURN));
            pre.add(ok);
            m.instructions.insert(pre);
            m.maxStack = Math.max(m.maxStack, 5);
            hit("solarspace:placeBlockAt");
        }
        return write(cn);
    }

    /** getFromMetadata clamps its argument before indexing values(). */
    static byte[] patchMetaClamp(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("getFromMetadata") || !m.desc.equals("(I)L" + GEN_TYPE + ";")) continue;
            InsnList pre = new InsnList();
            pre.add(new VarInsnNode(Opcodes.ILOAD, 0));
            pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "clampMeta", "(I)I", false));
            pre.add(new VarInsnNode(Opcodes.ISTORE, 0));
            m.instructions.insert(pre);
            m.maxStack = Math.max(m.maxStack, 1);
            hit("metaclamp:getFromMetadata");
        }
        return write(cn);
    }

    static byte[] patchParticle(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("read") || !m.desc.equals(READ_DESC)) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.owner.equals(WORLD) || !mi.name.equals("func_72796_p")) continue;
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "loadedTile",
                        "(" + OBJ + "III)" + OBJ, false);
                m.instructions.set(mi, call);
                m.instructions.insert(call, new TypeInsnNode(Opcodes.CHECKCAST, TILE));
                hit("particlepkt:getBlockTileEntity");
            }
        }
        return write(cn);
    }

    static byte[] patchOnBreak(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("onBreak")) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.owner.equals(WORLD) || !mi.name.equals("func_94571_i")) continue;
                m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "clearBound",
                        "(" + OBJ + "III)Z", false));
                hit("boundclear:setBlockToAir");
            }
        }
        return write(cn);
    }

    // ----------------------------------------------------------------- plumbing

    static void hit(String site) {
        Integer n = hits.get(site);
        hits.put(site, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
    }

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
