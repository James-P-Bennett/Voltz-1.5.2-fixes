import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Voltz 1.5.2 fixes - Modular Powersuits 0.7.0-534 patches.
 *
 *   blink       BlinkDriveModule.onRightClick   MusePlayerUtils.teleportEntity -> VoltzMPS.blink
 *   luxevent    EntityLuxCapacitor.onImpact     setBlock / setBlockTileEntity -> VoltzMPS
 *   enumclamp   TileEntityLuxCapacitor.readFromNBT   values()[nbt] clamped
 *   bladeevent  EntitySpinningBlade.onImpact    isShearable / destroyBlock -> VoltzMPS
 *
 * Blink Drive teleports to the raw ray hit point without checking the player fits there,
 * which can leave their hitbox inside blocks. The server stops validating movement for a
 * player who starts a move inside a block, so a modified client walks through walls.
 *
 * luxevent and bladeevent are compatibility rather than exploit fixes: both entities change
 * the world through raw Minecraft calls with no player attached, so on MCPC+ no Bukkit event
 * fires and no protection plugin can see them. The helper fires the event a plugin is already
 * listening for - BlockPlaceEvent and BlockBreakEvent, with the thrower as the player - and
 * honours a cancellation. On a plain Forge server there is no Bukkit and nothing changes.
 * The blink patch gates its move on a PlayerTeleportEvent for the same reason.
 *
 * usage: PatchMPS <in.jar> <out.jar> <patch>[,<patch>...] <VoltzMPS.class>
 */
public class PatchMPS {

    static final String BLINK_CLASS = "net/machinemuse/powersuits/powermodule/movement/BlinkDriveModule";
    static final String LUX_CLASS   = "net/machinemuse/powersuits/entity/EntityLuxCapacitor";
    static final String BLADE_CLASS = "net/machinemuse/powersuits/entity/EntitySpinningBlade";
    static final String LUX_TILE    = "net/machinemuse/powersuits/block/TileEntityLuxCapacitor";
    static final String WORLD       = "net/minecraft/world/World";
    static final String SHEARABLE   = "net/minecraftforge/common/IShearable";
    static final String OBJ         = "Ljava/lang/Object;";
    static final String IMPACT_DESC = "(Lnet/minecraft/util/MovingObjectPosition;)V";
    static final String HELPER      = "net/machinemuse/powersuits/VoltzMPS";
    static final String UTILS       = "net/machinemuse/utils/MusePlayerUtils";
    static final String TELEPORT_DESC =
            "(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/util/MovingObjectPosition;)V";

    static boolean doBlink, doLux, doBlade, doEnum;
    static int blinkHits, luxPlaceHits, luxTileHits, shearHits, destroyHits;
    static final int[] enumHits = new int[1];

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: PatchMPS <in.jar> <out.jar> <patches> <VoltzMPS.class>");
            System.err.println("patches: blink,luxevent,bladeevent,enumclamp");
            System.exit(2);
        }
        for (String p : args[2].split(",")) {
            p = p.trim();
            if (p.equals("blink")) doBlink = true;
            else if (p.equals("luxevent")) doLux = true;
            else if (p.equals("bladeevent")) doBlade = true;
            else if (p.equals("enumclamp")) doEnum = true;
            else throw new IllegalArgumentException("unknown patch: " + p);
        }

        LinkedHashMap<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        ZipFile zf = new ZipFile(args[0]);
        for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) { out.put(ze.getName(), null); continue; }
            byte[] d = readAll(zf.getInputStream(ze));
            String n = ze.getName();
            if (doBlink && n.equals(BLINK_CLASS + ".class")) d = patchBlink(d);
            if (doLux && n.equals(LUX_CLASS + ".class")) d = patchLux(d);
            if (doBlade && n.equals(BLADE_CLASS + ".class")) d = patchBlade(d);
            if (doEnum && n.equals(LUX_TILE + ".class")) d = patchEnumClamp(d, "func_70307_a", HELPER, enumHits);
            out.put(n, d);
        }
        zf.close();
        out.put(HELPER + ".class", readAll(new FileInputStream(args[3])));

        if (doBlink && blinkHits != 1)
            throw new IllegalStateException("blink patch: expected 1 teleportEntity call, found " + blinkHits);
        if (doLux && (luxPlaceHits != 1 || luxTileHits != 1))
            throw new IllegalStateException("luxevent: expected 1 setBlock and 1 setBlockTileEntity, found "
                    + luxPlaceHits + "/" + luxTileHits);
        if (doEnum && enumHits[0] != 1)
            throw new IllegalStateException("enumclamp: expected 1 ForgeDirection values() index, found " + enumHits[0]);
        if (doBlade && (shearHits != 1 || destroyHits != 1))
            throw new IllegalStateException("bladeevent: expected 1 block-branch isShearable and 1 destroyBlock, found "
                    + shearHits + "/" + destroyHits);

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])));
        for (Map.Entry<String, byte[]> en : out.entrySet()) {
            zos.putNextEntry(new ZipEntry(en.getKey()));
            if (en.getValue() != null) zos.write(en.getValue());
            zos.closeEntry();
        }
        zos.close();
        System.out.println("OK  wrote " + args[1] + "  [" + args[2] + "]");
    }

    /** Same arguments, same stack, same void return - only the destination is checked. */
    static byte[] patchBlink(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("onRightClick")) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKESTATIC) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.owner.equals(UTILS) || !mi.name.equals("teleportEntity")
                        || !mi.desc.equals(TELEPORT_DESC)) continue;
                m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "blink",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                blinkHits++;
            }
        }
        return write(cn);
    }

    /**
     * luxevent: in onImpact, `world.setBlock(x, y, z, id, 0, 7)` becomes
     * `VoltzMPS.luxPlace(this, world, x, y, z, id, 0, 7)` and the setBlockTileEntity on the
     * next line becomes VoltzMPS.luxTile, which checks the block actually went down first.
     * The entity is pushed ahead of the receiver, so the helper can reach the thrower.
     */
    static byte[] patchLux(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("func_70184_a") || !m.desc.equals(IMPACT_DESC)) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (!mi.owner.equals(WORLD)) continue;
                if (mi.name.equals("func_72832_d")) {
                    m.instructions.insertBefore(startOfCall(m, mi), new VarInsnNode(Opcodes.ALOAD, 0));
                    m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "luxPlace",
                            "(" + OBJ + OBJ + "IIIIII)Z", false));
                    luxPlaceHits++;
                } else if (mi.name.equals("func_72837_a")) {
                    m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "luxTile",
                            "(" + OBJ + "III" + OBJ + ")V", false));
                    luxTileHits++;
                }
            }
            m.maxStack = m.maxStack + 1;
        }
        return write(cn);
    }

    /**
     * bladeevent: in onImpact, the block branch's `target.isShearable(...)` becomes
     * `VoltzMPS.bladeMayShear(target, this, ...)` and `world.destroyBlock(...)` becomes
     * `VoltzMPS.bladeBreak(this, ...)`. The two decisions are taken together in the helper,
     * because stock spawns the shear drops before it destroys the block.
     *
     * onImpact has two isShearable calls - the block branch and the entity branch, in that
     * order - and only the first is a block break; the count is asserted so a reordering in
     * another build cannot silently patch the wrong one.
     */
    static byte[] patchBlade(byte[] in) {
        ClassNode cn = read(in);
        for (Object mo : cn.methods) {
            MethodNode m = (MethodNode) mo;
            if (!m.name.equals("func_70184_a") || !m.desc.equals(IMPACT_DESC)) continue;

            int shearables = 0;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i.getOpcode() == Opcodes.INVOKEINTERFACE
                        && ((MethodInsnNode) i).owner.equals(SHEARABLE)
                        && ((MethodInsnNode) i).name.equals("isShearable")) shearables++;
            }
            if (shearables != 2)
                throw new IllegalStateException("bladeevent: expected 2 isShearable calls, found " + shearables);

            boolean first = true;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (!(i instanceof MethodInsnNode)) continue;
                MethodInsnNode mi = (MethodInsnNode) i;
                if (mi.getOpcode() == Opcodes.INVOKEINTERFACE && mi.owner.equals(SHEARABLE)
                        && mi.name.equals("isShearable")) {
                    if (!first) continue;
                    first = false;
                    m.instructions.insertBefore(startOfCall(m, mi), new VarInsnNode(Opcodes.ALOAD, 0));
                    // the entity goes in front of the receiver: (this, target, item, world, x, y, z)
                    m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "bladeMayShear",
                            "(" + OBJ + OBJ + OBJ + OBJ + "III)Z", false));
                    shearHits++;
                } else if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL && mi.owner.equals(WORLD)
                        && mi.name.equals("func_94578_a")) {
                    m.instructions.insertBefore(startOfCall(m, mi), new VarInsnNode(Opcodes.ALOAD, 0));
                    m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "bladeBreak",
                            "(" + OBJ + OBJ + "IIIZ)Z", false));
                    destroyHits++;
                }
            }
            m.maxStack = m.maxStack + 1;
        }
        return write(cn);
    }

    /**
     * The instruction that pushes the receiver of `call`, so an extra leading argument can be
     * inserted in front of the whole argument sequence. Walks back over exactly as many stack
     * producers as the call consumes.
     */
    static AbstractInsnNode startOfCall(MethodNode m, MethodInsnNode call) {
        int needed = Type.getArgumentTypes(call.desc).length + 1;   // + the receiver
        AbstractInsnNode i = call;
        int seen = 0;
        while (i != null && seen < needed) {
            i = i.getPrevious();
            if (i == null || i.getOpcode() < 0) continue;
            int pushed = pushes(i);
            if (pushed == 0) continue;
            seen += pushed;
        }
        if (i == null) throw new IllegalStateException("could not find the start of " + call.name);
        return i;
    }

    /** How many stack slots this instruction leaves behind; only the shapes these calls use. */
    static int pushes(AbstractInsnNode i) {
        int op = i.getOpcode();
        if (op == Opcodes.ALOAD || op == Opcodes.ILOAD || op == Opcodes.FLOAD
                || op == Opcodes.BIPUSH || op == Opcodes.SIPUSH || op == Opcodes.LDC
                || (op >= Opcodes.ACONST_NULL && op <= Opcodes.ICONST_5)) return 1;
        if (i instanceof FieldInsnNode) return op == Opcodes.GETFIELD ? 0 : 1;   // GETFIELD swaps
        if (i instanceof MethodInsnNode || i instanceof TypeInsnNode) return 0;  // net zero or handled
        return 0;
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
