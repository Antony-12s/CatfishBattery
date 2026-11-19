package com.catfish.battery;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Settings > Update > Install: downloads the release APK, checks it against the release's .sha256 and this app's own
 * signer, then has the head unit's shell install it in place over the loopback adbd (LoopbackAdb), the way HUD Cluster
 * updates itself. Firmware 1.3 rejects shell installs through its package verifier, so the script turns
 * verifier_verify_adb_installs off for its own install and an EXIT trap restores it. `pm install -r` keeps app data.
 */
final class UpdateInstaller {
    static final String STAGED = "/data/local/tmp/catfish-battery-update.apk";
    private static final String SELF = "com.catfish.battery/.BatteryActivity";
    private static final int APK_MAX = 64 * 1024 * 1024, SHA_MAX = 1024;

    private UpdateInstaller() { }

    /** The lower-case hex hash at the start of a .sha256 file ("hash  name"), or null. */
    static String expectedSha256(String text) {
        String word = text == null ? "" : text.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        return word.matches("[0-9a-f]{64}") ? word : null;
    }

    static int userOf(int uid) { return uid / 100_000; }

    /**
     * Shell script that installs the staged APK for `user`, then reopens the app. Detached (toybox setsid -d), because
     * the install kills this process and its adb stream with it. Same verifier trap as HUD Cluster's VERIFIER_OFF: the
     * saved value comes back on exit (null = unset: deleted again; a saved 0 is never written back).
     */
    static String installScript(String staged, int user) {
        String body = "V=$(settings get global verifier_verify_adb_installs); "
                + "settings put global verifier_verify_adb_installs 0; "
                + "trap 'if [ \"$V\" = null ]; then settings delete global verifier_verify_adb_installs; "
                + "elif [ \"$V\" != 0 ]; then settings put global verifier_verify_adb_installs \"$V\"; fi' EXIT\n"
                + "pm install -r --user " + user + " '" + staged + "'; R=$?; rm -f '" + staged + "'; "
                + "sleep 1; am start --user " + user + " -n " + SELF + "; exit $R";
        return "setsid -d sh -c \"" + body.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                + "\" < /dev/null > /data/local/tmp/catfish-battery-update.log 2>&1; echo started";
    }

    /** Off the UI thread. Each step reports to `progress`; any failure throws with a message for the screen. */
    static void install(Context context, UpdateCheck.Release release, Consumer<String> progress) throws IOException {
        if (release == null || release.apkUrl == null || release.sha256Url == null) throw new IOException("This release has no APK");
        progress.accept("Checking wireless ADB…");
        if (!LoopbackAdb.available()) throw new IOException("Wireless ADB is off on this head unit");
        String expected = expectedSha256(new String(download(release.sha256Url, SHA_MAX, null), StandardCharsets.UTF_8));
        if (expected == null) throw new IOException("The release checksum is unreadable");
        File apk = new File(context.getCacheDir(), "update.apk");
        try {
            progress.accept("Downloading " + release.version + "…");
            byte[] hash = download(release.apkUrl, APK_MAX, apk);
            if (!hex(hash).equals(expected)) throw new IOException("Download does not match the release checksum");
            progress.accept("Checking the signature…");
            checkSigner(context, apk);
            progress.accept("Installing " + release.version + "… the app restarts by itself");
            try (LoopbackAdb adb = LoopbackAdb.connect()) {
                adb.readTimeout(60_000); // adbd answers a big upload's DONE only once it is written
                adb.push(apk, STAGED, 0644);
                String reply = adb.shell(installScript(STAGED, userOf(android.os.Process.myUid())));
                if (!reply.contains("started")) throw new IOException("Install did not start: " + reply.trim());
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
        }
    }

    /** Same package, a newer build, and the same signer as this app: Android would refuse anything else anyway. */
    private static void checkSigner(Context context, File apk) throws IOException {
        PackageManager pm = context.getPackageManager();
        PackageInfo update = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo self;
        try {
            self = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("Own package not found");
        }
        if (update == null || update.signingInfo == null) throw new IOException("The download is not a valid APK");
        if (!context.getPackageName().equals(update.packageName)) throw new IOException("The download is a different app");
        if (update.getLongVersionCode() <= self.getLongVersionCode()) throw new IOException("The download is not newer");
        Signature[] a = update.signingInfo.getApkContentsSigners(), b = self.signingInfo.getApkContentsSigners();
        if (!Arrays.equals(a, b)) throw new IOException("The download is signed by someone else");
    }

    /** GETs `url` (https only, redirects followed by HttpURLConnection) into `target`, or into memory when null. */
    private static byte[] download(String url, int max, File target) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("Not an https link");
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(15_000);
        http.setReadTimeout(30_000);
        try {
            if (http.getResponseCode() != 200) throw new IOException("HTTP " + http.getResponseCode());
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream memory = target == null ? new ByteArrayOutputStream() : null;
            try (InputStream in = http.getInputStream(); OutputStream out = target == null ? memory : new FileOutputStream(target)) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > max) throw new IOException("Download larger than expected");
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            return target == null ? memory.toByteArray() : sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            http.disconnect();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return s.toString();
    }
}
