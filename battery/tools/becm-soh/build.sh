#!/usr/bin/env bash
# Builds out/becm-soh/ReadBecmDid.jar (dex) for app_process on the head unit.
set -eu
cd "$(dirname "$0")/../../.."
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
PLATFORM=$SDK/platforms/android-33/android.jar
D8=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)/d8
rm -rf out/becm-soh && mkdir -p out/becm-soh/classes
javac --release 8 -Xlint:-options -cp "$PLATFORM" -d out/becm-soh/classes battery/tools/becm-soh/ReadBecmDid.java
"$D8" --min-api 30 --lib "$PLATFORM" --output out/becm-soh/ReadBecmDid.jar out/becm-soh/classes/*.class
echo out/becm-soh/ReadBecmDid.jar
