import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - NotEnoughItems 1.5.2.28 patches.
 *
 *   auth   NEIServerConfig.authenticatePacket   the stock switch names a permission for most
 *          of NEI's server packets and falls through to `return true` for the rest. The
 *          original is kept as authenticatePacket$voltz and a wrapper hands its verdict to
 *          VoltzNEI.auth, which also covers the two packets it forgot: 15 (retune any mob
 *          spawner in the world) and 25 (write a dummy slot).
 *
 * NEI ships against obfuscated vanilla names, so the descriptors here are the jar's own.
 * FML remaps them at load, including in the wrapper this adds.
 *
 * usage: PatchNEI <in.jar> <out.jar> <patch>[,<patch>...] <VoltzNEI.class>
 */
public class PatchNEI {

    static final String CONFIG = "codechicken/nei/NEIServerConfig";
    static final String HELPER = "codechicken/nei/VoltzNEI";
    static final String AUTH   = "authenticatePacket";
    static final String INNER  = AUTH + "$voltz";
    static final String AUTH_DESC = "(Ljc;Lcodechicken/core/packet/PacketCustom;)Z";
    static final String OBJ = "Ljava/lang/Object;";

    static final List<String> KNOWN = Arrays.asList("auth");

    static final Set<String> selected = new HashSet<String>();
    static int authHits;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchNEI <in.jar> <out.jar> <patches> <VoltzNEI.class>");
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
            if (selected.contains("auth") && n.equals(CONFIG + ".class")) d = patchAuth(d);
            out.put(n, d);
        }
        zf.close();
        out.put(HELPER + ".class", readAll(new FileInputStream(args[3])));

        if (selected.contains("auth") && authHits != 1)
            throw new IllegalStateException("auth: expected 1 authenticatePacket, found " + authHits);

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
     * The stock method keeps its body under a private name, and a new authenticatePacket
     * with the original signature forwards through the helper. Straight-line code only, so
     * the class needs no stack map.
     */
    @SuppressWarnings("unchecked")
    static byte[] patchAuth(byte[] in) {
        ClassNode cn = read(in);
        MethodNode target = null;
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (m.name.equals(AUTH) && m.desc.equals(AUTH_DESC)) target = m;
        }
        if (target == null) return in;

        target.name = INNER;
        target.access = (target.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PRIVATE;

        MethodNode wrapper = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                AUTH, AUTH_DESC, null, null);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONFIG, INNER, AUTH_DESC, false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "auth",
                "(Z" + OBJ + OBJ + ")Z", false));
        wrapper.instructions.add(new InsnNode(Opcodes.IRETURN));
        wrapper.maxStack = 3;
        wrapper.maxLocals = 2;
        cn.methods.add(wrapper);
        authHits++;
        return write(cn);
    }

    // ----------------------------------------------------------------- plumbing

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
