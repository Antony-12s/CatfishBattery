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

## Car verification

- SOH history: confirm a second row lands in `soh.csv` after the next park
  (value change or one day later), then the badge shows `· was …`.
- SOH badge in day theme on the car.
