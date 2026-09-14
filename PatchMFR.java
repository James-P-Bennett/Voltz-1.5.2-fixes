import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - MineFactoryReloaded 2.6.4 patches.
 *
 *   ghostslot   ServerPacketHandler.onPacketData, packet 19
 *               inventory.setInventorySlotContents(slot, stack)
 *                 -> VoltzMFR.setGhostSlot(inventory, slot, stack, player)
 *
 * Packet 19 writes a size-1 copy of the cursor into any inventory at client-sent
 * coordinates, or empties the slot when the cursor is empty, without taking the item.
 * The write is now allowed only into a ghost slot of the GUI the player has open.
 *
 * usage: PatchMFR <in.jar> <out.jar> <patch>[,<patch>...] <VoltzMFR.class>
 */
public class PatchMFR {

    static final String HANDLER = "powercrystals/minefactoryreloaded/net/ServerPacketHandler";
    static final String HELPER  = "powercrystals/minefactoryreloaded/VoltzMFR";
    static final String ON_PACKET_DESC =
            "(Lnet/minecraft/network/INetworkManager;Lnet/minecraft/network/packet/Packet250CustomPayload;Lcpw/mods/fml/common/network/Player;)V";
    static final int L_PLAYER = 3;

    static boolean doGhost;
    static int ghostHits;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchMFR <in.jar> <out.jar> <patches> <VoltzMFR.class>");
            System.err.println("patches: ghostslot");
            System.exit(2);
        }
        for (String p : args[2].split(",")) {
            p = p.trim();
            if (p.equals("ghostslot")) doGhost = true;
            else throw new IllegalArgumentException("unknown patch: " + p);
        }

        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(args[0]);
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) { out.put(ze.getName(), null); continue; }
            byte[] d = readAll(zf.getInputStream(ze));
            String n = ze.getName();
            if (doGhost && n.equals(HANDLER + ".class")) d = patchGhostSlot(d);
            out.put(n, d);
        }
        zf.close();
        out.put(HELPER + ".class", readAll(new FileInputStream(args[3])));

        if (doGhost && ghostHits != 2)
            throw new IllegalStateException("ghostslot: expected 2 inventory writes, found " + ghostHits);

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
     * The only two IInventory.setInventorySlotContents calls in onPacketData are the packet
     * 19 branch's null and copy writes. Each gets the player pushed as a fourth argument and
     * becomes VoltzMFR.setGhostSlot - same stack otherwise, same void return.
     */
    static byte[] patchGhostSlot(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("onPacketData") || !m.desc.equals(ON_PACKET_DESC)) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.owner.equals("net/minecraft/inventory/IInventory") || !mi.name.equals("func_70299_a")) continue;
                m.instructions.insertBefore(mi, new VarInsnNode(Opcodes.ALOAD, L_PLAYER));
                m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "setGhostSlot",
                        "(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/Object;)V", false));
                ghostHits++;
            }
            m.maxStack = m.maxStack + 1;
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
