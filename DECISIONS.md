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
