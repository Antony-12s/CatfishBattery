package com.catfish.battery;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** ADB wire protocol against a tiny fake adbd on a free local port. */
public class LoopbackAdbTest {
    @Test public void headerHasChecksumAndMagic() {
        byte[] h = LoopbackAdb.header(LoopbackAdb.OPEN, 1, 0, new byte[]{1, 2, (byte) 250});
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(LoopbackAdb.OPEN, b.getInt());
        assertEquals(1, b.getInt());
        assertEquals(0, b.getInt());
        assertEquals(3, b.getInt());
        assertEquals(253, b.getInt());
        assertEquals(~LoopbackAdb.OPEN, b.getInt());
    }

    @Test public void syncPacketIsIdLengthBody() {
        assertArrayEquals(new byte[]{'D', 'A', 'T', 'A', 2, 0, 0, 0, 7, 8}, LoopbackAdb.syncPacket("DATA", new byte[]{7, 8}));
    }

    @Test public void shellAndPushTalkToAdbd() throws Exception {
        AtomicReference<String> shellAsked = new AtomicReference<>();
        ByteArrayOutputStream pushed = new ByteArrayOutputStream();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread adbd = new Thread(() -> fakeAdbd(server, shellAsked, pushed));
            adbd.start();
            File apk = File.createTempFile("update", ".apk");
            Files.write(apk.toPath(), "hello apk".getBytes(StandardCharsets.UTF_8));
            try (LoopbackAdb adb = LoopbackAdb.connect(server.getLocalPort())) {
                assertEquals("started\n", adb.shell("echo started"));
                adb.push(apk, "/data/local/tmp/x.apk", 0644);
            }
            adbd.join(5000);
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
        assertEquals("shell:echo started", shellAsked.get());
        String sync = new String(pushed.toByteArray(), StandardCharsets.ISO_8859_1);
        assertTrue(sync.startsWith("SEND"));
        assertTrue(sync.contains("/data/local/tmp/x.apk,33188")); // 0100644
        assertTrue(sync.contains("DATA") && sync.contains("hello apk") && sync.contains("DONE"));
    }

    /** CNXN; shell: replies "started\n" then closes; sync: collects writes until DONE, then answers OKAY. */
    private static void fakeAdbd(ServerSocket server, AtomicReference<String> shellAsked, ByteArrayOutputStream pushed) {
        try (Socket s = server.accept()) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            int[] m = read(in, null);
            send(out, LoopbackAdb.CNXN, 0x01000001, 4096, "device::\0".getBytes(StandardCharsets.US_ASCII));
            byte[][] data = new byte[1][];
            while (true) {
                m = read(in, data);
                if (m == null) return;
                String payload = new String(data[0], StandardCharsets.UTF_8);
                if (m[0] == LoopbackAdb.OPEN && payload.startsWith("shell:")) {
                    shellAsked.set(payload.substring(0, payload.length() - 1));
                    send(out, LoopbackAdb.OKAY, 100, m[1], new byte[0]);
                    send(out, LoopbackAdb.WRTE, 100, m[1], "started\n".getBytes(StandardCharsets.UTF_8));
                    send(out, LoopbackAdb.CLSE, 100, m[1], new byte[0]);
                } else if (m[0] == LoopbackAdb.OPEN && payload.startsWith("sync:")) {
                    send(out, LoopbackAdb.OKAY, 200, m[1], new byte[0]);
                } else if (m[0] == LoopbackAdb.WRTE) {
                    pushed.write(data[0]);
                    send(out, LoopbackAdb.OKAY, 200, m[1], new byte[0]);
                    if (new String(data[0], 0, 4, StandardCharsets.US_ASCII).equals("DONE")) {
                        send(out, LoopbackAdb.WRTE, 200, m[1], LoopbackAdb.syncHeader("OKAY", 0));
                    }
                }
            }
        } catch (IOException ignored) { }
    }

    private static int[] read(DataInputStream in, byte[][] data) throws IOException {
        byte[] h = new byte[24];
        try { in.readFully(h); } catch (IOException e) { return null; }
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt(), arg0 = b.getInt(), arg1 = b.getInt(), length = b.getInt();
        byte[] body = new byte[length];
        in.readFully(body);
        if (data != null) data[0] = body;
        return new int[]{command, arg0, arg1};
    }

    private static void send(OutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        out.write(LoopbackAdb.header(command, arg0, arg1, data));
        out.write(data);
        out.flush();
    }
}
