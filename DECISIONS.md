# Decisions

## Standalone repository

Extract only the `:battery` module from the finalized working tree of
`GeelyCustomProfiles-catfish-battery` (branch `catfish/battery`, based on
`2c5af5f` plus the 1.2 presentation changes). CF's source and its history stay
in the original repository. This repository has its own small Gradle root
and no dependency on `:app` or `:split`.

## Preserve behavior and the original 3D pack

Keep the original 3D Schematic, sensor placement, energy-flow paths, Cells
view and telemetry. Presentation is isolated in `BatteryWindowStyle`.
Size the original SVG by both available axes without distortion; keep top
tabs and the vehicle's system bars/climate dock visible. Recreate native
chrome on day/night changes while preserving the selected page.

## Native pages instead of the WebView (2026-11-18)

The owner asked for every page as native Android views (backlog: "Make every
page native"). `BatteryActivity` keeps the native top tab bar and now hosts
`StatusPage`, `ChargingPage`, `HistoryPage` and `SettingsPage` directly; there
is no WebView, no in-app HTTP interception and no `CFBattery` JS bridge (pages
call `BatteryDataHub` themselves). The look follows the approved WebView
window sizes (`BatteryWindowStyle.css()`, 1 CSS px = 1 dp at the car's
density 160) and Midnight Champagne at night.

- Status: `PackSchematicView` draws the same 1180x470 3D pack, anchors and
  flow paths on a Canvas, scaled by both axes without distortion;
  `FlowOverlayView` sits exactly over it in the same FrameLayout. The
  Schematic/Cells switch stays (choice kept in preferences); the Cells view
  (`CellsView`/`CellsModel`) keeps the 118 cell + 12 sensor tiles, bands and
  ▼/▲ markers. The SOH badge left the Pack header; SOH is shown in the HV PACK
  block.
- Polling: Status reads `BatteryDataHub.dataJson()` once a second on a
  background thread while shown and resumed; Charging/History refresh every
  30 s and Settings on show, the WebView rates. Day/night still recreates the
  activity and the selected page survives it.
- `BatteryWebPages`/`BatteryWindowStyle` stay only as the porting reference
  until integration removes them; HTML-based tests and tools are replaced then.

## Data and privileges

Catfish Battery owns its history independently of CF; no old CF history is
migrated. Vehicle telemetry is read-only over localhost VHAL/APVP gRPC.
There is no CF root bridge, so the car_service range probe is not available.
Firmware 1.3 APVP reply IDs may shift; match by signal name and normalize
to the requested ID.

## Release identity and signing

Publish the exact 1.2 APK installed and verified on the car as the v1.2
release asset, with its SHA-256 checksum. A fresh rebuild is not assumed
byte-identical until checked. Signing keys and APK files are not tracked.
The shipped signer is the developer's existing Android Debug certificate;
a different machine's default debug key cannot update that installation.
Do not resolve signer mismatches by uninstalling or clearing data.

- Totals reset: manual "Reset now" and an optional monthly / every-N-months
  schedule clear only Used/Charged/Regen totals, never charge history or
  settings. A schedule change records `resetAnchor`, so totals counted before
  it are not wiped by an overdue date. A missed reset (head unit asleep) runs
  once on wake, dated the last due day. Day 31 means the month's last day.

- BECM SOH read (2026-11-18, approved by the user): when the app opens, at
  most once per local calendar day and only in Park, the app reads the BECM's
  own SOH (UDS 22 49 6D) through Dxapp's VehicleDiagIF middleware. This lifts
  the earlier "no automatic diagnostic requests" rule for this one read only.
  Guard: GEAR_SELECTION and CURRENT_GEAR re-read every 100 ms, abort when a
  fresh read is not Park; skip when the channel is busy; one request; always
  release the channel. The page shows a spinner during the read. BECM rows
  (0.01 % steps) are shown with two decimals; canspi HvBattEgyCdn (0.5 %
  steps) with one, and only fills in when no BECM read is under a day old.

- Public charger prices (2026-11-18): Settings lists public network prices for
  the chosen currency (THB, USD, EUR, CNY so far) from
  `battery/src/main/assets/charger-prices.json`. The app fetches the same file
  from GitHub `main` once per run and keeps it; the bundled copy is the offline
  fallback. Every price carries its source, URL and date. "+ Add" only fills a
  new charger row; nothing is saved until the driver taps Save. The cost to
  80 % uses the car's live SOC and remaining pack energy and is hidden when
  either is missing; no figures are made up.

- Settings layout (2026-11-18): one summary card per section on the left
  (Region, Preferences, Chargers & prices, Totals & reset, Update, About), each
  showing its current values; the open section is on the right. Region picks
  the currency (no separate Currency row) and with it the peak hours and the
  public charger price list from `charger-prices.json`. Update reads the last 5
  releases from the GitHub API (read-only, once per run or on Check now) and
  shows the changelog; it never downloads or installs anything. About shows the
  build time and commit from BuildConfig and a Ko-fi QR code.

- Cell lift motion (2026-11-18, owner picked an HTML
  mockup, kept out of git): on the schematic, green cells stay flat,
  a cell above the pack median springs up with a small overshoot (warn: by its
  deviation, danger: full height and a gentle bob), a cell below it sinks a
  little, and band colours blend instead of switching. `CellMotion` owns the
  physics; geometry, sensor anchors and flow paths are unchanged. The view only
  redraws per frame while cells move or a danger cell bobs, and snaps when the
  system turns animations off.
  The Cells grid uses the same CellMotion (owner's pick: mix of mockups 1, 3
  and 4): a high cell lifts as a raised key with a 3D edge and shadow, red adds
  a glow, a low cell sinks, value colours blend, and a reading that changes any
  cell sends one small wave across the grid from the most deviating cell (no
  wave when nothing changed, so a 1 Hz refresh does not keep the grid moving).

## Schematic layout 2026-11-19 (owner's mockup 7 + swipe card)
- HV pack card with the SOC graph moves to the top-left (-92..390 × 6..127); the sensor legend sits under it in four
  groups (BMS SYSTEM, HV BUS, COOLANT, DC CHARGE), each with a bar beside its rows. Port order on the BMS face, top
  first: coolant out, coolant in, DC+, DC−. Pack tags hang straight 38 units above (P1, P4) or below (P2, P3, air).
- Right column shares x 884: a swipe card (Range & energy / Pack power / Extremes, dots underneath), Loads
  (120..340), and the 12 V battery; DC-DC sits under the pack. Wires were shortened to the new card edges.
- Pack painted as "glass": translucent cells with a rim, tray and BMS edged like the cards, soft floor reflection.
  The pack geometry, 12 sensor anchors and the four energy-flow paths' meaning are unchanged; no FRONT label.
- Design-review fixes: one card title style (bold 18 + state pill), Loads card always titled "Loads", Regen is green
  only when real regen was recorded, header counters read "n/118 cells" and "n/12 sensors".
- Swipe card values: RANGE_REMAINING (rangeKm), HvBattEgyAvlDcha1 (energyWh), V × I pack power, highest cell with its
  number, hottest/coolest sensor. Missing readings show "--". No trip Wh/km yet: the app does not compute it.

## Range, highest cell and Health trend source (2026-11-19)
- Range: RANGE_REMAINING through CarPropertyManager (reflection, read only) every 20 s; adds the runtime permission
  android.car.permission.CAR_ENERGY, asked once from BatteryActivity. The gRPC stream never pushes it.
- Highest cell: the BMS's own HvBattVoltMaxSerlNr + CellUMax (APVP), not the first of several equal samples.
  CellUMax/CellUMin also fill cellMaxV/cellMinV, which the VHAL cache never carried on this car.
- Health: the trend, the day counter and the rates use BECM readings only when there are any (canspi stays in the
  table). Mixing 0.5 % canspi steps with 0.01 % BECM reads produced a fake -16 %/year. The counter now counts
  calendar days with a reading instead of elapsed whole days.

## License (2026-11-19)
- Apache License 2.0 for the code Antony-12s wrote (owner's choice: free use, required credit through NOTICE).
- VehiclePropertyObserver.java, VehiclePropertyManager.java and vhal.proto derive from Geely Custom Profiles, which
  has no license; they are listed in NOTICE as not covered. Rewriting them from scratch would let the whole repository
  carry the license.

## In-app install (2026-11-19)
- Settings > Update gets an Install button (owner request). It downloads the release APK from this repository's
  release downloads only, checks it against the release's .sha256 and against this app's own signer, package name and
  a newer versionCode, then installs it as the shell user over the head unit's loopback adbd (LoopbackAdb,
  written from the public ADB protocol docs; HUD Cluster's AdbShell derives from Custom Profiles' unlicensed
  LoopbackAdbClient, so it is not reused). Firmware 1.3's package verifier rejects shell installs, so the install script turns
  verifier_verify_adb_installs off for its own install and an EXIT trap restores it (HUD Cluster's VERIFIER_OFF).
  `pm install -r` keeps settings and history; the script runs detached and reopens the app.

## Clean VHAL stream (2026-11-19)
- VehiclePropertyObserver, VehiclePropertyManager and vhal.proto (from Geely Custom Profiles, unlicensed) are
  replaced by VhalStream: raw gRPC byte calls decoded with ApvpBatteryStream's own field reader, no generated
  protobuf code. Only StartPropertyValuesStream and SendAllPropertyValuesToStream exist; the set-property call is not
  referenced at all. The protobuf plugin and the protobuf-lite / grpc-protobuf-lite / annotations dependencies are
  gone (APK 7.0 -> 6.3 MB). The whole repository is now Apache 2.0.

## Platform check, read-only policy and event-driven Park guard (2026-11-19)
- The vehicle is identified by the platform code in Build.PRODUCT / ro.build.product (e245 EX5, p145 EX5 EM-i,
  e22 or p22 EX2), not by the model name or the APVP folder name (this e245 ships its APVP table as p145_j1).
- UdsPolicy is the single gate for UDS: only 22 49 6D to the BECM (0x1635); originally e245 only, now any platform (below). ReadOnlyPolicyTest fails the
  build if any other source sends UDS or names a vehicle-property write.
- The BECM read's Park guard listens to GEAR_SELECTION and CURRENT_GEAR events instead of polling. Leaving Park, an
  unknown value or an error event voids the read for good (generation token); the whole read has a 15 s budget.
- One shared CarPropertyManager per process (CarProps); the old per-supplier code created a new Car after every
  failed read.
- In-app updates keep the loopback-adb path. The head unit's package verifier (com.ecarx.xsfinstallverifier) only
  allows Geely-signed packages or the installers com.appmarket.automotive and com.faa.galaxy.appstore in its
  production environment, so a PackageInstaller session from this app would be rejected. Verified on the car:
  reinstalling the same 1.6.1 APK with the verifier on failed with INSTALL_FAILED_VERIFICATION_FAILURE
  (reason installer_not_whitelist) and the car showed "installation blocked, App Store only".

## CLI Park guard over the VHAL gRPC stream; live-only VHAL cache (2026-11-19)
- The VHAL gRPC stream (localhost:40004) does carry GEAR_SELECTION and CURRENT_GEAR (verified on the car), so the
  command-line ReadBecmDid / ScanDids guard now subscribes to it instead of running `cmd car_service` in a loop.
  It is a minimal hand-written HTTP/2 + protobuf client (stream and send-all calls only, same session id on both),
  so the tool stays one dex file. android.car is not usable there: the shell user lacks CAR_POWERTRAIN.
- VhalStream's int / float caches drop a value the car marks unavailable and clear on disconnect, so the app never
  shows a stale reading as live. APVP values were already cleared on disconnect.

## Navy Cyan night theme (2026-11-19)
- Replaces Midnight Champagne at night (owner's pick of 5, after feedback that yellow on black was too much and white
  text blended into the gold). Navy background #071019, cards #0D1A26, text #E3EEF7, muted #86A0B5, accent cyan
  #4CC9F0 with dark #04202B text on it; status good #85DAA4, warn #F2B86D, danger berry #FFA5C8 (no red).
- The accent is only for taps (tabs, selected buttons) and energy that flows (V x A line, load bars, charts).
  Headings, kickers, the sensor legend and its leader lines use the muted colour.
- Cells on the schematic at night: jade #42CB80, amber #EB9A04, berry #FA7FB3. The B+ bus stays red: it marks
  polarity, not a warning. The day theme is unchanged.

## BECM SOH read on any platform (2026-11-21)
- UdsPolicy no longer checks the platform: the BECM SOH read (22 49 6D to 0x1635) may run on the EX5 EM-i, EX2 or an
  unrecognised build. It is still the only UDS request, still Park-guarded and once a day. Verified on the EX5 only;
  elsewhere a BECM that does not know the DID answers 7F 22 31 (or times out) and SOH stays on canspi. A missing
  Dxapp.apk fails at class loading before anything is sent.
- EX5 EM-i (p145): the 2.2 OTA (ro.build.product antora1000_p145_eu, 24.07.01.263020.263924) lists all 45 of the app's
  APVP names, including the PA_HvBattCellTinfo_Res struct, so name resolution covers it; most built-in ids are 1-2 off
  there. Trimmed table: battery/src/test/resources/apvp/signal_config_263924_p145_trimmed.json. Its pack layout (cell
  and sensor counts) is not in the firmware and stays unknown until a live car is read. No EX2 firmware has been seen.
