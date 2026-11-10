package com.catfish.battery;

import java.nio.file.Files;
import java.nio.file.Path;

/** Exports the real production HTML for browser layout regression tests, without an Android runtime. */
public final class BatteryPageExport {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        for (String page : new String[]{"status", "charging", "history", "settings"}) {
            for (boolean night : new boolean[]{false, true}) {
                Files.writeString(output.resolve(page + (night ? "-night" : "-day") + ".html"),
                        BatteryWebPages.embedded(page, night));
            }
        }
    }
}
