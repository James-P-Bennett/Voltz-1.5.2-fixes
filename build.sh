#!/usr/bin/env bash
# Build every patched jar.
#
#   ./build.sh                 build all mods found at the default paths
#   ./build.sh <AS.jar>        override just the Atomic Science source jar
#
# Override any path with an env var:
#   MODS  AS_SRC  MPSA_SRC  MFFS_SRC  MEK_SRC  ICBM_SRC  ICBMS_SRC  ICBMC_SRC  MPS_SRC  MFR_SRC  FORGE  ASM  JAVAC8
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
compile8 src/mffs/BalancedMFFS.java
compile8 src/mekanism/common/VoltzMekanism.java
compile8 src/mekanism/common/BalancedTimeItems.java
compile8 src/icbm/zhapin/VoltzICBM.java
compile8 src/icbm/gangshao/VoltzSentry.java
compile8 src/icbm/wanyi/VoltzContraption.java
compile8 src/net/machinemuse/powersuits/VoltzMPS.java
compile8 src/powercrystals/minefactoryreloaded/VoltzMFR.java

for f in build/cls/atomicscience/fanwusu/VoltzFixConfig.class \
         build/cls/andrew/powersuits/VoltzMagnetConfig.class \
         build/cls/mffs/BalancedMFFS.class \
         build/cls/mekanism/common/VoltzMekanism.class \
         build/cls/mekanism/common/BalancedTimeItems.class \
         build/cls/icbm/zhapin/VoltzICBM.class \
         build/cls/icbm/gangshao/VoltzSentry.class \
         build/cls/icbm/wanyi/VoltzContraption.class \
         build/cls/net/machinemuse/powersuits/VoltzMPS.class \
         build/cls/powercrystals/minefactoryreloaded/VoltzMFR.class; do
  [ -f "$f" ] || { echo "helper class missing after compile: $f" >&2; exit 1; }
done

patch_one "Atomic Science" "$AS_SRC"   "Atomic_Science_v0.6.2.117-patched.jar" \
          PatchAS.java   build/cls/atomicscience/fanwusu/VoltzFixConfig.class \
          "plasma,noblastdamage,syncspawn,assemblerwear"

patch_one "MPS Addons"     "$MPSA_SRC" "MPSA-0.2.3-144_MPS-531+-patched.jar" \
          PatchMPSA.java build/cls/andrew/powersuits/VoltzMagnetConfig.class

patch_one "MFFS"           "$MFFS_SRC" "MFFS_v3.1.0.175-patched.jar" \
          PatchMFFS.java build/cls/mffs/BalancedMFFS.class

patch_one "Mekanism"       "$MEK_SRC"  "Mekanism-v5.5.6.bugfix1-patched.jar" \
          PatchMek.java  "build/cls/mekanism/common/VoltzMekanism.class build/cls/mekanism/common/BalancedTimeItems.class" \
          "chestcrash,chestdupe,chestremote,machinedupe,robitdupe,tntdupe,tntsource,timeitems"

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
          "ghostslot"
