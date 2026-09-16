#!/usr/bin/env bash
# Build every patched jar.
#
#   ./build.sh                 build all mods found at the default paths
#   ./build.sh <AS.jar>        override just the Atomic Science source jar
#
# Override any path with an env var:
#   MODS  AS_SRC  MPSA_SRC  MFFS_SRC  MEK_SRC  MEKGEN_SRC  ICBM_SRC  ICBMS_SRC  ICBMC_SRC  MPS_SRC
#   MFR_SRC  GC_SRC  COREMODS  MICRO_SRC  ICORE_SRC  NEI_SRC  FORGE  ASM  JAVAC8
#
# Helper classes are compiled against the Forge universal zip, NOT against the launcher's
# bin/minecraft.jar - PolyMC rewrites that file on every launch, so a build depending on it
# works right up until the next time the game starts. The zip is downloaded into build/ on
# first run and cached.
set -euo pipefail

MODS="${MODS:-$HOME/.local/share/PolyMC/instances/Voltz/.minecraft/mods}"
AS_SRC="${1:-${AS_SRC:-$MODS/Atomic_Science_v0.6.2.117.jar}}"
MPSA_SRC="${MPSA_SRC:-$MODS/MPSA-0.2.3-144_MPS-531+.jar}"
MFFS_SRC="${MFFS_SRC:-$MODS/MFFS_v3.1.0.175.jar}"
MEK_SRC="${MEK_SRC:-$MODS/Mekanism-v5.5.6.bugfix1.jar}"
ICBM_SRC="${ICBM_SRC:-$MODS/ICBM_Explosion_v1.2.1.172.jar}"
ICBMS_SRC="${ICBMS_SRC:-$MODS/ICBM_Sentry_v1.2.1.172.jar}"
ICBMC_SRC="${ICBMC_SRC:-$MODS/ICBM_Contraption_v1.2.1.172.jar}"
MPS_SRC="${MPS_SRC:-$MODS/ModularPowersuits-0.7.0-534.jar}"
MFR_SRC="${MFR_SRC:-$MODS/MineFactoryReloaded-2.6.4-975.jar}"
MEKGEN_SRC="${MEKGEN_SRC:-$MODS/MekanismGenerators-v5.5.6.bugfix1.jar}"
COREMODS="${COREMODS:-$HOME/.local/share/PolyMC/instances/Voltz/.minecraft/coremods}"
GC_SRC="${GC_SRC:-$COREMODS/Galacticraft-1.5.2-a0.1.36.410.jar}"
MICRO_SRC="${MICRO_SRC:-$COREMODS/immibis-microblocks-55.0.7.jar}"
ICORE_SRC="${ICORE_SRC:-$MODS/immibis-core-55.1.6.jar}"
NEI_SRC="${NEI_SRC:-$COREMODS/NotEnoughItems 1.5.2.28.jar}"

ASM="${ASM:-$HOME/.local/share/PolyMC/libraries/org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar}"
JAVAC8="${JAVAC8:-/usr/lib/jvm/java-8-openjdk/bin/javac}"
FORGE_URL="https://maven.minecraftforge.net/net/minecraftforge/forge/1.5.2-7.8.1.737/forge-1.5.2-7.8.1.737-universal.zip"
FORGE="${FORGE:-build/forge-1.5.2-universal.zip}"

rm -rf build/cls build/tool
mkdir -p build/cls build/tool

[ -f "$ASM" ]    || { echo "ASM not found at $ASM - set ASM=..." >&2; exit 1; }
[ -x "$JAVAC8" ] || { echo "Java 8 javac not found at $JAVAC8 - set JAVAC8=..." >&2; exit 1; }

if [ ! -f "$FORGE" ]; then
  echo "fetching Forge universal (once) -> $FORGE"
  mkdir -p "$(dirname "$FORGE")"
  curl -fsSL -o "$FORGE" "$FORGE_URL"
fi

# javac 8 warns that -source/-target 1.6 are obsolete. Filter only that noise -
# never redirect the whole stream, or a real compile error disappears and set -e
# kills the script with no explanation.
compile8() {
  "$JAVAC8" -nowarn -source 1.6 -target 1.6 \
    -bootclasspath "$(dirname "$JAVAC8")/../jre/lib/rt.jar" \
    -cp "$FORGE" -d build/cls "$1" 2>&1 \
    | grep -vE 'bootstrap class path|source value 1\.6|target value 1\.6|options|deprecat' || true
}

# <label> <src jar> <out jar> <patcher.java> <helper class(es)> [patch list]
# Several helper classes are passed as one space-separated argument.
patch_one() {
  local label="$1" src="$2" out="$3" tool="$4" helper="$5" patches="${6:-}"
  if [ ! -f "$src" ]; then
    echo "skip $label: source jar not found at $src"
    return 0
  fi
  javac -nowarn -cp "$ASM" -d build/tool "$tool"
  local cls="${tool%.java}"
  if [ -n "$patches" ]; then
    java -cp "$ASM:build/tool" "$cls" "$src" "$out" "$patches" $helper
  else
    java -cp "$ASM:build/tool" "$cls" "$src" "$out" $helper
  fi
}

compile8 src/atomicscience/fanwusu/VoltzFixConfig.java
compile8 src/andrew/powersuits/VoltzMagnetConfig.java
compile8 src/mffs/VoltzMFFS.java
compile8 src/mekanism/common/VoltzMekanism.java
compile8 src/mekanism/common/VoltzTimeItems.java
compile8 src/icbm/zhapin/VoltzICBM.java
compile8 src/icbm/gangshao/VoltzSentry.java
compile8 src/icbm/wanyi/VoltzContraption.java
compile8 src/net/machinemuse/powersuits/VoltzMPS.java
compile8 src/powercrystals/minefactoryreloaded/VoltzMFR.java
compile8 src/micdoodle8/mods/galacticraft/core/VoltzGC.java
compile8 src/mekanism/generators/common/VoltzMekGen.java
compile8 src/mods/immibis/core/api/multipart/util/VoltzMicro.java
compile8 src/codechicken/nei/VoltzNEI.java

for f in build/cls/atomicscience/fanwusu/VoltzFixConfig.class \
         build/cls/micdoodle8/mods/galacticraft/core/VoltzGC.class \
         build/cls/andrew/powersuits/VoltzMagnetConfig.class \
         build/cls/mffs/VoltzMFFS.class \
         build/cls/mekanism/common/VoltzMekanism.class \
         build/cls/mekanism/common/VoltzTimeItems.class \
         build/cls/icbm/zhapin/VoltzICBM.class \
         build/cls/icbm/gangshao/VoltzSentry.class \
         build/cls/icbm/wanyi/VoltzContraption.class \
         build/cls/net/machinemuse/powersuits/VoltzMPS.class \
         build/cls/powercrystals/minefactoryreloaded/VoltzMFR.class \
         build/cls/mekanism/generators/common/VoltzMekGen.class \
         build/cls/mods/immibis/core/api/multipart/util/VoltzMicro.class \
         build/cls/codechicken/nei/VoltzNEI.class; do
  [ -f "$f" ] || { echo "helper class missing after compile: $f" >&2; exit 1; }
done

patch_one "Atomic Science" "$AS_SRC"   "Atomic_Science_v0.6.2.117-patched.jar" \
          PatchAS.java   build/cls/atomicscience/fanwusu/VoltzFixConfig.class \
          "plasma,noblastdamage,syncspawn,assemblerwear"

patch_one "MPS Addons"     "$MPSA_SRC" "MPSA-0.2.3-144_MPS-531+-patched.jar" \
          PatchMPSA.java build/cls/andrew/powersuits/VoltzMagnetConfig.class

patch_one "MFFS"           "$MFFS_SRC" "MFFS_v3.1.0.175-patched.jar" \
          PatchMFFS.java build/cls/mffs/VoltzMFFS.class \
          "mergedupe,stabilizedupe"

patch_one "Mekanism"       "$MEK_SRC"  "Mekanism-v5.5.6.bugfix1-patched.jar" \
          PatchMek.java  "build/cls/mekanism/common/VoltzMekanism.class build/cls/mekanism/common/VoltzTimeItems.class" \
          "chestcrash,chestdupe,chestremote,machinedupe,robitdupe,tntdupe,tntsource,timeitems,aebridge,cablereload"

patch_one "ICBM Explosion" "$ICBM_SRC" "ICBM_Explosion_v1.2.1.172-patched.jar" \
          PatchICBM.java build/cls/icbm/zhapin/VoltzICBM.class \
          "redmatter,sonic,remote,explosivetype,empradius,launchertier,multiblock,cruiselauncher,designator,defuser,missilestack,listeners,radarradius,radargun,chunkload"

patch_one "Modular Powersuits" "$MPS_SRC" "ModularPowersuits-0.7.0-534-patched.jar" \
          PatchMPS.java  build/cls/net/machinemuse/powersuits/VoltzMPS.class \
          "blink"

patch_one "ICBM Sentry"    "$ICBMS_SRC" "ICBM_Sentry_v1.2.1.172-patched.jar" \
          PatchICBMSentry.java build/cls/icbm/gangshao/VoltzSentry.class \
          "terminal,turretpackets,multiblock,ammodupe,listeners,consolecap,targetcommand,antimatterammo,doubleenergy,accesscommands"

patch_one "ICBM Contraption" "$ICBMC_SRC" "ICBM_Contraption_v1.2.1.172-patched.jar" \
          PatchICBMContraption.java build/cls/icbm/wanyi/VoltzContraption.class \
          "camouflage,detector,listeners,chunkload"

patch_one "MineFactoryReloaded" "$MFR_SRC" "MineFactoryReloaded-2.6.4-975-patched.jar" \
          PatchMFR.java  build/cls/powercrystals/minefactoryreloaded/VoltzMFR.class \
          "ghostslot,pkttile,harvester,dsuside,rednet,guidupe,routerloop,dsunbt"

patch_one "MekanismGenerators" "$MEKGEN_SRC" "MekanismGenerators-v5.5.6.bugfix1-patched.jar" \
          PatchMekGen.java build/cls/mekanism/generators/common/VoltzMekGen.class \
          "solarspace,boundclear,metaclamp,particlepkt"

patch_one "Galacticraft" "$GC_SRC" "Galacticraft-1.5.2-a0.1.36.410-patched.jar" \
          PatchGC.java   build/cls/micdoodle8/mods/galacticraft/core/VoltzGC.class \
          "dimauth,station,reach,rider,guis,nbtclamp,uechunk"

# Both immibis jars ship the same BlockMultipartBase and either copy can win the class load,
# so both are patched; only the microblocks jar carries the placement packet.
patch_one "immibis microblocks" "$MICRO_SRC" "immibis-microblocks-55.0.7-patched.jar" \
          PatchMicro.java build/cls/mods/immibis/core/api/multipart/util/VoltzMicro.class \
          "dropstatic,placereach"

patch_one "immibis core"       "$ICORE_SRC" "immibis-core-55.1.6-patched.jar" \
          PatchMicro.java build/cls/mods/immibis/core/api/multipart/util/VoltzMicro.class \
          "dropstatic,placereach"

patch_one "NotEnoughItems"     "$NEI_SRC"   "NotEnoughItems 1.5.2.28-patched.jar" \
          PatchNEI.java   build/cls/codechicken/nei/VoltzNEI.class \
          "auth"

# BalancedMekanismTimeItems: the optional Stopwatch / Weather Orb server policy. A feature, so
# it ships separately from the patched Mekanism jar, which carries the exploit fix on its own.
# It transforms nothing, so it is an ordinary mod: drop the jar in a server's mods/.
echo "building BalancedMekanismTimeItems mod"
rm -rf build/bti && mkdir -p build/bti
"$JAVAC8" -nowarn -source 1.6 -target 1.6 -bootclasspath "$(dirname "$JAVAC8")/../jre/lib/rt.jar" \
    -cp "$FORGE" -d build/bti \
    src/voltz/timeitems/BalancedTimeItems.java src/voltz/timeitems/BalancedMekanismTimeItems.java 2>&1 \
    | grep -vE 'bootstrap class path|source value 1\.6|target value 1\.6|options|deprecat' || true
[ -f build/bti/voltz/timeitems/BalancedMekanismTimeItems.class ] \
    || { echo "BalancedMekanismTimeItems did not compile" >&2; exit 1; }
( cd build/bti && jar cf "$OLDPWD/BalancedMekanismTimeItems.jar" voltz )
echo "OK  wrote BalancedMekanismTimeItems.jar  [Stopwatch / Weather Orb policy mod]"

# BalancedMFFS: a coremod, not a mod patch - the zone-flag and admin-logging feature. It is a
# feature rather than a bug fix, so it ships separately from MFFS_v3.1.0.175-patched.jar and a
# server can install the dupe fixes without it. Drop the jar in a server's coremods/.
echo "building BalancedMFFS coremod"
rm -rf build/bmffs && mkdir -p build/bmffs/META-INF
"$JAVAC8" -nowarn -source 1.6 -target 1.6 -bootclasspath "$(dirname "$JAVAC8")/../jre/lib/rt.jar" \
    -cp "$ASM:$FORGE" -d build/bmffs \
    src/mffs/BalancedMFFS.java src/mffs/BalancedMFFSPlugin.java src/mffs/BalancedMFFSTransformer.java 2>&1 \
    | grep -vE 'bootstrap class path|source value 1\.6|target value 1\.6|options|deprecat' || true
[ -f build/bmffs/mffs/BalancedMFFSTransformer.class ] || { echo "BalancedMFFS did not compile" >&2; exit 1; }
printf 'Manifest-Version: 1.0\r\nFMLCorePlugin: mffs.BalancedMFFSPlugin\r\n' > build/bmffs/META-INF/MANIFEST.MF
( cd build/bmffs && jar cfm "$OLDPWD/BalancedMFFS.jar" META-INF/MANIFEST.MF mffs )
echo "OK  wrote BalancedMFFS.jar  [zone flags + admin logging coremod]"

# VoltzLoginGuard: a coremod (not a mod patch) - the FML login-sequence crash guard. Compiled
# against ASM (using only the 4-arg MethodInsnNode, which FML 1.5.2's ASM 4.1 also has) and the
# Forge zip, then jarred with an FMLCorePlugin manifest. Drop the jar in a server's coremods/.
echo "building VoltzLoginGuard coremod"
rm -rf build/lg && mkdir -p build/lg/META-INF build/lg/voltz/loginguard
"$JAVAC8" -nowarn -source 1.6 -target 1.6 -bootclasspath "$(dirname "$JAVAC8")/../jre/lib/rt.jar" \
    -cp "$ASM:$FORGE" -d build/lg \
    src/voltz/loginguard/VoltzLoginGuard.java src/voltz/loginguard/LoginGuardTransformer.java 2>&1 \
    | grep -vE 'bootstrap class path|source value 1\.6|target value 1\.6|options|deprecat' || true
[ -f build/lg/voltz/loginguard/LoginGuardTransformer.class ] || { echo "VoltzLoginGuard did not compile" >&2; exit 1; }
printf 'Manifest-Version: 1.0\r\nFMLCorePlugin: voltz.loginguard.VoltzLoginGuard\r\n' > build/lg/META-INF/MANIFEST.MF
( cd build/lg && jar cfm "$OLDPWD/VoltzLoginGuard.jar" META-INF/MANIFEST.MF voltz )
echo "OK  wrote VoltzLoginGuard.jar  [login guard coremod]"
