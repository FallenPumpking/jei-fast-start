#!/usr/bin/env bash
# Builds jeifast with plain javac (JDK 17+), no Gradle.
#
# The mixins name their JEI and KubeJS targets as strings, so neither jar is needed to compile
# and no refmap is needed; the few Minecraft methods used are called by SRG name. The jar
# therefore runs on a normal Forge 1.20.1 install but not in a Mojang-mapped dev environment.
#
# Required environment:
#   MC_LIBS   a Forge 1.20.1 (47.x) libraries folder, e.g. <launcher>/libraries
# Optional:
#   FORGE_VERSION (default 47.4.10), JAVA_HOME (default: javac and jar from PATH)
set -euo pipefail
cd "$(dirname "$0")"
VERSION=1.0.1
: "${MC_LIBS:?set MC_LIBS to a Forge 1.20.1 libraries folder}"
FORGE_VERSION="${FORGE_VERSION:-47.4.10}"
BIN=""
if [ -n "${JAVA_HOME:-}" ]; then BIN="$JAVA_HOME/bin/"; fi
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac

CP=""
for lib in \
  "net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-srg.jar" \
  "org/spongepowered/mixin/0.8.5/mixin-0.8.5.jar" \
  "org/ow2/asm/asm/9.6/asm-9.6.jar" \
  "org/ow2/asm/asm-tree/9.6/asm-tree-9.6.jar" \
  "net/minecraftforge/javafmllanguage/1.20.1-$FORGE_VERSION/javafmllanguage-1.20.1-$FORGE_VERSION.jar" \
  "net/minecraftforge/fmlcore/1.20.1-$FORGE_VERSION/fmlcore-1.20.1-$FORGE_VERSION.jar" \
  "org/apache/logging/log4j/log4j-api/2.19.0/log4j-api-2.19.0.jar"; do
  [ -f "$MC_LIBS/$lib" ] || { echo "missing $MC_LIBS/$lib" >&2; exit 1; }
  CP="${CP:+$CP$SEP}$MC_LIBS/$lib"
done

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/libs"
find src/main/java -name "*.java" > "$OUT/sources.txt"
"${BIN}javac" --release 17 -proc:none -encoding UTF-8 -cp "$CP" -d "$OUT/classes" @"$OUT/sources.txt"
cp -r src/main/resources/. "$OUT/classes/"
cp LICENSE "$OUT/classes/META-INF/LICENSE"
printf 'MixinConfigs: jeifast.mixins.json\n' > "$OUT/manifest.txt"
"${BIN}jar" --create --file "$OUT/libs/jeifast-forge-1.20.1-$VERSION.jar" --manifest "$OUT/manifest.txt" -C "$OUT/classes" .
echo "Built $OUT/libs/jeifast-forge-1.20.1-$VERSION.jar"
