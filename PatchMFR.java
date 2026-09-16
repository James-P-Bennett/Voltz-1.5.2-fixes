import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - MineFactoryReloaded 2.6.4 patches.
 *
 *   ghostslot   ServerPacketHandler, packet 19   ghost-slot writes must name an open SlotFake
 *   pkttile     ServerPacketHandler, every packet   client coordinates must name a loaded
 *                                                   tile in reach
 *   harvester   ServerPacketHandler, packet 3    only the Harvester's own three setting keys
 *   dsuside     ServerPacketHandler, packet 5    Deep Storage Unit side index bounded to 0..5
 *   rednet      ServerPacketHandler, packets 13/14/15   RedNet Logic circuit, pin and buffer
 *                                                       numbers bounded; circuit class names
 *                                                       limited to registered circuits
 *   guidupe     TileEntityDeepStorageUnit / TileEntityLaserDrill / TileEntityLiquiCrafter
 *               .isUseableByPlayer   restores the "still the tile at my own position" check
 *               those three drop when they override TileEntityFactoryInventory
 *   routerloop  TileEntityLiquidRouter.weightedRouteLiquid   depth-capped, so routers pointed
 *               at each other no longer recurse until the server thread runs out of stack
 *   dsunbt      BlockFactoryMachine   the Deep Storage Unit contents hand-off is per world
 *               thread, position-checked and same-tick instead of a global x/y/z map
 *
 * usage: PatchMFR <in.jar> <out.jar> <patch>[,<patch>...] <VoltzMFR.class>
 */
public class PatchMFR {

    static final String HANDLER  = "powercrystals/minefactoryreloaded/net/ServerPacketHandler";
    static final String HELPER   = "powercrystals/minefactoryreloaded/VoltzMFR";
    static final String ROUTER   = "powercrystals/minefactoryreloaded/tile/machine/TileEntityLiquidRouter";
    static final String MACHINE  = "powercrystals/minefactoryreloaded/block/BlockFactoryMachine";
    static final String NBT_MGR  = "powercrystals/minefactoryreloaded/core/BlockNBTManager";
    static final String RED_NET  = "powercrystals/minefactoryreloaded/tile/rednet/TileEntityRedNetLogic";
    static final String DSU_TILE = "powercrystals/minefactoryreloaded/tile/machine/TileEntityDeepStorageUnit";
    static final String[] GUI_TILES = {
        DSU_TILE,
        "powercrystals/minefactoryreloaded/tile/machine/TileEntityLaserDrill",
        "powercrystals/minefactoryreloaded/tile/machine/TileEntityLiquiCrafter",
    };

    static final String WORLD     = "net/minecraft/world/World";
    static final String TILE      = "net/minecraft/tileentity/TileEntity";
    static final String NBT       = "net/minecraft/nbt/NBTTagCompound";
    static final String OBJ       = "Ljava/lang/Object;";
    static final String ON_PACKET_DESC =
            "(Lnet/minecraft/network/INetworkManager;Lnet/minecraft/network/packet/Packet250CustomPayload;Lcpw/mods/fml/common/network/Player;)V";
    static final String USABLE_DESC = "(Lnet/minecraft/entity/player/EntityPlayer;)Z";
    static final int L_PLAYER = 3;

    static final List<String> KNOWN = Arrays.asList("ghostslot", "pkttile", "harvester",
            "dsuside", "rednet", "guidupe", "routerloop", "dsunbt");

    static final Set<String> selected = new HashSet<String>();
    /** patch -> site -> hits, checked against the expected counts before anything is written */
    static final Map<String, Integer> hits = new LinkedHashMap<String, Integer>();

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchMFR <in.jar> <out.jar> <patches> <VoltzMFR.class>");
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
        expected.put("ghostslot:setInventorySlotContents", 2);
        expected.put("pkttile:getBlockTileEntity", 12);
        expected.put("harvester:settings.put", 1);
        expected.put("dsuside:getIsSideOutput", 1);
        expected.put("dsuside:setSideIsOutput", 1);
        expected.put("rednet:sendCircuitDefinition", 3);
        expected.put("rednet:initCircuit", 1);
        expected.put("rednet:setInputPinMapping", 1);
        expected.put("rednet:setOutputPinMapping", 1);
        expected.put("guidupe:isUseableByPlayer", GUI_TILES.length);
        expected.put("routerloop:fill", 2);
        expected.put("dsunbt:setForBlock", 1);
        expected.put("dsunbt:getForBlock", 1);
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
        if (cls.equals(HANDLER)) return patchHandler(d);
        if (cls.equals(ROUTER) && selected.contains("routerloop")) return patchRouter(d);
        if (cls.equals(MACHINE) && selected.contains("dsunbt")) return patchMachineBlock(d);
        if (selected.contains("guidupe")) {
            for (String t : GUI_TILES) if (cls.equals(t)) return patchUsable(d);
        }
        return d;
    }

    // ------------------------------------------------------------ packet handler

    /**
     * Everything in onPacketData is a straight call-for-call swap: the receiver and arguments
     * already on the stack become the leading arguments of a static helper with the same
     * return type, so nothing branches and no stack map is needed.
     */
    static byte[] patchHandler(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("onPacketData") || !m.desc.equals(ON_PACKET_DESC)) continue;
            int extraStack = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (!(i instanceof MethodInsnNode)) continue;
                MethodInsnNode mi = (MethodInsnNode) i;

                if (selected.contains("ghostslot") && mi.getOpcode() == Opcodes.INVOKEINTERFACE
                        && mi.owner.equals("net/minecraft/inventory/IInventory")
                        && mi.name.equals("func_70299_a")) {
                    m.instructions.insertBefore(mi, new VarInsnNode(Opcodes.ALOAD, L_PLAYER));
                    m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "setGhostSlot",
                            "(" + OBJ + "I" + OBJ + OBJ + ")V", false));
                    extraStack = 1;
                    hit("ghostslot:setInventorySlotContents");
                    continue;
                }
                if (selected.contains("pkttile") && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && mi.owner.equals(WORLD) && mi.name.equals("func_72796_p")) {
                    m.instructions.insertBefore(mi, new VarInsnNode(Opcodes.ALOAD, L_PLAYER));
                    MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "tileAt",
                            "(" + OBJ + "III" + OBJ + ")" + OBJ, false);
                    m.instructions.set(mi, call);
                    m.instructions.insert(call, new TypeInsnNode(Opcodes.CHECKCAST, TILE));
                    extraStack = 1;
                    hit("pkttile:getBlockTileEntity");
                    continue;
                }
                if (selected.contains("harvester") && mi.getOpcode() == Opcodes.INVOKEINTERFACE
                        && mi.owner.equals("java/util/Map") && mi.name.equals("put")) {
                    m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "harvesterSetting",
                            "(" + OBJ + OBJ + OBJ + ")" + OBJ, false));
                    hit("harvester:settings.put");
                    continue;
                }
                if (selected.contains("dsuside") && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && mi.owner.equals(DSU_TILE)) {
                    if (mi.name.equals("getIsSideOutput")) {
                        m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "dsuGetSide",
                                "(" + OBJ + "I)Z", false));
                        hit("dsuside:getIsSideOutput");
                        continue;
                    }
                    if (mi.name.equals("setSideIsOutput")) {
                        m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "dsuSide",
                                "(" + OBJ + "IZ)V", false));
                        hit("dsuside:setSideIsOutput");
                        continue;
                    }
                }
                if (selected.contains("rednet") && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && mi.owner.equals(RED_NET)) {
                    if (mi.name.equals("sendCircuitDefinition")) {
                        m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "rnSendDef",
                                "(" + OBJ + "I)V", false));
                        hit("rednet:sendCircuitDefinition");
                    } else if (mi.name.equals("initCircuit")) {
                        m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "rnInit",
                                "(" + OBJ + "ILjava/lang/String;)V", false));
                        hit("rednet:initCircuit");
                    } else if (mi.name.equals("setInputPinMapping")) {
                        m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "rnPinIn",
                                "(" + OBJ + "IIII)V", false));
                        hit("rednet:setInputPinMapping");
                    } else if (mi.name.equals("setOutputPinMapping")) {
                        m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "rnPinOut",
                                "(" + OBJ + "IIII)V", false));
                        hit("rednet:setOutputPinMapping");
                    }
                }
            }
            m.maxStack = m.maxStack + extraStack;
        }
        return write(cn);
    }

    // ------------------------------------------------------------- liquid router

    static byte[] patchRouter(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("weightedRouteLiquid")) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.owner.equals("net/minecraftforge/liquids/ITankContainer")
                        || !mi.name.equals("fill")
                        || !mi.desc.startsWith("(Lnet/minecraftforge/common/ForgeDirection;")) continue;
                m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "routeFill",
                        "(" + OBJ + OBJ + OBJ + "Z)I", false));
                hit("routerloop:fill");
            }
        }
        return write(cn);
    }

    // ------------------------------------------------- deep storage unit hand-off

    static byte[] patchMachineBlock(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKESTATIC) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.owner.equals(NBT_MGR)) continue;
                if (mi.name.equals("setForBlock") && mi.desc.equals("(L" + TILE + ";)V")) {
                    m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "setBlockNbt",
                            "(" + OBJ + ")V", false));
                    hit("dsunbt:setForBlock");
                } else if (mi.name.equals("getForBlock")) {
                    MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "getBlockNbt",
                            "(III)" + OBJ, false);
                    m.instructions.set(mi, call);
                    m.instructions.insert(call, new TypeInsnNode(Opcodes.CHECKCAST, NBT));
                    hit("dsunbt:getForBlock");
                }
            }
        }
        return write(cn);
    }

    // ------------------------------------------------------------------ machines

    /** isUseableByPlayer becomes a straight `return VoltzMFR.tileUsable(this, player)`. */
    static byte[] patchUsable(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("func_70300_a") || !m.desc.equals(USABLE_DESC)) continue;
            m.instructions.clear();
            m.tryCatchBlocks.clear();
            if (m.localVariables != null) m.localVariables.clear();
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "tileUsable",
                    "(" + OBJ + OBJ + ")Z", false));
            m.instructions.add(new InsnNode(Opcodes.IRETURN));
            m.maxStack = 2;
            m.maxLocals = 2;
            hit("guidupe:isUseableByPlayer");
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
