package com.catfish.battery;

import android.util.Log;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Narrow read-only APVP subscription on the head unit's signal transfer service (port 40005, next to the
 * VHAL stream on 40004). The messages are decoded by hand from the wire format: only the few fields the
 * battery pages need are known, so no .proto is generated for this service.
 */
final class ApvpBatteryStream implements AutoCloseable {
    private static final String TAG = "GCP-BatteryAPVP";
    private static final String HOST = "localhost";
    private static final int PORT = 40005;
    private static final String METHOD = "transfer_proto.TransferServer/listenerSignalStream";
    private static final int MAX_MESSAGE = 65536;
    static final int CELL_UI_ARRAY = 309332894, CELL_TEMP_ARRAY = 309332892;
    // the BMS's own highest / lowest cell voltage (V); the VHAL cache never carried them on this car
    static final int CELL_U_MAX = 308282814, CELL_U_MIN = 308282816;
    // HvBattCellTInfoHvBattTSnsrNr / HvBattCellTInfoHvBattSnsrT (ids from build 244520); internal keys, never scalars
    static final int CELL_T_SENSOR = 306185635, CELL_T_VALUE = 306185640;
    private static final int[] IDS = {308282803,308282801,CELL_UI_ARRAY,CELL_TEMP_ARRAY,308282837,308282838,308282836,308282129,308282181,306185790,308282947,308282958,306185644,308282795,
            306184675,306184674,306184676,308281832,308282990,306184741,306185030,308282798,
            306185657,306185799,308282952,308282950,306185797,306185645,306184632,306184633,308282806,306185647,308282987,291504192,308282946,308282944,306185807,306185808,306185842,
            308282810,306185663,CELL_U_MAX,CELL_U_MIN,CELL_T_SENSOR,CELL_T_VALUE};
    private static final String[] NAMES = {"HvBattUDc","HvBattIDc1","PA_HvBattCellUIInfo_Res","PA_HvBattCellTinfo_Res","HvBattTMax","HvBattTMin","HvBattTAvg","VehBattUSysU","BattSocRaw","DcDcActvd","IDcDcActHiSide","IDcDcActLoSideIDcDcActLoSide","DCChrgSt","DCChaPwrAct",
            "HvCabinThermPwrCns","HvBattThermPwrCns","HvConvPwrAct","IsgIDc","IsgUDc","TotDrvPwrAct","BattCpEstimdRaw","HvBattCap",
            "HvSysRlyStsHvSysRlySts","OnBdChrgrSt","OnBdChrgrUAct","OnBdChrgrIAct","OnBdChrgrHndlSts1","DCChrgnHndlSts","ChrgLidAcSts","ChrgLidDCorAcDcSts","TotSingleChrgEgy","HvBattChrgnTiEstimd","RemnTiOfDrvChrg","PrpsnHvBattUsgOfChrgTarSOC","DchaUAct","DchaIAct","DischrgPwrExceedWarn","DischrgPwrExceedFlt","HvBattFullChrgCaution",
            // pack SOC: VHAL only pushes its SOC on change, so after a restart while parked it stayed "--"; APVP sends it on subscribe
            "HvBattSoc",
            // number of the highest cell, so the schematic names the BMS's own max instead of guessing among equal samples
            "HvBattVoltMaxSerlNr","CellUMax","CellUMin",
            // per-sensor cell temperature as two plain signals (build 244520 has no PA_HvBattCellTinfo_Res struct)
            "HvBattCellTInfoHvBattTSnsrNr","HvBattCellTInfoHvBattSnsrT"};
    private static final String CELL_TEMP_STRUCT = "PA_HvBattCellTinfo_Res";

    /** What is actually sent: the car's id and spelling per {@link #IDS} index; null name = not subscribed. */
    static final class Plan {
        final int[] ids = new int[IDS.length];
        final String[] names = new String[IDS.length];
        final List<String> missing = new ArrayList<>();
        int resolved;
    }
    /** The hardcoded table (fw 1.2/1.3 era); used when the car's signal_config.json cannot be read. */
    static final Plan BUILT_IN = plan(null);

    private static boolean plainCellTemp(int index) { return IDS[index] == CELL_T_SENSOR || IDS[index] == CELL_T_VALUE; }

    /**
     * Resolves every signal by name in the car's table. Signals the car does not list are left out instead of
     * guessed. The plain cell-temperature pair is only used when the struct is absent; with no table the
     * built-in ids and names are sent unchanged (the old behaviour).
     */
    static Plan plan(Map<String, Integer> table) {
        Plan plan = new Plan();
        boolean plainTemps = table != null && !table.containsKey(CELL_TEMP_STRUCT);
        for (int i = 0; i < IDS.length; i++) {
            if (table == null) {
                if (!plainCellTemp(i)) { plan.ids[i] = IDS[i]; plan.names[i] = NAMES[i]; plan.resolved++; }
                continue;
            }
            if (plainCellTemp(i) && !plainTemps) continue;
            String name = ApvpSignalConfig.carName(table, NAMES[i]);
            if (name == null) { plan.missing.add(NAMES[i]); continue; }
            plan.ids[i] = table.get(name); plan.names[i] = name; plan.resolved++;
        }
        return plan;
    }

    static Plan resolvePlan() {
        Map<String, Integer> table = ApvpSignalConfig.load(ApvpSignalConfig.ROOT);
        if (table == null) { Log.i(TAG, "No APVP signal table; using built-in signal ids"); return BUILT_IN; }
        Plan plan = plan(table);
        if (plan.resolved == 0) { Log.w(TAG, "APVP signal table has none of the battery signals; using built-in ids"); return BUILT_IN; }
        Log.i(TAG, "APVP signals resolved from the car: " + plan.resolved + ", not on this car: " + plan.missing);
        return plan;
    }
    interface Listener {
        void scalar(int id, double value);
        void cells(int[] payload);
        void cellTemps(int[] payload);
        /** One sensor (1..12) from the plain HvBattCellTInfo multiplex, see {@link TempMux}. */
        void cellTemp(int sensor, int tempC);
        void connected(boolean connected);
    }
    private final Listener listener;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CFBatteryAPVP"); t.setDaemon(true); return t;
    });
    private volatile boolean closed;
    private volatile boolean healthy;
    private volatile long generation;
    private ManagedChannel channel;
    private ClientCall<byte[], byte[]> call;
    private Plan plan;


    ApvpBatteryStream(Listener listener) { this.listener = listener; worker.execute(this::connect); }

    /** Raw byte[] marshaller: the request is hand-encoded and the reply is decoded by {@link #decodeUpdate}. */
    private static final MethodDescriptor.Marshaller<byte[]> RAW = new MethodDescriptor.Marshaller<byte[]>() {
        @Override public InputStream stream(byte[] value) { return new ByteArrayInputStream(value); }
        @Override public byte[] parse(InputStream input) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int n; (n = input.read(buffer)) != -1;) {
                    if (out.size() + n > MAX_MESSAGE) throw new IllegalArgumentException("APVP message too large");
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            } catch (IOException e) { throw new IllegalArgumentException("APVP message unreadable", e); }
        }
    };
    private static final MethodDescriptor<byte[], byte[]> DESCRIPTOR = MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.BIDI_STREAMING).setFullMethodName(METHOD)
            .setRequestMarshaller(RAW).setResponseMarshaller(RAW).build();

    private void connect() {
        if (closed) return;
        final long attempt = ++generation;
        try {
            releaseTransport();
            if (plan == null) plan = resolvePlan();
            final Plan active = plan;
            final TempMux mux = new TempMux(); // per connection: the server's initial values restart it
            channel = OkHttpChannelBuilder.forAddress(HOST, PORT).usePlaintext().build();
            call = channel.newCall(DESCRIPTOR, CallOptions.DEFAULT);
            StreamObserver<byte[]> response = new StreamObserver<byte[]>() {
                @Override public void onNext(byte[] value) {
                    if (closed || attempt != generation) return;
                    try {
                        decodeUpdate(value, active, mux, listener);
                        if (!healthy) { healthy = true; listener.connected(true); Log.i(TAG, "APVP battery subscription receiving"); }
                    } catch (RuntimeException e) { Log.w(TAG, "Rejected malformed APVP battery payload", e); }
                }
                @Override public void onError(Throwable t) { dropped(attempt); }
                @Override public void onCompleted() { dropped(attempt); }
            };
            StreamObserver<byte[]> request = ClientCalls.asyncBidiStreamingCall(call, response);
            request.onNext(subscription(active));
        } catch (Exception e) {
            healthy = false; listener.connected(false);
            Log.w(TAG, "APVP battery connection unavailable", e);
            if (!closed) worker.schedule(this::connect, 5, TimeUnit.SECONDS);
        }
    }

    private void dropped(long attempt) {
        if (closed || attempt != generation) return;
        healthy = false; listener.connected(false);
        Log.w(TAG, "APVP battery subscription disconnected");
        worker.schedule(this::connect, 5, TimeUnit.SECONDS);
    }

    static byte[] subscription() { return subscription(BUILT_IN); }

    static byte[] subscription(Plan plan) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        // ListenerRequest: mode=0 (selected IDs), repeated SignalIdentify at field 2.
        for(int i=0;i<IDS.length;i++) {
            if(plan.names[i]==null) continue;
            ByteArrayOutputStream id=new ByteArrayOutputStream();
            varint(id,8); varint(id,plan.ids[i]); field(id,2,plan.names[i].getBytes(StandardCharsets.UTF_8));
            field(out,2,id.toByteArray());
        }
        return out.toByteArray();
    }

    private static void varint(ByteArrayOutputStream out,long value) {
        do {int b=(int)(value&127);value>>>=7;out.write(b|(value!=0?128:0));}while(value!=0);
    }
    private static void field(ByteArrayOutputStream out,int tag,byte[] value) {
        varint(out,tag*8+2);varint(out,value.length);out.write(value,0,value.length);
    }

    static void decodeUpdate(byte[] raw,Listener listener) { decodeUpdate(raw,BUILT_IN,listener); }

    static void decodeUpdate(byte[] raw,Plan plan,Listener listener) { decodeUpdate(raw,plan,new TempMux(),listener); }

    static void decodeUpdate(byte[] raw,Plan plan,TempMux mux,Listener listener) {
        // Transfer stream: repeated update at field 1, repeated Signal at update field 3.
        for(Field update:fields(raw)) if(update.tag==1 && update.bytes!=null) {
            double[] pair={Double.NaN,Double.NaN};
            for(Field signal:fields(update.bytes)) if(signal.tag==3 && signal.bytes!=null)
                decodeSignal(signal.bytes,plan,listener,pair);
            int[] temp=mux.accept(pair);
            if(temp!=null) listener.cellTemp(temp[0],temp[1]);
        }
    }

    /**
     * HvBattCellTInfo is one multiplexed BECM frame: the sensor number cycles 1..12 (~10 updates/s) and the
     * temperature belongs to the number in the same frame. The transfer server only sends a signal when its value
     * changes (sniffed read-only on build 244520, 2026-11-20: 1790 sensor-number updates in 180 s, the temperature
     * once, at subscribe), so an update without a temperature means "unchanged": the sensor takes the last one.
     * A temperature with no number belongs to the unchanged current sensor. Nothing is emitted before both are known.
     */
    static final class TempMux {
        private double sensor=Double.NaN, temp=Double.NaN;
        int[] accept(double[] pair) {
            boolean any=!Double.isNaN(pair[0]) || !Double.isNaN(pair[1]);
            if(!Double.isNaN(pair[0])) sensor=pair[0];
            if(!Double.isNaN(pair[1])) temp=pair[1];
            return any?cellTempPair(new double[]{sensor,temp}):null;
        }
    }

    /** {sensor 1..12, degC -40..120} from an integral sensor number and temperature, else null. */
    static int[] cellTempPair(double[] pair) {
        double sensor=pair[0],temp=pair[1];
        if(!Double.isFinite(sensor) || !Double.isFinite(temp) || sensor!=Math.rint(sensor)) return null;
        if(sensor<1 || sensor>VehicleBatteryReader.EXPECTED_CELL_TEMP_COUNT || temp<-40 || temp>120) return null;
        return new int[]{(int)sensor,(int)Math.round(temp)};
    }

    static void decodeSignal(byte[] raw,Listener listener) { decodeSignal(raw,BUILT_IN,listener,null); }

    static void decodeSignal(byte[] raw,Plan plan,Listener listener,double[] pair) {
        int id=0;String name="";byte[] floats=null,bytes=null;Long integer=null,varint=null;
        for(Field f:fields(raw)) {
            if(f.tag==1 && f.bytes!=null) {
                for(Field ident:fields(f.bytes)) {
                    if(ident.tag==1) id=(int)ident.number;
                    if(ident.tag==2 && ident.bytes!=null) name=new String(ident.bytes,StandardCharsets.UTF_8);
                }
            }
            // Integer signals arrive as field 3 = a zigzag (sint32) varint in a byte string (captured 2026-09-17 on port 40005: DCChrgSt [00],
            // DcDcActvd [02] = 1, HvCabinThermPwrCns [80 0a] = 640 W; while DC charging on 2026-09-29 DCChrgSt [04] = 2 Charging and
            // DCChrgnHndlSts [06] = 3 ConnectedWithPower, which the EVCC export's enums only fit zigzag-decoded).
            else if(f.tag==3 && f.bytes!=null) integer=zigzag(bytesVarint(f.bytes));
            else if(f.tag==5) floats=f.bytes;
            else if(f.tag==7) bytes=f.bytes;
            else if(f.bytes==null && varint==null) varint=f.number;
        }
        // Match by signal name: firmware 1.3 replies with shifted ids for new subscriptions (asked 308282803 for
        // HvBattUDc, got 308282804; cell arrays +16; sniffed 2026-11-10). The id is only the fallback for a nameless reply.
        // Replies are reported under the app's own id (IDS), whatever id this firmware uses.
        int index=-1;for(int i=0;i<IDS.length;i++) {
            if(plan.names[i]==null) continue;
            if(name.isEmpty()?id==plan.ids[i]:name.equals(plan.names[i]) || name.equals(NAMES[i])) index=i;
        }
        if(index<0) return;
        id=IDS[index];
        boolean struct=id==CELL_UI_ARRAY || id==CELL_TEMP_ARRAY;
        Double scalar=null;
        if(!struct && floats!=null && floats.length==4) {
            float value=ByteBuffer.wrap(floats).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            if(Float.isFinite(value)) scalar=(double)value;
        } else if(!struct && floats==null) {
            Long value=integer!=null?integer:varint;
            if(value!=null) scalar=(double)value;
        }
        if(scalar!=null) {
            if(id==CELL_T_SENSOR || id==CELL_T_VALUE) { if(pair!=null) pair[id==CELL_T_SENSOR?0:1]=scalar; }
            else listener.scalar(id,scalar);
        }
        if(struct && bytes!=null) {
            int[] data=structData(bytes,id==CELL_UI_ARRAY?24:6);
            if(data==null) return;
            if(id==CELL_UI_ARRAY) listener.cells(data); else listener.cellTemps(data);
        }
    }

    /** The varint filling a byte string; null when empty, unterminated or followed by extra bytes. */
    static Long bytesVarint(byte[] b) {
        if(b.length==0) return null;
        int[] pos={0};
        try { long value=readVarint(b,pos); return pos[0]==b.length?value:null; }
        catch(IllegalArgumentException e) { return null; }
    }

    static Long zigzag(Long v) { return v == null ? null : (v >>> 1) ^ -(v & 1); }

    /** PAUInt8ArrayFuncTypeN: int32 availability, uint8 data[N] (packed or padded to int32), int32 format, int32 status; null unless all three flags are 0. */
    static int[] structData(byte[] bytes,int length) {
        int padded=(length+3)&~3;
        if(bytes.length!=length+12 && bytes.length!=padded+12) return null;
        ByteBuffer struct=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(struct.getInt(0)!=0 || struct.getInt(bytes.length-8)!=0 || struct.getInt(bytes.length-4)!=0) return null;
        int[] data=new int[length];for(int i=0;i<length;i++)data[i]=bytes[4+i]&255;
        return data;
    }

    static final class Field { int tag; long number; byte[] bytes; }
    static long readVarint(byte[] b,int[] pos) {
        long n=0;for(int shift=0;shift<64;shift+=7) {
            if(pos[0]>=b.length)throw new IllegalArgumentException("Truncated varint");
            int v=b[pos[0]++]&255;n|=(long)(v&127)<<shift;if((v&128)==0)return n;
        }throw new IllegalArgumentException("Invalid varint");
    }
    static List<Field> fields(byte[] b) {
        if(b.length>65536)throw new IllegalArgumentException("Oversized message");
        ArrayList<Field> out=new ArrayList<>();int[] pos={0};
        while(pos[0]<b.length) {
            long key=readVarint(b,pos);Field f=new Field();f.tag=(int)(key>>>3);int wire=(int)(key&7);
            if(f.tag==0)throw new IllegalArgumentException("Invalid field");
            if(wire==0)f.number=readVarint(b,pos);
            else if(wire==1 || wire==2 || wire==5) {
                long len=wire==2?readVarint(b,pos):wire==1?8:4;
                if(len<0 || len>b.length-pos[0])throw new IllegalArgumentException("Truncated field");
                f.bytes=new byte[(int)len];System.arraycopy(b,pos[0],f.bytes,0,(int)len);pos[0]+=(int)len;
            } else throw new IllegalArgumentException("Unsupported field");
            out.add(f);
        }return out;
    }


    private void releaseTransport() {
        if (call != null) try { call.cancel("Battery reader closed", null); } catch (Exception ignored) { }
        if (channel != null) try { channel.shutdownNow(); } catch (Exception ignored) { }
        call = null; channel = null;
    }
    @Override public void close() { closed = true; generation++; worker.shutdownNow(); releaseTransport(); }
}
