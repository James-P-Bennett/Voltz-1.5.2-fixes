import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - ICBM Explosion 1.2.1.172 patches.
 *
 *   redmatter   ExHongSu.doBaoZha         return false once callCount reaches
 *                                         VoltzICBM.redMatterMaxTicks
 *   sonic       ExShengBuo / ExChaoShengBuo
 *                 baoZhaQian              dataList1.add(v)  -> VoltzICBM.addUnique(list, v)
 *                 doBaoZha                world.spawnEntityInWorld(EFeiBlock)
 *                                           -> VoltzICBM.spawnFlyingBlock(world, e, source)
 *   remote      TZhaDan.handlePacketData   detonate packet refused unless
 *                                         VoltzICBM.remoteAllowed(tile, player, ...)
 *   explosivetype TZhaDan.handlePacketData set-type packet (ID 1) ignored on the server
 *   empradius   TDianCiQi                  settings packet (ID 1) ignored on the server; every
 *                                         banJing write clamped by VoltzICBM.empRadius
 *   launchertier TFaSheDi / TFaSheJia      tier packet ignored on the server
 *                TFaSheShiMuo              description packet (ID 0) ignored on the server
 *
 * Red matter's doBaoZha returns true unconditionally, so the black hole never ends. It is
 * saved with the chunk and rescans a radius-35 sphere every tick forever.
 *
 * The sonic and hypersonic ray march adds a position every 0.3 blocks, so each block
 * lands in dataList1 several times over, and every block within about 7 of the centre
 * becomes a flying block entity.
 *
 * The explosive block's detonate packet only checks that the sender holds a Remote. The
 * Remote's own rules - which explosives it fires, its 1,500 J charge, the 100-block aim
 * or the linked explosive - are enforced on the client alone.
 *
 * The same handler's set-type packet writes any int a client sends into the block's
 * explosive id: any explosive becomes any other, or an id past the end of ZhaPin.list,
 * which crashes the server on the next redstone update.
 *
 * usage: PatchICBM <in.jar> <out.jar> <patch>[,<patch>...] <VoltzICBM.class>
 */
public class PatchICBM {

    static final String EX         = "icbm/zhapin/zhapin/ex/";
    static final String RED_CLASS  = EX + "ExHongSu";
    static final String SONIC      = EX + "ExShengBuo";
    static final String HYPERSONIC = EX + "ExChaoShengBuo";
    static final String MAIN_CLASS = "icbm/zhapin/ZhuYaoZhaPin";
    static final String TILE_CLASS = "icbm/zhapin/zhapin/TZhaDan";
    static final String REMOTE     = "icbm/zhapin/dianqi/ItYaoKong";
    static final String ELECTRIC   = "universalelectricity/core/item/ItemElectric";
    static final String CFG_CLASS  = "icbm/zhapin/VoltzICBM";
    static final String EMP_CLASS     = "icbm/zhapin/jiqi/TDianCiQi";
    static final String LAUNCH_BASE   = "icbm/zhapin/jiqi/TFaSheDi";
    static final String LAUNCH_FRAME  = "icbm/zhapin/jiqi/TFaSheJia";
    static final String LAUNCH_SCREEN = "icbm/zhapin/jiqi/TFaSheShiMuo";

    static final String WORLD      = "net/minecraft/world/World";
    static final String SPAWN      = "func_72838_d";   // World.spawnEntityInWorld
    static final String PRE_DESC   =
            "(Lnet/minecraft/world/World;Luniversalelectricity/core/vector/Vector3;Lnet/minecraft/entity/Entity;)V";
    static final String DO_DESC    =
            "(Lnet/minecraft/world/World;Luniversalelectricity/core/vector/Vector3;Lnet/minecraft/entity/Entity;II)Z";

    // locals of doBaoZha(World, Vector3, Entity explosionSource, int metadata, int callCount)
    static final int L_SOURCE    = 3;
    static final int L_CALLCOUNT = 5;

    static boolean doRed, doSonic, doRemote, doType, doEmp, doLauncher;
    static boolean hitRed, hitInit, hitRemote, hitType, hitEmp;
    static final Set<String> launcherHits = new HashSet<String>();
    static final Set<String> sonicAdds = new HashSet<String>();
    static final Set<String> sonicSpawns = new HashSet<String>();

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchICBM <in.jar> <out.jar> <patches> <VoltzICBM.class>");
            System.err.println("patches: redmatter,sonic,remote,explosivetype,empradius,launchertier");
            System.exit(2);
        }
        for (String p : args[2].split(",")) {
            p = p.trim();
            if (p.equals("redmatter")) doRed = true;
            else if (p.equals("sonic")) doSonic = true;
            else if (p.equals("remote")) doRemote = true;
            else if (p.equals("explosivetype")) doType = true;
            else if (p.equals("empradius")) doEmp = true;
            else if (p.equals("launchertier")) doLauncher = true;
            else throw new IllegalArgumentException("unknown patch: " + p);
        }

        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(args[0]);
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) { out.put(ze.getName(), null); continue; }
            byte[] d = readAll(zf.getInputStream(ze));
            String n = ze.getName();
            if (doRed && n.equals(RED_CLASS + ".class"))    d = patchRedMatter(d);
            if (doSonic && n.equals(SONIC + ".class"))      d = patchSonic(d, SONIC);
            if (doSonic && n.equals(HYPERSONIC + ".class")) d = patchSonic(d, HYPERSONIC);
            if (doRemote && n.equals(TILE_CLASS + ".class")) d = patchRemote(d);
            if (doType && n.equals(TILE_CLASS + ".class"))   d = patchExplosiveType(d);
            if (doEmp && n.equals(EMP_CLASS + ".class"))     d = patchEmp(d);
            if (doLauncher && (n.equals(LAUNCH_BASE + ".class") || n.equals(LAUNCH_FRAME + ".class")))
                d = patchLauncherPart(d, n);
            if (doLauncher && n.equals(LAUNCH_SCREEN + ".class")) d = patchLauncherScreen(d);
            if (n.equals(MAIN_CLASS + ".class"))            d = patchInitHook(d);
            out.put(n, d);
        }
        zf.close();
        injectHelper(out, CFG_CLASS, args[3]);

        if (doRed && !hitRed) throw new IllegalStateException("redmatter patch did not apply");
        if (doSonic) {
            for (String c : new String[] { SONIC, HYPERSONIC }) {
                if (!sonicAdds.contains(c))   throw new IllegalStateException("sonic dedupe did not apply to " + c);
                if (!sonicSpawns.contains(c)) throw new IllegalStateException("sonic spawn cap did not apply to " + c);
            }
        }
        if (doRemote && !hitRemote) throw new IllegalStateException("remote patch did not apply");
        if (doType && !hitType) throw new IllegalStateException("explosivetype patch did not apply");
        if (doEmp && !hitEmp) throw new IllegalStateException("empradius patch did not apply");
        if (doLauncher && launcherHits.size() != 3)
            throw new IllegalStateException("launchertier patch applied to " + launcherHits + ", expected 3 classes");
        if (!hitInit) throw new IllegalStateException("config init hook did not apply");

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
     * Prepend to ExHongSu.doBaoZha:
     *
     *     if (VoltzICBM.redMatterExpired(callCount)) return false;
     *
     * EZhaPin treats false as "finished": it calls baoZhaHou and kills itself. callCount is
     * EZhaPin.jiaoShuMu, which is written to NBT, so a black hole already older than the
     * limit dies on its first tick after the chunk loads.
     */
    static byte[] patchRedMatter(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("doBaoZha") || !m.desc.equals(DO_DESC)) continue;
            LabelNode carryOn = new LabelNode();
            InsnList g = new InsnList();
            g.add(new VarInsnNode(Opcodes.ILOAD, L_CALLCOUNT));
            g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "redMatterExpired", "(I)Z", false));
            g.add(new JumpInsnNode(Opcodes.IFEQ, carryOn));
            g.add(new InsnNode(Opcodes.ICONST_0));
            g.add(new InsnNode(Opcodes.IRETURN));
            g.add(carryOn);
            m.instructions.insert(g);
            m.maxStack = Math.max(m.maxStack, 2);
            hitRed = true;
        }
        return write(cn);
    }

    /**
     * baoZhaQian: the single List.add into dataList1 becomes VoltzICBM.addUnique, which
     * drops repeat entries for a block already queued. Same stack shape, same boolean.
     *
     * doBaoZha: the single spawnEntityInWorld call (the flying block) gets the explosion
     * source pushed as a third argument and becomes VoltzICBM.spawnFlyingBlock, which
     * enforces the per-explosion cap and otherwise spawns exactly as before.
     */
    static byte[] patchSonic(byte[] in, String owner) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (m.name.equals("baoZhaQian") && m.desc.equals(PRE_DESC)) {
                int hits = 0;
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
                    MethodInsnNode mi = (MethodInsnNode) i;
                    if (!mi.owner.equals("java/util/List") || !mi.name.equals("add")
                            || !mi.desc.equals("(Ljava/lang/Object;)Z")) continue;
                    m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS,
                            "addUnique", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
                    hits++;
                }
                if (hits != 1) throw new IllegalStateException(owner + ".baoZhaQian: expected 1 List.add, found " + hits);
                sonicAdds.add(owner);
            }
            if (m.name.equals("doBaoZha") && m.desc.equals(DO_DESC)) {
                int hits = 0;
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                    MethodInsnNode mi = (MethodInsnNode) i;
                    if (!mi.owner.equals(WORLD) || !mi.name.equals(SPAWN)) continue;
                    m.instructions.insertBefore(mi, new VarInsnNode(Opcodes.ALOAD, L_SOURCE));
                    m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS,
                            "spawnFlyingBlock",
                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z", false));
                    hits++;
                }
                if (hits != 1) throw new IllegalStateException(owner + ".doBaoZha: expected 1 spawn, found " + hits);
                m.maxStack = m.maxStack + 1;
                sonicSpawns.add(owner);
            }
        }
        return write(cn);
    }

    /**
     * TZhaDan.handlePacketData, detonate branch (ID 2). Right after it stores the held stack
     * (`astore 7`) and before BZhaDan.yinZha, insert
     *
     *     if (!VoltzICBM.remoteAllowed(this, player,
     *             ((ItYaoKong) ZhuYaoZhaPin.itYaoKong).nengZha(this),
     *             ZhuYaoZhaPin.itYaoKong.getJoules(stack),
     *             ((ItYaoKong) ZhuYaoZhaPin.itYaoKong).getSavedCoord(stack))) return;
     *
     * The Remote's own methods are called in bytecode, so the helper needs no reflection
     * into ICBM item classes.
     */
    static byte[] patchRemote(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("handlePacketData")) continue;
            int hits = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.ASTORE || ((VarInsnNode) i).var != 7) continue;
                AbstractInsnNode prev = i.getPrevious();
                while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
                if (!(prev instanceof MethodInsnNode) || !((MethodInsnNode) prev).name.equals("func_70448_g")) continue;
                LabelNode carryOn = new LabelNode();
                InsnList g = new InsnList();
                g.add(new VarInsnNode(Opcodes.ALOAD, 0));
                g.add(new VarInsnNode(Opcodes.ALOAD, 4));
                g.add(new FieldInsnNode(Opcodes.GETSTATIC, MAIN_CLASS, "itYaoKong", "L" + ELECTRIC + ";"));
                g.add(new TypeInsnNode(Opcodes.CHECKCAST, REMOTE));
                g.add(new VarInsnNode(Opcodes.ALOAD, 0));
                g.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, REMOTE, "nengZha", "(Lnet/minecraft/tileentity/TileEntity;)Z", false));
                g.add(new FieldInsnNode(Opcodes.GETSTATIC, MAIN_CLASS, "itYaoKong", "L" + ELECTRIC + ";"));
                g.add(new VarInsnNode(Opcodes.ALOAD, 7));
                g.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ELECTRIC, "getJoules", "(Lnet/minecraft/item/ItemStack;)D", false));
                g.add(new FieldInsnNode(Opcodes.GETSTATIC, MAIN_CLASS, "itYaoKong", "L" + ELECTRIC + ";"));
                g.add(new TypeInsnNode(Opcodes.CHECKCAST, REMOTE));
                g.add(new VarInsnNode(Opcodes.ALOAD, 7));
                g.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, REMOTE, "getSavedCoord",
                        "(Lnet/minecraft/item/ItemStack;)Luniversalelectricity/core/vector/Vector3;", false));
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "remoteAllowed",
                        "(Ljava/lang/Object;Ljava/lang/Object;ZDLjava/lang/Object;)Z", false));
                g.add(new JumpInsnNode(Opcodes.IFNE, carryOn));
                g.add(new InsnNode(Opcodes.RETURN));
                g.add(carryOn);
                m.instructions.insert(i, g);
                hits++;
            }
            if (hits != 1) throw new IllegalStateException("remote: expected 1 held-stack store, found " + hits);
            m.maxStack = m.maxStack + 8;
            hitRemote = true;
        }
        return write(cn);
    }

    /**
     * TZhaDan.handlePacketData, packet ID 1, sets the explosive id: `haoMa = data.readInt()`.
     * Only the server sends it, as the block's description packet, but a client's copy is
     * accepted too. Right after the packet ID is read (`istore`), insert
     *
     *     if (id == 1 && !this.worldObj.isRemote) {
     *         VoltzICBM.typePacketRefused(this, player);
     *         return;
     *     }
     *
     * The client still applies the server's description packet.
     */
    static byte[] patchExplosiveType(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("handlePacketData")) continue;
            int hits = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEINTERFACE || !((MethodInsnNode) i).name.equals("readByte")) continue;
                AbstractInsnNode store = i.getNext();
                while (store != null && store.getOpcode() < 0) store = store.getNext();
                if (store == null || store.getOpcode() != Opcodes.ISTORE) continue;
                LabelNode carryOn = new LabelNode();
                InsnList g = new InsnList();
                g.add(new VarInsnNode(Opcodes.ILOAD, ((VarInsnNode) store).var));
                g.add(new InsnNode(Opcodes.ICONST_1));
                g.add(new JumpInsnNode(Opcodes.IF_ICMPNE, carryOn));
                g.add(new VarInsnNode(Opcodes.ALOAD, 0));
                g.add(new FieldInsnNode(Opcodes.GETFIELD, TILE_CLASS, "field_70331_k", "L" + WORLD + ";"));
                g.add(new FieldInsnNode(Opcodes.GETFIELD, WORLD, "field_72995_K", "Z"));   // World.isRemote
                g.add(new JumpInsnNode(Opcodes.IFNE, carryOn));
                g.add(new VarInsnNode(Opcodes.ALOAD, 0));
                g.add(new VarInsnNode(Opcodes.ALOAD, 4));
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "typePacketRefused",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                g.add(new InsnNode(Opcodes.RETURN));
                g.add(carryOn);
                m.instructions.insert(store, g);
                hits++;
            }
            if (hits != 1) throw new IllegalStateException("explosivetype: expected 1 packet ID read, found " + hits);
            m.maxStack = Math.max(m.maxStack, 2);
            hitType = true;
        }
        return write(cn);
    }

    /**
     * The guard used by empradius and launchertier:
     *
     *     if ([packetId == id &&] !this.worldObj.isRemote) {
     *         VoltzICBM.serverPacketRefused(this, player, what);
     *         return;
     *     }
     *
     * idVar < 0 guards every packet.
     */
    static InsnList refuseOnServer(String owner, int idVar, int id, String what) {
        LabelNode carryOn = new LabelNode();
        InsnList g = new InsnList();
        if (idVar >= 0) {
            g.add(new VarInsnNode(Opcodes.ILOAD, idVar));
            g.add(new LdcInsnNode(Integer.valueOf(id)));
            g.add(new JumpInsnNode(Opcodes.IF_ICMPNE, carryOn));
        }
        g.add(new VarInsnNode(Opcodes.ALOAD, 0));
        g.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "field_70331_k", "L" + WORLD + ";"));
        g.add(new FieldInsnNode(Opcodes.GETFIELD, WORLD, "field_72995_K", "Z"));   // World.isRemote
        g.add(new JumpInsnNode(Opcodes.IFNE, carryOn));
        g.add(new VarInsnNode(Opcodes.ALOAD, 0));
        g.add(new VarInsnNode(Opcodes.ALOAD, 4));
        g.add(new LdcInsnNode(what));
        g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "serverPacketRefused",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V", false));
        g.add(new InsnNode(Opcodes.RETURN));
        g.add(carryOn);
        return g;
    }

    /** the ISTORE right after the first readInt in handlePacketData: the packet ID, or null */
    static VarInsnNode packetIdStore(MethodNode m) {
        for (AbstractInsnNode i : m.instructions.toArray()) {
            if (i.getOpcode() != Opcodes.INVOKEINTERFACE || !((MethodInsnNode) i).name.equals("readInt")) continue;
            AbstractInsnNode n = i.getNext();
            while (n != null && n.getOpcode() < 0) n = n.getNext();
            return n != null && n.getOpcode() == Opcodes.ISTORE ? (VarInsnNode) n : null;
        }
        return null;
    }

    /**
     * TDianCiQi, the EMP tower. Its packet 1 is the server's description packet - joules,
     * disabled ticks, radius and mode - and packet 2 sets the radius from the GUI. The server
     * applies both from any client, with no cap: MAX_RADIUS (150) is declared and never used,
     * and onPowerOn feeds the radius straight into a (2r+1)^3 block loop. Packet 1 is now
     * ignored on the server, and every write to banJing - constructor, both packets and NBT
     * load - goes through VoltzICBM.empRadius, which also repairs towers saved with a bad radius.
     */
    static byte[] patchEmp(byte[] in) {
        ClassNode cn = read(in);
        int clamps = 0, guards = 0;
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.PUTFIELD) continue;
                FieldInsnNode f = (FieldInsnNode) i;
                if (!f.owner.equals(EMP_CLASS) || !f.name.equals("banJing") || !f.desc.equals("I")) continue;
                m.instructions.insertBefore(i, new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "empRadius", "(I)I", false));
                clamps++;
            }
            if (m.name.equals("handlePacketData")) {
                VarInsnNode store = packetIdStore(m);
                if (store == null) throw new IllegalStateException("empradius: no packet ID read in handlePacketData");
                m.instructions.insert(store, refuseOnServer(EMP_CLASS, store.var, 1, "EMP tower settings packet"));
                m.maxStack = Math.max(m.maxStack, 3);
                guards++;
            }
        }
        if (clamps != 4 || guards != 1)
            throw new IllegalStateException("empradius: expected 4 radius writes and 1 handler, found " + clamps + " and " + guards);
        hitEmp = true;
        return write(cn);
    }

    /**
     * TFaSheDi (launcher base) and TFaSheJia (launcher frame). Their only packet is the
     * server's description packet, `orientation = readByte(); tier = readInt();`, and the
     * server applies it from any client: a tier 0 base becomes tier 2, with full range, and
     * drops as a tier 2 base when broken. Ignored on the server.
     */
    static byte[] patchLauncherPart(byte[] in, String entry) {
        ClassNode cn = read(in);
        String owner = entry.substring(0, entry.length() - ".class".length());
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("handlePacketData")) continue;
            m.instructions.insert(refuseOnServer(owner, -1, 0, "launcher tier packet"));
            m.maxStack = Math.max(m.maxStack, 3);
            launcherHits.add(owner);
        }
        return write(cn);
    }

    /**
     * TFaSheShiMuo (launcher screen). Packet 0 is the server's description packet: facing,
     * tier, frequency and launch height, the height skipping the 3..99 clamp packet 3 uses.
     * Ignored on the server; the GUI's own packets (-1, 1, 2, 3) are untouched.
     */
    static byte[] patchLauncherScreen(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("handlePacketData")) continue;
            VarInsnNode store = packetIdStore(m);
            if (store == null) throw new IllegalStateException("launchertier: no packet ID read in TFaSheShiMuo");
            m.instructions.insert(store, refuseOnServer(LAUNCH_SCREEN, store.var, 0, "launcher screen description packet"));
            m.maxStack = Math.max(m.maxStack, 3);
            launcherHits.add(LAUNCH_SCREEN);
        }
        return write(cn);
    }

    /** Load config/VoltzFixes-ICBM.cfg at startup by calling VoltzICBM.init() from preInit. */
    static byte[] patchInitHook(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("preInit")) continue;
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

    /**
     * Frames are dropped on read. These are version 50 classes, so the JVM verifies them
     * by type inference when there is no StackMapTable, and the inserted jumps never have
     * to be described by a stale one.
     */
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
