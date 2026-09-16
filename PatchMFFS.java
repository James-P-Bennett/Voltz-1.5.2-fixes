import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - MFFS 3.1.0 dupe fixes.
 *
 *   mergedupe      TileEntityInventory.addStackToInventory   an insert into an empty slot
 *                  counts as accepted, as vanilla hoppers and pipes do, so an MFR conveyor
 *                  next to an Interdiction Matrix no longer multiplies everything it takes
 *   enumclamp      TileEntityFortronCapacitor.readFromNBT   values()[nbt] clamped
 *   stabilizedupe  ItemModuleStablize.onProject   the Stabilize module respects ISidedInventory
 *                  instead of building its field out of slots no hopper or pipe can reach,
 *                  including the ghost copies in MFR filter slots
 *
 * Bug fixes only. The zone-flag and admin-logging feature that used to be injected here now
 * ships separately as the BalancedMFFS coremod (src/mffs/BalancedMFFS*.java), so a server can
 * take the dupe fixes without taking the feature.
 *
 * usage: PatchMFFS <in.jar> <out.jar> <patch>[,<patch>...] <VoltzMFFS.class>
 */
public class PatchMFFS {

    static final String HELPER       = "mffs/VoltzMFFS";
    static final String INVENTORY_TE = "mffs/base/TileEntityInventory";
    static final String STABILIZE    = "mffs/item/module/projector/ItemModuleStablize";
    static final String SIDED        = "net/minecraft/inventory/ISidedInventory";
    static final String FORTRON_CAP  = "mffs/tileentity/TileEntityFortronCapacitor";

    static final List<String> KNOWN = Arrays.asList("mergedupe", "stabilizedupe", "enumclamp");

    static final Set<String> selected = new HashSet<String>();
    static boolean hitMerge, hitStabilize;
    static int enumHits;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchMFFS <in.jar> <out.jar> <patches> <VoltzMFFS.class>");
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
            if (selected.contains("mergedupe") && n.equals(INVENTORY_TE + ".class")) d = fixMerge(d);
            if (selected.contains("stabilizedupe") && n.equals(STABILIZE + ".class")) d = fixStabilize(d);
            if (selected.contains("enumclamp") && n.equals(FORTRON_CAP + ".class")) {
                int[] k = new int[1];
                d = patchEnumClamp(d, "func_70307_a", HELPER, k);
                enumHits += k[0];
            }
            out.put(n, d);
        }
        zf.close();
        injectHelper(out, HELPER, args[3]);

        if (selected.contains("mergedupe") && !hitMerge)
            throw new IllegalStateException("merge dupe fix did not apply");
        if (selected.contains("stabilizedupe") && !hitStabilize)
            throw new IllegalStateException("stabilize dupe fix did not apply");
        if (selected.contains("enumclamp") && enumHits != 1)
            throw new IllegalStateException("enumclamp: expected 1 TransferMode values() index, found " + enumHits);

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
     *             && (!VoltzMFFS.hasSlot(sided.getAccessibleSlotsFromSide(face), slot)
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
                g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "hasSlot", "([II)Z", false));
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
        new ClassReader(b).accept(cn, ClassReader.SKIP_FRAMES);
        return cn;
    }

    static byte[] write(ClassNode cn) {
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * enumclamp: rewrites `SomeEnum.values()[index]` to go through the helper, so an
     * out-of-range ordinal from NBT or block metadata falls back to the first constant
     * instead of throwing out of chunk loading and failing that chunk for good.
     *
     * The shape is always `INVOKESTATIC values() / <push index> / AALOAD`; the element type
     * for the cast is read back out of the values() descriptor, so this needs no hard-coded
     * enum names.
     */
    static byte[] patchEnumClamp(byte[] in, String method, String helper, int[] count) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals(method)) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.AALOAD) continue;
                MethodInsnNode values = null;
                for (AbstractInsnNode b = i.getPrevious(); b != null && values == null; b = b.getPrevious()) {
                    if (b.getOpcode() == Opcodes.INVOKESTATIC && ((MethodInsnNode) b).name.equals("values")) {
                        values = (MethodInsnNode) b;
                    } else if (b.getOpcode() == Opcodes.AALOAD) {
                        break;
                    }
                }
                if (values == null) continue;
                Type element = Type.getReturnType(values.desc).getElementType();
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, helper, "enumAt",
                        "([Ljava/lang/Object;I)Ljava/lang/Object;", false);
                m.instructions.set(i, call);
                m.instructions.insert(call, new TypeInsnNode(Opcodes.CHECKCAST, element.getInternalName()));
                count[0]++;
            }
        }
        return write(cn);
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
