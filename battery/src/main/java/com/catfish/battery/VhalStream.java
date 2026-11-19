package com.catfish.battery;

import android.util.Log;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * Read-only listener on the head unit's VHAL gRPC server (localhost:40004). It opens the value stream, asks the server
 * to send every current value once, and keeps the latest int / float per property for the readers. Messages are
 * decoded by hand with ApvpBatteryStream's protobuf field reader, so the app needs no generated protobuf code. Only
 * the two read calls exist here; the server's set-property call is never referenced.
 */
final class VhalStream {
    private static final String TAG = "CatfishBatteryVhal";
    private static final String SERVICE = "vhal_proto.VehicleServer/";
    private static final long RETRY_MS = 3000;
    private static final Map<Integer, Integer> INTS = new ConcurrentHashMap<>();
    private static final Map<Integer, Float> FLOATS = new ConcurrentHashMap<>();
    private static VhalStream shared;

    interface Listener {
        void onValue(int prop, int area, int intValue, float floatValue, boolean isFloat);
        default void onAvailability(int prop, int area, boolean available) { }
        void onConnected(boolean connected);
    }

    private static final MethodDescriptor.Marshaller<byte[]> BYTES = new MethodDescriptor.Marshaller<byte[]>() {
        @Override public InputStream stream(byte[] value) { return new ByteArrayInputStream(value); }
        @Override public byte[] parse(InputStream input) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = input.read(buffer)) != -1; ) out.write(buffer, 0, n);
                return out.toByteArray();
            } catch (IOException e) { throw new IllegalArgumentException("VHAL message unreadable", e); }
        }
    };
    private static final MethodDescriptor<byte[], byte[]> STREAM = method("StartPropertyValuesStream", MethodDescriptor.MethodType.SERVER_STREAMING);
    private static final MethodDescriptor<byte[], byte[]> SEND_ALL = method("SendAllPropertyValuesToStream", MethodDescriptor.MethodType.UNARY);

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    // the server drops an older stream that uses the same client_id, so each instance has its own
    private final String clientId = "catfish_battery_vhal_" + UUID.randomUUID().toString().substring(0, 8);
    private volatile boolean connected;

    private VhalStream() { }

    /** The one shared stream, started on first use; it feeds the int / float caches for the life of the process. */
    static synchronized VhalStream shared() {
        if (shared == null) {
            shared = new VhalStream();
            shared.listeners.add(new Listener() {
                @Override public void onValue(int prop, int area, int intValue, float floatValue, boolean isFloat) {
                    if (isFloat) FLOATS.put(prop, floatValue); else INTS.put(prop, intValue);
                }
                @Override public void onConnected(boolean connected) { }
            });
            Thread loop = new Thread(shared::run, "CatfishBatteryVhal");
            loop.setDaemon(true);
            loop.start();
        }
        return shared;
    }

    static Map<Integer, Integer> ints() { return INTS; }
    static Map<Integer, Float> floats() { return FLOATS; }

    boolean isConnected() { return connected; }
    void addListener(Listener l) { listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void run() {
        while (true) {
            ManagedChannel channel = null;
            try {
                Metadata headers = new Metadata();
                headers.put(Metadata.Key.of("session_id", Metadata.ASCII_STRING_MARSHALLER), UUID.randomUUID().toString());
                headers.put(Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER), clientId);
                channel = OkHttpChannelBuilder.forAddress("localhost", 40004).usePlaintext()
                        .intercept(MetadataUtils.newAttachHeadersInterceptor(headers)).build();
                CountDownLatch ended = new CountDownLatch(1);
                ClientCall<byte[], byte[]> stream = channel.newCall(STREAM, CallOptions.DEFAULT);
                ClientCalls.asyncServerStreamingCall(stream, new byte[0], new StreamObserver<byte[]>() {
                    @Override public void onNext(byte[] batch) { decodeBatch(batch, listeners); setConnected(true); }
                    @Override public void onError(Throwable t) { Log.w(TAG, "VHAL stream closed: " + t.getMessage()); ended.countDown(); }
                    @Override public void onCompleted() { ended.countDown(); }
                });
                // empty request; the server answers by pushing every current value into the stream above
                ClientCalls.asyncUnaryCall(channel.newCall(SEND_ALL, CallOptions.DEFAULT), new byte[0], new StreamObserver<byte[]>() {
                    @Override public void onNext(byte[] ignored) { }
                    @Override public void onError(Throwable t) { Log.w(TAG, "VHAL send-all failed: " + t.getMessage()); }
                    @Override public void onCompleted() { }
                });
                ended.await();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "VHAL stream unavailable: " + e.getMessage());
            } finally {
                setConnected(false);
                if (channel != null) channel.shutdownNow();
            }
            try { Thread.sleep(RETRY_MS); } catch (InterruptedException e) { return; }
        }
    }

    /**
     * One batch: repeated (field 1) wrapped values, each holding (field 1) a property value with prop (1), area (4),
     * packed sint32 (5), int64 (6) and float (7) values and status (10, 0 = available). The first float wins, then the
     * first int64 read as float bits, then the first int32. Malformed input is dropped.
     */
    static void decodeBatch(byte[] batch, List<Listener> to) {
        try {
            for (ApvpBatteryStream.Field w : ApvpBatteryStream.fields(batch)) {
                if (w.tag != 1 || w.bytes == null) continue;
                for (ApvpBatteryStream.Field v : ApvpBatteryStream.fields(w.bytes)) {
                    if (v.tag == 1 && v.bytes != null) decodeValue(v.bytes, to);
                }
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Rejected malformed VHAL batch: " + e.getMessage());
        }
    }

    private static void decodeValue(byte[] message, List<Listener> to) {
        int prop = 0, area = 0, status = 0;
        Long int32 = null, int64 = null;
        Float real = null;
        for (ApvpBatteryStream.Field f : ApvpBatteryStream.fields(message)) {
            switch (f.tag) {
                case 1: prop = (int) f.number; break;
                case 4: area = (int) f.number; break;
                case 10: status = (int) f.number; break;
                case 5: if (int32 == null) int32 = ApvpBatteryStream.zigzag(first(f)); break;
                case 6: if (int64 == null) int64 = first(f); break;
                case 7: if (real == null) real = firstFloat(f); break;
                default: break;
            }
        }
        boolean available = status == 0;
        for (Listener l : to) l.onAvailability(prop, area, available);
        if (!available) return;
        if (real != null) for (Listener l : to) l.onValue(prop, area, 0, real, true);
        else if (int64 != null) for (Listener l : to) l.onValue(prop, area, 0, Float.intBitsToFloat((int) (long) int64), true);
        else if (int32 != null) for (Listener l : to) l.onValue(prop, area, (int) (long) int32, 0f, false);
    }

    /** First varint of a packed (length-delimited) or a single field; null when empty. */
    private static Long first(ApvpBatteryStream.Field f) {
        if (f.bytes == null) return f.number;
        return f.bytes.length == 0 ? null : ApvpBatteryStream.readVarint(f.bytes, new int[]{0});
    }

    private static Float firstFloat(ApvpBatteryStream.Field f) {
        if (f.bytes == null || f.bytes.length < 4) return null;
        return ByteBuffer.wrap(f.bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    private void setConnected(boolean now) {
        if (connected == now) return;
        connected = now;
        Log.i(TAG, "VHAL stream " + (now ? "connected" : "disconnected"));
        for (Listener l : listeners) l.onConnected(now);
    }

    private static MethodDescriptor<byte[], byte[]> method(String name, MethodDescriptor.MethodType type) {
        return MethodDescriptor.<byte[], byte[]>newBuilder().setType(type).setFullMethodName(SERVICE + name)
                .setRequestMarshaller(BYTES).setResponseMarshaller(BYTES).build();
    }
}
