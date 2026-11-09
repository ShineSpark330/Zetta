package com.swfmanager.app;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.swfmanager.core.ApkRepack;
import com.swfmanager.core.Asset;
import com.swfmanager.core.SwfDocument;
import com.swfmanager.core.SwfEditor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Run in AIR": builds a copy of an AIR app APK (the runtime) that plays the SWF currently open here,
 * signs it, and hands it to the system installer. Real ActionScript runs, on whichever AIR version the
 * chosen APK carries.
 */
final class AirPreview {
    private final AppCompatActivity act;
    private final Editing ui;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String[]> runtimePicker;
    private final ActivityResultLauncher<String> apkSaver;
    private File built;

    AirPreview(AppCompatActivity activity, Editing editing) {
        this.act = activity;
        this.ui = editing;
        runtimePicker = act.registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                new ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri uri) {
                        if (uri != null) addRuntime(uri);
                    }
                });
        apkSaver = act.registerForActivityResult(new ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"),
                new ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri uri) {
                        if (uri != null) saveBuilt(uri);
                    }
                });
    }

    void close() {
        exec.shutdown();
    }

    // ---- choosing a runtime --------------------------------------------------------------

    void start() {
        if (AppState.doc == null) return;
        final List<Runtimes.Entry> list = Runtimes.list(act);
        if (list.isEmpty()) {
            new AlertDialog.Builder(act)
                    .setTitle("Run in AIR")
                    .setMessage("The built-in player can't run ActionScript. To run the SWF for real, this app builds a copy "
                            + "of an AIR app (for example your Gacha Club APK) that plays your edited SWF instead of its own, "
                            + "then installs it.\n\nPick the APK to use as the runtime. Its AIR version decides which SWFs "
                            + "will run; you can add several and choose each time.")
                    .setPositiveButton("Pick APK…", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            runtimePicker.launch(new String[] {"*/*"});
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        final String[] items = new String[list.size() + (1) + 1];
        for (int i = 0; i < list.size(); i++) items[i] = list.get(i).info.label();
        items[list.size()] = "Add another runtime APK…";
        items[list.size() + 1] = "Remove a runtime…";
        new AlertDialog.Builder(act)
                .setTitle("Run with which AIR runtime?")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which < list.size()) build(list.get(which));
                        else if (which == list.size()) runtimePicker.launch(new String[] {"*/*"});
                        else chooseRemove(list);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseRemove(final List<Runtimes.Entry> list) {
        String[] items = new String[list.size()];
        for (int i = 0; i < items.length; i++) items[i] = list.get(i).info.label();
        new AlertDialog.Builder(act)
                .setTitle("Remove which runtime?")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Runtimes.remove(list.get(which));
                        ui.toast("Removed");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addRuntime(final Uri uri) {
        ui.showBusy("Checking APK…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Runtimes.Entry e = Runtimes.add(act, uri);
                    ui.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            ui.hideBusy();
                            ui.toast("Added " + e.info.label());
                            start();
                        }
                    });
                } catch (final Throwable e) {
                    ui.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            ui.hideBusy();
                            ui.toast("Can't use that APK: " + Editing.message(e));
                        }
                    });
                }
            }
        });
    }

    // ---- building ------------------------------------------------------------------------

    private void build(final Runtimes.Entry rt) {
        final SwfDocument doc = AppState.doc;
        final boolean compress = AppState.compress;
        ui.showBusy("Building APK…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(
                            new PKCS8EncodedKeySpec(readAsset("preview_key.pk8")));
                    X509Certificate cert;
                    try (InputStream in = act.getAssets().open("preview_cert.der")) {
                        cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
                    }
                    final File out = new File(new File(act.getCacheDir(), "preview"), "run.apk");
                    byte[] swf = SwfEditor.finish(doc.file.data, compress);
                    ApkRepack.repack(rt.apk, rt.info, swf, key, cert, out);
                    final String warning = ApkRepack.compatibilityWarning(rt.info, doc.file.version);
                    ui.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            ui.hideBusy();
                            built = out;
                            showBuilt(rt, out, warning);
                        }
                    });
                } catch (final Throwable e) {
                    ui.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            ui.hideBusy();
                            ui.toast("Could not build the APK: " + Editing.message(e));
                        }
                    });
                }
            }
        });
    }

    private byte[] readAsset(String name) throws java.io.IOException {
        try (InputStream in = act.getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    private void showBuilt(Runtimes.Entry rt, final File out, String warning) {
        if (act.isFinishing() || act.isDestroyed()) return;
        String pkg = rt.info.packageName.isEmpty() ? "its app" : rt.info.packageName;
        StringBuilder sb = new StringBuilder();
        sb.append("Built ").append(Asset.formatSize(out.length())).append(" on ").append(rt.info.label()).append(".\n\n");
        if (warning != null) sb.append("Warning: ").append(warning).append("\n\n");
        sb.append("It installs as ").append(pkg).append(". If that app is already installed and was signed with a "
                + "different key, Android refuses the update; uninstalling the old one first deletes its saved data, so "
                + "back that up, or use a runtime APK with a different package name.\n\nAfter installing, open the app "
                + "to run your SWF.");
        new AlertDialog.Builder(act)
                .setTitle("Ready to install")
                .setMessage(sb.toString())
                .setPositiveButton("Install", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        install(out);
                    }
                })
                .setNeutralButton("Save APK…", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String base = AppState.fileName == null || AppState.fileName.isEmpty() ? "preview" : AppState.fileName;
                        if (base.toLowerCase(java.util.Locale.ROOT).endsWith(".swf")) base = base.substring(0, base.length() - 4);
                        apkSaver.launch(base + "_air.apk");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void install(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(act)
                    .setTitle("Allow installs")
                    .setMessage("Android needs your permission for this app to install APKs. Turn it on in the next screen, "
                            + "come back, and choose Run in AIR again (the APK is already built; pick the same runtime).")
                    .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName())));
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".files", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (RuntimeException e) {
            ui.toast("Could not start the installer: " + Editing.message(e) + ". Try Save APK… and install it yourself.");
        }
    }

    private void saveBuilt(final Uri uri) {
        final File apk = built;
        if (apk == null || !apk.exists()) {
            ui.toast("Nothing built yet");
            return;
        }
        ui.showBusy("Saving APK…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try (InputStream in = new java.io.FileInputStream(apk);
                     java.io.OutputStream out = act.getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new java.io.IOException("Cannot open file for writing");
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    ui.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            ui.hideBusy();
                            ui.toast("APK saved");
                        }
                    });
                } catch (final Throwable e) {
                    ui.ui.post(new Runnable() {
                        @Override
                        public void run() {
                            ui.hideBusy();
                            ui.toast("Could not save: " + Editing.message(e));
                        }
                    });
                }
            }
        });
    }
}
