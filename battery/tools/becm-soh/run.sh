#!/usr/bin/env bash
# Read BECM DID 0x496D through Dxapp's middleware. Read-only UDS 22 49 6D, VHAL Park guard. Cleans up its JAR.
#   SERIAL=<ip:port> ./run.sh [--guard-only]
set -eu
ADB=$HOME/Android/Sdk/platform-tools/adb
S=${SERIAL:-192.168.1.158:5566}
JAR=${JAR:-out/becm-soh/ReadBecmDid.jar}
car() { timeout 40 "$ADB" -s "$S" shell "$@"; }
product=$(car getprop ro.build.product | tr -d '\r')
# same rule as VehiclePlatform.java: e245 = EX5 is the only platform this read is verified on
case "$product" in *[!a-z0-9]e245[!a-z0-9]*|e245|*[!a-z0-9]e245|e245[!a-z0-9]*) ;; *) echo "Not a Geely EX5 (e245): ro.build.product=$product"; exit 1 ;; esac
remote=$(car mktemp -d /data/local/tmp/geely-did.XXXXXX | tr -d '\r')
case "$remote" in /data/local/tmp/geely-did.??????) ;; *) echo "bad temp dir: $remote"; exit 1 ;; esac
cleanup() { car "rm -f '$remote/ReadBecmDid.jar' && rmdir '$remote' && test ! -e '$remote'" && echo "Temporary client removed"; }
trap cleanup EXIT
"$ADB" -s "$S" push -q "$JAR" "$remote/ReadBecmDid.jar"
car "timeout 25 env CLASSPATH='$remote/ReadBecmDid.jar' /system/bin/app_process64 /system/bin ReadBecmDid ${1:-}"
