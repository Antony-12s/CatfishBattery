package com.catfish.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** In-app install: checksum file, the shell script that installs over the running app, and its result text. */
public class UpdateInstallerTest {
    private static final String HASH = "2f6b814bdc870171eaf0c07f248676ff09c053fa29f0840c63a3ca2c3c33981d";

    @Test public void checksumFileGivesTheFirstHexWord() {
        assertEquals(HASH, UpdateInstaller.expectedSha256(HASH + "  catfish_battery_1.6.1_release.apk\n"));
        assertEquals(HASH, UpdateInstaller.expectedSha256(HASH.toUpperCase()));
        assertNull(UpdateInstaller.expectedSha256("not a hash"));
        assertNull(UpdateInstaller.expectedSha256(""));
    }

    @Test public void scriptTurnsTheVerifierOffOnlyForTheInstallAndReopensTheApp() {
        String s = UpdateInstaller.installScript("/data/local/tmp/catfish-battery-update.apk", 11);
        // runs detached: installing this app kills the process that asked for it
        assertTrue(s.startsWith("setsid -d sh -c \""));
        assertTrue(s.endsWith("echo started"));
        assertTrue(s.contains("settings put global verifier_verify_adb_installs 0"));
        assertTrue(s.contains("trap "));  // the old value comes back when the script ends, even on failure
        assertTrue(s.contains("pm install -r --user 11 '/data/local/tmp/catfish-battery-update.apk'"));
        assertTrue(s.contains("rm -f '/data/local/tmp/catfish-battery-update.apk'"));
        assertTrue(s.contains("am start --user 11 -n com.catfish.battery/.BatteryActivity"));
        // no uninstall, no data wipe
        assertTrue(!s.contains("uninstall") && !s.contains("pm clear"));
        // $ escaped so the variables belong to the detached shell
        assertTrue(s.contains("\\$V"));
    }

    @Test public void userComesFromTheUid() {
        assertEquals(11, UpdateInstaller.userOf(1110161));
        assertEquals(0, UpdateInstaller.userOf(10161));
    }
}
