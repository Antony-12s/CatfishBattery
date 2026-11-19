package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Platform from ro.build.product, the way the car's own builds name it (e245 EX5, p145 EX5 EM-i, e22/p22 EX2). */
public class VehiclePlatformTest {
    @Test public void knownProducts() {
        assertEquals(VehiclePlatform.EX5, VehiclePlatform.of("antora1000_e245_plus")); // this car: ro.build.product
        assertEquals(VehiclePlatform.EX5, VehiclePlatform.of("antora1000_e245_plus_car")); // its ro.product.name
        assertEquals(VehiclePlatform.EX5_EMI, VehiclePlatform.of("antora1000_P145_car"));
        assertEquals(VehiclePlatform.EX2, VehiclePlatform.of("x_e22_car"));
        assertEquals(VehiclePlatform.EX2, VehiclePlatform.of("x_p22_car"));
    }

    @Test public void codesMustStandAloneNotInsideOtherNumbers() {
        assertNull(VehiclePlatform.of("sdk_gcar_x86_64"));
        assertNull(VehiclePlatform.of("antora1000_e2450_car"));
        assertNull(VehiclePlatform.of("v2_pro_st"));
        assertNull(VehiclePlatform.of(null));
        assertNull(VehiclePlatform.of(""));
    }

    @Test public void labelForSettings() {
        assertEquals("Geely EX5 (e245)", VehiclePlatform.label("antora1000_e245_plus_car"));
        assertEquals("Not a supported Geely (sdk_gcar_x86_64)", VehiclePlatform.label("sdk_gcar_x86_64"));
    }
}
