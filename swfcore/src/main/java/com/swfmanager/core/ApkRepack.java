package com.swfmanager.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Builds a runnable test APK out of an Adobe AIR "captive runtime" APK (any APK exported from
 * AIR, such as a game) by swapping the SWF it plays for another one and re-signing it. The AIR
 * runtime inside the base APK decides which ActionScript features and SWF versions work, so
 * using a different base APK = testing on a different AIR version.
 *
 * Pure Java (no Android classes). Signs with the classic JAR (v1) scheme, which Android
 * accepts for apps whose targetSdkVersion is below 30, like every AIR app up to AIR 33.
 */
public final class ApkRepack {
    private ApkRepack() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String APP_XML = "assets/META-INF/AIR/application.xml";

    /** What we learned about a base APK. */
    public static final class Info {
        /** From application.xml: the AIR namespace version, e.g. "32.0". */
        public String airNamespace = "";
        /** Runtime build found inside libCore.so, e.g. "32.0.0.116" (may be empty). */
        public String runtimeVersion = "";
        public String appId = "";
        /** Android package name from AndroidManifest.xml (what the installed app is called). */
        public String packageName = "";
        /** Name of the root SWF inside assets/ that the app starts. */
        public String content = "";
        public String abis = "";
        public long size;

        /** Major AIR version (32), or 0 if unknown. */
        public int airMajor() {
            String v = !runtimeVersion.isEmpty() ? runtimeVersion : airNamespace;
            Matcher m = Pattern.compile("^(\\d+)").matcher(v);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        }

        public String label() {
            String v = !runtimeVersion.isEmpty() ? runtimeVersion : airNamespace;
            String app = !packageName.isEmpty() ? packageName : appId;
            return "AIR " + (v.isEmpty() ? "?" : v) + " · " + (app.isEmpty() ? "unknown app" : app)
                    + (abis.isEmpty() ? "" : " · " + abis);
        }
    }

    /** Reads application.xml and libCore.so from an AIR APK. Throws if it isn't one. */
    public static Info inspect(File apk) throws IOException {
        Info info = new Info();
        info.size = apk.length();
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry xe = zf.getEntry(APP_XML);
            if (xe == null) {
                throw new IOException("This APK doesn't look like an AIR app (no " + APP_XML + ")");
            }
            String xml = new String(readAll(zf.getInputStream(xe)), UTF8);
            Matcher m = Pattern.compile("xmlns=\"http://ns\\.adobe\\.com/air/application/([0-9.]+)\"").matcher(xml);
            if (m.find()) info.airNamespace = m.group(1);
            m = Pattern.compile("<id>\\s*([^<]*?)\\s*</id>").matcher(xml);
            if (m.find()) info.appId = m.group(1);
            m = Pattern.compile("<content>\\s*([^<]*?)\\s*</content>").matcher(xml);
            if (m.find()) info.content = m.group(1);
            if (info.content.isEmpty()) throw new IOException("application.xml has no <content> SWF");
            if (zf.getEntry("assets/" + info.content) == null) {
                throw new IOException("The app's SWF (assets/" + info.content + ") is missing from the APK");
            }

            ZipEntry me = zf.getEntry("AndroidManifest.xml");
            if (me != null) info.packageName = manifestPackage(readAll(zf.getInputStream(me)));

            TreeSet<String> abis = new TreeSet<>();
            ZipEntry core = null;
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.startsWith("lib/")) {
                    int s = n.indexOf('/', 4);
                    if (s > 4) abis.add(n.substring(4, s));
                    if (n.endsWith("/libCore.so") && core == null) core = e;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String a : abis) sb.append(sb.length() > 0 ? ", " : "").append(a);
            info.abis = sb.toString();
            if (core != null) info.runtimeVersion = findRuntimeVersion(readAll(zf.getInputStream(core)), info.airNamespace);
        }
        return info;
    }

    /** Reads the package attribute of a compiled (binary) AndroidManifest.xml, or "" if it can't. */
    static String manifestPackage(byte[] x) {
        try {
            if (u16(x, 0) != 0x0003) return "";
            int p = u16(x, 2); // first chunk after the file header
            String[] strings = null;
            while (p + 8 <= x.length) {
                int type = u16(x, p), size = i32(x, p + 4);
                if (size <= 0) return "";
                if (type == 0x0001) {
                    int count = i32(x, p + 8), flags = i32(x, p + 16), start = i32(x, p + 20);
                    boolean utf8 = (flags & 0x100) != 0;
                    strings = new String[count];
                    for (int i = 0; i < count; i++) {
                        int o = p + start + i32(x, p + 28 + i * 4);
                        if (utf8) {
                            int l = x[o] & 0xFF;
                            o += (l & 0x80) != 0 ? 2 : 1; // char count (skipped)
                            int bl = x[o] & 0xFF;
                            o++;
                            if ((bl & 0x80) != 0) {
                                bl = ((bl & 0x7F) << 8) | (x[o] & 0xFF);
                                o++;
                            }
                            strings[i] = new String(x, o, bl, UTF8);
                        } else {
                            int l = u16(x, o);
                            strings[i] = new String(x, o + 2, l * 2, Charset.forName("UTF-16LE"));
                        }
                    }
                } else if (type == 0x0102 && strings != null) {
                    int attrStart = u16(x, p + 24), attrSize = u16(x, p + 26), attrCount = u16(x, p + 28);
                    int base = p + 16 + attrStart;
                    for (int i = 0; i < attrCount; i++) {
                        int a = base + i * attrSize;
                        int nameIdx = i32(x, a + 4), raw = i32(x, a + 8);
                        if (nameIdx >= 0 && nameIdx < strings.length && "package".equals(strings[nameIdx])
                                && raw >= 0 && raw < strings.length) {
                            return strings[raw];
                        }
                    }
                    return ""; // only the first start tag (<manifest>) matters
                }
                p += size;
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return "";
    }

    private static int u16(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }

    /** Most common "NN.N.N.NNN" string in the runtime library whose major version fits the namespace. */
    private static String findRuntimeVersion(byte[] so, String namespace) {
        String text = new String(so, Charset.forName("ISO-8859-1"));
        Matcher m = Pattern.compile("(?<![0-9.])(\\d{2})\\.(\\d)\\.(\\d)\\.(\\d{1,4})(?![0-9.])").matcher(text);
        String nsMajor = namespace.contains(".") ? namespace.substring(0, namespace.indexOf('.')) : namespace;
        Map<String, Integer> counts = new HashMap<>();
        while (m.find()) {
            if (!nsMajor.isEmpty() && !m.group(1).equals(nsMajor)) continue;
            Integer c = counts.get(m.group());
            counts.put(m.group(), c == null ? 1 : c + 1);
        }
        String best = "";
        int bestN = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestN) {
                best = e.getKey();
                bestN = e.getValue();
            }
        }
        return best;
    }

    /** A warning if the SWF is too new for the runtime, else null. */
    public static String compatibilityWarning(Info info, int swfVersion) {
        int air = info.airMajor();
        if (air <= 0) return null;
        // Flash Player / AIR N plays SWF version N + 9.
        int max = air + 9;
        if (swfVersion > max) {
            return "This SWF is version " + swfVersion + ", which needs AIR " + (swfVersion - 9) + " or newer, but this "
                    + "runtime is AIR " + air + " (plays up to SWF " + max + "). It may fail to start; use a newer AIR APK.";
        }
        return null;
    }

    // ---- repacking -----------------------------------------------------------------------

    /**
     * Writes {@code out}: a copy of {@code base} whose app SWF is {@code swf}, signed with the
     * given key. The base APK is not modified.
     */
    public static void repack(File base, Info info, byte[] swf, PrivateKey key, X509Certificate cert, File out)
            throws IOException, GeneralSecurityException {
        String swfEntry = "assets/" + info.content;
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        List<String> names = new ArrayList<>();
        List<byte[]> digests = new ArrayList<>();
        try (ZipFile zf = new ZipFile(base);
             CountingStream cs = new CountingStream(new FileOutputStream(out));
             ZipOutputStream zos = new ZipOutputStream(cs)) {
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) {
                ZipEntry src = en.nextElement();
                String name = src.getName();
                if (isSignatureFile(name)) continue;
                if (src.isDirectory()) {
                    ZipEntry d = new ZipEntry(name);
                    d.setTime(src.getTime());
                    zos.putNextEntry(d);
                    zos.closeEntry();
                    continue;
                }
                byte[] data = name.equals(swfEntry) ? swf : readAll(zf.getInputStream(src));
                putEntry(zos, cs, name, data, src.getMethod() == ZipEntry.STORED, src.getTime());
                names.add(name);
                digests.add(sha256(data));
            }
            sign(zos, cs, names, digests, key, cert);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            out.delete();
            throw e;
        }
    }

    private static boolean isSignatureFile(String n) {
        if (!n.startsWith("META-INF/") || n.indexOf('/', 9) >= 0) return false;
        String u = n.toUpperCase(java.util.Locale.ROOT);
        return u.equals("META-INF/MANIFEST.MF") || u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA")
                || u.endsWith(".EC") || u.startsWith("META-INF/SIG-");
    }

    private static void putEntry(ZipOutputStream zos, CountingStream cs, String name, byte[] data, boolean stored, long time)
            throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(time);
        if (stored) {
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            CRC32 crc = new CRC32();
            crc.update(data, 0, data.length);
            e.setCrc(crc.getValue());
            // Keep stored data aligned like zipalign does (4 bytes, 4096 for native libraries).
            int align = name.endsWith(".so") ? 4096 : 4;
            long dataStart = cs.count + 30 + name.getBytes(UTF8).length;
            int pad = (int) ((align - (dataStart % align)) % align);
            if (pad > 0) {
                if (pad < 4) pad += align;
                byte[] extra = new byte[pad];
                extra[0] = (byte) 0x35; // zipalign's 0xD935 padding field
                extra[1] = (byte) 0xD9;
                extra[2] = (byte) (pad - 4);
                extra[3] = (byte) ((pad - 4) >> 8);
                e.setExtra(extra);
            }
        } else {
            e.setMethod(ZipEntry.DEFLATED);
        }
        zos.putNextEntry(e);
        zos.write(data, 0, data.length);
        zos.closeEntry();
    }

    // ---- JAR (v1) signature --------------------------------------------------------------

    private static void sign(ZipOutputStream zos, CountingStream cs, List<String> names, List<byte[]> digests,
                             PrivateKey key, X509Certificate cert) throws IOException, GeneralSecurityException {
        ByteArrayOutputStream mf = new ByteArrayOutputStream();
        writeLine(mf, "Manifest-Version: 1.0");
        writeLine(mf, "Created-By: SWF Asset Manager");
        writeLine(mf, "");
        List<byte[]> sections = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            ByteArrayOutputStream sec = new ByteArrayOutputStream();
            writeLine(sec, "Name: " + names.get(i));
            writeLine(sec, "SHA-256-Digest: " + base64(digests.get(i)));
            writeLine(sec, "");
            byte[] b = sec.toByteArray();
            sections.add(b);
            mf.write(b, 0, b.length);
        }
        byte[] manifest = mf.toByteArray();

        ByteArrayOutputStream sf = new ByteArrayOutputStream();
        writeLine(sf, "Signature-Version: 1.0");
        writeLine(sf, "Created-By: 1.0 (SWF Asset Manager)");
        writeLine(sf, "SHA-256-Digest-Manifest: " + base64(sha256(manifest)));
        writeLine(sf, "");
        for (int i = 0; i < names.size(); i++) {
            writeLine(sf, "Name: " + names.get(i));
            writeLine(sf, "SHA-256-Digest: " + base64(sha256(sections.get(i))));
            writeLine(sf, "");
        }
        byte[] sfBytes = sf.toByteArray();

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(sfBytes);
        byte[] rsa = pkcs7(cert, sig.sign());

        putEntry(zos, cs, "META-INF/MANIFEST.MF", manifest, false, System.currentTimeMillis());
        putEntry(zos, cs, "META-INF/CERT.SF", sfBytes, false, System.currentTimeMillis());
        putEntry(zos, cs, "META-INF/CERT.RSA", rsa, false, System.currentTimeMillis());
    }

    /** Writes a manifest line, wrapped at 72 bytes as the JAR spec requires. */
    private static void writeLine(ByteArrayOutputStream o, String line) {
        byte[] b = line.getBytes(UTF8);
        int pos = 0, limit = 72;
        if (b.length == 0) {
            o.write('\r');
            o.write('\n');
            return;
        }
        while (pos < b.length) {
            int n = Math.min(limit, b.length - pos);
            if (pos > 0) o.write(' ');
            o.write(b, pos, n);
            o.write('\r');
            o.write('\n');
            pos += n;
            limit = 71; // continuation lines start with a space
        }
    }

    private static byte[] pkcs7(X509Certificate cert, byte[] signature) throws GeneralSecurityException {
        byte[] sha256Alg = der(0x30, der(0x06, hex("608648016503040201")), der(0x05));
        byte[] rsaAlg = der(0x30, der(0x06, hex("2A864886F70D010101")), der(0x05));
        byte[] issuerAndSerial = der(0x30, cert.getIssuerX500Principal().getEncoded(),
                der(0x02, cert.getSerialNumber().toByteArray()));
        byte[] signerInfo = der(0x30, der(0x02, new byte[] {1}), issuerAndSerial, sha256Alg, rsaAlg,
                der(0x04, signature));
        byte[] signedData = der(0x30,
                der(0x02, new byte[] {1}),
                der(0x31, sha256Alg),
                der(0x30, der(0x06, hex("2A864886F70D010701"))),
                der(0xA0, cert.getEncoded()),
                der(0x31, signerInfo));
        return der(0x30, der(0x06, hex("2A864886F70D010702")), der(0xA0, signedData));
    }

    private static byte[] der(int tag, byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream(len + 6);
        o.write(tag);
        if (len < 128) {
            o.write(len);
        } else if (len < 256) {
            o.write(0x81);
            o.write(len);
        } else if (len < 65536) {
            o.write(0x82);
            o.write(len >> 8);
            o.write(len);
        } else {
            o.write(0x83);
            o.write(len >> 16);
            o.write(len >> 8);
            o.write(len);
        }
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    // ---- small utilities -----------------------------------------------------------------

    private static final class CountingStream extends OutputStream {
        private final OutputStream out;
        long count;

        CountingStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(1024, i.available()));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    private static byte[] sha256(byte[] d) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(d);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Standard base64 (java.util.Base64 needs Android 8). */
    static String base64(byte[] d) {
        StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 0xFF, b1 = i + 1 < d.length ? d[i + 1] & 0xFF : 0, b2 = i + 2 < d.length ? d[i + 2] & 0xFF : 0;
            sb.append(B64[b0 >> 2]).append(B64[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < d.length ? B64[((b1 & 15) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < d.length ? B64[b2 & 63] : '=');
        }
        return sb.toString();
    }
}
