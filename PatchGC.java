import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - Galacticraft coremod packet and container patches.
 *
 * Galacticraft references Minecraft by obfuscated names (aab = World, jc = EntityPlayerMP,
 * sq = EntityPlayer, wm = ItemStack, tj = Container). The injected bytecode mirrors whatever
 * names already appear in each target method; FML remaps them at load exactly as it does the
 * rest of the class. Everything that needs a Minecraft/GC object at runtime is handed to
 * VoltzGC as Object and reached reflectively there.
 *
 *   dimauth   GCCorePacketHandlerServer packet 2  both WorldUtil.transferEntityToDimension
 *                                                 calls -> VoltzGC.transferToDimension, which
 *                                                 refuses a destination the player can't reach
 *   station   GCCorePacketHandlerServer packet 15 branch -> VoltzGC.createSpaceStation, which
 *                                                 checks the recipe before consuming and binding
 *   reach     GCCorePacketHandlerServer packets 11/17/22  VoltzGC.reachPacket gate on the tile
 *                                                 at the client's coordinates (reach + chunk)
 *   rider     GCCorePacketEntityUpdate.handlePacket   found entity kept only when the sender
 *                                                 is riding it (VoltzGC.entityIfRider)
 *   guis      six machine containers' canInteractWith -> VoltzGC.containerReach (delegates to
 *                                                 the backing tile's own reach test)
 *   nbtclamp  GCCoreTileEntityParachest / GCCoreEntityLander readFromNBT  chest length clamped
 *
 * usage: PatchGC <in.jar> <out.jar> <patch>[,<patch>...] <VoltzGC.class>
 */
public class PatchGC {

    static final String PKG      = "micdoodle8/mods/galacticraft/core/";
    static final String HANDLER  = PKG + "network/GCCorePacketHandlerServer";
    static final String ENTITY_UPDATE = PKG + "network/GCCorePacketEntityUpdate";
    static final String CONTROLLABLE  = PKG + "entities/GCCoreEntityControllable";
    static final String WORLDUTIL     = PKG + "util/WorldUtil";
    static final String PACKETUTIL    = PKG + "util/PacketUtil";
    static final String CONFIG        = PKG + "GCCoreConfigManager";
    static final String SPACE_RECIPE  = "micdoodle8/mods/galacticraft/API/SpaceStationRecipe";
    static final String DISABLEABLE   = "micdoodle8/mods/galacticraft/API/IDisableableMachine";
    static final String PARACHEST_UPDATE = PKG + "network/GCCorePacketParachestUpdate";
    static final String PARACHEST_TILE   = PKG + "tile/GCCoreTileEntityParachest";
    static final String LANDER        = PKG + "entities/GCCoreEntityLander";
    static final String UE_ROUTER     = "universalelectricity/prefab/network/PacketManager";
    static final String HELPER        = PKG + "VoltzGC";

    static final String PLAYERMP  = PKG + "entities/GCCorePlayerMP";

    static final String TRANSFER_DESC = "(Lmp;ILiz;)V";
    static final String OBJ3_DESC     = "(Ljava/lang/Object;ILjava/lang/Object;)V";
    static final String CANINTERACT   = "a";
    static final String CANINTERACT_DESC = "(Lsq;)Z";
    static final String READ_NBT_DESC = "(Lbs;)V";
    static final String HANDLE_PACKET = "handlePacket";

    // GCCorePacketHandlerServer.onPacketData locals: 6 = EntityPlayerMP, 7 = GCCorePlayerMP
    static final int L_PLAYER   = 6;
    static final int L_PLAYERMP = 7;

    static final String[] MACHINE_CONTAINERS = {
        PKG + "inventory/GCCoreContainerFuelLoader",
        PKG + "inventory/GCCoreContainerCargoLoader",
        PKG + "inventory/GCCoreContainerAirCollector",
        PKG + "inventory/GCCoreContainerAirCompressor",
        PKG + "inventory/GCCoreContainerAirSealer",
        PKG + "inventory/GCCoreContainerAirDistributor",
    };

    static boolean doDim, doStation, doReach, doRider, doGuis, doNbt, doUeChunk;
    static boolean hitDim, hitStation, hitRider, hitParaNbt, hitLanderNbt, hitUeChunk;
    static final Set<String> reachHits = new HashSet<String>();
    static final Set<String> guiHits = new HashSet<String>();

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchGC <in.jar> <out.jar> <patches> <VoltzGC.class>");
            System.err.println("patches: dimauth,station,reach,rider,guis,nbtclamp");
            System.exit(2);
        }
        for (String p : args[2].split(",")) {
            p = p.trim();
            if (p.equals("dimauth")) doDim = true;
            else if (p.equals("station")) doStation = true;
            else if (p.equals("reach")) doReach = true;
            else if (p.equals("rider")) doRider = true;
            else if (p.equals("guis")) doGuis = true;
            else if (p.equals("nbtclamp")) doNbt = true;
            else if (p.equals("uechunk")) doUeChunk = true;
            else throw new IllegalArgumentException("unknown patch: " + p);
        }
        Set<String> machines = new HashSet<String>(Arrays.asList(MACHINE_CONTAINERS));

        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(args[0]);
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) { out.put(ze.getName(), null); continue; }
            byte[] d = readAll(zf.getInputStream(ze));
            String n = ze.getName();
            String cls = n.endsWith(".class") ? n.substring(0, n.length() - ".class".length()) : null;
            if (cls != null && cls.equals(HANDLER) && (doDim || doStation || doReach))
                d = patchHandler(d);
            if (cls != null && cls.equals(ENTITY_UPDATE) && doRider)
                d = patchRider(d);
            if (cls != null && doGuis && machines.contains(cls))
                d = patchContainer(d, cls);
            if (cls != null && doNbt && cls.equals(PARACHEST_TILE))
                d = patchNbtClamp(d, true);
            if (cls != null && doNbt && cls.equals(LANDER))
                d = patchNbtClamp(d, false);
            if (cls != null && doUeChunk && cls.equals(UE_ROUTER))
                d = patchUeChunk(d);
            out.put(n, d);
        }
        zf.close();
        injectHelper(out, HELPER, args[3]);

        if (doDim && !hitDim) throw new IllegalStateException("dimauth patch did not apply");
        if (doStation && !hitStation) throw new IllegalStateException("station patch did not apply");
        if (doReach && reachHits.size() != 3)
            throw new IllegalStateException("reach patch applied to " + reachHits + ", expected packets 11, 17, 22");
        if (doRider && !hitRider) throw new IllegalStateException("rider patch did not apply");
        if (doGuis && guiHits.size() != MACHINE_CONTAINERS.length)
            throw new IllegalStateException("guis patch applied to " + guiHits + ", expected " + MACHINE_CONTAINERS.length);
        if (doNbt && !hitParaNbt) throw new IllegalStateException("nbtclamp did not apply to the parachest tile");
        if (doUeChunk && !hitUeChunk) throw new IllegalStateException("uechunk patch did not apply");

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])));
        for (Map.Entry<String, byte[]> en : out.entrySet()) {
            zos.putNextEntry(new ZipEntry(en.getKey()));
            if (en.getValue() != null) zos.write(en.getValue());
            zos.closeEntry();
        }
        zos.close();
        System.out.println("OK  wrote " + args[1] + "  [" + args[2] + "]"
                + (doNbt ? "  (lander nbt clamp: " + (hitLanderNbt ? "applied" : "not found") + ")" : ""));
    }

    /** All three onPacketData edits share one pass over the method. */
    static byte[] patchHandler(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("onPacketData")) continue;
            if (doDim) redirectTransfer(m);
            if (doReach) insertReachGuards(m);
            if (doStation) rewriteStationBranch(m);   // last: it removes a run of instructions
            m.maxStack = Math.max(m.maxStack, 4);
        }
        return write(cn);
    }

    /**
     * Packet 2: both WorldUtil.transferEntityToDimension(entity, dim, world) calls become
     * VoltzGC.transferToDimension(Object, int, Object). Entity and world widen to Object with
     * no cast, so only the owner and descriptor change.
     */
    static void redirectTransfer(MethodNode m) {
        int hits = 0;
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i.getOpcode() != Opcodes.INVOKESTATIC) continue;
            MethodInsnNode mi = (MethodInsnNode) i;
            if (!mi.owner.equals(WORLDUTIL) || !mi.name.equals("transferEntityToDimension")
                    || !mi.desc.equals(TRANSFER_DESC)) continue;
            m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER,
                    "transferToDimension", OBJ3_DESC, false));
            hits++;
        }
        if (hits != 2) throw new IllegalStateException("dimauth: expected 2 transfer calls, found " + hits);
        hitDim = true;
    }

    /**
     * Packets 11, 17 and 22 each read the packet into an Object[] and then act on the tile at
     * those coordinates. After the Object[] is stored, insert
     *
     *     if (!VoltzGC.reachPacket(player, data)) return;
     */
    static void insertReachGuards(MethodNode m) {
        insertReachGuard(m, "17", anchorInterface(m, DISABLEABLE, "setDisabled"));
        insertReachGuard(m, "22", anchorStatic(m, PARACHEST_UPDATE, "buildKeyPacket"));
        insertReachGuard(m, "11", anchorField(m, Opcodes.GETSTATIC, CONFIG, "idGuiRefinery"));
    }

    static void insertReachGuard(MethodNode m, String label, AbstractInsnNode anchor) {
        if (anchor == null) throw new IllegalStateException("reach: no anchor for packet " + label);
        // walk back to the readPacketData call that fills this branch's Object[]
        AbstractInsnNode p = anchor;
        MethodInsnNode read = null;
        while (p != null) {
            if (p.getOpcode() == Opcodes.INVOKESTATIC) {
                MethodInsnNode mi = (MethodInsnNode) p;
                if (mi.owner.equals(PACKETUTIL) && mi.name.equals("readPacketData")) { read = mi; break; }
            }
            p = p.getPrevious();
        }
        if (read == null) throw new IllegalStateException("reach: no readPacketData before packet " + label);
        AbstractInsnNode store = read.getNext();
        while (store != null && store.getOpcode() < 0) store = store.getNext();
        if (store == null || store.getOpcode() != Opcodes.ASTORE)
            throw new IllegalStateException("reach: readPacketData result not stored for packet " + label);
        int dataVar = ((VarInsnNode) store).var;
        LabelNode cont = new LabelNode();
        InsnList g = new InsnList();
        g.add(new VarInsnNode(Opcodes.ALOAD, L_PLAYER));
        g.add(new VarInsnNode(Opcodes.ALOAD, dataVar));
        g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "reachPacket",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Z", false));
        g.add(new JumpInsnNode(Opcodes.IFNE, cont));
        g.add(new InsnNode(Opcodes.RETURN));
        g.add(cont);
        m.instructions.insert(store, g);
        reachHits.add(label);
    }

    /**
     * Packet 15: replace the whole branch body (from the first read of spaceStationDimensionID
     * through the popped recipe.matches call) with VoltzGC.createSpaceStation(player, data). The
     * readPacketData call that fills the Object[] runs before that point and is left in place.
     */
    static void rewriteStationBranch(MethodNode m) {
        MethodInsnNode matches = null;
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                MethodInsnNode mi = (MethodInsnNode) i;
                if (mi.owner.equals(SPACE_RECIPE) && mi.name.equals("matches")) { matches = mi; break; }
            }
        }
        if (matches == null) throw new IllegalStateException("station: no SpaceStationRecipe.matches");
        AbstractInsnNode end = matches.getNext();               // the POP that discards the result
        while (end != null && end.getOpcode() < 0) end = end.getNext();
        if (end == null || end.getOpcode() != Opcodes.POP)
            throw new IllegalStateException("station: matches result is not popped");

        // start: the ALOAD before the first getfield spaceStationDimensionID
        FieldInsnNode firstGet = null;
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i.getOpcode() == Opcodes.GETFIELD) {
                FieldInsnNode f = (FieldInsnNode) i;
                if (f.owner.equals(PLAYERMP) && f.name.equals("spaceStationDimensionID")) { firstGet = f; break; }
            }
        }
        if (firstGet == null) throw new IllegalStateException("station: no spaceStationDimensionID read");
        AbstractInsnNode start = firstGet.getPrevious();
        while (start != null && start.getOpcode() < 0) start = start.getPrevious();
        if (start == null || start.getOpcode() != Opcodes.ALOAD)
            throw new IllegalStateException("station: branch does not begin with an aload");
        int playerVar = ((VarInsnNode) start).var;

        // the branch's Object[] local (readPacketData result before start)
        AbstractInsnNode p = start;
        MethodInsnNode read = null;
        while (p != null) {
            if (p.getOpcode() == Opcodes.INVOKESTATIC) {
                MethodInsnNode mi = (MethodInsnNode) p;
                if (mi.owner.equals(PACKETUTIL) && mi.name.equals("readPacketData")) { read = mi; break; }
            }
            p = p.getPrevious();
        }
        if (read == null) throw new IllegalStateException("station: no readPacketData before the branch");
        AbstractInsnNode rstore = read.getNext();
        while (rstore != null && rstore.getOpcode() < 0) rstore = rstore.getNext();
        int dataVar = ((VarInsnNode) rstore).var;

        AbstractInsnNode before = start.getPrevious();
        // remove [start .. end] inclusive
        AbstractInsnNode cur = start;
        while (cur != null) {
            AbstractInsnNode next = cur.getNext();
            m.instructions.remove(cur);
            if (cur == end) break;
            cur = next;
        }
        InsnList g = new InsnList();
        g.add(new VarInsnNode(Opcodes.ALOAD, playerVar));
        g.add(new VarInsnNode(Opcodes.ALOAD, dataVar));
        g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "createSpaceStation",
                "(Ljava/lang/Object;[Ljava/lang/Object;)V", false));
        if (before == null) m.instructions.insert(g); else m.instructions.insert(before, g);
        hitStation = true;
    }

    /**
     * GCCorePacketEntityUpdate.handlePacket: after the loop stores the matching entity in local 6,
     * and before the null check, insert
     *
     *     found = VoltzGC.entityIfRider(player, found);   // null unless the sender is riding it
     */
    static byte[] patchRider(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals(HANDLE_PACKET)) continue;
            int hits = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.ALOAD) continue;
                int foundVar = ((VarInsnNode) i).var;
                AbstractInsnNode nx = i.getNext();
                while (nx != null && nx.getOpcode() < 0) nx = nx.getNext();
                if (nx == null || nx.getOpcode() != Opcodes.IFNULL) continue;
                InsnList g = new InsnList();
                g.add(new VarInsnNode(Opcodes.ALOAD, 4));                       // player (sq)
                g.add(new VarInsnNode(Opcodes.ALOAD, foundVar));
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "entityIfRider",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
                g.add(new TypeInsnNode(Opcodes.CHECKCAST, CONTROLLABLE));
                g.add(new VarInsnNode(Opcodes.ASTORE, foundVar));
                m.instructions.insertBefore(i, g);
                m.maxStack = Math.max(m.maxStack, 2);
                hits++;
                break;
            }
            if (hits != 1) throw new IllegalStateException("rider: expected 1 null check, found " + hits);
            hitRider = true;
        }
        return write(cn);
    }

    /**
     * Replace a machine container's canInteractWith body ({@code return true}) with
     * {@code return VoltzGC.containerReach(this, player);}
     */
    static byte[] patchContainer(byte[] in, String owner) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals(CANINTERACT) || !m.desc.equals(CANINTERACT_DESC)) continue;
            m.instructions.clear();
            m.tryCatchBlocks.clear();
            InsnList g = m.instructions;
            g.add(new VarInsnNode(Opcodes.ALOAD, 0));
            g.add(new VarInsnNode(Opcodes.ALOAD, 1));
            g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "containerReach",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
            g.add(new InsnNode(Opcodes.IRETURN));
            m.maxStack = 2;
            guiHits.add(owner);
        }
        return write(cn);
    }

    /**
     * readFromNBT sizes the chest array straight from a stored int. Clamp that int with
     * VoltzGC.clampInv before the anewarray, so a negative or absurd count can't crash the load.
     * Only an anewarray whose count is produced by a method call (the stored length) is touched.
     */
    static byte[] patchNbtClamp(byte[] in, boolean parachest) {
        ClassNode cn = read(in);
        int hits = 0;
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("a") || !m.desc.equals(READ_NBT_DESC)) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.ANEWARRAY) continue;
                AbstractInsnNode prev = i.getPrevious();
                while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
                if (prev == null) continue;
                int op = prev.getOpcode();
                if (op != Opcodes.INVOKEVIRTUAL && op != Opcodes.INVOKEINTERFACE) continue;
                m.instructions.insertBefore(i, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER,
                        "clampInv", "(I)I", false));
                hits++;
            }
        }
        if (parachest) {
            if (hits < 1) throw new IllegalStateException("nbtclamp: no dynamic chest array in the parachest tile");
            hitParaNbt = true;
        } else {
            hitLanderNbt = hits > 0;
        }
        return write(cn);
    }

    /**
     * Bundled UE PacketManager.onPacketData. In the TILEENTITY branch it reads x,y,z and then
     * looks the tile up with getBlockTileEntity, loading the chunk. After the world null-check
     * and before that lookup, insert
     *
     *     if (!VoltzGC.chunkLoaded(world, x, y, z)) skip the tile dispatch;
     *
     * reusing the null-check's own skip target, so an unloaded chunk is left untouched.
     */
    static byte[] patchUeChunk(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("onPacketData")) continue;
            JumpInsnNode worldNull = null;
            int worldVar = -1;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.IFNULL) continue;
                AbstractInsnNode prev = i.getPrevious();
                while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
                if (prev == null || prev.getOpcode() != Opcodes.ALOAD) continue;
                worldNull = (JumpInsnNode) i;
                worldVar = ((VarInsnNode) prev).var;
                break;                                  // first: the world null-check
            }
            if (worldNull == null) throw new IllegalStateException("uechunk: no world null-check in onPacketData");
            InsnList g = new InsnList();
            g.add(new VarInsnNode(Opcodes.ALOAD, worldVar));
            g.add(new VarInsnNode(Opcodes.ILOAD, 7));
            g.add(new VarInsnNode(Opcodes.ILOAD, 8));
            g.add(new VarInsnNode(Opcodes.ILOAD, 9));
            g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "chunkLoaded",
                    "(Ljava/lang/Object;III)Z", false));
            g.add(new JumpInsnNode(Opcodes.IFEQ, worldNull.label));
            m.instructions.insert(worldNull, g);
            m.maxStack = Math.max(m.maxStack, 4);
            hitUeChunk = true;
        }
        return write(cn);
    }

    // ------------------------------------------------------------- anchors

    static AbstractInsnNode anchorInterface(MethodNode m, String owner, String name) {
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i.getOpcode() == Opcodes.INVOKEINTERFACE) {
                MethodInsnNode mi = (MethodInsnNode) i;
                if (mi.owner.equals(owner) && mi.name.equals(name)) return i;
            }
        }
        return null;
    }

    static AbstractInsnNode anchorStatic(MethodNode m, String owner, String name) {
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i.getOpcode() == Opcodes.INVOKESTATIC) {
                MethodInsnNode mi = (MethodInsnNode) i;
                if (mi.owner.equals(owner) && mi.name.equals(name)) return i;
            }
        }
        return null;
    }

    static AbstractInsnNode anchorField(MethodNode m, int opcode, String owner, String name) {
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i.getOpcode() == opcode) {
                FieldInsnNode f = (FieldInsnNode) i;
                if (f.owner.equals(owner) && f.name.equals(name)) return i;
            }
        }
        return null;
    }

    // ------------------------------------------------------------- plumbing

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
