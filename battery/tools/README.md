# Catfish Battery UI checks

These tools exercise the production pages/APK. Browser fixtures contain only
unavailable telemetry and the normal default settings; never describe their
screenshots as live vehicle readings. The APK itself has no fixture data.

From the repository root, with the existing SDK and JDK available:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
bash gradlew -Dorg.gradle.java.home="$JAVA_HOME" \
  :battery:testDebugUnitTest :battery:assembleRelease :battery:lintRelease

mkdir -p out/ui-qa/classes out/ui-qa/html
javac -d out/ui-qa/classes \
  battery/src/main/java/com/catfish/battery/BatteryWebPages.java \
  battery/src/main/java/com/catfish/battery/BatteryWindowStyle.java \
  battery/tools/BatteryPageExport.java
java -cp out/ui-qa/classes com.catfish.battery.BatteryPageExport out/ui-qa/html
node battery/tools/check-layout.mjs out/ui-qa/html

python3 battery/tools/emulator-ui-check.py \
  battery/build/outputs/apk/release/catfish_battery_1.2_release.apk \
  out/ui-qa/android11-clean
```

Requirements: installed Chrome, Node with global WebSocket (Node 24 works),
Python standard library, and the existing `geely_a11` AVD. No npm/Python package
installation is needed. The browser check uses a private Chrome profile under
`$TMPDIR`, ephemeral loopback HTTP/CDP ports, and exports its JSON ledger.
It checks both themes at the EX5 WebView dimensions (2496x1176) and a smaller
1900x950 window, including aspect preservation and all 118 cell tiles.

The emulator sweep owns port 5580 for its whole lifetime. Do not run it if
that port is already in use. It starts a read-only Android 11 AVD, removes
only that disposable guest's inherited developer overlays, installs the
release APK, taps native tabs and Schematic/Cells through current UI bounds,
changes day/night, saves/reopens the default Settings, and captures screenshots.
It shuts down only its own emulator in `finally`. It does not use the car,
change another emulator, clear app data, or write back to the source AVD.

Inspect every resulting screenshot as well as the automated checks. SVG
aspect and viewport bounds do not prove text contrast, populated-history
readability, real charge logging, or head-unit STR survival.

## Battery-health logger (canspi, read-only)

Records the head unit's bigdata `canspi` snapshot once a minute so SOH
candidates (`HvBattSOCE`, `HvBattEgyCdn`, `HvBattPwrCdn`) can be compared with
SOC across a full charge. It runs as the adb shell user (no root) on the car, needs no APK
install, and only subscribes (CONNECT, SUBSCRIBE, DISCONNECT). It never
publishes to the broker.

```sh
battery/tools/canspi-log.sh start 60 1440   # every 60 s, stop after 1440 captures
battery/tools/canspi-log.sh status
battery/tools/canspi-log.sh stop
battery/tools/canspi-log.sh pull            # out/canspi-log/<time>/canspi.csv
```

The loop lives in `/data/local/tmp`, so it stops at the next head-unit sleep or
reboot. Run `status` after the car wakes. Captures are private vehicle data:
`out/` stays out of git. Offline check: the loop and CSV export were run
against a strict local fake broker that replays real snapshots. They have not
yet run on the car (`toybox nc` behaviour is untested).

## BECM SOH read (DID 0x496D, read-only)

Reads the dealer-tool SOH (`High Voltage Battery Health (SOH) - BECM`) through
Dxapp's installed `VehicleDiagIF` middleware, from the adb shell user. It
acquires diagnostic mode 5, sends only UDS `22 49 6D` to BECM `0x1635`, and
releases the channel. A VHAL guard requires `GEAR_SELECTION` and
`CURRENT_GEAR` = Park (4) before acquisition and every 125 ms during the read.

```sh
battery/tools/becm-soh/build.sh
battery/tools/becm-soh/run.sh --guard-only   # checks the Park guard, no middleware calls
battery/tools/becm-soh/run.sh                # reads 496D
```

This takes the car's diagnostic channel for a few seconds: run it parked, and
not while a workshop tool or OTA is active.
