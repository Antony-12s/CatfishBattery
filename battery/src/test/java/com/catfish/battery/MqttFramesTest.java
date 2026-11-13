package com.catfish.battery;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

/** Splitting a raw MQTT 3.1.1 byte stream (as captured from the broker socket) into PUBLISH messages. */
public class MqttFramesTest {
    private static final byte[] CONNACK = {0x20, 0x02, 0x00, 0x00};
    private static final byte[] SUBACK = {(byte) 0x90, 0x03, 0x00, 0x01, 0x00};

    private static byte[] publish(int flags, String topic, byte[] payload, boolean withPacketId) {
        byte[] name = topic.getBytes(StandardCharsets.UTF_8);
        int remaining = 2 + name.length + (withPacketId ? 2 : 0) + payload.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30 | flags);
        do {
            int digit = remaining % 128;
            remaining /= 128;
            out.write(remaining > 0 ? digit | 0x80 : digit);
        } while (remaining > 0);
        out.write(name.length >>> 8);
        out.write(name.length);
        out.write(name, 0, name.length);
        if (withPacketId) { out.write(0); out.write(7); }
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    @Test
    public void skipsAcksAndReturnsPublishTopicAndPayload() {
        List<MqttFrames.Publish> p = MqttFrames.publishes(concat(CONNACK, SUBACK, publish(0, "canspi", new byte[]{1, 2, 3}, false)));
        assertEquals(1, p.size());
        assertEquals("canspi", p.get(0).topic);
        assertArrayEquals(new byte[]{1, 2, 3}, p.get(0).payload);
    }

    @Test
    public void readsMultiByteRemainingLength() {
        byte[] big = new byte[15530];
        big[15529] = 9;
        List<MqttFrames.Publish> p = MqttFrames.publishes(concat(
                publish(0, "canspi", big, false), publish(0, "canspi", new byte[]{4}, false)));
        assertEquals(2, p.size());
        assertEquals(15530, p.get(0).payload.length);
        assertEquals(9, p.get(0).payload[15529]);
        assertArrayEquals(new byte[]{4}, p.get(1).payload);
    }

    @Test
    public void skipsPacketIdOnQos1() {
        List<MqttFrames.Publish> p = MqttFrames.publishes(publish(0x02, "battcell", new byte[]{5, 6}, true));
        assertArrayEquals(new byte[]{5, 6}, p.get(0).payload);
    }

    @Test
    public void dropsTruncatedTrailingPacket() {
        byte[] whole = publish(0, "canspi", new byte[200], false);
        byte[] cut = new byte[whole.length - 50];
        System.arraycopy(whole, 0, cut, 0, cut.length);
        List<MqttFrames.Publish> p = MqttFrames.publishes(concat(publish(0, "canspi", new byte[]{1}, false), cut));
        assertEquals(1, p.size());
    }

    @Test
    public void emptyOrGarbageStreamGivesNoPublishes() {
        assertTrue(MqttFrames.publishes(new byte[0]).isEmpty());
        assertTrue(MqttFrames.publishes(new byte[]{0x30}).isEmpty());
        assertTrue(MqttFrames.publishes(new byte[]{0x30, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x01}).isEmpty());
    }
}
