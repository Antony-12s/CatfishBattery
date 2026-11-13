# Vehicle Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every signal ID comes from an active vehicle profile; the app can scan the car to build a profile, and the owner can switch, confirm, export and import profiles.

**Architecture:** A `VehicleProfile` (JSON, cleaned like `BatterySettings`) maps logical roles to IDs. `SignalConfigParser` reads the car's APVP dictionary; `ProfileScanner` builds a profile from it plus the VHAL values already cached by `VehiclePropertyManager`. `ProfileStore` persists profiles; the readers and `ApvpBatteryStream` take IDs from the active profile and restart on switch.

**Tech Stack:** Java 11, Android SDK 33 (minSdk 30), `org.json`, gRPC okhttp (existing), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-13-vehicle-profiles-design.md`

## Global Constraints

- Package `com.catfish.battery`; no imports from CF.
- Read-only: never call `SetProperty`; no new vehicle writes of any kind.
- Never show unconfirmed or guessed values; a missing role renders `--`.
- Keep the 1180x470 3D schematic, top tabs and system-bar/climate-dock fit unchanged.
- Existing installs keep history and settings; with no profile file the built-in EX5 profile is active and behavior is identical except the dead-ID fix in Task 4.
- All strings in code, UI, commits and docs are English.
- Gate: `./gradlew :battery:testDebugUnitTest :battery:assembleRelease :battery:lintRelease` plus UI checks in `battery/tools/README.md`.

## Facts from the car (EX5 build 264133, read 2026-11-13)

- APVP dictionary: `/vendor/etc/transfers/libapvp/*/signal_config.json`, JSON array, entry `{"id":{"name","value"},"module","readOnly","valueType","enumDefine"?,"structDefine"?}`. valueType 4194304 int, 6291456 float, 7340032 struct. Fixture: `battery/src/test/resources/apvp/signal_config_264133_trimmed.json`.
- VHAL values on the car: `2160730e` TYPE_EV_BATTERY_PERCENTAGE 75.0, `21608057` DispHvBattLvlOfChrg 75.0, `21607653` SENSOR_TYPE_EV_BATTERY_TEMP 33.0, `216072da` SENSOR_TYPE_ENDURANCE_MILEAGE_EV 372.0 (status 0), `11600308` RANGE_REMAINING 372.0 **status 2 (unavailable)**, `11600309` EV_BATTERY_LEVEL 41340.0 Wh.
- `0x126005BA`, `0x126005BD`, `0x126005BF`, `0x12700B9E` do not exist in VHAL. They are fw1.2 APVP IDs of `HvBattSoc`, `CellUMax`, `CellUMin` and the old cell array. The reader's VHAL lookups for SOC fallback and cell max/min are dead today.

## Review Focus

1. A malformed, huge or hostile imported profile JSON: rejected or cleaned, never crashes, never yields non-integer IDs. (Task 1 test `cleanRejectsGarbage`.)
2. `signal_config.json` missing, unreadable, or a different platform dir name: scan reports "APVP dictionary not found" and still offers VHAL results. (Task 2 test `missingFileGivesEmpty`; glob test.)
3. Switching profile while streams are connected: APVP resubscribes with new IDs, old values cleared so no stale reading shows under the new profile. (Task 4 test `switchClearsScalars`.)
4. Vendor VHAL role whose value sits at a placeholder (`status != 0` or timestamp 0): never offered as a candidate. (Task 3 test `skipsUnavailable`.)
5. Profile file written by a newer build with unknown keys: unknown keys dropped, known ones kept. (Task 1 test `unknownKeysDropped`.)

---

### Task 1: VehicleProfile model and built-in EX5

**Files:**
- Create: `battery/src/main/java/com/catfish/battery/VehicleProfile.java`
- Test: `battery/src/test/java/com/catfish/battery/VehicleProfileTest.java`

**Interfaces:**
- Produces:
  - `enum VehicleProfile.Role { SOC_PERCENT, SOC_DISPLAY, BATTERY_TEMP, RANGE_KM, ENERGY_WH }` (VHAL roles).
  - `final class VehicleProfile { String id; String name; String source /* builtin|scan|import */; Map<Role,Integer> vhal; LinkedHashMap<String,Integer> apvp /* signal name -> subscribe id */; int cellCount; int tempCount; }`
  - `static VehicleProfile builtinEx5()` — VHAL: SOC_PERCENT `0x2160730E`, SOC_DISPLAY `0x21608057`, BATTERY_TEMP `0x21607653`, RANGE_KM `0x216072DA`, ENERGY_WH `0x11600309`; APVP: the 39 names/IDs now in `ApvpBatteryStream.IDS/NAMES` plus `HvBattSoc` 308282810, `CellUMax` 308282813, `CellUMin` 308282815 (fw1.2 IDs; replies match by name); cellCount 118, tempCount 12; id `"ex5"`, source `"builtin"`.
  - `static VehicleProfile clean(JSONObject raw)` returns null when unusable; `JSONObject toJson()`.
  - `Integer vhal(Role role)` returns null when absent.

- [ ] **Step 1: Write failing tests**: `builtinMatchesCurrentIds` (every entry of today's `ApvpBatteryStream.IDS/NAMES` present with same id; RANGE_KM == 0x216072DA), `roundTrip` (`clean(p.toJson())` equals p), `cleanRejectsGarbage` (non-object, missing id, name > 40 chars truncated, apvp name not `[A-Za-z0-9_]{1,64}` dropped, id not int dropped, cellCount clamped 1..512, tempCount 0..128, more than 256 apvp entries truncated), `unknownKeysDropped`.
- [ ] **Step 2: Run** `./gradlew :battery:testDebugUnitTest --tests '*VehicleProfileTest'` — expect FAIL (class missing).
- [ ] **Step 3: Implement** `VehicleProfile.java` per Interfaces; clean like `BatterySettings.clean` (rebuild from known keys only).
- [ ] **Step 4: Run** same command — PASS.
- [ ] **Step 5: Commit** `git commit -m "Add VehicleProfile with built-in EX5 profile"`

### Task 2: SignalConfigParser

**Files:**
- Create: `battery/src/main/java/com/catfish/battery/SignalConfigParser.java`
- Test: `battery/src/test/java/com/catfish/battery/SignalConfigParserTest.java`

**Interfaces:**
- Produces:
  - `static final class Signal { String name; int id; String module; int valueType; boolean readOnly; }`
  - `static Map<String,Signal> parse(Reader json)` — name -> signal; skips entries without `id.name`/`id.value`.
  - `static File find(File vendorRoot)` — first `<root>/etc/transfers/libapvp/*/signal_config.json`, null if none. Production passes `new File("/vendor")`.

- [ ] **Step 1: Write failing tests** with the fixture: `parsesFixture` (44 entries; `HvBattUDc` id 308282804 module `BECM` valueType 6291456; `CellUMax` 308282814), `skipsBrokenEntries`, `missingFileGivesEmpty` (`find` on a temp dir with no file returns null), `findsAnyPlatformDir` (temp `etc/transfers/libapvp/x99/signal_config.json` found).
- [ ] **Step 2: Run** `--tests '*SignalConfigParserTest'` — FAIL.
- [ ] **Step 3: Implement**; stream-read with `org.json.JSONArray(new JSONTokener(reader))` (2.4 MB is fine on the head unit; parse off the UI thread).
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `git commit -m "Parse the car's APVP signal dictionary"`

### Task 3: ProfileScanner

**Files:**
- Create: `battery/src/main/java/com/catfish/battery/ProfileScanner.java`
- Test: `battery/src/test/java/com/catfish/battery/ProfileScannerTest.java`

**Interfaces:**
- Consumes: `VehicleProfile`, `SignalConfigParser.Signal`.
- Produces:
  - `static final class Candidate { int propId; double value; }`
  - `static final class Result { VehicleProfile profile; List<String> apvpMissing; Map<Role,List<Candidate>> needsConfirm; boolean dictionaryFound; }`
  - `static Result scan(String name, Map<String,Signal> dictionary, Map<Integer,Double> vhalValues, Set<Integer> vhalUnavailable)`
- Rules:
  - APVP: for each name in `builtinEx5().apvp`, take the id from `dictionary`; absent -> `apvpMissing`.
  - VHAL AOSP standard, confirmed directly if present and available: ENERGY_WH `0x11600309`. RANGE_KM: `0x11600308` only if available, else candidates.
  - VHAL vendor roles (SOC_PERCENT, SOC_DISPLAY, BATTERY_TEMP, RANGE_KM): candidates = props with value in range (SOC 0..100, temp -40..120, range 0..1500), not in `vhalUnavailable`, vendor id (`(id & 0xF0000000) == 0x20000000`), max 12 per role, the built-in id first when present. Never auto-assign: `profile.vhal` holds only AOSP-confirmed roles.
  - cellCount/tempCount from built-in (shortcut: EX5 sizes, upgrade when another model's struct sizes are known).

- [ ] **Step 1: Write failing tests**: `apvpResolvedByName` (fixture -> `HvBattUDc` 308282804), `missingNameReported`, `vendorRolesNeverAutoAssigned`, `candidatesFilteredByRange` (SOC candidates exclude 372.0 and 41340.0), `skipsUnavailable` (`0x11600308` status 2 -> RANGE_KM not confirmed, not a candidate), `noDictionary` (empty map -> `dictionaryFound` false, VHAL still scanned).
- [ ] **Step 2: Run** `--tests '*ProfileScannerTest'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `git commit -m "Scan the car into a vehicle profile"`

### Task 4: ProfileStore and readers use the active profile

**Files:**
- Create: `battery/src/main/java/com/catfish/battery/ProfileStore.java`
- Modify: `VehicleBatteryReader.java:25-36,201-221,264-266`, `ApvpBatteryStream.java:35-41,59-70,100-110`, `VehiclePropertyObserver.java:110-120` (record unavailable ids), `VehiclePropertyManager.java` (expose `unavailable()` set)
- Test: `battery/src/test/java/com/catfish/battery/ProfileStoreTest.java`, extend `BatteryStreamTest.java`

**Interfaces:**
- Consumes: `VehicleProfile`.
- Produces:
  - `ProfileStore(File dir)`; `List<VehicleProfile> all()` (built-in always first, not deletable); `VehicleProfile active()`; `void setActive(String id)`; `void save(VehicleProfile p)`; `boolean delete(String id)`; file `profiles.json` in `context.getFilesDir()`; listener `static void onChange(Runnable r)`.
  - `ApvpBatteryStream(Listener, VehicleProfile)`; subscription built from `profile.apvp`; reply matched by name and reported under the **built-in** id for that name (internal key), so `VehicleBatteryReader` keys stay stable.
  - Reader: VHAL lookups by `profile.vhal(role)`; delete constants `SOC_APVP`, `CELL_MAX_V`, `CELL_MIN_V`, `CELL_UI_ARRAY` VHAL use; cell max/min/SOC fallback now come from APVP `CellUMax`/`CellUMin`/`HvBattSoc`. Range from RANGE_KM role (no status-2 AOSP id).
  - On `ProfileStore` change: close and recreate `ApvpBatteryStream`; clear `observedScalars`, cells and temps.

- [ ] **Step 1: Write failing tests**: `ProfileStoreTest.noFileMeansBuiltin`, `saveAndActivate`, `builtinNotDeletable`, `corruptFileFallsBack` (garbage file -> built-in active, file kept as `profiles.json.bad`); `BatteryStreamTest.subscribesFromProfile` (request bytes contain profile ids), `replyNormalizedByName` (reply name `CellUMax` id 308282814 -> listener id 308282813), `switchClearsScalars`.
- [ ] **Step 2: Run** `--tests '*ProfileStoreTest' --tests '*BatteryStreamTest'` — FAIL.
- [ ] **Step 3: Implement**; keep existing comments in edited files.
- [ ] **Step 4: Run full** `./gradlew :battery:testDebugUnitTest` — all PASS (56 existing + new).
- [ ] **Step 5: Commit** `git commit -m "Read every signal through the active vehicle profile"`

### Task 5: Settings UI — switch, scan, confirm, export, import

**Files:**
- Modify: `BatteryActivity.java:91-100` (JS interface), `BatteryWebPages.java` (Settings page section), `BatteryDataHub.java` (scan entry, off UI thread)
- Test: extend `BatteryWebPagesTest.java`

**Interfaces:**
- Consumes: `ProfileStore`, `ProfileScanner`, `SignalConfigParser`.
- JS bridge methods (all return JSON strings): `profiles()` -> `{active, list:[{id,name,source}]}`; `setProfile(id)`; `scanProfile(name)` -> Result as JSON with live candidate values; `confirmRole(profileId, role, propId)`; `deleteProfile(id)`; `exportProfile(id)` -> path written under `Download/catfish-profile-<id>.json`; import via `ACTION_OPEN_DOCUMENT` (`application/json`), result passed through `VehicleProfile.clean`.
- UI (Settings, new "Vehicle" card above Chargers, same card style): profile dropdown; Scan button; scan result counts (resolved / needs confirmation / missing); per vendor role a list of candidates `0x2160730E — 75.0` with a Use button; Export, Import, Delete. Missing role text: `Not found — shows --`.

- [ ] **Step 1: Write failing tests** in `BatteryWebPagesTest`: settings page contains `id="vehicleProfile"`, `scanProfile(`, `confirmRole(`; bridge JSON for built-in lists `ex5` first.
- [ ] **Step 2: Run** `--tests '*BatteryWebPagesTest'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** full gate: `./gradlew :battery:testDebugUnitTest :battery:assembleRelease :battery:lintRelease` — 0 failures, 0 lint errors; then UI checks in `battery/tools/README.md`, screenshot of Settings with the Vehicle card.
- [ ] **Step 5: Commit** `git commit -m "Settings: vehicle profile switch, scan, confirm, export and import"`

### Task 6: Docs and version

**Files:** `DECISIONS.md`, `README.md` (Settings + How it works + Known limits), `battery/build.gradle` (versionCode 6, versionName 1.5)

- [ ] **Step 1:** DECISIONS: profiles, built-in internal keys, dead fw1.2 VHAL ids removed, vendor VHAL roles only by owner confirmation, other models fixture-tested only.
- [ ] **Step 2:** Run full gate again — PASS.
- [ ] **Step 3: Commit** `git commit -m "Docs and 1.5 version for vehicle profiles"`

Car install, push and release need the owner's approval (AGENTS.md).

## Open question for the owner (does not block Tasks 1-3)

`VehiclePropertyObserver` calls `SendAllPropertyValuesToStream` on every connect (inherited from CF). The HUD notes say not to use it because "it writes car state"; the basis of that note is not recorded. The scanner needs VHAL values; without SendAll the stream only pushes changes. Decide before Task 4: keep SendAll (status quo), or drop it and fill the scan from values that arrive over time.
