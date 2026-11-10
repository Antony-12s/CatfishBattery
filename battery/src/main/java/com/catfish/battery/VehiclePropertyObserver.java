package com.catfish.battery;

import android.util.Log;

import com.google.protobuf.Empty;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

/**
 * Read-only client of the head unit's VHAL gRPC stream (localhost:40004). Custom Profiles' observer also
 * writes properties; this app never does, so only StartPropertyValuesStream + SendAll are used.
 */
public final class VehiclePropertyObserver {
    private static final String TAG = "CatfishBatteryVhal";
    private static final String HOST = "localhost";
    private static final int PORT = 40004;
    private static final long RECONNECT_DELAY_MS = 3000;

    public interface VehiclePropertyListener {
        void onPropertyUpdated(int propId, int areaId, int intValue, float floatValue, boolean isFloat);
        default void onPropertyAvailabilityChanged(int propId, int areaId, boolean available) { }
        void onConnectionStateChanged(boolean connected);
    }

    private final List<VehiclePropertyListener> listeners = new CopyOnWriteArrayList<>();
    // The server evicts an older stream with the same client_id, so every instance gets its own.
    private final String clientId = "catfish_battery_vhal_" + UUID.randomUUID().toString().substring(0, 8);
    private volatile boolean running;
    private volatile boolean connected;
    private volatile ManagedChannel channel;
    private Thread loop;

    public boolean isConnected() { return connected; }

    public void addListener(VehiclePropertyListener listener) { listeners.add(listener); }

    public void removeListener(VehiclePropertyListener listener) { listeners.remove(listener); }

    synchronized void start() {
        if (running) return;
        running = true;
        loop = new Thread(this::run, "CatfishBatteryVhal");
        loop.setDaemon(true);
        loop.start();
    }

    synchronized void stop() {
        running = false;
        ManagedChannel current = channel;
        if (current != null) current.shutdownNow();
        if (loop != null) loop.interrupt();
        loop = null;
    }

    private void run() {
        while (running) {
            try {
                stream();
            } catch (Exception e) {
                Log.w(TAG, "VHAL stream failed: " + e.getMessage());
            }
            setConnected(false);
            ManagedChannel current = channel;
            if (current != null) current.shutdownNow();
            channel = null;
            if (!running) break;
            try { Thread.sleep(RECONNECT_DELAY_MS); } catch (InterruptedException e) { break; }
        }
    }

    private void stream() throws InterruptedException {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("session_id", Metadata.ASCII_STRING_MARSHALLER), UUID.randomUUID().toString());
        headers.put(Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER), clientId);
        ManagedChannel current = OkHttpChannelBuilder.forAddress(HOST, PORT).usePlaintext()
                .intercept(MetadataUtils.newAttachHeadersInterceptor(headers)).build();
        channel = current;
        CountDownLatch done = new CountDownLatch(1);
        vhal_proto.VehicleServerGrpc.VehicleServerStub stub = vhal_proto.VehicleServerGrpc.newStub(current);
        stub.startPropertyValuesStream(Empty.getDefaultInstance(), new StreamObserver<vhal_proto.WrappedVehiclePropValues>() {
            @Override public void onNext(vhal_proto.WrappedVehiclePropValues batch) {
                process(batch);
                setConnected(true);
            }
            @Override public void onError(Throwable t) { Log.w(TAG, "Property stream closed: " + t.getMessage()); done.countDown(); }
            @Override public void onCompleted() { done.countDown(); }
        });
        stub.sendAllPropertyValuesToStream(Empty.getDefaultInstance(), new StreamObserver<Empty>() {
            @Override public void onNext(Empty ignored) { }
            @Override public void onError(Throwable t) { Log.w(TAG, "SendAll failed: " + t.getMessage()); }
            @Override public void onCompleted() { }
        });
        done.await();
    }

    /** Same decoding as Custom Profiles: float first, then int64 bits as float, then int32. */
    private void process(vhal_proto.WrappedVehiclePropValues batch) {
        for (vhal_proto.WrappedVehiclePropValue wrapped : batch.getValuesList()) {
            if (!wrapped.hasValue()) continue;
            vhal_proto.VehiclePropValue prop = wrapped.getValue();
            int propId = prop.getProp(), areaId = prop.getAreaId();
            boolean available = prop.getStatus() == 0;
            for (VehiclePropertyListener l : listeners) l.onPropertyAvailabilityChanged(propId, areaId, available);
            if (!available) continue;
            if (prop.getFloatValuesCount() > 0) {
                notifyValue(propId, areaId, 0, prop.getFloatValues(0), true);
            } else if (prop.getInt64ValuesCount() > 0) {
                notifyValue(propId, areaId, 0, Float.intBitsToFloat((int) prop.getInt64Values(0)), true);
            } else if (prop.getInt32ValuesCount() > 0) {
                notifyValue(propId, areaId, prop.getInt32Values(0), 0f, false);
            }
        }
    }

    private void notifyValue(int propId, int areaId, int intValue, float floatValue, boolean isFloat) {
        for (VehiclePropertyListener l : listeners) l.onPropertyUpdated(propId, areaId, intValue, floatValue, isFloat);
    }

    private void setConnected(boolean now) {
        if (connected == now) return;
        connected = now;
        Log.i(TAG, "VHAL stream " + (now ? "connected" : "disconnected"));
        for (VehiclePropertyListener l : listeners) l.onConnectionStateChanged(now);
    }
}
