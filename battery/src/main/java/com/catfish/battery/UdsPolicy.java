package com.catfish.battery;

import java.io.IOException;
import java.util.Arrays;

/**
 * The only UDS request this app may ever send: the BECM state of health, 22 49 6D to 0x1635, on the EX5 (e245)
 * where it is verified. BecmSoh checks every request here right before it goes out; ReadOnlyPolicyTest keeps every
 * other source file from sending UDS or writing vehicle properties.
 */
final class UdsPolicy {
    static final char BECM = 0x1635;
    static final byte[] BECM_SOH = {0x22, 0x49, 0x6D};

    private UdsPolicy() { }

    static boolean allowed(VehiclePlatform platform, char ecu, byte[] request) {
        return platform == VehiclePlatform.EX5 && ecu == BECM && Arrays.equals(request, BECM_SOH);
    }

    static void require(VehiclePlatform platform, char ecu, byte[] request) throws IOException {
        if (!allowed(platform, ecu, request))
            throw new IOException("UDS request not allowed: platform " + platform + ", ECU " + Integer.toHexString(ecu));
    }
}
