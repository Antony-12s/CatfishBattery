# Vehicle profiles and property discovery

Date: 2026-11-13. Status: draft, awaiting owner approval.

## Why

Every signal ID is hardcoded today. APVP already survives ID shifts because
replies are matched by signal name, but the five VHAL vendor IDs
(`0x2160730E`, `0x21608057`, `0x21607653`, range `291504904`, ...) break
silently on a firmware or model change, and the owner then has to sniff and
re-map by hand. Goal: the app finds the IDs itself, on any EX5 firmware and
on other Geely/ECARX models, and the owner can switch between saved models.

## Goals

1. **Vehicle profile** = one JSON mapping of logical roles to signal IDs.
   The app reads only through the active profile; no reader keeps its own
   hardcoded IDs.
2. **Built-in profile** "Geely EX5" holding today's IDs. Existing installs
   use it automatically: no behavior change after the update.
3. **Model switch** in Settings: pick the active profile from a list
   (built-in, scanned, imported). Switching resubscribes both streams
   without an app restart.
4. **Scan from car** button that builds a new profile:
   - APVP: read `/vendor/etc/transfers/libapvp/*/signal_config.json`
     (world-readable 0644) and resolve every role by exact signal name.
     Exact name match = confirmed.
   - VHAL: call the existing `sendAllPropertyValuesToStream` and collect
     every property the car reports for a few seconds. Standard AOSP IDs
     (for example `EV_BATTERY_LEVEL` `0x11600309`) are confirmed directly.
   - VHAL vendor roles (SOC, displayed SOC, range, battery temperature):
     list plausible candidates (value inside the role's range, e.g. SOC
     0-100, temperature -40..120) with their live values. **The owner
     confirms each one by hand** against the instrument cluster. Nothing
     unconfirmed is used.
   - Result screen: found / confirmed / needs confirmation / missing counts.
     Missing roles render `--`.
5. **Export / import** a profile as a JSON file, so a friend with another
   model can share theirs.

## Non-goals

- Non-ECARX cars (no APVP stream).
- A new 3D pack drawing per model. Other models reuse the EX5 schematic;
  the cell and sensor counts come from the profile and the Cells view
  follows them.
- Migrating or splitting history per profile. History stays one log.

## Anti-goals (failure even if it "works")

- Any fabricated or auto-guessed reading shown as live data.
- Any vehicle-property write. `SetProperty` in `vhal.proto` stays unused.
- Replacing or restyling the original 1180x470 3D schematic.
- Losing existing history or settings on update.

## Constraints

- Read-only VHAL (`localhost:40004`) and APVP (`localhost:40005`) only.
- Imported/scanned profiles are untrusted input: rebuild from known keys
  with clamped integers, like `BatterySettings.clean`.
- APVP replies keep name matching; the profile ID is only the fallback for
  a nameless reply (current rule, see DECISIONS.md).
- Top navigation and system-bar/climate-dock fit unchanged.

## Decided by the owner

- Scope: EX5 on every firmware plus other Geely/ECARX models.
- Model switch button and a scan-from-car button.
- Vendor VHAL candidates are confirmed one by one by the owner (option 1).

## Builder's calls (marked as mine, owner may override)

- One built-in EX5 profile, not separate fw1.2/fw1.3 profiles: name
  matching already covers both firmwares.
- Profiles stored in the app's private files dir (`profiles.json`), active
  profile ID stored in settings. Export writes to Downloads; import uses the
  system file picker.
- Scan results are saved as a new profile; a scan never edits the built-in.
- `signal_config.json` exact schema is not documented here: the builder
  pulls one from the car (read-only, adb, owner approval) before writing
  the parser, and keeps a trimmed copy as a test fixture with no private
  data.

## Verification

- Unit tests: profile clean/validate, `signal_config.json` parsing from a
  fixture, VHAL candidate filtering, reader using profile IDs.
- `:battery:testDebugUnitTest :battery:assembleRelease :battery:lintRelease`
  and the UI checks in `battery/tools/README.md`.
- Car (with approval): scan produces a profile equal to the built-in on the
  EX5; switching profiles keeps live data. Other models: fixture tests only,
  stated as unverified on a real car.
