import com.swfmanager.core.*;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * Builds a preview APK from an AIR APK and a SWF, the same way the app does.
 *
 * usage: RepackCheck base.apk movie.swf out.apk [keyDir]
 * keyDir defaults to app/src/main/assets (preview_key.pk8 + preview_cert.der).
 */
public class RepackCheck {
    public static void main(String[] a) throws Exception {
        File base = new File(a[0]);
        byte[] swf = Files.readAllBytes(new File(a[1]).toPath());
        File out = new File(a[2]);
        File keys = new File(a.length > 3 ? a[3] : "app/src/main/assets");
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Files.readAllBytes(new File(keys, "preview_key.pk8").toPath())));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                Files.newInputStream(new File(keys, "preview_cert.der").toPath()));

        long t0 = System.currentTimeMillis();
        ApkRepack.Info info = ApkRepack.inspect(base);
        System.out.println("base: " + info.label() + "  package=" + info.packageName + "  content=" + info.content + "  ns=" + info.airNamespace
                + "  runtime=" + info.runtimeVersion + "  size=" + info.size);
        SwfDocument doc = SwfDocument.load(swf);
        System.out.println("swf version " + doc.file.version + " warning=" + ApkRepack.compatibilityWarning(info, doc.file.version));
        ApkRepack.repack(base, info, swf, key, cert, out);
        System.out.println("wrote " + out + " (" + out.length() + " bytes) in " + (System.currentTimeMillis() - t0) + " ms");
    }
}
