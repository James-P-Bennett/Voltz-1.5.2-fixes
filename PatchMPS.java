import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - Modular Powersuits 0.7.0-534 patches.
 *
 *   blink   BlinkDriveModule.onRightClick   MusePlayerUtils.teleportEntity -> VoltzMPS.blink
 *
 * Blink Drive teleports to the raw ray hit point without checking the player fits there,
 * which can leave their hitbox inside blocks. The server stops validating movement for a
 * player who starts a move inside a block, so a modified client walks through walls.
 *
 * usage: PatchMPS <in.jar> <out.jar> <patch>[,<patch>...] <VoltzMPS.class>
 */
public class PatchMPS {

    static final String BLINK_CLASS = "net/machinemuse/powersuits/powermodule/movement/BlinkDriveModule";
    static final String HELPER      = "net/machinemuse/powersuits/VoltzMPS";
    static final String UTILS       = "net/machinemuse/utils/MusePlayerUtils";
    static final String TELEPORT_DESC =
            "(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/util/MovingObjectPosition;)V";

    static boolean doBlink;
    static int blinkHits;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchMPS <in.jar> <out.jar> <patches> <VoltzMPS.class>");
            System.err.println("patches: blink");
            System.exit(2);
        }
        for (String p : args[2].split(",")) {
            p = p.trim();
            if (p.equals("blink")) doBlink = true;
            else throw new IllegalArgumentException("unknown patch: " + p);
        }

        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(args[0]);
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) { out.put(ze.getName(), null); continue; }
            byte[] d = readAll(zf.getInputStream(ze));
            String n = ze.getName();
            if (doBlink && n.equals(BLINK_CLASS + ".class")) d = patchBlink(d);
            out.put(n, d);
        }
        zf.close();
        out.put(HELPER + ".class", readAll(new FileInputStream(args[3])));

        if (doBlink && blinkHits != 1)
            throw new IllegalStateException("blink patch: expected 1 teleportEntity call, found " + blinkHits);

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])));
        for (Map.Entry<String, byte[]> en : out.entrySet()) {
            zos.putNextEntry(new ZipEntry(en.getKey()));
            if (en.getValue() != null) zos.write(en.getValue());
            zos.closeEntry();
        }
        zos.close();
        System.out.println("OK  wrote " + args[1] + "  [" + args[2] + "]");
    }

    /** Same arguments, same stack, same void return - only the destination is checked. */
    static byte[] patchBlink(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("onRightClick")) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKESTATIC) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.owner.equals(UTILS) || !mi.name.equals("teleportEntity")
                        || !mi.desc.equals(TELEPORT_DESC)) continue;
                m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "blink",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                blinkHits++;
            }
        }
        return write(cn);
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
