import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - ICBM Sentry 1.2.1.172 patches.
 *
 *   terminal       TileEntityTerminal.handlePacketData, command packet
 *                    world.getPlayerEntityByName(readUTF())
 *                      -> VoltzSentry.commandSender(world, name, this, player), return if null
 *   turretpackets  TPaoDaiBase.handlePacketData  ignored on the server
 *   multiblock     TPaoTaiQi.onActivated needs reach, TCiGuiPao.onDestroy needs an adjacent dummy
 *   ammodupe       TPaoTaiZhan.isUseableByPlayer -> VoltzSentry.usableByPlayer
 *                  BlockTurretPlatform.dropEntireInventory empties each slot before dropping it
 *
 * The platform terminal runs each command as whatever username the packet names, from any
 * distance, so any client can destroy or take over a platform while its owner is online.
 *
 * Every turret packet - rotation, description NBT, shot, health - is a server-to-client
 * packet, but the server applies them from clients too: set any turret's health, aim, or
 * saved coordinates.
 *
 * usage: PatchICBMSentry <in.jar> <out.jar> <patch>[,<patch>...] <VoltzSentry.class>
 */
public class PatchICBMSentry {

    static final String TERMINAL = "icbm/gangshao/terminal/TileEntityTerminal";
    static final String TURRET   = "icbm/gangshao/turret/TPaoDaiBase";
    static final String HELPER   = "icbm/gangshao/VoltzSentry";
    static final String WORLD    = "net/minecraft/world/World";
    static final String PLAYER   = "net/minecraft/entity/player/EntityPlayer";
    static final String MOUNT    = "icbm/gangshao/turret/mount/TPaoTaiQi";
    static final String RAILGUN  = "icbm/gangshao/turret/mount/TCiGuiPao";
    static final String PLATFORM = "icbm/gangshao/platform/TPaoTaiZhan";
    static final String PLATFORM_BLOCK = "icbm/gangshao/platform/BlockTurretPlatform";
    static final String IINV     = "net/minecraft/inventory/IInventory";

    static boolean doTerminal, doTurret, doMulti, doAmmo;
    static boolean hitTerminal, hitTurret, hitMount, hitRailgun, hitUsable, hitDrop;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchICBMSentry <in.jar> <out.jar> <patches> <VoltzSentry.class>");
            System.err.println("patches: terminal,turretpackets,multiblock,ammodupe");
            System.exit(2);
        }
        for (String p : args[2].split(",")) {
            p = p.trim();
            if (p.equals("terminal")) doTerminal = true;
            else if (p.equals("turretpackets")) doTurret = true;
            else if (p.equals("multiblock")) doMulti = true;
            else if (p.equals("ammodupe")) doAmmo = true;
            else throw new IllegalArgumentException("unknown patch: " + p);
        }

        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(args[0]);
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) { out.put(ze.getName(), null); continue; }
            byte[] d = readAll(zf.getInputStream(ze));
            String n = ze.getName();
            if (doTerminal && n.equals(TERMINAL + ".class")) d = patchTerminal(d);
            if (doTurret && n.equals(TURRET + ".class"))     d = patchTurret(d);
            if (doMulti && n.equals(MOUNT + ".class"))       d = patchMultiblock(d, MOUNT);
            if (doMulti && n.equals(RAILGUN + ".class"))     d = patchMultiblock(d, RAILGUN);
            if (doAmmo && n.equals(PLATFORM + ".class"))     d = patchUsable(d);
            if (doAmmo && n.equals(PLATFORM_BLOCK + ".class")) d = patchDrop(d);
            out.put(n, d);
        }
        zf.close();
        out.put(HELPER + ".class", readAll(new FileInputStream(args[3])));

        if (doTerminal && !hitTerminal) throw new IllegalStateException("terminal patch did not apply");
        if (doTurret && !hitTurret) throw new IllegalStateException("turretpackets patch did not apply");
        if (doMulti && !(hitMount && hitRailgun)) throw new IllegalStateException("multiblock patch did not apply");
        if (doAmmo && !(hitUsable && hitDrop)) throw new IllegalStateException("ammodupe patch did not apply");

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
     * The command branch reads a username and a command and runs
     * `CommandRegistry.onCommand(world.getPlayerEntityByName(name), this, command)`.
     * The lookup gets the packet's real sender and the tile pushed, and becomes
     * VoltzSentry.commandSender, which returns that sender when within reach and null
     * otherwise; null returns before the command runs.
     */
    static byte[] patchTerminal(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("handlePacketData")) continue;
            int hits = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.owner.equals(WORLD) || !mi.name.equals("func_72924_a")) continue;   // getPlayerEntityByName
                AbstractInsnNode prev = mi.getPrevious();
                while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
                if (!(prev instanceof MethodInsnNode) || !((MethodInsnNode) prev).name.equals("readUTF")) continue;
                m.instructions.insertBefore(mi, new VarInsnNode(Opcodes.ALOAD, 0));
                m.instructions.insertBefore(mi, new VarInsnNode(Opcodes.ALOAD, 4));
                LabelNode carryOn = new LabelNode();
                InsnList after = new InsnList();
                after.add(new TypeInsnNode(Opcodes.CHECKCAST, PLAYER));
                after.add(new InsnNode(Opcodes.DUP));
                after.add(new JumpInsnNode(Opcodes.IFNONNULL, carryOn));
                after.add(new InsnNode(Opcodes.POP));
                after.add(new InsnNode(Opcodes.RETURN));
                after.add(carryOn);
                m.instructions.insert(mi, after);
                m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "commandSender",
                        "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
                hits++;
            }
            if (hits != 1) throw new IllegalStateException("terminal: expected 1 username lookup, found " + hits);
            m.maxStack = m.maxStack + 3;
            hitTerminal = true;
        }
        return write(cn);
    }

    /**
     * Prepend to TPaoDaiBase.handlePacketData, which every turret uses:
     *
     *     if (!this.worldObj.isRemote) { VoltzSentry.serverPacketRefused(this, player, "turret packet"); return; }
     */
    static byte[] patchTurret(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("handlePacketData")) continue;
            LabelNode carryOn = new LabelNode();
            InsnList g = new InsnList();
            g.add(new VarInsnNode(Opcodes.ALOAD, 0));
            g.add(new FieldInsnNode(Opcodes.GETFIELD, TURRET, "field_70331_k", "L" + WORLD + ";"));
            g.add(new FieldInsnNode(Opcodes.GETFIELD, WORLD, "field_72995_K", "Z"));   // World.isRemote
            g.add(new JumpInsnNode(Opcodes.IFNE, carryOn));
            g.add(new VarInsnNode(Opcodes.ALOAD, 0));
            g.add(new VarInsnNode(Opcodes.ALOAD, 4));
            g.add(new LdcInsnNode("turret packet"));
            g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "serverPacketRefused",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V", false));
            g.add(new InsnNode(Opcodes.RETURN));
            g.add(carryOn);
            m.instructions.insert(g);
            m.maxStack = Math.max(m.maxStack, 3);
            hitTurret = true;
        }
        return write(cn);
    }

    /**
     * The railgun is a multiblock driven through its dummy block, whose main-block position the
     * shared UE TileEntityMulti accepts from any client packet. Guard the owner: TPaoTaiQi's
     * onActivated(player) (mount, or fire when sneaking) needs the player within reach, and
     * TCiGuiPao's onDestroy(callingBlock) - which deletes the railgun - needs the dummy to be part
     * of it. See VoltzSentry.multiblockReach / multiblockPart.
     */
    static byte[] patchMultiblock(byte[] in, String owner) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            boolean activate = owner.equals(MOUNT) && m.name.equals("onActivated") && m.desc.equals("(L" + PLAYER + ";)Z");
            boolean destroy = owner.equals(RAILGUN) && m.name.equals("onDestroy") && m.desc.equals("(Lnet/minecraft/tileentity/TileEntity;)V");
            if (!activate && !destroy) continue;
            LabelNode carryOn = new LabelNode();
            InsnList g = new InsnList();
            g.add(new VarInsnNode(Opcodes.ALOAD, 0));
            g.add(new VarInsnNode(Opcodes.ALOAD, 1));
            g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, activate ? "multiblockReach" : "multiblockPart",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
            g.add(new JumpInsnNode(Opcodes.IFNE, carryOn));
            if (activate) {
                g.add(new InsnNode(Opcodes.ICONST_0));
                g.add(new InsnNode(Opcodes.IRETURN));
                hitMount = true;
            } else {
                g.add(new InsnNode(Opcodes.RETURN));
                hitRailgun = true;
            }
            g.add(carryOn);
            m.instructions.insert(g);
            m.maxStack = Math.max(m.maxStack, 2);
        }
        return write(cn);
    }

    /**
     * TPaoTaiZhan.isUseableByPlayer is `return true`, so the platform's GUI never closes: not out
     * of reach, and not after the platform is broken. Its body becomes
     * `return VoltzSentry.usableByPlayer(this, player);`.
     */
    static byte[] patchUsable(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("func_70300_a") || !m.desc.equals("(L" + PLAYER + ";)Z")) continue;
            m.instructions.clear();
            m.tryCatchBlocks.clear();
            if (m.localVariables != null) m.localVariables.clear();
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "usableByPlayer",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
            m.instructions.add(new InsnNode(Opcodes.IRETURN));
            m.maxStack = 2;
            m.maxLocals = Math.max(m.maxLocals, 2);
            hitUsable = true;
        }
        return write(cn);
    }

    /**
     * BlockTurretPlatform.dropEntireInventory drops each slot's contents as a copy and leaves the
     * slot full, so a platform GUI still open after the break hands the whole inventory out again.
     * Right after each slot is read (`stack = inventory.getStackInSlot(slot)`), insert
     * `inventory.setInventorySlotContents(slot, null)`; the drop still uses the read stack.
     */
    static byte[] patchDrop(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("dropEntireInventory")) continue;
            int hits = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEINTERFACE || !((MethodInsnNode) i).name.equals("func_70301_a")) continue;   // getStackInSlot
                AbstractInsnNode slotLoad = prev(i), invLoad = prev(slotLoad);
                AbstractInsnNode store = next(i);
                if (invLoad.getOpcode() != Opcodes.ALOAD || slotLoad.getOpcode() != Opcodes.ILOAD || store.getOpcode() != Opcodes.ASTORE) continue;
                InsnList clear = new InsnList();
                clear.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode) invLoad).var));
                clear.add(new VarInsnNode(Opcodes.ILOAD, ((VarInsnNode) slotLoad).var));
                clear.add(new InsnNode(Opcodes.ACONST_NULL));
                clear.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, IINV, "func_70299_a", "(ILnet/minecraft/item/ItemStack;)V", true));
                m.instructions.insert(store, clear);
                hits++;
            }
            if (hits != 1) throw new IllegalStateException("ammodupe: expected 1 slot read in dropEntireInventory, found " + hits);
            m.maxStack = Math.max(m.maxStack, 3);
            hitDrop = true;
        }
        return write(cn);
    }

    static AbstractInsnNode prev(AbstractInsnNode n) {
        n = n.getPrevious();
        while (n != null && n.getOpcode() < 0) n = n.getPrevious();
        return n;
    }

    static AbstractInsnNode next(AbstractInsnNode n) {
        n = n.getNext();
        while (n != null && n.getOpcode() < 0) n = n.getNext();
        return n;
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
