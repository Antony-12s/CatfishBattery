import android.content.Context;
import dalvik.system.PathClassLoader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReadBecmDid {
    private static final int MODE = 5;
    private static final char TESTER = 0x0E80;
    private static final char BECM = 0x1635;
    private static final byte[] REQUEST = {0x22, 0x49, 0x6D};

    public static void main(String[] args) {
        try {
            read(args.length > 0 && args[0].equals("--guard-only"));
            System.exit(0);
        } catch (Throwable error) {
            if (error instanceof InvocationTargetException && error.getCause() != null) {
                error = error.getCause();
            }
            error.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Park guard from the head unit's VHAL gRPC stream (localhost:40004, vhal_proto.VehicleServer), the same stream
     * the car's own apps use: GEAR_SELECTION 0x11400400 and CURRENT_GEAR 0x11400401 must both be 4 (Park). Values
     * arrive as events, so nothing is polled. Leaving Park, a non-zero status or a dropped stream voids the guard for
     * good (generation), and every check also enforces the read's time budget. Read only: only the stream and
     * send-all calls exist here. android.car is not an option for this tool: the shell user lacks CAR_POWERTRAIN.
     * Minimal HTTP/2 + protobuf by hand so the tool stays one dex file; plain java.net, so it also runs on a PC JVM.
     */
    static final class VhalParkGuard {
        static final int GEAR_SELECTION = 0x11400400, CURRENT_GEAR = 0x11400401, PARK = 4;
        private final long deadline;
        private final java.net.Socket socket;
        private Integer selection, current;
        private boolean parked;
        private int generation, token = -1;
        private String last = "no gear event yet";

        VhalParkGuard() throws IOException { this("127.0.0.1", 40004, TimeUnit.SECONDS.toNanos(20)); }

        VhalParkGuard(String host, int port, long budgetNs) throws IOException {
            deadline = System.nanoTime() + budgetNs;
            socket = new java.net.Socket(host, port);
            socket.setTcpNoDelay(true);
            String session = java.util.UUID.randomUUID().toString();
            String client = "catfish_becm_cli_" + Long.toHexString(System.nanoTime());
            java.io.OutputStream out = socket.getOutputStream();
            byte[] empty = {0, 0, 0, 0, 0}; // one empty gRPC message: the request type has no fields
            synchronized (this) {
                out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes("US-ASCII"));
                out.write(frame(4, 0, 0, new byte[0]));
                out.write(frame(1, 4, 1, headers("StartPropertyValuesStream", session, client)));
                out.write(frame(0, 1, 1, empty));
                // same session: the server answers by pushing every current value into stream 1
                out.write(frame(1, 4, 3, headers("SendAllPropertyValuesToStream", session, client)));
                out.write(frame(0, 1, 3, empty));
                out.flush();
            }
            Thread reader = new Thread(this::readLoop, "VhalParkGuard");
            reader.setDaemon(true);
            reader.start();
        }

        synchronized void onGear(int prop, Integer value) {
            if (prop == GEAR_SELECTION) selection = value; else if (prop == CURRENT_GEAR) current = value; else return;
            boolean now = selection != null && selection == PARK && current != null && current == PARK;
            if (parked && !now) generation++;
            parked = now;
            last = "selection=" + selection + " current=" + current;
            notifyAll();
        }

        private synchronized void lost(String why) {
            last = why;
            if (parked) generation++;
            parked = false;
            notifyAll();
        }

        synchronized void awaitPark() throws IOException, InterruptedException {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!parked) {
                long left = end - System.nanoTime();
                if (left <= 0) throw new IOException("Park not confirmed: " + last);
                wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
            }
            token = generation;
        }

        /** Throws unless both gears stayed in Park since awaitPark and the budget is not spent. */
        synchronized void check() throws IOException {
            if (!parked || generation != token) throw new IOException("Left Park or gear unknown: " + last);
            if (System.nanoTime() > deadline) throw new IOException("Read took too long");
        }

        synchronized String description() { return last; }

        void close() { try { socket.close(); } catch (IOException ignored) { } }

        private void readLoop() {
            try {
                java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(socket.getInputStream()));
                java.io.ByteArrayOutputStream messages = new java.io.ByteArrayOutputStream();
                while (true) {
                    int length = (in.readUnsignedByte() << 16) | in.readUnsignedShort();
                    int type = in.readUnsignedByte(), flags = in.readUnsignedByte(), stream = in.readInt() & 0x7FFFFFFF;
                    byte[] payload = new byte[length];
                    in.readFully(payload);
                    if (type == 4 && (flags & 1) == 0) send(frame(4, 1, 0, new byte[0]));        // settings ack
                    else if (type == 6 && (flags & 1) == 0) send(frame(6, 1, 0, payload));       // ping ack
                    else if (type == 3 || type == 7) { lost("VHAL stream reset"); return; }
                    else if (stream == 1 && (flags & 1) != 0) { lost("VHAL stream ended"); return; }
                    else if (type == 0 && stream == 1) {
                        int pad = (flags & 8) != 0 ? (payload[0] & 0xFF) + 1 : 0;
                        messages.write(payload, (flags & 8) != 0 ? 1 : 0, payload.length - pad);
                        if (length > 0) {
                            byte[] inc = java.nio.ByteBuffer.allocate(4).putInt(length).array();
                            send(frame(8, 0, 0, inc));
                            send(frame(8, 0, 1, inc));
                        }
                        byte[] all = messages.toByteArray();
                        int at = 0;
                        while (all.length - at >= 5) {
                            int size = java.nio.ByteBuffer.wrap(all, at + 1, 4).getInt();
                            if (all.length - at - 5 < size) break;
                            decodeBatch(Arrays.copyOfRange(all, at + 5, at + 5 + size), this);
                            at += 5 + size;
                        }
                        messages.reset();
                        messages.write(all, at, all.length - at);
                    }
                }
            } catch (IOException e) {
                lost("VHAL stream closed: " + e.getMessage());
            }
        }

        private synchronized void send(byte[] bytes) throws IOException {
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        }

        /** Batch: repeated field 1 (wrapper) -> field 1 (value): prop (1), int32 values (5, zigzag), status (10). */
        static void decodeBatch(byte[] batch, VhalParkGuard to) {
            for (long[] w : fields(batch)) {
                if (w[0] != 1 || w[1] < 0) continue;
                byte[] wrapper = Arrays.copyOfRange(batch, (int) w[1], (int) w[2]);
                for (long[] v : fields(wrapper)) {
                    if (v[0] != 1 || v[1] < 0) continue;
                    byte[] value = Arrays.copyOfRange(wrapper, (int) v[1], (int) v[2]);
                    long prop = -1, status = 0;
                    Long first = null;
                    for (long[] f : fields(value)) {
                        if (f[0] == 1 && f[1] < 0) prop = f[2];
                        else if (f[0] == 10 && f[1] < 0) status = f[2];
                        else if (f[0] == 5 && first == null && f[1] < 0) first = f[2];          // single
                        else if (f[0] == 5 && first == null && f[2] > f[1]) first = varint(value, (int) f[1])[0]; // packed
                    }
                    if (prop == GEAR_SELECTION || prop == CURRENT_GEAR) {
                        Integer gear = status == 0 && first != null ? (int) ((first >>> 1) ^ -(first & 1)) : null;
                        to.onGear((int) prop, gear);
                    }
                }
            }
        }

        /** {tag, -1, varint} for varints, {tag, start, end} for length-delimited fields; fixed fields skipped. */
        static java.util.List<long[]> fields(byte[] b) {
            java.util.List<long[]> out = new java.util.ArrayList<>();
            int i = 0;
            while (i < b.length) {
                long[] key = varint(b, i);
                i = (int) key[1];
                int tag = (int) (key[0] >>> 3), wire = (int) (key[0] & 7);
                if (wire == 0) { long[] v = varint(b, i); i = (int) v[1]; out.add(new long[]{tag, -1, v[0]}); }
                else if (wire == 2) { long[] n = varint(b, i); i = (int) n[1]; out.add(new long[]{tag, i, i + n[0]}); i += (int) n[0]; }
                else if (wire == 5) i += 4;
                else if (wire == 1) i += 8;
                else break;
            }
            return out;
        }

        static long[] varint(byte[] b, int i) {
            long v = 0;
            for (int shift = 0; i < b.length && shift < 64; shift += 7) {
                int x = b[i++] & 0xFF;
                v |= (long) (x & 0x7F) << shift;
                if (x < 0x80) break;
            }
            return new long[]{v, i};
        }

        static byte[] frame(int type, int flags, int stream, byte[] payload) {
            return java.nio.ByteBuffer.allocate(9 + payload.length).put((byte) (payload.length >>> 16))
                .putShort((short) payload.length).put((byte) type).put((byte) flags).putInt(stream).put(payload).array();
        }

        /** HPACK literal headers without indexing (all short, so one-byte lengths). */
        private static byte[] headers(String method, String session, String client) throws IOException {
            java.io.ByteArrayOutputStream h = new java.io.ByteArrayOutputStream();
            String[] pairs = {":method", "POST", ":scheme", "http", ":path", "/vhal_proto.VehicleServer/" + method,
                ":authority", "localhost:40004", "content-type", "application/grpc", "te", "trailers",
                "session_id", session, "client_id", client};
            for (int i = 0; i < pairs.length; i += 2) {
                h.write(0);
                for (String part : new String[]{pairs[i], pairs[i + 1]}) {
                    byte[] bytes = part.getBytes("US-ASCII");
                    h.write(bytes.length);
                    h.write(bytes);
                }
            }
            return h.toByteArray();
        }
    }

    private static void read(boolean guardOnly) throws Exception {
        String sdkApk = "/system/app/Dxapp/Dxapp.apk";
        ClassLoader parent = ReadBecmDid.class.getClassLoader();
        VhalParkGuard park = new VhalParkGuard();
        try {
            park.awaitPark();
            System.out.println("Park confirmed: " + park.description());
            if (guardOnly) {
                for (int i = 0; i < 16; i++) { park.check(); Thread.sleep(125); }
                System.out.println("Guard held Park for 2 s: " + park.description());
                return;
            }
            PathClassLoader sdk = new PathClassLoader(sdkApk,
                sdkApk + "!/lib/arm64-v8a:/vendor/lib64", parent);
            Class<?> api = sdk.loadClass("com.geely.udsmiddleware.VehicleDiagIF");
            Class<?> listener = sdk.loadClass("com.geely.udsmiddleware.IVehicleDiagListener");
            Object client = api.getConstructor().newInstance();
            LinkedBlockingQueue<Object> responses = new LinkedBlockingQueue<>();
            AtomicBoolean awaiting = new AtomicBoolean();
            Object callback = Proxy.newProxyInstance(sdk, new Class<?>[]{listener}, (proxy, method, values) -> {
                if (!awaiting.get()) {
                    return null;
                }
                if (method.getName().equals("onUdsResponse")
                        && (Integer) values[0] == MODE && (Character) values[1] == BECM
                        && (Character) values[2] == TESTER) {
                    byte[] data = (byte[]) values[4];
                    int length = (Integer) values[3];
                    if (data != null && length > 0 && length <= data.length) {
                        responses.offer(Arrays.copyOf(data, length));
                    }
                } else if (method.getName().equals("onDoIPMessageSendNackNotification")) {
                    responses.offer("DoIP NACK " + values[0]);
                }
                return null;
            });
            api.getMethod("registeListener", listener).invoke(client, callback);
            // This SDK build uses process-local GOS configuration for a local client.
            // Its local init path stores the Context but does not dereference it.
            System.out.println("SDK init: " + api.getMethod("init", Context.class).invoke(client, new Object[]{null}));
            Method status = api.getMethod("getStatusWithMiddleware");
            long readyBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!(Boolean) status.invoke(client)) {
                park.check();
                if (System.nanoTime() >= readyBy) {
                    throw new IOException("Middleware did not become ready");
                }
                Thread.sleep(100);
            }
            System.out.println("Middleware ready");
            Method getMode = api.getMethod("getCurDiagType");
            int currentMode = (Integer) getMode.invoke(client);
            if (currentMode != 0) {
                throw new IOException("Diagnostic channel busy, mode " + currentMode);
            }
            park.check();
            int result = (Integer) api.getMethod("takeDiagWay", int.class, char.class, int.class)
                .invoke(client, MODE, TESTER, 1);
            System.out.println("Channel acquisition result: " + result);
            if (result != 0) {
                throw new IOException("Middleware declined the diagnostic channel");
            }
            try {
                park.check();
                if ((Integer) getMode.invoke(client) != MODE) {
                    throw new IOException("Requested diagnostic mode did not become active");
                }
                awaiting.set(true);
                boolean accepted = (Boolean) api.getMethod("sendUdsPayload",
                    int.class, char.class, char.class, int.class, byte[].class)
                    .invoke(client, MODE, TESTER, BECM, REQUEST.length, REQUEST);
                System.out.println("Send 22 49 6D accepted: " + accepted);
                if (!accepted) {
                    throw new IOException("Middleware rejected the request");
                }
                awaitResponse(responses, park);
            } finally {
                awaiting.set(false);
                if ((Integer) getMode.invoke(client) == MODE) {
                    api.getMethod("releaseDiagWay").invoke(client);
                    System.out.println("Diagnostic channel released");
                }
            }
        } finally {
            park.close();
        }
    }

    private static void awaitResponse(LinkedBlockingQueue<Object> responses, VhalParkGuard park)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            park.check();
            Object item = responses.poll(125, TimeUnit.MILLISECONDS);
            if (item instanceof String) {
                throw new IOException((String) item);
            }
            if (!(item instanceof byte[])) {
                continue;
            }
            byte[] reply = (byte[]) item;
            boolean positive = reply.length >= 3 && reply[0] == 0x62 && reply[1] == 0x49 && reply[2] == 0x6D;
            boolean negative = reply.length == 3 && reply[0] == 0x7F && reply[1] == 0x22;
            if (!positive && !negative) {
                continue;
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : reply) {
                hex.append(String.format(Locale.ROOT, "%02X ", b & 0xFF));
            }
            System.out.println("BECM response: " + hex.toString().trim());
            if (!negative || reply[2] != 0x78) {
                return;
            }
        }
        throw new IOException("No final BECM UDS response before timeout");
    }
}
