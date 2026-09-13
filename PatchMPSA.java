import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - MPS Addons (MPSA 0.2.3) patches.
 *
 *   magnet   andrew.powersuits.tick.CommonTickHandler.updateMagneticPlayer
 *
 * The Magnet module only moves items in ClientTickHandler. Item entity positions are
 * server authoritative, so on a dedicated server the client's motion is overwritten by
 * the next EntityTracker update and nothing moves - while MagnetModule.onPlayerTickActive
 * keeps draining power every 20 ticks. It works in singleplayer only because the client
 * and the integrated server share one world object.
 *
 * The server half already walks every nearby item and computes dx, dz and the distance;
 * it just never uses them for anything except a pickup check at range 1.0. This splices
 * the motion assignment in just before that check, so the pull happens server-side and
 * vanilla's tracker syncs it to every client.
 *
 * usage: PatchMPSA <in.jar> <out.jar> <VoltzMagnetConfig.class>
 */
public class PatchMPSA {

    static final String TICK_CLASS  = "andrew/powersuits/tick/CommonTickHandler";
    static final String PROXY_CLASS = "andrew/powersuits/common/CommonProxy";
    static final String CFG_CLASS   = "andrew/powersuits/VoltzMagnetConfig";

    static final String ENTITY_ITEM = "net/minecraft/entity/item/EntityItem";
    static final String PLAYER      = "net/minecraft/entity/player/EntityPlayer";
    static final String POS_Y       = "field_70163_u";
    static final String MOTION_X    = "field_70159_w";
    static final String MOTION_Y    = "field_70181_x";
    static final String MOTION_Z    = "field_70179_y";
    static final String ON_COLLIDE  = "func_70100_b_";

    // locals inside updateMagneticPlayer, read off the stock bytecode
    static final int L_PLAYER = 1;
    static final int L_ITEM   = 6;
    static final int L_DX     = 7;
    static final int L_DZ     = 9;
    static final int L_DIST   = 11;

    static boolean hitMagnet, hitInit;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: PatchMPSA <in.jar> <out.jar> <VoltzMagnetConfig.class>");
            System.exit(2);
        }
        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(args[0]);
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) { out.put(ze.getName(), null); continue; }
            byte[] d = readAll(zf.getInputStream(ze));
            String n = ze.getName();
            if (n.equals(TICK_CLASS + ".class"))  d = patchMagnet(d);
            if (n.equals(PROXY_CLASS + ".class")) d = patchInitHook(d);
            out.put(n, d);
        }
        zf.close();
        injectHelper(out, CFG_CLASS, args[2]);

        if (!hitMagnet) throw new IllegalStateException("magnet patch did not apply");
        if (!hitInit)   throw new IllegalStateException("config init hook did not apply");

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])));
        for (Map.Entry<String, byte[]> en : out.entrySet()) {
            zos.putNextEntry(new ZipEntry(en.getKey()));
            if (en.getValue() != null) zos.write(en.getValue());
            zos.closeEntry();
        }
        zos.close();
        System.out.println("OK  wrote " + args[1] + "  [magnet]");
    }

    /**
     * Insert, immediately before the existing `if (dist < 1.0) onCollideWithPlayer` check:
     *
     *   item.motionX = VoltzMagnetConfig.pull(dx, dist);
     *   item.motionY = VoltzMagnetConfig.pull(player.posY - item.posY, dist);
     *   item.motionZ = VoltzMagnetConfig.pull(dz, dist);
     *
     * dx, dz and dist are already in locals 7, 9 and 11. The pickup check is left alone,
     * so an item pulled inside range 1.0 is still collected exactly as before.
     *
     * Anchored on the onCollideWithPlayer call rather than a raw offset, so it fails
     * loudly if the method ever changes shape.
     */
    static byte[] patchMagnet(byte[] in) {
        ClassNode cn = read(in);
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (!m.name.equals("updateMagneticPlayer")) continue;
            MethodInsnNode collide = null;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i instanceof MethodInsnNode && ((MethodInsnNode) i).name.equals(ON_COLLIDE))
                    collide = (MethodInsnNode) i;
            }
            if (collide == null) continue;
            // walk back to the start of the `dload dist / dconst_1 / dcmpg / ifge` test
            AbstractInsnNode at = collide;
            while (at != null && !(at instanceof VarInsnNode
                    && at.getOpcode() == Opcodes.DLOAD
                    && ((VarInsnNode) at).var == L_DIST
                    && at.getNext() != null
                    && at.getNext().getOpcode() == Opcodes.DCONST_1)) {
                at = at.getPrevious();
            }
            if (at == null) throw new IllegalStateException("pickup check not found");

            InsnList add = new InsnList();
            // motionX = pull(dx, dist)
            add.add(new VarInsnNode(Opcodes.ALOAD, L_ITEM));
            add.add(new VarInsnNode(Opcodes.DLOAD, L_DX));
            add.add(new VarInsnNode(Opcodes.DLOAD, L_DIST));
            add.add(pull());
            add.add(new FieldInsnNode(Opcodes.PUTFIELD, ENTITY_ITEM, MOTION_X, "D"));
            // motionY = pull(player.posY - item.posY, dist)
            add.add(new VarInsnNode(Opcodes.ALOAD, L_ITEM));
            add.add(new VarInsnNode(Opcodes.ALOAD, L_PLAYER));
            add.add(new FieldInsnNode(Opcodes.GETFIELD, PLAYER, POS_Y, "D"));
            add.add(new VarInsnNode(Opcodes.ALOAD, L_ITEM));
            add.add(new FieldInsnNode(Opcodes.GETFIELD, ENTITY_ITEM, POS_Y, "D"));
            add.add(new InsnNode(Opcodes.DSUB));
            add.add(new VarInsnNode(Opcodes.DLOAD, L_DIST));
            add.add(pull());
            add.add(new FieldInsnNode(Opcodes.PUTFIELD, ENTITY_ITEM, MOTION_Y, "D"));
            // motionZ = pull(dz, dist)
            add.add(new VarInsnNode(Opcodes.ALOAD, L_ITEM));
            add.add(new VarInsnNode(Opcodes.DLOAD, L_DZ));
            add.add(new VarInsnNode(Opcodes.DLOAD, L_DIST));
            add.add(pull());
            add.add(new FieldInsnNode(Opcodes.PUTFIELD, ENTITY_ITEM, MOTION_Z, "D"));

            m.instructions.insertBefore(at, add);
            m.maxStack = Math.max(m.maxStack, 8);
            hitMagnet = true;
        }
        return write(cn);
    }

    static MethodInsnNode pull() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "pull", "(DD)D", false);
    }

    /** Load the config at startup by calling VoltzMagnetConfig.init() from CommonProxy. */
    static byte[] patchInitHook(byte[] in) {
        ClassNode cn = read(in);
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (!m.name.equals("registerHandlers")) continue;
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

    static ClassNode read(byte[] b) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
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
