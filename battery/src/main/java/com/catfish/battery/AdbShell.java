package com.catfish.battery;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minimal plain-ADB client for the head unit's own unauthenticated adbd on 127.0.0.1 (wireless ADB), copied from
 * HUD Cluster's AdbShell. Used only by UpdateInstaller to stage and install a release APK as the shell user.
 */
final class AdbShell {
    static final String HOST = "127.0.0.1";
    /**
     * Port the unit's adbd listens on: 5555 is what EVCC sets (and what we turn on, so
     * EVCC keeps working), 5566 is CF's. The first one that answers is used.
     */
    static final int PORT = 5555;
    private static final int[] PORTS = { 5555, 5566 };
    private static volatile int live = PORT;

    private AdbShell() { }

    /** One TCP connect, no ADB protocol: is the loopback adbd listening? */
    static boolean available() {
        for (int port : PORTS) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(HOST, port), 600);
                live = port;
                return true;
            } catch (IOException ignored) { }
        }
        return false;
    }

    /** Runs one shell command and returns its output. */
    static String run(String command) throws IOException {
        try (Client client = Client.connect()) {
            return client.shell(command);
        }
    }

    static final class Client implements AutoCloseable {
        private static final int CNXN = 0x4e584e43;
        private static final int AUTH = 0x48545541;
        private static final int OPEN = 0x4e45504f;
        private static final int OKAY = 0x59414b4f;
        private static final int CLSE = 0x45534c43;
        private static final int WRTE = 0x45545257;
        private static final int STLS = 0x534c5453;

        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;
        private int nextLocalId = 1;

        private Client(Socket socket) throws IOException {
            this.socket = socket;
            input = socket.getInputStream();
            output = socket.getOutputStream();
            write(CNXN, 0x01000001, 4096, "host::".getBytes(StandardCharsets.US_ASCII));
            Packet response = read();
            if (response.command == AUTH) throw new IOException("Loopback ADB requires a host key");
            if (response.command == STLS) throw new IOException("Loopback ADB requires TLS");
            if (response.command != CNXN) throw new IOException("Unexpected ADB handshake response");
        }

        static Client connect() throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(InetAddress.getByName(HOST), live), 2_000);
            socket.setSoTimeout(6_000);
            return new Client(socket);
        }

        String service(String destination) throws IOException {
            Stream stream = open(destination);
            try {
                return stream.readAll();
            } finally {
                stream.close();
            }
        }

        /** How long a read may wait for output (connect() sets 6 s). */
        void readTimeout(int ms) throws IOException { socket.setSoTimeout(ms); }

        String shell(String command) throws IOException {
            return service("shell:" + command);
        }

        void push(File source, String target, int mode) throws IOException {
            if (source == null || !source.isFile()) throw new IOException("Missing file to upload: " + source);
            Stream stream = open("sync:");
            try (InputStream file = new FileInputStream(source)) {
                syncWrite(stream, "SEND", (target + "," + mode).getBytes(StandardCharsets.UTF_8));
                byte[] buffer = new byte[4096];
                int count;
                while ((count = file.read(buffer)) != -1) {
                    byte[] data = new byte[count];
                    System.arraycopy(buffer, 0, data, 0, count);
                    syncWrite(stream, "DATA", data);
                }
                syncWrite(stream, "DONE", new byte[0]);
                String id = new String(stream.readExactly(4), StandardCharsets.US_ASCII);
                int length = littleEndian(stream.readExactly(4), 0);
                if ("FAIL".equals(id)) throw new IOException("ADB upload failed: "
                        + new String(stream.readExactly(length), StandardCharsets.UTF_8));
                if (!"OKAY".equals(id)) throw new IOException("Unexpected ADB sync response: " + id);
            } finally {
                stream.close();
            }
        }

        private void syncWrite(Stream stream, String id, byte[] data) throws IOException {
            byte[] header = new byte[8];
            byte[] name = id.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, header, 0, 4);
            putLittleEndian(header, 4, data.length);
            stream.write(header);
            if (data.length > 0) stream.write(data);
        }

        private Stream open(String destination) throws IOException {
            int localId = nextLocalId++;
            write(OPEN, localId, 0, (destination + "\0").getBytes(StandardCharsets.UTF_8));
            while (true) {
                Packet packet = read();
                if (packet.command == OKAY && packet.arg1 == localId) return new Stream(localId, packet.arg0);
                if (packet.command == CLSE && packet.arg1 == localId) {
                    throw new IOException("ADB rejected service " + destination);
                }
            }
        }

        private Packet read() throws IOException {
            byte[] header = readFully(24);
            int command = littleEndian(header, 0);
            int arg0 = littleEndian(header, 4);
            int arg1 = littleEndian(header, 8);
            int length = littleEndian(header, 12);
            if (length < 0 || length > 1024 * 1024) throw new IOException("Invalid ADB packet length");
            return new Packet(command, arg0, arg1, readFully(length));
        }

        private void write(int command, int arg0, int arg1, byte[] data) throws IOException {
            byte[] header = new byte[24];
            putLittleEndian(header, 0, command);
            putLittleEndian(header, 4, arg0);
            putLittleEndian(header, 8, arg1);
            putLittleEndian(header, 12, data.length);
            putLittleEndian(header, 16, checksum(data));
            putLittleEndian(header, 20, command ^ 0xffffffff);
            output.write(header);
            if (data.length > 0) output.write(data);
            output.flush();
        }

        private byte[] readFully(int length) throws IOException {
            byte[] data = new byte[length];
            int offset = 0;
            while (offset < length) {
                int count = input.read(data, offset, length - offset);
                if (count == -1) throw new IOException("ADB connection closed");
                offset += count;
            }
            return data;
        }

        @Override public void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }

        private final class Stream implements AutoCloseable {
            private final int localId;
            private final int remoteId;
            private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
            private boolean closed;

            Stream(int localId, int remoteId) { this.localId = localId; this.remoteId = remoteId; }

            void write(byte[] data) throws IOException {
                int offset = 0;
                while (offset < data.length) {
                    int count = Math.min(4096, data.length - offset);
                    byte[] chunk = new byte[count];
                    System.arraycopy(data, offset, chunk, 0, count);
                    Client.this.write(WRTE, localId, remoteId, chunk);
                    waitForOkay();
                    offset += count;
                }
            }

            String readAll() throws IOException {
                while (!closed) receive();
                return pending.toString(StandardCharsets.UTF_8.name());
            }

            byte[] readExactly(int length) throws IOException {
                while (pending.size() < length && !closed) receive();
                if (pending.size() < length) throw new IOException("ADB stream closed early");
                byte[] all = pending.toByteArray();
                byte[] result = new byte[length];
                System.arraycopy(all, 0, result, 0, length);
                pending.reset();
                if (all.length > length) pending.write(all, length, all.length - length);
                return result;
            }

            private void waitForOkay() throws IOException {
                while (true) {
                    Packet packet = read();
                    if (packet.command == OKAY && packet.arg1 == localId) return;
                    handle(packet);
                }
            }

            private void receive() throws IOException { handle(read()); }

            private void handle(Packet packet) throws IOException {
                if (packet.arg1 != localId) return;
                if (packet.command == WRTE) {
                    pending.write(packet.data);
                    Client.this.write(OKAY, localId, remoteId, new byte[0]);
                } else if (packet.command == CLSE) {
                    closed = true;
                    Client.this.write(CLSE, localId, remoteId, new byte[0]);
                }
            }

            @Override public void close() {
                if (!closed) {
                    try { Client.this.write(CLSE, localId, remoteId, new byte[0]); } catch (IOException ignored) { }
                    closed = true;
                }
            }
        }
    }

    private static final class Packet {
        final int command, arg0, arg1;
        final byte[] data;
        Packet(int command, int arg0, int arg1, byte[] data) {
            this.command = command; this.arg0 = arg0; this.arg1 = arg1; this.data = data;
        }
    }

    private static int checksum(byte[] data) {
        int sum = 0;
        for (byte value : data) sum += value & 0xff;
        return sum;
    }

    private static int littleEndian(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16) | ((data[offset + 3] & 0xff) << 24);
    }

    private static void putLittleEndian(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }
}
