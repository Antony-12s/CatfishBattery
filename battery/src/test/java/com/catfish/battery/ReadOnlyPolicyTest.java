package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/** The app is read-only: one UDS request on one platform, and no vehicle-property write anywhere in the sources. */
public class ReadOnlyPolicyTest {
    private static final byte[] SOH = {0x22, 0x49, 0x6D};

    @Test public void onlyTheBecmSohReadOnTheEx5() throws IOException {
        assertTrue(UdsPolicy.allowed(VehiclePlatform.EX5, (char) 0x1635, SOH));
        UdsPolicy.require(VehiclePlatform.EX5, (char) 0x1635, SOH);
    }

    @Test public void everythingElseIsRefused() {
        assertFalse(UdsPolicy.allowed(VehiclePlatform.EX5_EMI, (char) 0x1635, SOH));
        assertFalse(UdsPolicy.allowed(VehiclePlatform.EX2, (char) 0x1635, SOH));
        assertFalse(UdsPolicy.allowed(null, (char) 0x1635, SOH));
        assertFalse(UdsPolicy.allowed(VehiclePlatform.EX5, (char) 0x1001, SOH));
        assertFalse(UdsPolicy.allowed(VehiclePlatform.EX5, (char) 0x1635, new byte[]{0x22, 0x49, 0x6E}));
        assertFalse(UdsPolicy.allowed(VehiclePlatform.EX5, (char) 0x1635, new byte[]{0x2E, 0x49, 0x6D, 0x00}));
        assertFalse(UdsPolicy.allowed(VehiclePlatform.EX5, (char) 0x1635, new byte[]{0x22, 0x49, 0x6D, 0x00}));
        assertFalse(UdsPolicy.allowed(VehiclePlatform.EX5, (char) 0x1635, null));
        assertThrows(IOException.class, () -> UdsPolicy.require(VehiclePlatform.EX5, (char) 0x1635, new byte[]{0x14, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
    }

    private static final Pattern VEHICLE_WRITE = Pattern.compile(
            "set(Int|Boolean|Float)?Property\\b|setProperty\\b|SetProperty|writeSignal|setSignal");

    @Test public void noSourceWritesVehicleProperties() throws IOException {
        List<String> hits = sources().stream()
                .filter(p -> VEHICLE_WRITE.matcher(read(p)).find())
                .map(Path::toString).collect(Collectors.toList());
        assertEquals("vehicle-property writes found", java.util.Collections.emptyList(), hits);
    }

    @Test public void udsIsSentOnlyByBecmSohAndOnlyThroughThePolicy() throws IOException {
        List<String> senders = sources().stream()
                .filter(p -> read(p).contains("sendUdsPayload") || read(p).contains("takeDiagWay"))
                .map(p -> p.getFileName().toString()).collect(Collectors.toList());
        assertEquals(java.util.Collections.singletonList("BecmSoh.java"), senders);
        String becm = read(Paths.get("src/main/java/com/catfish/battery/BecmSoh.java"));
        assertTrue(becm.indexOf("UdsPolicy.require(") < becm.indexOf("\"sendUdsPayload\""));
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            return files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
