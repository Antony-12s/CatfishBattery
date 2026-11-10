# Catfish Battery contributor instructions

- This is the standalone `com.catfish.battery` app. Do not import the rest of CF.
- Keep the original 1180x470 3D Schematic, sensor anchors, energy-flow paths and Cells switch. The user explicitly rejected replacing it with a SOC-ring or heatmap dashboard.
- Keep navigation at the top. Fit between the car's system status bar and climate dock; never hide vehicle controls.
- Preserve real/unavailable telemetry. Never put fabricated vehicle readings in production or present fixtures as live evidence.
- Keep VHAL/APVP read-only; do not send vehicle-property writes.
- Preserve existing app history and settings during updates. No uninstall, clear-data or vehicle reboot without explicit approval.
- Keep credentials, signing private keys and captured private device data out of git. Publish APKs as release assets, not committed binaries.
- Run `:battery:testDebugUnitTest :battery:assembleRelease :battery:lintRelease` and the relevant UI checks in `battery/tools/README.md` before claiming changes work.
- Build success, emulator QA and car verification are separate claims. Ask before pushes and car installations.
