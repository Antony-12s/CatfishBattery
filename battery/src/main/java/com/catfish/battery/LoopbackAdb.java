package com.catfish.battery;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Small client for the head unit's own adbd on 127.0.0.1 (wireless ADB with no key check), written from the public
 * ADB wire protocol (AOSP adb/protocol.txt and SYNC.TXT). It does two things: run a shell command and push a file.
 * UpdateInstaller uses it to install a release as the shell user.
 */
final class LoopbackAdb implements AutoCloseable {
    static final int[] PORTS = {5555, 5566}; // 5555: EVCC's port, 5566: Custom Profiles'
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43,
            WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024, SYNC_CHUNK = 64 * 1024;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private LoopbackAdb(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** Is any loopback adbd port listening (TCP connect only)? */
    static boolean available() {
        for (int port : PORTS) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 600);
                return true;
            } catch (IOException ignored) { }
        }
        return false;
    }

    /** Connects and completes the CNXN handshake; fails if adbd wants a key (AUTH). */
    static LoopbackAdb connect() throws IOException { return connect(PORTS); }

    static LoopbackAdb connect(int... ports) throws IOException {
        IOException last = new IOException("Wireless ADB is off");
        for (int port : ports) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
                s.setSoTimeout(10_000);
                LoopbackAdb adb = new LoopbackAdb(s);
                adb.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
                Message reply = adb.read();
                if (reply.command == AUTH) throw new IOException("ADB on this head unit asks for a key");
                if (reply.command != CNXN) throw new IOException("Unexpected ADB reply " + Integer.toHexString(reply.command));
                return adb;
            } catch (IOException e) {
                last = e;
                s.close();
            }
        }
        throw last;
    }

    void readTimeout(int ms) throws IOException { socket.setSoTimeout(ms); }

    /** Runs `command` in `shell:` and returns everything it printed. */
    String shell(String command) throws IOException {
        int local = nextLocalId++, remote = open("shell:" + command, local);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.command == WRTE) {
                output.write(m.data);
                send(OKAY, local, remote, new byte[0]);
            } else if (m.command == CLSE) {
                send(CLSE, local, remote, new byte[0]);
                return output.toString("UTF-8");
            }
        }
    }

    /** Pushes `file` to `path` with Unix permission `mode` through `sync:` (SEND, DATA..., DONE). */
    void push(File file, String path, int mode) throws IOException {
        int local = nextLocalId++, remote = open("sync:", local);
        syncWrite(local, remote, syncPacket("SEND", (path + "," + (0100000 | mode)).getBytes(StandardCharsets.UTF_8)));
        byte[] chunk = new byte[SYNC_CHUNK];
        try (InputStream source = new FileInputStream(file)) {
            for (int n; (n = source.read(chunk)) > 0; ) {
                byte[] data = new byte[n];
                System.arraycopy(chunk, 0, data, 0, n);
                syncWrite(local, remote, syncPacket("DATA", data));
            }
        }
        syncWrite(local, remote, syncHeader("DONE", (int) (System.currentTimeMillis() / 1000)));
        // the answer is "OKAY" + 0, or "FAIL" + length + reason
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.command == WRTE) {
                send(OKAY, local, remote, new byte[0]);
                String id = m.data.length >= 4 ? new String(m.data, 0, 4, StandardCharsets.US_ASCII) : "";
                send(CLSE, local, remote, new byte[0]);
                if (id.equals("OKAY")) return;
                throw new IOException("Push failed: " + (m.data.length > 8 ? new String(m.data, 8, m.data.length - 8, StandardCharsets.UTF_8) : id));
            }
            if (m.command == CLSE) throw new IOException("Push closed without an answer");
        }
    }

    /** A sync request: 4-byte id, little-endian length, then the bytes. */
    static byte[] syncPacket(String id, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(body.length).put(body);
        return b.array();
    }

    static byte[] syncHeader(String id, int value) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.getBytes(StandardCharsets.US_ASCII)).putInt(value).array();
    }

    /** 24-byte message header: command, arg0, arg1, length, byte-sum checksum, command ^ 0xffffffff. */
    static byte[] header(int command, int arg0, int arg1, byte[] data) {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command).array();
    }

    private int open(String service, int local) throws IOException {
        send(OPEN, local, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        while (true) {
            Message m = read();
            if (m.command == OKAY && m.arg1 == local) return m.arg0;
            if (m.command == CLSE && m.arg1 == local) throw new IOException("adbd refused " + service.split(":")[0]);
        }
    }

    /** One WRTE, then wait for the device's OKAY before the next (the protocol allows one write in flight). */
    private void syncWrite(int local, int remote, byte[] data) throws IOException {
        for (int at = 0; at < data.length; at += MAX_DATA) {
            int n = Math.min(MAX_DATA, data.length - at);
            byte[] part = new byte[n];
            System.arraycopy(data, at, part, 0, n);
            send(WRTE, local, remote, part);
            while (true) {
                Message m = read();
                if (m.arg1 != local) continue;
                if (m.command == OKAY) break;
                if (m.command == CLSE) throw new IOException("adbd closed the push");
                if (m.command == WRTE) { // early FAIL from sync
                    send(OKAY, local, remote, new byte[0]);
                    throw new IOException("Push failed: " + new String(m.data, StandardCharsets.UTF_8).replaceAll("[^\\x20-\\x7e]", ""));
                }
            }
        }
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        out.write(header(command, arg0, arg1, data));
        out.write(data);
        out.flush();
    }

    static final class Message {
        final int command, arg0, arg1;
        final byte[] data;
        Message(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }

    private Message read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt(), arg0 = b.getInt(), arg1 = b.getInt(), length = b.getInt();
        b.getInt();
        if (b.getInt() != ~command) throw new IOException("Bad ADB header");
        if (length < 0 || length > MAX_DATA * 4) throw new IOException("ADB message too large");
        byte[] data = new byte[length];
        in.readFully(data);
        return new Message(command, arg0, arg1, data);
    }

    @Override public void close() throws IOException { socket.close(); }
}
