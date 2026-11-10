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
