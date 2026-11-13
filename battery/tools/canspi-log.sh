#!/usr/bin/env bash
# Host side of the canspi battery-health logger. Read-only on the car: it starts a
# subscribe-only capture loop as root, and pulls its files. No APK install, no vehicle writes.
#   canspi-log.sh start [interval_s=60] [max_captures=1440]
#   canspi-log.sh status | stop
#   canspi-log.sh pull          -> out/canspi-log/<time>/canspi.csv
set -euo pipefail
cd "$(dirname "$0")/../.."
ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
CAR_DIR=/data/local/tmp/catfish-canspi
CAR_LOOP=/data/local/tmp/catfish-canspi-loop.sh

# Only ever talk to the EX5: another adb device (V2_PRO) can be attached instead.
SERIAL=${SERIAL:-$("$ADB" devices -l | awk 'tolower($0) ~ /ex5/ {print $1; exit}')}
[ -n "$SERIAL" ] || { echo "No Geely EX5 on adb (set SERIAL to override)." >&2; exit 1; }
car() { "$ADB" -s "$SERIAL" shell "su 0 sh -c '$1'"; }
running() { car "p=\$(cat $CAR_DIR/pid 2>/dev/null) && kill -0 \$p 2>/dev/null && echo yes" | grep -q yes; }

case "${1:-}" in
start)
    if running; then echo "Already running on $SERIAL."; exit 0; fi
    "$ADB" -s "$SERIAL" push -q battery/tools/canspi-loop.sh "$CAR_LOOP"
    car "nohup sh $CAR_LOOP ${2:-60} ${3:-1440} >/dev/null 2>&1 &"
    sleep 3
    running && echo "Logging on $SERIAL every ${2:-60} s." || { echo "Logger did not start." >&2; exit 1; }
    ;;
status)
    echo "running: $(running && echo yes || echo no), captures: $(car "ls $CAR_DIR/*.bin 2>/dev/null | wc -l")"
    ;;
stop)
    car "kill \$(cat $CAR_DIR/pid) 2>/dev/null; rm -f $CAR_DIR/pid"
    echo "Stopped."
    ;;
pull)
    dest="out/canspi-log/$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$dest/bin" out/canspi-tool
    # exec-out keeps binary intact (shell's pty rewrites \n); su because the files are root-owned
    "$ADB" -s "$SERIAL" exec-out "su 0 sh -c 'cd $CAR_DIR && tar cf - *.bin'" | tar xf - -C "$dest/bin"
    src=battery/src/main/java/com/catfish/battery
    javac -d out/canspi-tool "$src/CanSpiDecoder.java" "$src/MqttFrames.java" battery/tools/CanSpiLogExport.java
    java -cp out/canspi-tool com.catfish.battery.CanSpiLogExport "$dest/bin" "$dest/canspi.csv"
    ;;
*)
    sed -n '2,7p' "$0"; exit 2
    ;;
esac
