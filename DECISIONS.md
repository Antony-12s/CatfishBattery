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
