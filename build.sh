#!/usr/bin/env bash
# Build the patched Atomic Science jar.
#   ./build.sh <path-to-stock-Atomic_Science_v0.6.2.117.jar> [out.jar]
set -euo pipefail
SRC="${1:?usage: ./build.sh <stock-AS.jar> [out.jar]}"
OUT="${2:-Atomic_Science_v0.6.2.117-patched.jar}"

ASM="${ASM:-$HOME/.local/share/PolyMC/libraries/org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar}"
MC="${MC:-$HOME/.local/share/PolyMC/instances/Voltz/.minecraft/bin/minecraft.jar}"
GUAVA="${GUAVA:-$HOME/.local/share/PolyMC/instances/Voltz/.minecraft/lib/guava-14.0-rc3.jar}"
JAVAC8="${JAVAC8:-/usr/lib/jvm/java-8-openjdk/bin/javac}"

rm -rf build && mkdir -p build/cls build/tool

# 1. config holder, compiled to class version 50 to match the mod
"$JAVAC8" -nowarn -source 1.6 -target 1.6 \
  -bootclasspath /usr/lib/jvm/java-8-openjdk/jre/lib/rt.jar \
  -cp "$MC:$GUAVA" -d build/cls src/atomicscience/fanwusu/VoltzFixConfig.java 2>/dev/null

# 2. the ASM patcher
javac -nowarn -cp "$ASM" -d build/tool PatchAS.java

# 3. apply
java -cp "$ASM:build/tool" PatchAS "$SRC" "$OUT" \
     plasma,noblastdamage,syncspawn,assemblerwear \
     build/cls/atomicscience/fanwusu/VoltzFixConfig.class
