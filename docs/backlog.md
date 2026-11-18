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

## Car verification

- SOH history: confirm a second row lands in `soh.csv` after the next park
  (value change or one day later), then the badge shows `· was …`.
- SOH badge in day theme on the car.
