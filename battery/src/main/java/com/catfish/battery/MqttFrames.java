package com.catfish.battery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Splits a raw MQTT 3.1.1 byte stream captured from a broker socket into its PUBLISH messages. */
final class MqttFrames {
    private static final int TYPE_PUBLISH = 3;

    private MqttFrames() { }

    static final class Publish {
        final String topic;
        final byte[] payload;

        Publish(String topic, byte[] payload) {
            this.topic = topic;
            this.payload = payload;
        }
    }

    /** All complete PUBLISH packets; other packet types are skipped and a truncated tail is dropped. */
    static List<Publish> publishes(byte[] stream) {
        List<Publish> out = new ArrayList<>();
        int pos = 0;
        while (pos < stream.length) {
            int first = stream[pos] & 0xFF;
            int remaining = 0, multiplier = 1, cursor = pos + 1;
            while (true) {
                if (cursor >= stream.length || multiplier > 128 * 128 * 128) return out; // truncated or malformed length
                int digit = stream[cursor++] & 0xFF;
                remaining += (digit & 0x7F) * multiplier;
                multiplier *= 128;
                if ((digit & 0x80) == 0) break;
            }
            int end = cursor + remaining;
            if (end > stream.length) return out;
            if (first >>> 4 == TYPE_PUBLISH && remaining >= 2) {
                int topicLength = ((stream[cursor] & 0xFF) << 8) | (stream[cursor + 1] & 0xFF);
                int body = cursor + 2 + topicLength + (((first >>> 1) & 3) > 0 ? 2 : 0); // QoS 1/2 carry a packet id
                if (body <= end) {
                    byte[] payload = new byte[end - body];
                    System.arraycopy(stream, body, payload, 0, payload.length);
                    out.add(new Publish(new String(stream, cursor + 2, topicLength, StandardCharsets.UTF_8), payload));
                }
            }
            pos = end;
        }
        return out;
    }
}
