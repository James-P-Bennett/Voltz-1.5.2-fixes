import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - Mekanism 5.5.6.bugfix1 patches.
 *
 *   chestcrash   TileEntityElectricChest.getAccessibleSlotsFromSide
 *                  fills a 55-slot array with `i <= 55` - any side but the bottom throws,
 *                  so a hopper or pipe on it crashes the server every time the chunk loads
 *   chestdupe    InventoryElectricChest     bound to the stack it was opened from
 *                ContainerElectricChest     item GUI closes once that stack leaves its slot
 *                SlotElectricChest          + isItemValid: no Electric Chests inside one
 *                TileEntityElectricChest    isItemValidForSlot: same, for automation
 *   chestremote  PacketElectricChest.read   client coordinates must name a loaded chest in reach
 *   machinedupe  TileEntityContainerBlock.isUseableByPlayer   vanilla chest semantics
 *   robitdupe    ContainerRobitMain/Inventory/Smelting.canInteractWith   Robit alive and in reach
 *                CommonProxy.getServerGui   client-sent Robit id must be a Robit in reach
 *   tntdupe      BlockObsidianTNT.onBlockDestroyedByPlayer   no second drop on top of the harvest drop
 *   tntsource    EntityObsidianTNT.explode  pass itself as the exploder, as vanilla TNT does
 *   timeitems    PacketTime / PacketWeather.read   routed through BalancedTimeItems
 *
 * usage: PatchMek <in.jar> <out.jar> <patch>[,<patch>...] <VoltzMekanism.class> <BalancedTimeItems.class>
 */
public class PatchMek {

    static final String C = "mekanism/common/";
    static final String TE_CHEST     = C + "TileEntityElectricChest";
    static final String SLOT_CHEST   = C + "SlotElectricChest";
    static final String INV_CHEST    = C + "InventoryElectricChest";
    static final String CONT_CHEST   = C + "ContainerElectricChest";
    static final String PKT_CHEST    = C + "network/PacketElectricChest";
    static final String TE_CONTAINER = C + "TileEntityContainerBlock";
    static final String PROXY        = C + "CommonProxy";
    static final String ROBIT        = C + "EntityRobit";
    static final String TNT_BLOCK    = C + "BlockObsidianTNT";
    static final String TNT_ENTITY   = C + "EntityObsidianTNT";
    static final String PKT_TIME     = C + "network/PacketTime";
    static final String PKT_WEATHER  = C + "network/PacketWeather";
    static final String MAIN         = C + "Mekanism";
    static final String FIX          = C + "VoltzMekanism";
    static final String TIME         = C + "BalancedTimeItems";
    static final String TE_CUBE      = C + "TileEntityEnergyCube";
    static final String CABLE_UTILS  = C + "CableUtils";
    static final String ENERGY_NET   = C + "EnergyNetwork";
    static final String HOOKS        = C + "MekanismHooks";
    static final String[] ROBIT_CONTAINERS = {
        C + "ContainerRobitMain", C + "ContainerRobitInventory", C + "ContainerRobitSmelting",
    };

    static final String WORLD      = "net/minecraft/world/World";
    static final String ITEMSTACK  = "net/minecraft/item/ItemStack";
    static final String DATA_INPUT = "com/google/common/io/ByteArrayDataInput";
    static final String PACKET_READ_DESC =
            "(Lcom/google/common/io/ByteArrayDataInput;Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;)V";
    static final String USABLE_DESC = "(Lnet/minecraft/entity/player/EntityPlayer;)Z";
    static final String OBJ2_Z = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

    // locals of IMekanismPacket.read(ByteArrayDataInput, EntityPlayer, World)
    static final int L_STREAM = 1;
    static final int L_PLAYER = 2;
    static final int L_WORLD  = 3;

    static final Set<String> selected = new LinkedHashSet<String>();
    static final Set<String> applied = new HashSet<String>();

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: PatchMek <in.jar> <out.jar> <patches> <VoltzMekanism.class> <BalancedTimeItems.class>");
            System.err.println("patches: chestcrash,chestdupe,chestremote,machinedupe,robitdupe,tntdupe,tntsource,timeitems");
            System.exit(2);
        }
        List<String> known = Arrays.asList("chestcrash", "chestdupe", "chestremote", "machinedupe",
                "robitdupe", "tntdupe", "tntsource", "timeitems", "aebridge", "cablereload");
        for (String p : args[2].split(",")) {
            p = p.trim();
            if (!known.contains(p)) throw new IllegalArgumentException("unknown patch: " + p);
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
        injectHelper(out, FIX, args[3]);
        injectHelper(out, TIME, args[4]);

        // every site a selected patch touches must have been found, exactly as expected
        Map<String, String[]> sites = new LinkedHashMap<String, String[]>();
        sites.put("chestcrash",  new String[] { "chestcrash.slots" });
        sites.put("chestdupe",   new String[] { "chestdupe.bind", "chestdupe.getItemStack", "chestdupe.guards",
                                                "chestdupe.container", "chestdupe.slot", "chestdupe.tile" });
        sites.put("chestremote", new String[] { "chestremote.packet" });
        sites.put("machinedupe", new String[] { "machinedupe.tile" });
        sites.put("robitdupe",   new String[] { "robitdupe.containers", "robitdupe.proxy" });
        sites.put("tntdupe",     new String[] { "tntdupe.drop" });
        sites.put("tntsource",   new String[] { "tntsource.exploder" });
        sites.put("timeitems",   new String[] { "timeitems." + PKT_TIME, "timeitems." + PKT_WEATHER, "timeitems.init" });
        sites.put("aebridge",    new String[] { "aebridge." + TE_CUBE + ".onUpdate",
                                                "aebridge." + CABLE_UTILS + ".getConnectedEnergyAcceptors",
                                                "aebridge." + ENERGY_NET + ".getEnergyAcceptors",
                                                "aebridge." + ENERGY_NET + ".emit" });
        sites.put("cablereload", new String[] { "cablereload.tick" });
        for (String p : selected)
            for (String s : sites.get(p))
                if (!applied.contains(s)) throw new IllegalStateException(p + " did not apply: " + s);

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])));
        for (Map.Entry<String, byte[]> en : out.entrySet()) {
            zos.putNextEntry(new ZipEntry(en.getKey()));
            if (en.getValue() != null) zos.write(en.getValue());
            zos.closeEntry();
        }
        zos.close();
        System.out.println("OK  wrote " + args[1] + "  [" + args[2] + "]");
    }

    static final Set<String> robitContainersDone = new HashSet<String>();
    static final Set<String> guardsDone = new HashSet<String>();

    static byte[] patchClass(String cls, byte[] in) {
        boolean chestcrash = selected.contains("chestcrash"), chestdupe = selected.contains("chestdupe");
        if (cls.equals(TE_CHEST) && (chestcrash || chestdupe)) return patchChestTile(in, chestcrash, chestdupe);
        if (cls.equals(SLOT_CHEST) && chestdupe)   return patchChestSlot(in);
        if (cls.equals(INV_CHEST) && chestdupe)    return patchChestInventory(in);
        if (cls.equals(CONT_CHEST) && chestdupe)   return patchChestContainer(in);
        if (cls.equals(PKT_CHEST) && selected.contains("chestremote"))  return patchChestPacket(in);
        if (cls.equals(TE_CONTAINER) && selected.contains("machinedupe")) return patchMachineUsable(in);
        if (cls.equals(PROXY) && selected.contains("robitdupe"))        return patchRobitGui(in);
        if (Arrays.asList(ROBIT_CONTAINERS).contains(cls) && selected.contains("robitdupe"))
            return patchRobitContainer(in, cls);
        if (cls.equals(TNT_BLOCK) && selected.contains("tntdupe"))      return patchTntDrop(in);
        if (cls.equals(TNT_ENTITY) && selected.contains("tntsource"))   return patchTntSource(in);
        if ((cls.equals(PKT_TIME) || cls.equals(PKT_WEATHER)) && selected.contains("timeitems"))
            return patchTimePacket(in, cls, cls.equals(PKT_TIME) ? 0 : 1);
        if (cls.equals(MAIN) && selected.contains("timeitems"))         return patchInitHook(in);
        if (selected.contains("aebridge")
                && (cls.equals(TE_CUBE) || cls.equals(CABLE_UTILS) || cls.equals(ENERGY_NET)))
            in = patchAeBridge(in, cls);
        if (selected.contains("cablereload") && cls.equals(ENERGY_NET))
            in = patchCableReload(in);
        return in;
    }

    /**
     * cablereload: the energy network's acceptor set (possibleAcceptors) is filled only by
     * refresh(); cables never tick (canUpdate() is false); and after a chunk unload/reload it is
     * not reliably rebuilt for an acceptor that loads on a different schedule than the cable - so
     * a working cable-to-AE link silently dies until a neighbouring block is changed. The network
     * itself is ticked by EnergyNetworkRegistry, so at the top of EnergyNetwork.tick() insert
     *
     *     if (VoltzMekanism.shouldRefreshNetwork(this)) this.refresh();
     *
     * which re-scans acceptors every few seconds and heals the link.
     */
    static byte[] patchCableReload(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("tick") || !m.desc.equals("()V")) continue;
            LabelNode skip = new LabelNode();
            InsnList g = new InsnList();
            g.add(new VarInsnNode(Opcodes.ALOAD, 0));
            g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FIX, "shouldRefreshNetwork",
                    "(Ljava/lang/Object;)Z", false));
            g.add(new JumpInsnNode(Opcodes.IFEQ, skip));
            g.add(new VarInsnNode(Opcodes.ALOAD, 0));
            g.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENERGY_NET, "refresh", "()V", false));
            g.add(skip);
            m.instructions.insert(g);
            m.maxStack = Math.max(m.maxStack, 1);
            applied.add("cablereload.tick");
        }
        return write(cn);
    }

    // --------------------------------------------------------- AE power bridge

    /**
     * aebridge: Mekanism gates every BuildCraft IPowerReceptor OUTPUT path behind
     * MekanismHooks.BuildCraftLoaded (= the BuildCraft mod is installed). Applied Energistics
     * implements IPowerReceptor via the bundled BuildCraft power API with no BuildCraft mod, so
     * with AE and no BuildCraft (Voltz) Mekanism never powers AE - a cube face against the ME
     * Controller does nothing and the cable network skips AE. In the four output methods, the
     * `getfield MekanismHooks.BuildCraftLoaded` (which follows `getstatic Mekanism.hooks`) is
     * replaced by `pop; VoltzMekanism.bcPowerAvailable()` - same stack shape, but gated on the
     * BC power API actually being on the classpath instead of on the mod being present.
     */
    static byte[] patchAeBridge(byte[] in, String cls) {
        String[] methods;
        if (cls.equals(TE_CUBE))          methods = new String[] { "onUpdate" };
        else if (cls.equals(CABLE_UTILS)) methods = new String[] { "getConnectedEnergyAcceptors" };
        else                              methods = new String[] { "getEnergyAcceptors", "emit" };
        Set<String> want = new HashSet<String>(Arrays.asList(methods));
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!want.contains(m.name)) continue;
            int hits = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.GETFIELD) continue;
                FieldInsnNode f = (FieldInsnNode) i;
                if (!f.owner.equals(HOOKS) || !f.name.equals("BuildCraftLoaded")) continue;
                InsnList repl = new InsnList();
                repl.add(new InsnNode(Opcodes.POP));                                   // drop the hooks objectref
                repl.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FIX, "bcPowerAvailable", "()Z", false));
                m.instructions.insert(i, repl);
                m.instructions.remove(i);
                hits++;
            }
            if (hits != 1)
                throw new IllegalStateException("aebridge: " + cls + "." + m.name
                        + " expected 1 BuildCraftLoaded gate, found " + hits);
            applied.add("aebridge." + cls + "." + m.name);
        }
        return write(cn);
    }

    // ------------------------------------------------------------ Electric Chest

    /**
     * chestcrash: getAccessibleSlotsFromSide(side != 0) builds `new int[55]` and fills it
     * with `for (i = 0; i <= ret.length; i++)`, which writes index 55 and throws. Becomes
     * `new int[54]` and `i < ret.length`: slots 0-53, the storage slots, matching
     * getSizeInventorySide. The bottom face still exposes the energy slot 54 alone.
     *
     * chestdupe: isItemValidForSlot refuses Electric Chests, so automation can't nest them.
     */
    static byte[] patchChestTile(byte[] in, boolean crash, boolean dupe) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (crash && m.name.equals("func_94128_d") && m.desc.equals("(I)[I")) {
                int sizes = 0, compares = 0;
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i.getOpcode() == Opcodes.BIPUSH && ((IntInsnNode) i).operand == 55) {
                        ((IntInsnNode) i).operand = 54;
                        sizes++;
                    }
                    if (i.getOpcode() == Opcodes.IF_ICMPGT) {
                        ((JumpInsnNode) i).setOpcode(Opcodes.IF_ICMPGE);
                        compares++;
                    }
                }
                if (sizes != 1 || compares != 1)
                    throw new IllegalStateException("chestcrash: expected one 55 and one if_icmpgt, found "
                            + sizes + "/" + compares);
                applied.add("chestcrash.slots");
            }
            if (dupe && m.name.equals("func_94041_b") && m.desc.equals("(ILnet/minecraft/item/ItemStack;)Z")) {
                LabelNode carryOn = new LabelNode();
                InsnList g = new InsnList();
                g.add(new VarInsnNode(Opcodes.ALOAD, 2));
                g.add(new InsnNode(Opcodes.ACONST_NULL));
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FIX, "allowedInChest", OBJ2_Z, false));
                g.add(new JumpInsnNode(Opcodes.IFNE, carryOn));
                g.add(new InsnNode(Opcodes.ICONST_0));
                g.add(new InsnNode(Opcodes.IRETURN));
                g.add(carryOn);
                m.instructions.insert(g);
                m.maxStack = Math.max(m.maxStack, 2);
                applied.add("chestdupe.tile");
            }
        }
        return write(cn);
    }

    /** chestdupe: add isItemValid(stack) = VoltzMekanism.allowedInChest(stack, this.inventory). */
    static byte[] patchChestSlot(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods)
            if (((MethodNode) mo).name.equals("func_75214_a"))
                throw new IllegalStateException("chestdupe: SlotElectricChest already has isItemValid");
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "func_75214_a",
                "(Lnet/minecraft/item/ItemStack;)Z", null, null);
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/inventory/Slot",
                "field_75224_c", "Lnet/minecraft/inventory/IInventory;"));      // Slot.inventory
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FIX, "allowedInChest", OBJ2_Z, false));
        m.instructions.add(new InsnNode(Opcodes.IRETURN));
        m.maxStack = 2;
        m.maxLocals = 2;
        cn.methods.add(m);
        applied.add("chestdupe.slot");
        return write(cn);
    }

    /**
     * chestdupe: bind in the constructor, resolve getItemStack() through the binding, and
     * make read/write/openChest/closeChest do nothing once the binding is gone - stock would
     * NPE or, worse, write the contents into whatever item replaced the chest.
     */
    static byte[] patchChestInventory(byte[] in) {
        ClassNode cn = read(in);
        String[] guarded = { "read", "write", "func_70295_k_", "func_70305_f" };
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (m.name.equals("<init>")) {
                for (AbstractInsnNode i : m.instructions.toArray()) {
                    if (i.getOpcode() != Opcodes.INVOKESPECIAL) continue;
                    MethodInsnNode mi = (MethodInsnNode) i;
                    if (!mi.owner.equals("net/minecraft/inventory/InventoryBasic") || !mi.name.equals("<init>")) continue;
                    InsnList add = new InsnList();
                    add.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    add.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FIX, "bindChest",
                            "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                    m.instructions.insert(mi, add);
                    m.maxStack = Math.max(m.maxStack, 2);
                    applied.add("chestdupe.bind");
                }
            }
            if (m.name.equals("getItemStack") && m.desc.equals("()Lnet/minecraft/item/ItemStack;")) {
                m.instructions.clear();
                m.tryCatchBlocks.clear();
                if (m.localVariables != null) m.localVariables.clear();
                m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FIX, "chestStack",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false));
                m.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ITEMSTACK));
                m.instructions.add(new InsnNode(Opcodes.ARETURN));
                m.maxStack = 1;
                m.maxLocals = 1;
                applied.add("chestdupe.getItemStack");
            }
            if (Arrays.asList(guarded).contains(m.name) && m.desc.equals("()V")) {
                LabelNode carryOn = new LabelNode();
                InsnList g = new InsnList();
                g.add(new VarInsnNode(Opcodes.ALOAD, 0));
                g.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, INV_CHEST, "getItemStack",
                        "()Lnet/minecraft/item/ItemStack;", false));
                g.add(new JumpInsnNode(Opcodes.IFNONNULL, carryOn));
                g.add(new InsnNode(Opcodes.RETURN));
                g.add(carryOn);
                m.instructions.insert(g);
                m.maxStack = Math.max(m.maxStack, 1);
                guardsDone.add(m.name);
            }
        }
        if (guardsDone.size() == guarded.length) applied.add("chestdupe.guards");
        return write(cn);
    }

    /**
     * chestdupe: canInteractWith returns a bare `true` in item mode. That constant becomes
     * VoltzMekanism.chestOpen(this.itemInventory), so the server closes the GUI once the
     * chest it was opened from has left its slot. Block mode already defers to the tile.
     */
    static byte[] patchChestContainer(byte[] in) {
        ClassNode cn = read(in);
        String invDesc = null;
        for (Object fo : cn.fields) { FieldNode f = (FieldNode) fo; if (f.name.equals("itemInventory")) invDesc = f.desc; }
        if (invDesc == null) throw new IllegalStateException("chestdupe: ContainerElectricChest.itemInventory not found");
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("func_75145_c") || !m.desc.equals(USABLE_DESC)) continue;
            List<AbstractInsnNode> ones = new ArrayList<AbstractInsnNode>();
            for (AbstractInsnNode i : m.instructions.toArray())
                if (i.getOpcode() == Opcodes.ICONST_1) ones.add(i);
            if (ones.size() != 1 || ones.get(0).getNext().getOpcode() != Opcodes.IRETURN)
                throw new IllegalStateException("chestdupe: expected one `return true`, found " + ones.size());
            InsnList r = new InsnList();
            r.add(new VarInsnNode(Opcodes.ALOAD, 0));
            r.add(new FieldInsnNode(Opcodes.GETFIELD, CONT_CHEST, "itemInventory", invDesc));
            r.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FIX, "chestOpen", "(Ljava/lang/Object;)Z", false));
            m.instructions.insertBefore(ones.get(0), r);
            m.instructions.remove(ones.get(0));
            m.maxStack = Math.max(m.maxStack, 2);
            applied.add("chestdupe.container");
        }
        return write(cn);
    }

    /**
     * chestremote: each `(TileEntityElectricChest) world.getBlockTileEntity(x, y, z)` in
     * read() becomes
     *
     *     (TileEntityElectricChest) VoltzMekanism.chestAt(world, x, y, z, player)
     *     if (chest == null) return;
     *
     * The three sites are the SERVER_OPEN, PASSWORD and LOCK block paths.
     */
    static byte[] patchChestPacket(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("read") || !m.desc.equals(PACKET_READ_DESC)) continue;
            int hits = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.owner.equals(WORLD) || !mi.name.equals("func_72796_p")) continue;
                AbstractInsnNode cast = mi.getNext();
                if (cast.getOpcode() != Opcodes.CHECKCAST || !((TypeInsnNode) cast).desc.equals(TE_CHEST))
                    throw new IllegalStateException("chestremote: getBlockTileEntity not followed by the chest cast");
                m.instructions.insertBefore(mi, new VarInsnNode(Opcodes.ALOAD, L_PLAYER));
                m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, FIX, "chestAt",
                        "(Ljava/lang/Object;IIILjava/lang/Object;)Ljava/lang/Object;", false));
                LabelNode carryOn = new LabelNode();
                InsnList g = new InsnList();
                g.add(new InsnNode(Opcodes.DUP));
                g.add(new JumpInsnNode(Opcodes.IFNONNULL, carryOn));
                g.add(new InsnNode(Opcodes.POP));
                g.add(new InsnNode(Opcodes.RETURN));
                g.add(carryOn);
                m.instructions.insert(cast, g);
                hits++;
            }
            if (hits != 3) throw new IllegalStateException("chestremote: expected 3 chest lookups, found " + hits);
            m.maxStack = m.maxStack + 2;
            applied.add("chestremote.packet");
        }
        return write(cn);
    }

    // --------------------------------------------------------------- machines

    /** machinedupe: isUseableByPlayer(player) = VoltzMekanism.tileUsable(this, player). */
    static byte[] patchMachineUsable(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("func_70300_a") || !m.desc.equals(USABLE_DESC)) continue;
            replaceBody(m, "tileUsable", null);
            applied.add("machinedupe.tile");
        }
        return write(cn);
    }

    // ------------------------------------------------------------------- Robit

    /** robitdupe: canInteractWith(player) = VoltzMekanism.robitUsable(this.robit, player). */
    static byte[] patchRobitContainer(byte[] in, String cls) {
        ClassNode cn = read(in);
        String robitDesc = null;
        for (Object fo : cn.fields) { FieldNode f = (FieldNode) fo; if (f.name.equals("robit")) robitDesc = f.desc; }
        if (!("L" + ROBIT + ";").equals(robitDesc))
            throw new IllegalStateException("robitdupe: " + cls + ".robit not found");
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("func_75145_c") || !m.desc.equals(USABLE_DESC)) continue;
            replaceBody(m, "robitUsable", new FieldInsnNode(Opcodes.GETFIELD, cls, "robit", robitDesc));
            robitContainersDone.add(cls);
        }
        if (robitContainersDone.size() == ROBIT_CONTAINERS.length) applied.add("robitdupe.containers");
        return write(cn);
    }

    /**
     * robitdupe: getServerGui resolves the Robit GUIs with world.getEntityByID(x),
     * where x is an entity id the client put in a PacketRobit. Each becomes
     *
     *     (EntityRobit) VoltzMekanism.robitFor(world, x, player)
     *     if (robit == null) return null;
     *
     * Returning at once matters: the stock switch falls through to the next case when the
     * Robit is null, and ends up opening the Robit repair GUI instead of nothing.
     */
    static byte[] patchRobitGui(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("getServerGui")) continue;
            int playerLocal = localOf(m, "Lnet/minecraft/entity/player/EntityPlayer;");
            int hits = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.owner.equals(WORLD) || !mi.name.equals("func_73045_a")) continue;
                AbstractInsnNode cast = mi.getNext();
                if (cast.getOpcode() != Opcodes.CHECKCAST || !((TypeInsnNode) cast).desc.equals(ROBIT))
                    throw new IllegalStateException("robitdupe: getEntityByID not followed by the Robit cast");
                m.instructions.insertBefore(mi, new VarInsnNode(Opcodes.ALOAD, playerLocal));
                m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, FIX, "robitFor",
                        "(Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;", false));
                LabelNode carryOn = new LabelNode();
                InsnList g = new InsnList();
                g.add(new InsnNode(Opcodes.DUP));
                g.add(new JumpInsnNode(Opcodes.IFNONNULL, carryOn));
                g.add(new InsnNode(Opcodes.POP));
                g.add(new InsnNode(Opcodes.ACONST_NULL));
                g.add(new InsnNode(Opcodes.ARETURN));
                g.add(carryOn);
                m.instructions.insert(cast, g);
                hits++;
            }
            if (hits != 3) throw new IllegalStateException("robitdupe: expected 3 Robit lookups, found " + hits);
            m.maxStack = m.maxStack + 2;
            applied.add("robitdupe.proxy");
        }
        return write(cn);
    }

    // ------------------------------------------------------------ Obsidian TNT

    /**
     * tntdupe: onBlockDestroyedByPlayer drops an Obsidian TNT item whenever the block was
     * not primed - but the normal harvest has already dropped one, so every break yields
     * two. The dropBlockAsItem_do call is replaced by popping its six arguments.
     */
    static byte[] patchTntDrop(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("func_71898_d") || !m.desc.equals("(Lnet/minecraft/world/World;IIII)V")) continue;
            int hits = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.name.equals("func_71929_a")
                        || !mi.desc.equals("(Lnet/minecraft/world/World;IIILnet/minecraft/item/ItemStack;)V")) continue;
                InsnList pops = new InsnList();
                for (int k = 0; k < 5; k++) pops.add(new InsnNode(Opcodes.POP));
                m.instructions.insertBefore(mi, pops);
                m.instructions.set(mi, new InsnNode(Opcodes.POP));
                hits++;
            }
            if (hits != 1) throw new IllegalStateException("tntdupe: expected 1 drop call, found " + hits);
            applied.add("tntdupe.drop");
        }
        return write(cn);
    }

    /**
     * tntsource: explode() calls world.createExplosion(null, ...). Vanilla TNT passes itself,
     * and MCPC+ hands that entity to EntityExplodeEvent - with null there is no source for
     * anything listening to attribute the blast to.
     */
    static byte[] patchTntSource(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("explode") || !m.desc.equals("()V")) continue;
            List<AbstractInsnNode> nulls = new ArrayList<AbstractInsnNode>();
            for (AbstractInsnNode i : m.instructions.toArray())
                if (i.getOpcode() == Opcodes.ACONST_NULL) nulls.add(i);
            if (nulls.size() != 1) throw new IllegalStateException("tntsource: expected 1 aconst_null, found " + nulls.size());
            m.instructions.set(nulls.get(0), new VarInsnNode(Opcodes.ALOAD, 0));
            applied.add("tntsource.exploder");
        }
        return write(cn);
    }

    // -------------------------------------------------- Stopwatch / Weather Orb

    /**
     * timeitems: read(stream, player, world) becomes
     *
     *     int v = stream.readInt();
     *     if (!BalancedTimeItems.use(player, world, v, kind)) return;
     *     <stock body, minus its damageItem(4999) call, with its readInt() replaced by v>
     *
     * BalancedTimeItems sets the item's damage itself, from the configured cooldown.
     */
    static byte[] patchTimePacket(byte[] in, String cls, int kind) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("read") || !m.desc.equals(PACKET_READ_DESC)) continue;
            int v = m.maxLocals;
            m.maxLocals = v + 1;

            // drop `player.getCurrentEquippedItem().damageItem(4999, player)`
            int damage = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.SIPUSH || ((IntInsnNode) i).operand != 4999) continue;
                AbstractInsnNode held = i.getPrevious(), load = held.getPrevious();
                AbstractInsnNode load2 = i.getNext(), call = load2.getNext();
                if (!(held instanceof MethodInsnNode) || !((MethodInsnNode) held).name.equals("func_71045_bC")
                        || !(call instanceof MethodInsnNode) || !((MethodInsnNode) call).name.equals("func_77972_a")
                        || load.getOpcode() != Opcodes.ALOAD || load2.getOpcode() != Opcodes.ALOAD)
                    throw new IllegalStateException("timeitems: " + cls + " damage call has an unexpected shape");
                for (AbstractInsnNode x : new AbstractInsnNode[] { load, held, i, load2, call })
                    m.instructions.remove(x);
                damage++;
            }

            // `stream.readInt()` -> `v`
            int reads = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.owner.equals(DATA_INPUT) || !mi.name.equals("readInt")) continue;
                AbstractInsnNode load = mi.getPrevious();
                if (load.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) load).var != L_STREAM)
                    throw new IllegalStateException("timeitems: " + cls + " readInt has an unexpected shape");
                m.instructions.remove(load);
                m.instructions.set(mi, new VarInsnNode(Opcodes.ILOAD, v));
                reads++;
            }
            if (damage != 1 || reads != 1)
                throw new IllegalStateException("timeitems: " + cls + " expected 1 damage call and 1 readInt, found "
                        + damage + "/" + reads);

            LabelNode carryOn = new LabelNode();
            InsnList g = new InsnList();
            g.add(new VarInsnNode(Opcodes.ALOAD, L_PLAYER));
            g.add(new VarInsnNode(Opcodes.ALOAD, L_WORLD));
            g.add(new VarInsnNode(Opcodes.ALOAD, L_STREAM));
            g.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, DATA_INPUT, "readInt", "()I", true));
            g.add(new InsnNode(Opcodes.DUP));
            g.add(new VarInsnNode(Opcodes.ISTORE, v));
            g.add(new InsnNode(kind == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1));
            g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TIME, "use",
                    "(Ljava/lang/Object;Ljava/lang/Object;II)Z", false));
            g.add(new JumpInsnNode(Opcodes.IFNE, carryOn));
            g.add(new InsnNode(Opcodes.RETURN));
            g.add(carryOn);
            m.instructions.insert(g);
            m.maxStack = Math.max(m.maxStack, 5);
            applied.add("timeitems." + cls);
        }
        return write(cn);
    }

    /** Load config/BalancedTimeItems.cfg at startup by calling init() from Mekanism.preInit. */
    static byte[] patchInitHook(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("preInit")) continue;
            m.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, TIME, "init", "()V", false));
            m.maxStack = Math.max(m.maxStack, 1);
            applied.add("timeitems.init");
        }
        return write(cn);
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Replace a `boolean m(EntityPlayer)` body with
     * `return VoltzMekanism.<helper>(this[.field], player)`.
     */
    static void replaceBody(MethodNode m, String helper, FieldInsnNode thisField) {
        m.instructions.clear();
        m.tryCatchBlocks.clear();
        if (m.localVariables != null) m.localVariables.clear();
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (thisField != null) m.instructions.add(thisField);
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FIX, helper, OBJ2_Z, false));
        m.instructions.add(new InsnNode(Opcodes.IRETURN));
        m.maxStack = 2;
        m.maxLocals = 2;
    }

    /** local variable index of the first parameter of the given type */
    static int localOf(MethodNode m, String desc) {
        int local = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type t : Type.getArgumentTypes(m.desc)) {
            if (t.getDescriptor().equals(desc)) return local;
            local += t.getSize();
        }
        throw new IllegalStateException(m.name + " has no " + desc + " parameter");
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
