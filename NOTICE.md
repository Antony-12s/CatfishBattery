# Source provenance

Catfish Battery is extracted from the battery feature in
[Antony-12s/GeelyCustomProfiles](https://github.com/Antony-12s/GeelyCustomProfiles),
a fork of [eucalyptussoftware/GeelyCustomProfiles](https://github.com/eucalyptussoftware/GeelyCustomProfiles).
Existing source comments and attributions are retained.

Extraction snapshot: `catfish/battery` at `2c5af5f` plus the finalized 1.2 UI
working-tree changes. The battery source, resources, tests, proto and QA tools
were copied byte-for-byte; only the standalone repository/build documentation
and root Gradle configuration were authored for this split.

No root-level license file was present in the source snapshot. On 2026-11-19
the last upstream-derived files (the VHAL stream observer, its manager and the
VHAL proto) were replaced by a new implementation, VhalStream.java, written for
Catfish Battery. The whole repository is licensed under the Apache License 2.0
(see LICENSE and NOTICE).
