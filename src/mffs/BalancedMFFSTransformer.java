package mffs;

import cpw.mods.fml.relauncher.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Applies the BalancedMFFS zone flags and admin logging to MFFS's own classes at load time.
 *
 * Five interdiction modules act through one method:
 *
 *     boolean onDefend(IInterdictionMatrix matrix, EntityLiving entity)
 *
 * and the base class's own implementation is `return false`, so false already means "this
 * module did nothing". A guard at the head of each override returns false inside a denied
 * zone, which is indistinguishable from a module that chose not to act. Admins can then keep
 * Anti-Personnel and Confiscate craftable and deny them around spawn, shops and public areas
 * instead of banning the modules outright.
 *
 * On top of that it logs what Anti-Personnel and Confiscate actually take, which stock does
 * not record anywhere, and announces a live matrix once per chunk load.
 *
 * FML runs coremod transformers after its deobfuscating remapper, so the classes arrive in
 * SRG names and the vanilla field references injected below match at runtime. Nothing here
 * throws: any hook that does not find its insertion point logs and leaves that class
 * untouched, so a version mismatch can never stop the server from starting. Compiled against
 * ASM 4.1 (FML 1.5.2's runtime ASM), so only the 4-argument MethodInsnNode constructor.
 */
public class BalancedMFFSTransformer implements IClassTransformer {

    static final String TAG = "[BalancedMFFS] ";

    static final String CFG          = "mffs/BalancedMFFS";
    static final String PKG          = "mffs.item.module.interdiction.";
    static final String MATRIX_TE    = "mffs.tileentity.TileEntityInterdictionMatrix";
    static final String MOD_CLASS    = "mffs.ModularForceFieldSystem";

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

    public byte[] transform(String name, String transformedName, byte[] data) {
        if (data == null || transformedName == null || !transformedName.startsWith("mffs.")) {
            return data;
        }
        try {
            for (int i = 0; i < MODULES.length; i++) {
                if (!transformedName.equals(PKG + MODULES[i][0])) continue;
                byte[] out = guard(data, MODULES[i][1], MODULES[i][0]);
                if (MODULES[i][0].equals("ItemModuleAntiPersonnel")) out = logKill(out);
                if (MODULES[i][0].equals("ItemModuleConfiscate"))    out = logTaken(out);
                return out;
            }
            if (transformedName.equals(MATRIX_TE)) return logMatrix(data);
            if (transformedName.equals(MOD_CLASS)) return initHook(data);
            return data;
        } catch (Throwable t) {
            System.out.println(TAG + "transform failed, leaving " + transformedName + " unchanged: " + t);
            return data;
        }
    }

    /** `if (BalancedMFFS.denied(flag, dim, x, y, z)) return false;` at the head of onDefend. */
    static byte[] guard(byte[] in, String flag, String label) {
        ClassNode cn = read(in);
        boolean hit = false;
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
            g.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG, "denied", "(Ljava/lang/String;IDDD)Z"));
            g.add(new JumpInsnNode(Opcodes.IFEQ, carryOn));
            g.add(new InsnNode(Opcodes.ICONST_0));
            g.add(new InsnNode(Opcodes.IRETURN));
            g.add(carryOn);
            m.instructions.insert(g);
            m.maxStack = Math.max(m.maxStack, 8);
            hit = true;
        }
        report(hit, "zone flag " + flag + " on " + label);
        return hit ? write(cn) : in;
    }

    /**
     * Anti-Personnel strips the player's whole inventory into the matrix and then deals
     * Integer.MAX_VALUE damage, leaving no record. Log immediately after the killing blow,
     * so only an actual kill is reported rather than every proximity check.
     */
    static byte[] logKill(byte[] in) {
        ClassNode cn = read(in);
        boolean hit = false;
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
                add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG, "logKill",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                m.instructions.insert(mi, add);
                m.maxStack = Math.max(m.maxStack, 8);
                hit = true;
            }
        }
        report(hit, "kill logging on ItemModuleAntiPersonnel");
        return hit ? write(cn) : in;
    }

    /**
     * Confiscate moves filtered stacks out of player inventories silently. Log each stack as
     * it is handed to the matrix: DUP the ItemStack already on the stack, add the entity and
     * matrix, and call the logger before the merge proceeds untouched.
     */
    static byte[] logTaken(byte[] in) {
        ClassNode cn = read(in);
        boolean hit = false;
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
                add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG, "logTaken",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"));
                m.instructions.insertBefore(mi, add);
                m.maxStack = Math.max(m.maxStack, 8);
                hit = true;
            }
        }
        report(hit, "confiscation logging on ItemModuleConfiscate");
        return hit ? write(cn) : in;
    }

    /**
     * Announce a matrix once per chunk load. Called every tick, but BalancedMFFS keys off the
     * TileEntity instance - which is rebuilt on each chunk load - so it prints once, and only
     * for a matrix actually carrying Anti-Personnel or Confiscate.
     */
    static byte[] logMatrix(byte[] in) {
        ClassNode cn = read(in);
        boolean hit = false;
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (!m.name.equals(UPDATE_TICK) || !m.desc.equals("()V")) continue;
            InsnList add = new InsnList();
            add.add(new VarInsnNode(Opcodes.ALOAD, 0));
            add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CFG, "matrixTick",
                                       "(Ljava/lang/Object;)V"));
            m.instructions.insert(add);
            m.maxStack = Math.max(m.maxStack, 4);
            hit = true;
        }
        report(hit, "matrix announcement on TileEntityInterdictionMatrix");
        return hit ? write(cn) : in;
    }

    /** Load VoltzZones.txt when MFFS itself pre-initialises. */
    static byte[] initHook(byte[] in) {
        ClassNode cn = read(in);
        boolean hit = false;
        for (Object o : cn.methods) {
            MethodNode m = (MethodNode) o;
            if (!m.name.equals("preInit")) continue;
            m.instructions.insert(
                    new MethodInsnNode(Opcodes.INVOKESTATIC, CFG, "init", "()V"));
            m.maxStack = Math.max(m.maxStack, 1);
            hit = true;
        }
        report(hit, "config init on ModularForceFieldSystem");
        return hit ? write(cn) : in;
    }

    static void report(boolean hit, String what) {
        System.out.println(TAG + (hit ? "applied " : "could not find an insertion point for ") + what);
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
}
