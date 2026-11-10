# Catfish Battery

Standalone battery monitor for the Geely EX5 head unit.

- **Package:** `com.catfish.battery`
- **Current release:** 1.2 (`versionCode` 3)
- **Android:** 11+ (`minSdk` 30)
- **UI:** Status (original 3D Schematic and 118-cell view), Charging, History, Settings

The app reads the head unit's VHAL (`localhost:40004`) and APVP
(`localhost:40005`) streams. It does not write vehicle properties. A foreground
service keeps charge logging alive while the screen is closed and starts on
boot. It works independently of Custom Profiles (CF), which is unchanged.

## Download

Download `catfish_battery_1.2_release.apk` and `SHA256SUMS` from
[Releases](https://github.com/Antony-12s/CatfishBattery/releases).
Release files are not committed to the source tree.

## Build

Requires an Android SDK with platform 33, JDK 17+ (JDK 21 tested) and network
access for the Gradle dependencies. Gradle 8.14.4 is included through its wrapper.
No CF checkout or signing credentials are included.

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew :battery:testDebugUnitTest :battery:assembleRelease :battery:lintRelease
```

Output: `battery/build/outputs/apk/release/catfish_battery_1.2_release.apk`.
Read [UI test instructions](battery/tools/README.md) for browser and owned
Android 11 emulator checks.

### Signing and data-preserving updates

The Gradle release variant currently uses the local Android debug keystore,
matching the development build used on the car. **Each machine's debug key
is different.** A rebuild from another machine is not update-compatible
unless it is signed with the authorized original key. No private key is
included in this repository.

Installed 1.2 signing-certificate SHA-256:

```text
77ed0ffb4263c5a7ac30002cc79825c03068fc390eee0e446daf53ca2df37e46
```

Compare the installed and candidate signers before updating. Use an in-place
`-r` install for the verified active Android user. Never uninstall or clear
data to work around a signing mismatch. Vehicle installation needs the
owner's approval and a parked vehicle.

## Verification

The extracted repository was independently built and checked:

- 45 unit tests, 0 failures/errors; release build passed.
- Android lint: 0 errors, 14 warnings (not a warning-free build).
- 20 browser layout states across two window sizes and day/night passed again.
- Every rebuilt ZIP entry outside `META-INF/` matched the original on-car APK;
  complete APK bytes differ, so the release retains the original verified binary.

The shipped 1.2 APK was additionally tested before extraction:
- Actual Android 11 release-APK interaction sweep: all 4 tabs, Schematic/Cells,
  day/night and Settings Save/reopen; no observed crash.
- Exact installed APK bytes pulled back from the car matched the release hash.
- On-car 1.2 review: original 3D Schematic, all 118 cells/12 temperature sensors,
  Charging chart, empty History, and Settings fit above the climate dock.

These are distinct checks; an emulator's unavailable telemetry is not live
vehicle evidence. No real charge session or STR/sleep survival was verified.

## Known limits

- CF history is not migrated; Catfish has its own independent files/settings.
- No root-based car_service range probe. Unavailable metrics remain `--`.
- Background logging may be interrupted by the head unit's STR/idle policy;
  long-term survival is not verified.
- Pricing and cost calculations require the user's charger settings.

See [DECISIONS.md](DECISIONS.md) for scope and [NOTICE.md](NOTICE.md) for source
provenance. This private extraction does not establish a new code license.
