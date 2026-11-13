package com.catfish.battery;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/** Turns canspi-loop.sh captures (raw MQTT bytes, one file per capture) into a CSV of battery-health signals. */
public final class CanSpiLogExport {
    private static final CanSpiDecoder.Signal[] COLUMNS = {
        CanSpiDecoder.HV_BATT_SOC, CanSpiDecoder.HV_BATT_SOCE, CanSpiDecoder.HV_BATT_EGY_CDN, CanSpiDecoder.HV_BATT_PWR_CDN,
        CanSpiDecoder.HV_BATT_CAP, CanSpiDecoder.HV_BATT_EGY_AVL_DCHA, CanSpiDecoder.HV_BATT_U_DC, CanSpiDecoder.HV_BATT_I_DC,
        CanSpiDecoder.CELL_U_MAX, CanSpiDecoder.CELL_U_MIN, CanSpiDecoder.HV_BATT_T_AVG, CanSpiDecoder.HV_BATT_T_MAX,
        CanSpiDecoder.HV_BATT_T_MIN,
    };

    public static void main(String[] args) throws IOException {
        List<Path> captures;
        try (Stream<Path> files = Files.list(Path.of(args[0]))) {
            captures = files.filter(p -> p.toString().endsWith(".bin")).sorted().toList();
        }
        int rows = 0;
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
            StringBuilder header = new StringBuilder("snapshot_utc");
            for (CanSpiDecoder.Signal s : COLUMNS) header.append(',').append(s.name).append('_').append(s.unit);
            csv.println(header);
            for (Path capture : captures) {
                CanSpiDecoder.Snapshot snapshot = firstSnapshot(Files.readAllBytes(capture));
                if (snapshot == null) {
                    System.err.println("skip " + capture.getFileName() + ": no complete canspi snapshot");
                    continue;
                }
                StringBuilder row = new StringBuilder(Instant.ofEpochMilli(snapshot.timestampMs).toString());
                for (CanSpiDecoder.Signal s : COLUMNS) {
                    double v = s.value(snapshot);
                    row.append(',').append(Double.isNaN(v) ? "" : String.format("%.4f", v));
                }
                csv.println(row);
                rows++;
            }
        }
        System.out.println(rows + " of " + captures.size() + " captures -> " + args[1]);
    }

    private static CanSpiDecoder.Snapshot firstSnapshot(byte[] stream) {
        for (MqttFrames.Publish p : MqttFrames.publishes(stream)) {
            if (!p.topic.equals("canspi")) continue;
            try {
                return CanSpiDecoder.parse(p.payload);
            } catch (IllegalArgumentException malformed) {
                System.err.println("malformed canspi payload: " + malformed.getMessage());
            }
        }
        return null;
    }
}
