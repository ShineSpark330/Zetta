package com.swfmanager.app;

import android.content.Context;
import android.net.Uri;

import com.swfmanager.core.ApkRepack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** AIR app APKs the user has saved to use as runtimes for "Run in AIR". Kept in the app's private storage. */
final class Runtimes {
    private Runtimes() {}

    static final class Entry {
        final File apk;
        final ApkRepack.Info info;

        Entry(File apk, ApkRepack.Info info) {
            this.apk = apk;
            this.info = info;
        }
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "runtimes");
        d.mkdirs();
        return d;
    }

    static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            if (!f.getName().endsWith(".apk")) continue;
            try {
                out.add(new Entry(f, load(f)));
            } catch (IOException | RuntimeException e) {
                // unreadable: skip it
            }
        }
        return out;
    }

    /** Copies the picked APK into private storage and checks that it is an AIR app. */
    static Entry add(Context c, Uri uri) throws IOException {
        File f = new File(dir(c), "rt" + System.currentTimeMillis() + ".apk");
        try (InputStream in = c.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(f)) {
            if (in == null) throw new IOException("Cannot open file");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException | RuntimeException e) {
            f.delete();
            throw e;
        }
        try {
            ApkRepack.Info info = ApkRepack.inspect(f);
            save(f, info);
            return new Entry(f, info);
        } catch (IOException | RuntimeException e) {
            f.delete();
            throw e;
        }
    }

    static void remove(Entry e) {
        e.apk.delete();
        sidecar(e.apk).delete();
    }

    // ---- cached info ---------------------------------------------------------------------

    private static File sidecar(File apk) {
        return new File(apk.getParentFile(), apk.getName() + ".info");
    }

    private static void save(File apk, ApkRepack.Info i) throws IOException {
        Properties p = new Properties();
        p.setProperty("ns", i.airNamespace);
        p.setProperty("rt", i.runtimeVersion);
        p.setProperty("id", i.appId);
        p.setProperty("pkg", i.packageName);
        p.setProperty("content", i.content);
        p.setProperty("abis", i.abis);
        p.setProperty("size", String.valueOf(i.size));
        try (OutputStream o = new FileOutputStream(sidecar(apk))) {
            p.store(o, null);
        }
    }

    private static ApkRepack.Info load(File apk) throws IOException {
        File sc = sidecar(apk);
        if (!sc.exists()) {
            ApkRepack.Info i = ApkRepack.inspect(apk);
            save(apk, i);
            return i;
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(sc)) {
            p.load(in);
        }
        ApkRepack.Info i = new ApkRepack.Info();
        i.airNamespace = p.getProperty("ns", "");
        i.runtimeVersion = p.getProperty("rt", "");
        i.appId = p.getProperty("id", "");
        i.packageName = p.getProperty("pkg", "");
        i.content = p.getProperty("content", "");
        i.abis = p.getProperty("abis", "");
        try {
            i.size = Long.parseLong(p.getProperty("size", "0"));
        } catch (NumberFormatException e) {
            i.size = apk.length();
        }
        return i;
    }
}
