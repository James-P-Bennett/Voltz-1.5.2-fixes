import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - immibis microblocks patches.
 *
 * Run against both immibis-microblocks-55.0.7.jar (coremods) and immibis-core-55.1.6.jar
 * (mods): they ship the same BlockMultipartBase and either copy can win the class load.
 * Only the microblocks jar carries PacketMicroblockPlace, so `placereach` is a no-op on the
 * core jar and its expected site count is driven by what the jar actually contains.
 *
 *   dropstatic   BlockMultipartBase.lastDrop   per world thread instead of one global slot,
 *                so two dimensions breaking parts at once cannot swap each other's drops
 *   placereach   PacketMicroblockPlace.onReceived   the placement has to be in a loaded chunk
 *                within the player's reach
 *
 * usage: PatchMicro <in.jar> <out.jar> <patch>[,<patch>...] <VoltzMicro.class>
 */
public class PatchMicro {

    static final String BASE   = "mods/immibis/core/api/multipart/util/BlockMultipartBase";
    static final String HELPER = "mods/immibis/core/api/multipart/util/VoltzMicro";
    static final String PACKET = "mods/immibis/microblocks/PacketMicroblockPlace";
    static final String ITEM   = "mods/immibis/microblocks/ItemMicroblock";
    static final String LIST   = "Ljava/util/List;";
    static final String OBJ    = "Ljava/lang/Object;";
    static final String PLACE_DESC =
            "(Lnet/minecraft/world/World;IIILmods/immibis/microblocks/api/EnumPosition;"
            + "Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/EntityPlayer;I)Z";

    static final List<String> KNOWN = Arrays.asList("dropstatic", "placereach");

    static final Set<String> selected = new HashSet<String>();
    static final Map<String, Integer> hits = new LinkedHashMap<String, Integer>();
    /** which target classes this jar actually contains */
    static boolean sawBase, sawPacket;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchMicro <in.jar> <out.jar> <patches> <VoltzMicro.class>");
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
        if (!sawBase && !sawPacket) {
            throw new IllegalStateException(args[0] + ": neither BlockMultipartBase nor "
                    + "PacketMicroblockPlace is in this jar");
        }
        out.put(HELPER + ".class", readAll(new FileInputStream(args[3])));

        if (selected.contains("dropstatic") && sawBase) expect("dropstatic:lastDrop", 6);
        if (selected.contains("placereach") && sawPacket) expect("placereach:placeInBlock", 1);

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])));
        for (Map.Entry<String, byte[]> en : out.entrySet()) {
            zos.putNextEntry(new ZipEntry(en.getKey()));
            if (en.getValue() != null) zos.write(en.getValue());
            zos.closeEntry();
        }
        zos.close();
        System.out.println("OK  wrote " + args[1] + "  [" + args[2]
                + (sawBase ? "" : " - no BlockMultipartBase")
                + (sawPacket ? "" : " - no PacketMicroblockPlace") + "]");
    }

    static byte[] patchClass(String cls, byte[] d) {
        if (cls.equals(BASE)) {
            sawBase = true;
            return selected.contains("dropstatic") ? patchLastDrop(d) : d;
        }
        if (cls.equals(PACKET)) {
            sawPacket = true;
            return selected.contains("placereach") ? patchPlace(d) : d;
        }
        return d;
    }

    /** Every lastDrop read/write becomes a helper call with the same stack shape. */
    static byte[] patchLastDrop(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (!(i instanceof FieldInsnNode)) continue;
                FieldInsnNode fi = (FieldInsnNode) i;
                if (!fi.owner.equals(BASE) || !fi.name.equals("lastDrop")) continue;
                if (fi.getOpcode() == Opcodes.GETSTATIC) {
                    m.instructions.set(fi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER,
                            "getLastDrop", "()" + LIST, false));
                } else if (fi.getOpcode() == Opcodes.PUTSTATIC) {
                    m.instructions.set(fi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER,
                            "setLastDrop", "(" + LIST + ")V", false));
                } else {
                    continue;
                }
                hit("dropstatic:lastDrop");
            }
        }
        return write(cn);
    }

    static byte[] patchPlace(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("onReceived")) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.owner.equals(ITEM) || !mi.name.equals("placeInBlockWithBukkitEvent")
                        || !mi.desc.equals(PLACE_DESC)) continue;
                m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "placeGuarded",
                        "(" + OBJ + OBJ + "III" + OBJ + OBJ + OBJ + "I)Z", false));
                hit("placereach:placeInBlock");
            }
        }
        return write(cn);
    }

    // ----------------------------------------------------------------- plumbing

    static void expect(String site, int n) {
        int got = hits.containsKey(site) ? hits.get(site) : 0;
        if (got != n)
            throw new IllegalStateException(site + ": expected " + n + " site(s), found " + got);
    }

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
