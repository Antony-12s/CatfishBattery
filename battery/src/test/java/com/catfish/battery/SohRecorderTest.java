package com.catfish.battery;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

/** SOH (HvBattEgyCdn) recorded once per park, kept across restarts, fetched subscribe-only from the local broker. */
public class SohRecorderTest {
    private static final long DAY = 24L * 3600L * 1000L;
    private static final long T0 = 1794600000000L;

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("soh").toFile();
    }

    @Test
    public void recordsFirstValueThenOnlyChangesOrOncePerDay() {
        assertTrue(SohRecorder.shouldRecord(null, T0, 96.5));
        SohRecorder.Entry last = new SohRecorder.Entry(T0, 96.5);
        assertFalse(SohRecorder.shouldRecord(last, T0 + DAY - 1, 96.5));
        assertTrue(SohRecorder.shouldRecord(last, T0 + DAY, 96.5));
        assertTrue(SohRecorder.shouldRecord(last, T0 + 60000, 96.0));
    }

    @Test
    public void recordsOncePerParkAndOnlyWhenParked() throws Exception {
        AtomicReference<Integer> gear = new AtomicReference<>(4);
        int[] fetches = {0};
        SohRecorder r = new SohRecorder(tempDir(), gear::get, () -> { fetches[0]++; return 96.5; });
        r.tick(T0);
        r.tick(T0 + 60000);              // same park: no second fetch
        assertEquals(1, fetches[0]);
        gear.set(1);                     // drove off
        r.tick(T0 + 120000);
        assertEquals(1, fetches[0]);
        gear.set(4);                     // parked again, a day later
        r.tick(T0 + DAY + 1);
        assertEquals(2, fetches[0]);
        assertEquals(2, r.entries().size());
    }

    @Test
    public void unknownGearOrMissingValueRecordsNothing() throws Exception {
        AtomicReference<Integer> gear = new AtomicReference<>(null);
        AtomicReference<Double> value = new AtomicReference<>(Double.NaN);
        SohRecorder r = new SohRecorder(tempDir(), gear::get, value::get);
        r.tick(T0);
        gear.set(4);
        r.tick(T0 + 60000);              // parked, but the frame was missing
        assertTrue(r.entries().isEmpty());
        value.set(96.5);
        r.tick(T0 + 120000);             // same park, retried because nothing was recorded yet
        assertEquals(1, r.entries().size());
    }

    @Test
    public void rejectsOutOfRangeValues() throws Exception {
        SohRecorder r = new SohRecorder(tempDir(), () -> 4, () -> 0.0);
        r.tick(T0);
        assertTrue(r.entries().isEmpty());
    }

    @Test
    public void historySurvivesRestartAndJsonShowsLatestAndPrevious() throws Exception {
        File dir = tempDir();
        AtomicReference<Integer> gear = new AtomicReference<>(4);
        AtomicReference<Double> value = new AtomicReference<>(97.0);
        SohRecorder first = new SohRecorder(dir, gear::get, value::get);
        first.tick(T0);
        SohRecorder second = new SohRecorder(dir, gear::get, value::get);  // app restarted
        value.set(96.5);
        second.tick(T0 + 5 * DAY);
        List<SohRecorder.Entry> entries = second.entries();
        assertEquals(2, entries.size());
        JSONObject json = new JSONObject(second.json());
        assertEquals(96.5, json.getDouble("pct"), 0);
        assertEquals(T0 + 5 * DAY, json.getLong("atMs"));
        assertEquals(97.0, json.getDouble("prevPct"), 0);
        assertEquals(T0, json.getLong("prevAtMs"));
        assertEquals(2, json.getInt("count"));
    }

    @Test
    public void emptyHistoryJsonHasNoValue() throws Exception {
        JSONObject json = new JSONObject(new SohRecorder(tempDir(), () -> 4, () -> Double.NaN).json());
        assertTrue(json.isNull("pct"));
        assertEquals(0, json.getInt("count"));
        assertEquals("starting", json.getString("state"));
    }

    @Test
    public void corruptLinesAreSkipped() throws Exception {
        File dir = tempDir();
        Files.write(new File(dir, "soh.csv").toPath(), ("ts,soh_pct\n" + T0 + ",96.5\ngarbage\n").getBytes("UTF-8"));
        assertEquals(1, new SohRecorder(dir, () -> 4, () -> Double.NaN).entries().size());
    }

    /** One-shot broker: answers CONNACK/SUBACK, sends one canspi PUBLISH, records every client byte. */
    private static Thread fakeBroker(ServerSocket server, byte[] publishPayload, ByteArrayOutputStream clientBytes) {
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                s.setSoTimeout(3000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                byte[] buf = new byte[256];
                int expected = SohRecorder.CONNECT.length + SohRecorder.SUBSCRIBE.length;
                while (clientBytes.size() < expected) {
                    int n = in.read(buf);
                    if (n < 0) return;
                    clientBytes.write(buf, 0, n);
                }
                out.write(new byte[]{0x20, 0x02, 0, 0, (byte) 0x90, 0x03, 0, 1, 0});
                byte[] topic = "canspi".getBytes("UTF-8");
                int remaining = 2 + topic.length + publishPayload.length;
                out.write(0x30);
                out.write(remaining | 0x80);
                out.write(remaining >> 7);
                out.write(0);
                out.write(topic.length);
                out.write(topic);
                out.write(publishPayload);
                out.flush();
                for (int n; (n = in.read(buf)) > 0; ) clientBytes.write(buf, 0, n);
            } catch (Exception ignored) { }
        });
        t.start();
        return t;
    }

    @Test
    public void fetchesEgyCdnSubscribeOnly() throws Exception {
        // canspi snapshot with one PropulsionCAN 0x293 frame; HvBattEgyCdn = 96.5 % (byte 7 = 0xC1, per cantools on gee.json)
        byte[] record = {0x55, 0x22, (byte) 0x93, (byte) 0x88, 0, 0, 0, 0, 0, 0, 0, (byte) 0xC1};
        byte[] payload = new byte[10 + record.length];
        payload[9] = (byte) record.length;
        System.arraycopy(record, 0, payload, 10, record.length);
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            ByteArrayOutputStream clientBytes = new ByteArrayOutputStream();
            Thread broker = fakeBroker(server, payload, clientBytes);
            assertEquals(96.5, SohRecorder.fetchEgyCdn(server.getLocalPort(), 3000), 0);
            broker.join(4000);
            byte[] sent = clientBytes.toByteArray();
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            expected.write(SohRecorder.CONNECT);
            expected.write(SohRecorder.SUBSCRIBE);
            expected.write(SohRecorder.DISCONNECT);
            assertArrayEquals("client may only CONNECT, SUBSCRIBE and DISCONNECT", expected.toByteArray(), sent);
        }
    }

    @Test
    public void brokerHostIsIpv4LoopbackBecauseNanomqBindsOnly127001() {
        assertArrayEquals(new byte[]{127, 0, 0, 1}, SohRecorder.BROKER_HOST.getAddress());
    }

    @Test
    public void fetchWithoutBrokerIsUnavailable() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        assertTrue(Double.isNaN(SohRecorder.fetchEgyCdn(port, 500)));
    }

    // ---- BECM DID 496D, read once per day when the app opens in Park ----

    private static SohRecorder becmRecorder(File dir, AtomicReference<Integer> gear) {
        return new SohRecorder(dir, gear::get, () -> 96.5);
    }

    @Test
    public void becmReadsOncePerDayOnlyInParkAndKeepsTheSource() throws Exception {
        File dir = tempDir();
        AtomicReference<Integer> gear = new AtomicReference<>(1);
        int[] reads = {0};
        SohRecorder r = becmRecorder(dir, gear);
        r.becmTick(T0, () -> { reads[0]++; return 96.44; });
        assertEquals(0, reads[0]);                       // not in P: no request at all
        gear.set(4);
        r.becmTick(T0 + 1000, () -> { reads[0]++; return 96.44; });
        r.becmTick(T0 + 2000, () -> { reads[0]++; return 96.44; });
        assertEquals(1, reads[0]);                       // once per day
        JSONObject json = new JSONObject(r.json());
        assertEquals(96.44, json.getDouble("pct"), 0);
        assertEquals("becm", json.getString("source"));
        assertEquals("ok", json.getString("state"));
        assertEquals("becm", new SohRecorder(dir, gear::get, () -> 96.5).entries().get(0).source); // kept on disk
    }

    @Test
    public void failedBecmReadIsRetriedOnTheNextOpenAndRecordsNothing() throws Exception {
        AtomicReference<Integer> gear = new AtomicReference<>(4);
        SohRecorder r = becmRecorder(tempDir(), gear);
        r.becmTick(T0, () -> { throw new java.io.IOException("Middleware did not become ready"); });
        assertEquals(0, r.entries().size());
        assertEquals("becm failed", new JSONObject(r.json()).getString("state"));
        r.becmTick(T0 + 1000, () -> 96.44);
        assertEquals(1, r.entries().size());
    }

    @Test
    public void stateIsReadingWhileTheRequestRuns() throws Exception {
        AtomicReference<Integer> gear = new AtomicReference<>(4);
        SohRecorder r = becmRecorder(tempDir(), gear);
        String[] during = {null};
        r.becmTick(T0, () -> { during[0] = new JSONObject(r.json()).getString("state"); return 96.44; });
        assertEquals("reading", during[0]);
    }

    @Test
    public void canspiDoesNotOverwriteABecmReadingFromTheLastDay() throws Exception {
        AtomicReference<Integer> gear = new AtomicReference<>(4);
        int[] fetches = {0};
        SohRecorder r = new SohRecorder(tempDir(), gear::get, () -> { fetches[0]++; return 96.5; });
        r.becmTick(T0, () -> 96.44);
        r.tick(T0 + 60000);
        assertEquals(0, fetches[0]);
        assertEquals(96.44, new JSONObject(r.json()).getDouble("pct"), 0);
        gear.set(1); r.tick(T0 + DAY); gear.set(4);
        r.tick(T0 + DAY + 1);                           // no BECM read for a day: canspi fills in
        assertEquals(1, fetches[0]);
        assertEquals("canspi", new JSONObject(r.json()).getString("source"));
    }

    @Test
    public void oldTwoColumnHistoryLoadsAsCanspi() throws Exception {
        File dir = tempDir();
        Files.write(new File(dir, "soh.csv").toPath(), ("ts,soh_pct\n" + T0 + ",96.50\n").getBytes());
        SohRecorder r = new SohRecorder(dir, () -> 4, () -> 96.5);
        assertEquals("canspi", r.entries().get(0).source);
        assertEquals("canspi", new JSONObject(r.json()).getString("source"));
    }

    // ---- odometer next to every SOH row, for SOH per distance ----

    @Test
    public void everyRowKeepsTheOdometerAndOldFilesLoadWithout() throws Exception {
        File dir = tempDir();
        AtomicReference<Integer> gear = new AtomicReference<>(4);
        SohRecorder r = new SohRecorder(dir, gear::get, () -> 96.5);
        r.setOdometer(() -> 29161.0);
        r.setCellDiff(() -> 3.0);
        r.becmTick(T0, () -> 96.44);
        assertEquals(29161.0, r.entries().get(0).km, 0);
        assertEquals(29161.0, new JSONObject(r.json()).getDouble("km"), 0);
        List<String> lines = Files.readAllLines(new File(dir, "soh.csv").toPath());
        assertEquals("ts,soh_pct,source,odometer_km,cell_diff_mv", lines.get(0));
        assertEquals(T0 + ",96.44,becm,29161,3", lines.get(1));
        assertEquals(3.0, r.entries().get(0).cellDiffMv, 0);
        assertEquals(29161.0, new SohRecorder(dir, gear::get, () -> 96.5).entries().get(0).km, 0); // reloads

        File old = tempDir();
        Files.write(new File(old, "soh.csv").toPath(), ("ts,soh_pct,source\n" + T0 + ",96.44,becm\n").getBytes());
        SohRecorder o = new SohRecorder(old, gear::get, () -> 96.5);
        assertTrue(Double.isNaN(o.entries().get(0).km));
        assertTrue(new JSONObject(o.json()).isNull("km"));
    }

    @Test
    public void unknownOdometerIsLeftBlank() throws Exception {
        File dir = tempDir();
        SohRecorder r = new SohRecorder(dir, () -> 4, () -> 96.5);
        r.setOdometer(() -> Double.NaN);
        r.becmTick(T0, () -> 96.44);
        assertEquals(T0 + ",96.44,becm,,", Files.readAllLines(new File(dir, "soh.csv").toPath()).get(1));
    }
}
