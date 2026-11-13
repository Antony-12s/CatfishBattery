# Battery-health signals and the HU bigdata pipeline (EX5, build 264133)

Read-only investigation on 2026-11-13. Nothing was written to the car.
Captured data (parquet, MQTT payloads, VIN, keys) stays outside git.

## Question

Can the app read the BattPP* battery-health set (BattPPCapFade,
BattPPCapExhaus, BattPPPowerFade20/50/80, BattPPRemainPower20/50/80,
BattPPRIncreasce, BattPPEgyEfficBOL/50, BattPPCycleCount, BattPPRemainCap,
BattPPRemainEgy, OBMHVbattSOCE, BattPPDeepChgEven/DeepDisEven/NegativeEven,
BattPPTemp, high/low temperature time) said to come from
`spi_60000ms_stream`, CAN frames 0x4CA, 0x4CD, 0x4D1, 0x4D3, 0x4D6, 0x4F5?

## Answer: not on this car's firmware

Four independent checks agree:

1. No file on the HU contains any BattPP* name or `spi_60000ms_stream`
   (full filesystem grep, all system APK/JAR dex, logcat and its zips,
   raw partitions `r52_a`, `m4_a`, `cp_*`, `bigdata`, `diag_app`).
2. eKuiper (`kuiperd`, REST 127.0.0.1:9081) defines 9 streams; none is
   `spi_60000ms_stream`. `mqtt_source.yaml` only has `spi_1sec`,
   `spi_1000ms`, `spi_200ms`.
3. The decoder DBC `/bigdata/data/ekuiper/basedata_E4/data/uploads/dbc/gee.json`
   (`SDB2243603__24R3U3-E245-J1...363.json`, v1.0.6, 166 messages,
   1248 signals) has none of the six frame IDs and no BattPP* signal.
4. The raw `canspi` MQTT snapshot (453 frames per snapshot, 272 snapshots
   over 70 s) never carries those six IDs. The 0x4xx range present stops
   at 0x4BF plus 0x4F0. So the CP does not forward them to the HU at all.

The set probably exists in Geely's full signal database and is enabled by a
cloud-pushed rule/DBC update on other cars or later firmware. Not verified.

## The pipeline that does exist

- Processes: `nanomq` (MQTT broker, 127.0.0.1:1883, anonymous), `kuiperd`
  (eKuiper v2.3.0-yupik.43), `sdv-flow`, `geely_api`. Config under
  `/bigdata/etc`, data under `/bigdata/data`.
- Topics: `canspi` (~4 Hz binary snapshot of all forwarded CAN frames),
  `can_signal` (decoded CSV), `battcell` (cell voltages), `batttsnsr`
  (cell temperature sensors), `ek/...` and `ecp/...` (encrypted uploads).
- Parquet history in `/bigdata/data/nanomq/parquet/{canspi,signal,battcell,batttsnsr}`
  uses Parquet modular encryption (AES_GCM_V1, key id `kf`); the configured
  key is itself wrapped. Not decrypted. Not needed: the live MQTT feed is
  plaintext.

### `canspi` payload format

```
u64 BE  timestamp, ms since epoch
u16 BE  body length
repeat:
  u8    0x55 marker
  u16 BE header: bus index = header >> 12, CAN id = header & 0xFFF
  u8    flags/len: bit7 updated(?), bit6 CAN FD, low 6 bits = length;
        length 0 with FD = 64 bytes, length 0 without FD = empty record
  bytes data
```

Bus index map: 0 ZCU_CANFD1, 1 ZCU_CANFD2, 2 PropulsionCAN,
3 ConnectivityCANFD, 4 PrivateInfoCAN, 8 DHU_LIN3. Validated: 146 of 148
DBC frames found, decoded values physically plausible.

### Health-adjacent signals that ARE available (sample, parked/idle, 70 s)

| Signal | Value | Note |
|---|---|---|
| PropulsionCAN.0x499.HvBattCap | 162.5 Ah | pack capacity; nominal or aged is unknown |
| PropulsionCAN.0x15C.HvBattSOCE | 92 % | not SOC (SOC was 74.7 %); meaning unconfirmed |
| PropulsionCAN.0x293.HvBattEgyCdn | 96.5 % | "energy condition"; meaning unconfirmed |
| PropulsionCAN.0x301.HvBattPwrCdn | 4 % | "power condition"; meaning unconfirmed |
| PropulsionCAN.0x286.HvBattEgyAvlDcha1 | 40.8 kWh | at SOC 74.7 % |
| PropulsionCAN.0x178.HvBattSoc | 74.7 % | displayed 74 % (0x131) |
| PropulsionCAN.0x145.CellUMax / CellUMin | 3.314 / 3.311 V | cell spread |
| PropulsionCAN.0x315.HvBattTAvg/TMax/TMin | 32.4 / 33 / 32 degC | |
| ZCU_CANFD2.0x491.BattRRaw | 5.0 mOhm | 12 V battery, not HV |

## Open risks before any app use

- Whether an untrusted app UID can connect to 127.0.0.1:1883 is untested.
- The broker accepts anonymous publish. The app must subscribe only and
  never publish (rules, ctrl and upload topics live on the same broker).
- Topic names carry the VIN. Never log or export them.
