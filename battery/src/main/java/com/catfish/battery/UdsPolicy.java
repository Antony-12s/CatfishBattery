package com.catfish.battery;

import java.io.IOException;
import java.util.Arrays;

/**
 * The only UDS request this app may ever send: the BECM state of health, 22 49 6D to 0x1635. Verified on the EX5
 * (e245); allowed on any platform because it is a read (ReadDataByIdentifier), and a car whose BECM does not answer
 * it just gets a negative response or a timeout and the canspi fallback. BecmSoh checks every request here right before it goes out; ReadOnlyPolicyTest keeps every
 * other source file from sending UDS or writing vehicle properties.
 */
final class UdsPolicy {
    static final char BECM = 0x1635;
    static final byte[] BECM_SOH = {0x22, 0x49, 0x6D};

    private UdsPolicy() { }

    static boolean allowed(char ecu, byte[] request) {
        return ecu == BECM && Arrays.equals(request, BECM_SOH);
    }

    static void require(char ecu, byte[] request) throws IOException {
        if (!allowed(ecu, request))
            throw new IOException("UDS request not allowed: ECU " + Integer.toHexString(ecu));
    }
}
