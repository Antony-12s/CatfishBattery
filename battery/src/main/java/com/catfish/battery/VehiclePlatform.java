package com.catfish.battery;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Geely the app runs on, from ro.build.product (Build.PRODUCT): e245 = EX5, p145 = EX5 EM-i / Starray,
 * e22 or p22 = EX2. The code must stand alone between non-alphanumerics ("antora1000_e245_plus_car"), so a number
 * that merely contains it does not match. Null = not a supported Geely (an emulator, another device).
 * Do not use ro.product.model or the APVP config folder name: this EX5 (e245) ships its APVP table as p145_j1.
 */
enum VehiclePlatform {
    EX5("Geely EX5"), EX5_EMI("Geely EX5 EM-i"), EX2("Geely EX2");

    private static final Pattern CODE = Pattern.compile("(?<![a-z0-9])(e245|p145|e22|p22)(?![a-z0-9])");
    final String title;

    VehiclePlatform(String title) { this.title = title; }

    static VehiclePlatform of(String product) {
        Matcher m = CODE.matcher(product == null ? "" : product.toLowerCase(Locale.ROOT));
        if (!m.find()) return null;
        switch (m.group(1)) {
            case "e245": return EX5;
            case "p145": return EX5_EMI;
            default: return EX2;
        }
    }

    static String label(String product) {
        VehiclePlatform p = of(product);
        if (p == null) return "Not a supported Geely (" + product + ")";
        Matcher m = CODE.matcher(product.toLowerCase(Locale.ROOT));
        //noinspection ResultOfMethodCallIgnored
        m.find();
        return p.title + " (" + m.group(1) + ")";
    }

    static VehiclePlatform current() { return of(android.os.Build.PRODUCT); }
}
