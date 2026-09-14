import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - MFFS 3.1.0 zone flags.
 *
 * Every interdiction module acts through one method:
 *
 *     boolean onDefend(IInterdictionMatrix matrix, EntityLiving entity)
 *
 * and the base class's own implementation is `return false`, so false already means
 * "this module did nothing". This injects a guard at the head of each override:
 *
 *     if (VoltzZones.denied(<flag>, entity.worldObj.provider.dimensionId,
 *                           entity.posX, entity.posY, entity.posZ)) return false;
 *
 * Admins can then deny Anti-Personnel and Confiscate around spawn, shops and public
 * areas instead of banning the modules outright.
 *
 * It also fixes the Interdiction Matrix merge dupe (fixMerge below): confiscated and
 * Anti-Personnel stacks were multiplied by MFR conveyors next to the matrix.
 *
 * And the Stabilize module dupe (fixStabilize below): it took items from slots of sided
 * inventories that no hopper or pipe could reach, such as the ghost copies in MFR filter slots.
 *
 * usage: PatchMFFS <in.jar> <out.jar> <VoltzZones.class>
 */
public class PatchMFFS {

    static final String CFG_CLASS  = "mffs/BalancedMFFS";
    static final String MOD_CLASS  = "mffs/ModularForceFieldSystem";
    static final String PKG        = "mffs/item/module/interdiction/";
    static final String MATRIX_TE  = "mffs/tileentity/TileEntityInterdictionMatrix";
    static final String INVENTORY_TE = "mffs/base/TileEntityInventory";
    static final String STABILIZE    = "mffs/item/module/projector/ItemModuleStablize";
    static final String SIDED        = "net/minecraft/inventory/ISidedInventory";

    static final String ATTACK       = "func_70097_a";   // Entity.attackEntityFrom
    static final String ATTACK_DESC  = "(Lnet/minecraft/util/DamageSource;I)Z";
    static final String MERGE        = "mergeIntoInventory";
    static final String UPDATE_TICK  = "func_70316_g";   // TileEntity.updateEntity

    static final String ENTITY_LIVING = "net/minecraft/entity/EntityLiving";
    static final String WORLD         = "net/minecraft/world/World";
    static final String PROVIDER      = "net/minecraft/world/WorldProvider";
    static final String F_WORLD       = "field_70170_p";   // Entity.worldObj
    static final String F_PROVIDER    = "field_73011_w";   // World.provider
    static final String F_DIM         = "field_76574_g";   // WorldProvider.dimensionId
    static final String F_POSX        = "field_70165_t";
    static final String F_POSY        = "field_70163_u";
    static final String F_POSZ        = "field_70161_v";

    /** module class -> flag name used in VoltzZones.txt */
    static final String[][] MODULES = {
        { "ItemModuleAntiPersonnel", "mffs.antipersonnel" },
        { "ItemModuleConfiscate",    "mffs.confiscate"    },
        { "ItemModuleAntiHostile",   "mffs.antihostile"   },
        { "ItemModuleAntiFriendly",  "mffs.antifriendly"  },
        { "ItemModuleWarn",          "mffs.warn"          },
    };

    static final Set<String> patched = new HashSet<String>();
    static boolean hitInit, hitKill, hitTaken, hitTick, hitMerge, hitStabilize;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: PatchMFFS <in.jar> <out.jar> <VoltzZones.class>");
            System.exit(2);
        }
        Map<String, String> flagFor = new HashMap<String, String>();
        for (String[] m : MODULES) flagFor.put(PKG + m[0] + ".class", m[1]);

        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(args[0]);
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) { out.put(ze.getName(), null); continue; }
            byte[] d = readAll(zf.getInputStream(ze));
            String n = ze.getName();
            if (flagFor.containsKey(n)) d = guard(d, flagFor.get(n), n);
            if (n.equals(PKG + "ItemModuleAntiPersonnel.class")) d = logKill(d);
            if (n.equals(PKG + "ItemModuleConfiscate.class"))    d = logTaken(d);
            if (n.equals(MATRIX_TE + ".class"))                  d = logMatrix(d);
            if (n.equals(MOD_CLASS + ".class")) d = initHook(d);
            if (n.equals(INVENTORY_TE + ".class"))               d = fixMerge(d);
            if (n.equals(STABILIZE + ".class"))                  d = fixStabilize(d);
            out.put(n, d);
        }
        zf.close();
        injectHelper(out, CFG_CLASS, args[2]);

        for (String[] m : MODULES)
            if (!patched.contains(PKG + m[0] + ".class"))
                throw new IllegalStateException("guard did not apply to " + m[0]);
        if (!hitInit)  throw new IllegalStateException("init hook did not apply");
        if (!hitKill)  throw new IllegalStateException("kill log hook did not apply");
        if (!hitTaken) throw new IllegalStateException("confiscate log hook did not apply");
        if (!hitTick)  throw new IllegalStateException("matrix load hook did not apply");
        if (!hitMerge) throw new IllegalStateException("merge dupe fix did not apply");
        if (!hitStabilize) throw new IllegalStateException("stabilize dupe fix did not apply");

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])));
        for (Map.Entry<String, byte[]> en : out.entrySet()) {
            zos.putNextEntry(new ZipEntry(en.getKey()));
            if (en.getValue() != null) zos.write(en.getValue());
            zos.closeEntry();
        }
        zos.close();
        System.out.println("OK  wrote " + args[1] + "  [zones: " + patched.size() + " modules]");
    }

    static byte[] guard(byte[] in, String flag, String entry) {
        ClassNode cn = read(in);
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (!m.name.equals("onDefend")) continue;
            LabelNode carryOn = new LabelNode();
            InsnList g = new InsnList();
            g.add(new LdcInsnNode(flag));
            // entity.worldObj.provider.dimensionId
            g.add(new VarInsnNode(Opcodes.ALOAD, 2));
            g.add(new FieldInsnNode(Opcodes.GETFIELD, ENTITY_LIVING, F_WORLD, "L" + WORLD + ";"));
            g.add(new FieldInsnNode(Opcodes.GETFIELD, WORLD, F_PROVIDER, "L" + PROVIDER + ";"));
            g.add(new FieldInsnNode(Opcodes.GETFIELD, PROVIDER, F_DIM, "I"));
            // entity.posX / posY / posZ
            g.add(new VarInsnNode(Opcodes.ALOAD, 2));
            g.add(new FieldInsnNode(Opcodes.GETFIELD, ENTITY_LIVING, F_POSX, "D"));
            g.add(new VarInsnNode(Opcodes.ALOAD, 2));
            g.add(new FieldInsnNode(Opcodes.GETFIELD, ENTITY_LIVING, F_POSY, "D"));
            g.add(new VarInsnNode(Opcodes.ALOAD, 2));
            g.add(new FieldInsnNode(Opcodes.GETFIELD, ENTITY_LIVING, F_POSZ, "D"));
            g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "denied",
                                     "(Ljava/lang/String;IDDD)Z", false));
            g.add(new JumpInsnNode(Opcodes.IFEQ, carryOn));
            g.add(new InsnNode(Opcodes.ICONST_0));
            g.add(new InsnNode(Opcodes.IRETURN));
            g.add(carryOn);
            m.instructions.insert(g);
            m.maxStack = Math.max(m.maxStack, 8);
            patched.add(entry);
        }
        return write(cn);
    }

    /**
     * Anti-Personnel strips the player's whole inventory into the matrix and then deals
     * Integer.MAX_VALUE damage, leaving no record. Log immediately after the killing blow,
     * so only an actual kill is reported rather than every proximity check.
     */
    static byte[] logKill(byte[] in) {
        ClassNode cn = read(in);
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (!m.name.equals("onDefend")) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (!(i instanceof MethodInsnNode)) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.name.equals(ATTACK) || !mi.desc.equals(ATTACK_DESC)) continue;
                InsnList add = new InsnList();
                add.add(new VarInsnNode(Opcodes.ALOAD, 2));   // entity
                add.add(new VarInsnNode(Opcodes.ALOAD, 1));   // matrix
                add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "logKill",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                m.instructions.insert(mi, add);
                m.maxStack = Math.max(m.maxStack, 8);
                hitKill = true;
            }
        }
        return write(cn);
    }

    /**
     * Confiscate moves filtered stacks out of player inventories silently. Log each stack
     * as it is handed to the matrix: DUP the ItemStack already on the stack, add the entity
     * and matrix, and call the logger before the merge proceeds untouched.
     */
    static byte[] logTaken(byte[] in) {
        ClassNode cn = read(in);
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (!m.name.equals("onDefend")) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (!(i instanceof MethodInsnNode)) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.name.equals(MERGE)) continue;
                InsnList add = new InsnList();
                add.add(new InsnNode(Opcodes.DUP));           // the ItemStack argument
                add.add(new VarInsnNode(Opcodes.ALOAD, 2));   // entity
                add.add(new VarInsnNode(Opcodes.ALOAD, 1));   // matrix
                add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "logTaken",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false));
                m.instructions.insertBefore(mi, add);
                m.maxStack = Math.max(m.maxStack, 8);
                hitTaken = true;
            }
        }
        return write(cn);
    }

    /**
     * Announce a matrix once per chunk load. Called every tick, but BalancedMFFS keys off
     * the TileEntity instance - which is rebuilt on each chunk load - so it prints once,
     * and only for a matrix actually carrying Anti-Personnel or Confiscate.
     */
    static byte[] logMatrix(byte[] in) {
        ClassNode cn = read(in);
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (!m.name.equals(UPDATE_TICK) || !m.desc.equals("()V")) continue;
            InsnList add = new InsnList();
            add.add(new VarInsnNode(Opcodes.ALOAD, 0));
            add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "matrixTick",
                                       "(Ljava/lang/Object;)V", false));
            m.instructions.insert(add);
            m.maxStack = Math.max(m.maxStack, 4);
            hitTick = true;
        }
        return write(cn);
    }

    /**
     * TileEntityInventory.addStackToInventory - used by the matrix to store everything
     * Confiscate and Anti-Personnel take - puts the stack into an empty slot and then
     * re-reads the slot to decide whether it was accepted:
     *
     *     inventory.setInventorySlotContents(slot, stack);
     *     if (inventory.getStackInSlot(slot) == null) return stack;   // "refused", keep it
     *     return null;
     *
     * An MFR conveyor spawns the item on the belt and always reports an empty slot. The
     * matrix keeps the whole stack, offers it to the next slot and side, and finally drops
     * it on top of itself, so every stack comes out as one copy per accepting belt plus the
     * original. Vanilla hoppers and pipes count an insert into an empty slot as done; this
     * does the same by returning null straight after the set.
     */
    static byte[] fixMerge(byte[] in) {
        ClassNode cn = new ClassNode();
        new ClassReader(in).accept(cn, ClassReader.SKIP_FRAMES);
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (!m.name.equals("addStackToInventory")
                    || !m.desc.equals("(ILnet/minecraft/inventory/IInventory;Lnet/minecraft/item/ItemStack;)Lnet/minecraft/item/ItemStack;"))
                continue;
            int hits = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEINTERFACE || !((MethodInsnNode) i).name.equals("func_70299_a")) continue;
                AbstractInsnNode a = real(i.getNext()), b = real(a.getNext()), c = real(b.getNext()), d = real(c.getNext());
                if (a.getOpcode() != Opcodes.ALOAD || b.getOpcode() != Opcodes.ILOAD
                        || !(c instanceof MethodInsnNode) || !((MethodInsnNode) c).name.equals("func_70301_a")
                        || d.getOpcode() != Opcodes.IFNONNULL) continue;
                InsnList accept = new InsnList();
                accept.add(new InsnNode(Opcodes.ACONST_NULL));
                accept.add(new InsnNode(Opcodes.ARETURN));
                m.instructions.insert(i, accept);
                hits++;
            }
            if (hits != 1) throw new IllegalStateException("merge dupe fix: expected 1 re-read after insert, found " + hits);
            hitMerge = true;
        }
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * ItemModuleStablize.onProject(IProjector, Vector3) - the Stabilize module - builds the
     * field out of block items from every inventory touching the projector:
     *
     *     for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
     *         ItemStack stack = inventory.getStackInSlot(slot);
     *         if (stack == null) continue;
     *         ... place stack's block ...; inventory.decrStackSize(slot, 1);
     *
     * Every slot, ignoring ISidedInventory, so it takes items no hopper or pipe can reach -
     * including the ghost copies in MFR filter slots, which the player refills with a click
     * for one free block each time. Straight after the null check this adds:
     *
     *     if (inventory instanceof ISidedInventory
     *             && (!BalancedMFFS.hasSlot(sided.getAccessibleSlotsFromSide(face), slot)
     *                 || !sided.canExtractItem(slot, stack, face))) continue;
     *
     * face is the inventory's side touching the projector, the opposite of the loop's
     * direction (ForgeDirection opposites differ only in the low bit). Plain inventories
     * such as chests are unaffected.
     */
    static byte[] fixStabilize(byte[] in) {
        ClassNode cn = new ClassNode();
        new ClassReader(in).accept(cn, ClassReader.SKIP_FRAMES);
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (!m.name.equals("onProject")
                    || !m.desc.equals("(Lmffs/api/IProjector;Luniversalelectricity/core/vector/Vector3;)I"))
                continue;
            int dirVar = -1, hits = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (!(i instanceof MethodInsnNode)) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (mi.name.equals("getOrientation") && prevReal(i).getOpcode() == Opcodes.ILOAD) {
                    dirVar = ((VarInsnNode) prevReal(i)).var;
                    continue;
                }
                if (i.getOpcode() != Opcodes.INVOKEINTERFACE || !mi.name.equals("func_70301_a")) continue;
                AbstractInsnNode slotLoad = prevReal(i), invLoad = prevReal(slotLoad);
                AbstractInsnNode store = real(i.getNext()), load = real(store.getNext()), isNull = real(load.getNext());
                if (dirVar < 0 || invLoad.getOpcode() != Opcodes.ALOAD || slotLoad.getOpcode() != Opcodes.ILOAD
                        || store.getOpcode() != Opcodes.ASTORE || load.getOpcode() != Opcodes.ALOAD
                        || ((VarInsnNode) load).var != ((VarInsnNode) store).var
                        || isNull.getOpcode() != Opcodes.IFNULL) continue;
                int inv = ((VarInsnNode) invLoad).var, slot = ((VarInsnNode) slotLoad).var;
                int stack = ((VarInsnNode) store).var;
                LabelNode skip = ((JumpInsnNode) isNull).label;
                LabelNode carryOn = new LabelNode();
                InsnList g = new InsnList();
                g.add(new VarInsnNode(Opcodes.ALOAD, inv));
                g.add(new TypeInsnNode(Opcodes.INSTANCEOF, SIDED));
                g.add(new JumpInsnNode(Opcodes.IFEQ, carryOn));
                g.add(new VarInsnNode(Opcodes.ALOAD, inv));
                g.add(new TypeInsnNode(Opcodes.CHECKCAST, SIDED));
                addFace(g, dirVar);
                g.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, SIDED, "func_94128_d", "(I)[I", true));
                g.add(new VarInsnNode(Opcodes.ILOAD, slot));
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG_CLASS, "hasSlot", "([II)Z", false));
                g.add(new JumpInsnNode(Opcodes.IFEQ, skip));
                g.add(new VarInsnNode(Opcodes.ALOAD, inv));
                g.add(new TypeInsnNode(Opcodes.CHECKCAST, SIDED));
                g.add(new VarInsnNode(Opcodes.ILOAD, slot));
                g.add(new VarInsnNode(Opcodes.ALOAD, stack));
                addFace(g, dirVar);
                g.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, SIDED, "func_102008_b",
                        "(ILnet/minecraft/item/ItemStack;I)Z", true));
                g.add(new JumpInsnNode(Opcodes.IFEQ, skip));
                g.add(carryOn);
                m.instructions.insert(isNull, g);
                hits++;
            }
            if (hits != 1) throw new IllegalStateException("stabilize dupe fix: expected 1 slot read, found " + hits);
            m.maxStack = Math.max(m.maxStack, 5);
            hitStabilize = true;
        }
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** the inventory's face toward the projector: opposite of ForgeDirection direction */
    static void addFace(InsnList g, int dirVar) {
        g.add(new VarInsnNode(Opcodes.ILOAD, dirVar));
        g.add(new InsnNode(Opcodes.ICONST_1));
        g.add(new InsnNode(Opcodes.IXOR));
    }

    /** skip labels, line numbers and frames, which sit between real instructions */
    static AbstractInsnNode real(AbstractInsnNode n) {
        while (n != null && n.getOpcode() < 0) n = n.getNext();
        return n;
    }

    static AbstractInsnNode prevReal(AbstractInsnNode n) {
        n = n.getPrevious();
        while (n != null && n.getOpcode() < 0) n = n.getPrevious();
        return n;
    }

    static byte[] initHook(byte[] in) {
        ClassNode cn = read(in);
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
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
