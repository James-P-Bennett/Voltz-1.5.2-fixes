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
 *   multiblock  TFaSheDi / TFaSheJia / TDianCiQi / TLeiDaTai
 *                                         onActivated needs reach, onDestroy needs an adjacent dummy
 *   cruiselauncher TXiaoFaSheQi            description packet (ID 0) ignored on the server
 *   designator  ZhaPinPacketGuanLi         laser designator packet checked by VoltzICBM.designatorAllowed
 *   defuser     ItJieJa.onLeftClickEntity  dead entities ignored
 *   missilestack TFaSheDi / TXiaoFaSheQi   onActivated loads one missile from the held stack
 *   listeners   TDianCiQi / TFaSheShiMuo / TLeiDaTai / TXiaoFaSheQi
 *                                         GUI listener sets: joined within reach, pruned every tick loop
 *   radarradius TLeiDaTai                  every alarm/safety radius write clamped to 0..500
 *   radargun    ZhaPinPacketGuanLi         radar gun packet needs the gun's 1,000 J
 *   chunkload   ZhaPinPacketGuanLi         onPacketData drops a tile packet aimed at an unloaded chunk
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
    static final String CRUISE        = "icbm/zhapin/jiqi/TXiaoFaSheQi";
    static final String RADAR         = "icbm/zhapin/jiqi/TLeiDaTai";
    static final String PACKETS       = "icbm/zhapin/ZhaPinPacketGuanLi";
    static final String ROUTER        = "universalelectricity/prefab/network/PacketManager";
    static final String ON_PACKET_DESC =
            "(Lnet/minecraft/network/INetworkManager;Lnet/minecraft/network/packet/Packet250CustomPayload;Lcpw/mods/fml/common/network/Player;)V";
    static final String DESIGNATOR    = "icbm/zhapin/dianqi/ItLeiSheZhiBiao";
    static final String DEFUSER       = "icbm/zhapin/dianqi/ItJieJa";
    static final String STACK         = "net/minecraft/item/ItemStack";
    static final String INV_PLAYER    = "net/minecraft/entity/player/InventoryPlayer";
    static final String[] MULTIBLOCK_OWNERS = { LAUNCH_BASE, LAUNCH_FRAME, EMP_CLASS, RADAR };
    static final String[] LISTENER_OWNERS = { EMP_CLASS, LAUNCH_SCREEN, RADAR, CRUISE };

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
    static boolean doMulti, doCruise, doDesignator, doDefuser, doMissile;
    static boolean hitCruise, hitDesignator, hitDefuser;
    static boolean doListeners, doRadarRadius, doRadarGun, hitRadarRadius, hitRadarGun;
    static boolean doChunkLoad, hitChunkLoad;
    static final Set<String> listenerHits = new HashSet<String>();
    static final Set<String> multiHits = new HashSet<String>();
    static final Set<String> missileHits = new HashSet<String>();
    static boolean hitRed, hitInit, hitRemote, hitType, hitEmp;
    static final Set<String> launcherHits = new HashSet<String>();
    static final Set<String> sonicAdds = new HashSet<String>();
    static final Set<String> sonicSpawns = new HashSet<String>();

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchICBM <in.jar> <out.jar> <patches> <VoltzICBM.class>");
            System.err.println("patches: redmatter,sonic,remote,explosivetype,empradius,launchertier,multiblock,cruiselauncher,designator,defuser,missilestack,listeners,radarradius,radargun,chunkload");
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
            else if (p.equals("multiblock")) doMulti = true;
            else if (p.equals("cruiselauncher")) doCruise = true;
            else if (p.equals("designator")) doDesignator = true;
            else if (p.equals("defuser")) doDefuser = true;
            else if (p.equals("missilestack")) doMissile = true;
            else if (p.equals("listeners")) doListeners = true;
            else if (p.equals("radarradius")) doRadarRadius = true;
            else if (p.equals("radargun")) doRadarGun = true;
            else if (p.equals("chunkload")) doChunkLoad = true;
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
            for (String owner : MULTIBLOCK_OWNERS)
                if (doMulti && n.equals(owner + ".class")) d = patchMultiblockOwner(d, owner);
            if (doCruise && n.equals(CRUISE + ".class"))       d = patchCruiseLauncher(d);
            if (doDesignator && n.equals(PACKETS + ".class"))  d = patchDesignator(d);
            if (doDefuser && n.equals(DEFUSER + ".class"))     d = patchDefuser(d);
            if (doMissile && (n.equals(LAUNCH_BASE + ".class") || n.equals(CRUISE + ".class")))
                d = patchMissileStack(d, n.substring(0, n.length() - ".class".length()));
            for (String owner : LISTENER_OWNERS)
                if (doListeners && n.equals(owner + ".class")) d = patchListeners(d, owner);
            if (doRadarRadius && n.equals(RADAR + ".class"))  d = patchRadarRadius(d);
            if (doRadarGun && n.equals(PACKETS + ".class"))   d = patchRadarGun(d);
            if (doChunkLoad && n.equals(PACKETS + ".class"))  d = patchChunkLoad(d);
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
        if (doMulti && multiHits.size() != MULTIBLOCK_OWNERS.length * 2)
            throw new IllegalStateException("multiblock patch applied to " + multiHits + ", expected onActivated and onDestroy in 4 classes");
        if (doCruise && !hitCruise) throw new IllegalStateException("cruiselauncher patch did not apply");
        if (doDesignator && !hitDesignator) throw new IllegalStateException("designator patch did not apply");
        if (doDefuser && !hitDefuser) throw new IllegalStateException("defuser patch did not apply");
        if (doMissile && missileHits.size() != 2)
            throw new IllegalStateException("missilestack patch applied to " + missileHits + ", expected 2 classes");
        if (doListeners && listenerHits.size() != 7)
            throw new IllegalStateException("listeners patch applied to " + listenerHits + ", expected 4 tick loops and 3 joins");
        if (doRadarRadius && !hitRadarRadius) throw new IllegalStateException("radarradius patch did not apply");
        if (doRadarGun && !hitRadarGun) throw new IllegalStateException("radargun patch did not apply");
        if (doChunkLoad && !hitChunkLoad) throw new IllegalStateException("chunkload patch did not apply");
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

    /**
     * Multiblock machines are driven through their dummy blocks: a dummy's TileEntityMulti stores
     * the main block's position and forwards right-clicks (onActivated) and its own removal
     * (onDestroy) to whatever tile is there. The shared UE class accepts that position from any
     * client packet, and ships in several jars, Galacticraft's included, so the class that loads is
     * not ours to patch. The owners are guarded instead:
     *
     *     onActivated(player):       if (!VoltzICBM.multiblockReach(this, player)) return false;
     *     onDestroy(callingBlock):   if (!VoltzICBM.multiblockPart(this, callingBlock)) return;
     *
     * so a dummy pointed at someone else's machine can neither use it from afar nor delete it.
     */
    static byte[] patchMultiblockOwner(byte[] in, String owner) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            boolean activate = m.name.equals("onActivated") && m.desc.equals("(Lnet/minecraft/entity/player/EntityPlayer;)Z");
            boolean destroy = m.name.equals("onDestroy") && m.desc.equals("(Lnet/minecraft/tileentity/TileEntity;)V");
            if (!activate && !destroy) continue;
            LabelNode carryOn = new LabelNode();
            InsnList g = new InsnList();
            g.add(new VarInsnNode(Opcodes.ALOAD, 0));
            g.add(new VarInsnNode(Opcodes.ALOAD, 1));
            g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, activate ? "multiblockReach" : "multiblockPart",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
            g.add(new JumpInsnNode(Opcodes.IFNE, carryOn));
            if (activate) {
                g.add(new InsnNode(Opcodes.ICONST_0));
                g.add(new InsnNode(Opcodes.IRETURN));
            } else {
                g.add(new InsnNode(Opcodes.RETURN));
            }
            g.add(carryOn);
            m.instructions.insert(g);
            m.maxStack = Math.max(m.maxStack, 2);
            multiHits.add(owner + "." + m.name);
        }
        return write(cn);
    }

    /**
     * TXiaoFaSheQi (cruise launcher). Packet 0 is the server's description packet - joules,
     * frequency, EMP-disabled ticks and target - and the server applies it from any client: a
     * full 800,000 J launch charge for free, or an EMP disable cancelled. Ignored on the server;
     * the GUI's frequency and target packets (1, 2) are untouched.
     */
    static byte[] patchCruiseLauncher(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("handlePacketData")) continue;
            VarInsnNode store = packetIdStore(m);
            if (store == null) throw new IllegalStateException("cruiselauncher: no packet ID read");
            m.instructions.insert(store, refuseOnServer(CRUISE, store.var, 0, "cruise launcher description packet"));
            m.maxStack = Math.max(m.maxStack, 3);
            hitCruise = true;
        }
        return write(cn);
    }

    /**
     * ZhaPinPacketGuanLi.handlePacketData, LASER_DESIGNATOR branch. The client only sends the
     * packet when the held designator has a frequency, more than 6,000 J and no strike counting
     * down, and the target came from its own ray trace. The server checks only that a designator
     * is held, then starts a strike and spawns a light-beam entity at the client's coordinates.
     * Right after the target vector is stored (`astore` of a new Vector3), insert
     *
     *     if (!VoltzICBM.designatorAllowed(player, target, designator.getFrequency(stack),
     *             designator.getLauncherCountDown(stack), designator.getJoules(stack),
     *             ItLeiSheZhiBiao.BAN_JING)) return;
     */
    static byte[] patchDesignator(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("handlePacketData")) continue;
            int hits = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.ASTORE) continue;
                AbstractInsnNode prev = i.getPrevious();
                while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
                if (!(prev instanceof MethodInsnNode) || !((MethodInsnNode) prev).owner.equals("universalelectricity/core/vector/Vector3")
                        || !((MethodInsnNode) prev).name.equals("<init>")) continue;
                int target = ((VarInsnNode) i).var;
                int stack = target - 1;                       // the held designator, stored just before
                LabelNode carryOn = new LabelNode();
                InsnList g = new InsnList();
                g.add(new VarInsnNode(Opcodes.ALOAD, 4));
                g.add(new VarInsnNode(Opcodes.ALOAD, target));
                g.add(new FieldInsnNode(Opcodes.GETSTATIC, MAIN_CLASS, "itLeiSheZhiBiao", "L" + ELECTRIC + ";"));
                g.add(new TypeInsnNode(Opcodes.CHECKCAST, DESIGNATOR));
                g.add(new VarInsnNode(Opcodes.ALOAD, stack));
                g.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DESIGNATOR, "getFrequency", "(L" + STACK + ";)I", false));
                g.add(new FieldInsnNode(Opcodes.GETSTATIC, MAIN_CLASS, "itLeiSheZhiBiao", "L" + ELECTRIC + ";"));
                g.add(new TypeInsnNode(Opcodes.CHECKCAST, DESIGNATOR));
                g.add(new VarInsnNode(Opcodes.ALOAD, stack));
                g.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DESIGNATOR, "getLauncherCountDown", "(L" + STACK + ";)I", false));
                g.add(new FieldInsnNode(Opcodes.GETSTATIC, MAIN_CLASS, "itLeiSheZhiBiao", "L" + ELECTRIC + ";"));
                g.add(new VarInsnNode(Opcodes.ALOAD, stack));
                g.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ELECTRIC, "getJoules", "(L" + STACK + ";)D", false));
                g.add(new FieldInsnNode(Opcodes.GETSTATIC, DESIGNATOR, "BAN_JING", "I"));
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "designatorAllowed",
                        "(Ljava/lang/Object;Ljava/lang/Object;IIDI)Z", false));
                g.add(new JumpInsnNode(Opcodes.IFNE, carryOn));
                g.add(new InsnNode(Opcodes.RETURN));
                g.add(carryOn);
                m.instructions.insert(i, g);
                hits++;
            }
            if (hits != 1) throw new IllegalStateException("designator: expected 1 target vector, found " + hits);
            m.maxStack = m.maxStack + 8;
            hitDesignator = true;
        }
        return write(cn);
    }

    /**
     * ItJieJa (defuser) onLeftClickEntity drops the explosive and kills the entity, with no check
     * that it is still alive. A dead entity stays attackable until the world tick removes it, so
     * two attacks in one tick drop two explosives. Prepend
     *
     *     if (entity.isDead) return true;
     */
    static byte[] patchDefuser(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("onLeftClickEntity")) continue;
            LabelNode carryOn = new LabelNode();
            InsnList g = new InsnList();
            g.add(new VarInsnNode(Opcodes.ALOAD, 3));
            g.add(new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/entity/Entity", "field_70128_L", "Z"));   // isDead
            g.add(new JumpInsnNode(Opcodes.IFEQ, carryOn));
            g.add(new InsnNode(Opcodes.ICONST_1));
            g.add(new InsnNode(Opcodes.IRETURN));
            g.add(carryOn);
            m.instructions.insert(g);
            m.maxStack = Math.max(m.maxStack, 1);
            hitDefuser = true;
        }
        return write(cn);
    }

    /**
     * TFaSheDi and TXiaoFaSheQi onActivated load a missile with
     *
     *     setInventorySlotContents(0, player.inventory.getCurrentItem());
     *     player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
     *
     * which loads the whole held stack into a one-missile slot and deletes it from the hand. The
     * first call now gets `getCurrentItem().splitStack(1)`, and the `null` becomes
     * VoltzICBM.heldRemainder(player): the rest of the stack, or null when nothing is left.
     */
    static byte[] patchMissileStack(byte[] in, String owner) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("onActivated") || !m.desc.equals("(Lnet/minecraft/entity/player/EntityPlayer;)Z")) continue;
            int split = 0, remainder = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.name.equals("func_70299_a")) continue;                                   // setInventorySlotContents
                AbstractInsnNode prev = mi.getPrevious();
                while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
                if (mi.owner.equals(owner) && prev instanceof MethodInsnNode && ((MethodInsnNode) prev).name.equals("func_70448_g")) {
                    InsnList one = new InsnList();
                    one.add(new InsnNode(Opcodes.ICONST_1));
                    one.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, STACK, "func_77979_a", "(I)L" + STACK + ";", false));   // splitStack
                    m.instructions.insertBefore(mi, one);
                    split++;
                } else if (mi.owner.equals(INV_PLAYER) && prev.getOpcode() == Opcodes.ACONST_NULL) {
                    InsnList rest = new InsnList();
                    rest.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    rest.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "heldRemainder", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
                    rest.add(new TypeInsnNode(Opcodes.CHECKCAST, STACK));
                    m.instructions.insertBefore(prev, rest);
                    m.instructions.remove(prev);
                    remainder++;
                }
            }
            if (split != 1 || remainder != 1)
                throw new IllegalStateException("missilestack: " + owner + " expected 1 load and 1 clear, found " + split + " and " + remainder);
            m.maxStack = m.maxStack + 1;
            missileHits.add(owner);
        }
        return write(cn);
    }

    /**
     * The EMP tower, launcher screen, radar and cruise launcher keep a Set of players with the
     * GUI open (yongZhe) and send each one a description packet from their tick. Players join
     * through packet -1 from any distance, and are only removed when their client says the GUI
     * closed, so a disconnect or death leaves them in the set until the chunk unloads. Two changes:
     *
     *     yongZhe.add(player)       -> VoltzICBM.addListener(yongZhe, player, this)   joins within reach
     *     yongZhe.iterator() in the tick gets VoltzICBM.pruneListeners(yongZhe, this) first
     */
    static byte[] patchListeners(byte[] in, String owner) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.GETFIELD) continue;
                FieldInsnNode f = (FieldInsnNode) i;
                if (!f.owner.equals(owner) || !f.name.equals("yongZhe")) continue;
                AbstractInsnNode nx = i.getNext();
                while (nx != null && nx.getOpcode() < 0) nx = nx.getNext();
                if (m.name.equals("func_70316_g") && nx instanceof MethodInsnNode && ((MethodInsnNode) nx).name.equals("iterator")) {
                    InsnList prune = new InsnList();
                    prune.add(new InsnNode(Opcodes.DUP));
                    prune.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    prune.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "pruneListeners", "(Ljava/util/Set;Ljava/lang/Object;)V", false));
                    m.instructions.insert(i, prune);
                    m.maxStack = m.maxStack + 2;
                    listenerHits.add(owner + ".tick");
                } else if (m.name.equals("handlePacketData") && nx.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) nx).var == 4) {
                    AbstractInsnNode call = nx.getNext();
                    while (call != null && call.getOpcode() < 0) call = call.getNext();
                    if (!(call instanceof MethodInsnNode) || !((MethodInsnNode) call).name.equals("add")) continue;
                    m.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
                    m.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "addListener",
                            "(Ljava/util/Set;Ljava/lang/Object;Ljava/lang/Object;)Z", false));
                    m.maxStack = m.maxStack + 1;
                    listenerHits.add(owner + ".join");
                }
            }
        }
        return write(cn);
    }

    /**
     * TLeiDaTai (radar). Packets 2 and 3 set the safety and alarm radius from the GUI, which
     * clamps them to 0..MAX_BIAN_JING (500); the server takes any int. Every write to either
     * field - constructor, packets, NBT load - goes through VoltzICBM.radarRadius.
     */
    static byte[] patchRadarRadius(byte[] in) {
        ClassNode cn = read(in);
        int hits = 0;
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.PUTFIELD) continue;
                FieldInsnNode f = (FieldInsnNode) i;
                if (!f.owner.equals(RADAR) || !(f.name.equals("alarmBanJing") || f.name.equals("safetyBanJing"))) continue;
                m.instructions.insertBefore(i, new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "radarRadius", "(I)I", false));
                hits++;
            }
        }
        if (hits != 8) throw new IllegalStateException("radarradius: expected 8 radius writes, found " + hits);
        hitRadarRadius = true;
        return write(cn);
    }

    /**
     * ZhaPinPacketGuanLi.handlePacketData, RADAR_GUN branch: stores the client's coordinates on
     * the held radar gun and drains 1,000 J, without the client's check that the gun has more
     * than 1,000 J. Right after the held stack is stored (the store followed by a read of its NBT
     * tag), insert
     *
     *     if (itLeiDaQiang.getJoules(stack) <= 1000) { VoltzICBM.radarGunRefused(player); return; }
     */
    static byte[] patchRadarGun(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("handlePacketData")) continue;
            int hits = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.ASTORE) continue;
                AbstractInsnNode a = i.getNext();
                while (a != null && a.getOpcode() < 0) a = a.getNext();
                AbstractInsnNode b = a == null ? null : a.getNext();
                while (b != null && b.getOpcode() < 0) b = b.getNext();
                if (a == null || a.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) a).var != ((VarInsnNode) i).var
                        || !(b instanceof FieldInsnNode) || !((FieldInsnNode) b).name.equals("field_77990_d")) continue;   // stackTagCompound
                int stack = ((VarInsnNode) i).var;
                LabelNode carryOn = new LabelNode();
                InsnList g = new InsnList();
                g.add(new FieldInsnNode(Opcodes.GETSTATIC, MAIN_CLASS, "itLeiDaQiang", "L" + ELECTRIC + ";"));
                g.add(new VarInsnNode(Opcodes.ALOAD, stack));
                g.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ELECTRIC, "getJoules", "(L" + STACK + ";)D", false));
                g.add(new LdcInsnNode(Double.valueOf(1000.0d)));
                g.add(new InsnNode(Opcodes.DCMPL));
                g.add(new JumpInsnNode(Opcodes.IFGT, carryOn));
                g.add(new VarInsnNode(Opcodes.ALOAD, 4));
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "radarGunRefused", "(Ljava/lang/Object;)V", false));
                g.add(new InsnNode(Opcodes.RETURN));
                g.add(carryOn);
                m.instructions.insert(i, g);
                hits++;
            }
            if (hits != 1) throw new IllegalStateException("radargun: expected 1 radar gun stack store, found " + hits);
            m.maxStack = m.maxStack + 4;
            hitRadarGun = true;
        }
        return write(cn);
    }

    /**
     * Adds an onPacketData(INetworkManager, Packet250CustomPayload, Player) override to the ICBM
     * packet handler, gating the inherited router on {HELPER}.chunkGuard so a TILEENTITY packet
     * aimed at an unloaded chunk is dropped instead of loading it. Only added when the class does
     * not already define onPacketData.
     */
    static byte[] patchChunkLoad(byte[] in) {{
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {{
            MethodNode em = (MethodNode) mo;
            if (em.name.equals("onPacketData") && em.desc.equals(ON_PACKET_DESC))
                throw new IllegalStateException("chunkload: ZhaPinPacketGuanLi already defines onPacketData");
        }}
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "onPacketData", ON_PACKET_DESC, null, null);
        LabelNode drop = new LabelNode();
        InsnList g = m.instructions;
        g.add(new VarInsnNode(Opcodes.ALOAD, 2));                                               // packet
        g.add(new VarInsnNode(Opcodes.ALOAD, 3));                                               // player
        g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "chunkGuard", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        g.add(new JumpInsnNode(Opcodes.IFEQ, drop));
        g.add(new VarInsnNode(Opcodes.ALOAD, 0));
        g.add(new VarInsnNode(Opcodes.ALOAD, 1));
        g.add(new VarInsnNode(Opcodes.ALOAD, 2));
        g.add(new VarInsnNode(Opcodes.ALOAD, 3));
        g.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, ROUTER, "onPacketData", ON_PACKET_DESC, false));
        g.add(new LabelNode());
        g.add(drop);
        g.add(new InsnNode(Opcodes.RETURN));
        m.maxStack = 4;
        m.maxLocals = 4;
        cn.methods.add(m);
        hitChunkLoad = true;
        return write(cn);
    }}

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
