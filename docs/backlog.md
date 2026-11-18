# Backlog

Items noted for a later batch of fixes. Not started.

## UI

- **Schematic min/max cell markers overlap.** When the lowest and highest
  cells are neighbours, ▼ and ▲ (`markCell` in `BatteryWebPages.java`) draw on
  top of each other and look like a crumpled hourglass (seen on the car
  2026-11-18, spread 2 mV). Options discussed, owner has not chosen yet:
  1. Hide both markers while the spread is in the green band (≤ 2 mV).
  2. Replace the glyphs with outlines on the cells (dashed = lowest,
     solid = highest).
  3. Nudge the markers apart when they collide.
  4. Drop the markers from the Schematic, keep them in Cells.
  Keep the schematic geometry and a non-colour cue for the extremes.
- **Move SOH into the HV PACK block, two decimals.** Owner wants the
  `SOH 96.5 %` badge (Pack card header) moved into the schematic's
  "HV PACK · 400 V" block next to SOC / kW / V·A, shown as `96.50 %`.
  Caveat to settle first: canspi `HvBattEgyCdn` only moves in 0.5 % steps,
  so a second decimal is always 0 (`96.50`, never `96.44`). Real 0.01 %
  needs the BECM DID 496D read, which takes the diagnostic channel and is
  not run by the app. Decide: show `96.50`, or one decimal, or add a
  manual "read exact SOH" action.
- **Charge time left shown in raw minutes.** The schematic's charger node
  reads `3.6 kWh in · 715 min left` (car, 2026-11-18, AC 1.1 kW). Over 60
  minutes it must show hours: `11 h 55 min left`. The node formats
  `chgEtaMin` with `fx(m.chgEtaMin,0)+' min'` (`BatteryWebPages.java` ~:177);
  the Charging page already has `mins(ms)` (~:287) that does `h` + `min`,
  so reuse that format instead of adding another.
- **Schematic stays black in day mode.** In the daylight theme the page is
  light but the whole pack diagram panel (`.packWrap` plus the 3D blocks,
  terminals and labels) is still black (car screenshot, 2026-11-18). This was
  deliberate earlier: `BatteryWindowStyle.java` ~:123 "keeps its original dark
  substrate so the original SVG labels stay legible in daylight", and the SVG
  hardcodes dark colours (`drawBox` faces `#26323f…`, terminal/dot strokes
  `#0a0f16`, wire `#06101a`, `.sysNode`, `.socKicker` `#8f9cb3`). Owner now
  wants a light day version. Needs a day palette for the panel, blocks, wires,
  node boxes and labels with enough contrast; keep the 1180x470 geometry,
  anchors and flow paths unchanged (AGENTS.md). Check both themes in the
  layout tool and on the car.
- **Make every page native (no WebView).** Owner wants the content below the
  native tab bar (Status with the 3D schematic and Cells view, Charging,
  History, Settings) rebuilt as native Android views instead of the WebView
  HTML/SVG in `BatteryWebPages.java` (~117 KB of HTML/CSS/JS in Java strings)
  and `BatteryWindowStyle.java`. Scope notes:
  - Largest item in the backlog: a full UI rewrite. The schematic becomes a
    custom `View` drawing the same 1180x470 geometry, sensor anchors, energy
    flow paths and moving dots on a `Canvas`, plus the Cells switch, tooltips,
    the history chart, charge sessions and the Settings forms.
  - AGENTS.md / DECISIONS.md: keep the original schematic look and geometry,
    top navigation, system bar / climate dock fit, real/unavailable telemetry.
    DECISIONS.md must record the WebView-to-native change.
  - Tests and tools built on HTML must be replaced: `BatteryWebPagesTest`,
    `BatteryPageExport` + `check-layout.mjs` (browser layout checks), the
    emulator sweep's tap targets.
  - Do it before items 1-4, or do 1-4 inside the native rewrite: fixing them in
    the WebView first would be thrown away.

## Car verification

- SOH history: confirm a second row lands in `soh.csv` after the next park
  (value change or one day later), then the badge shows `· was …`.
- SOH badge in day theme on the car.
