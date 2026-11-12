package com.unitool.lk;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Patches an LK image of a Transsion phone (Tecno, Infinix, itel) so that
 * {@code fastboot flashing unlock} no longer asks for the vendor authorisation.
 *
 * <p>The image keeps its size and its layout, the code is replaced in place and the
 * certificate of the partition is made to agree with the new content.
 */
public final class LkPatch {
    /** The modes of the certificate, the first one is the default. */
    public static final String[] CERT_MODES = {"wrap-override", "wrap", "override"};

    private LkPatch() {
    }

    /** Receives the output of the run. */
    public interface Output {
        void println(String text);

        void error(String text);

        default void verbose(String text) {
            println(text);
        }
    }

    /** Options of one run. */
    public static final class Options {
        public String partition = "lk";
        public String output;
        public String certMode = "wrap-override";
        public boolean analyze;
        public boolean resign = true;
        public boolean dryRun;
        public boolean verbose;
    }

    /** What happened. */
    public static final class Result {
        public boolean ok;
        public boolean alreadyPatched;
        public String error;
        public int patchCount;
        public final List<String> signed = new ArrayList<>();
        public String output;
    }

    /**
     * Runs the patcher.
     *
     * @param image the lk image, either a complete LK container or a bare lk payload
     */
    public static Result run(File image, Options options, Output out) {
        Result result = new Result();
        byte[] original;
        try {
            original = readFile(image);
        } catch (IOException e) {
            result.error = "cannot read " + image + ": " + e.getMessage();
            return result;
        }
        int originalSize = original.length;

        LkImage container = null;
        LkImage.Partition partition = null;
        byte[] raw;
        try {
            container = new LkImage(original);
            partition = container.partitions.get(options.partition);
            if (partition == null) {
                result.error = "the image has no partition '" + options.partition + "', it has: "
                        + String.join(", ", container.partitions.keySet());
                return result;
            }
            raw = partition.data;
            out.println("image        : " + image.getName() + " (" + human(originalSize) + ")");
            out.println("partition    : " + options.partition + " (" + human(raw.length) + ")");
            out.println("load address : 0x" + String.format("%016x", partition.header.memoryAddress()));
        } catch (RuntimeException e) {
            container = null;
            partition = null;
            raw = original;
            out.println("image        : " + image.getName() + " (" + human(originalSize)
                    + ") - a bare lk payload");
        }

        byte[] patched = raw.clone();
        LkUnlock unlock = new LkUnlock(patched);
        LkUnlock.Analysis analysis;
        try {
            analysis = unlock.analyse();
        } catch (LkUnlock.AlreadyPatched e) {
            result.alreadyPatched = true;
            result.ok = true;
            out.println(e.getMessage());
            return result;
        } catch (RuntimeException e) {
            result.error = e.getMessage();
            out.error("error: " + e.getMessage());
            out.error("this lk build does not match the known layout; the patch would have to"
                    + " be made for this build first");
            return result;
        }

        result.patchCount = analysis.patches.size();
        out.println("");
        out.println("patches found: " + analysis.patches.size());
        out.println("--------------------------------------------------------------");
        for (LkUnlock.Patch patch : analysis.patches) {
            out.println("  " + String.format("%-10s", patch.name) + " lk+0x"
                    + String.format("%06x", patch.offset) + "  " + patch.note);
        }
        for (String note : analysis.notes) {
            out.println("  " + note);
        }
        if (options.analyze) {
            result.ok = true;
            return result;
        }

        unlock.apply(analysis.patches);

        byte[] contents;
        if (container == null || partition == null) {
            out.error("note: a bare lk payload has no certificate, the result will not pass"
                    + " the signature check");
            contents = patched;
        } else {
            partition.setData(patched);
            container.rebuild();
            if (options.resign) {
                try {
                    result.signed.addAll(resign(container, options.certMode, out));
                } catch (RuntimeException e) {
                    result.error = e.getMessage();
                    out.error("error: " + e.getMessage());
                    return result;
                }
                if (result.signed.isEmpty()) {
                    out.error("warning: nothing had to be signed again, the image may still be"
                            + " rejected");
                } else {
                    out.println("");
                    out.println("certificate signed again (" + options.certMode + "): "
                            + String.join(", ", result.signed));
                }
            } else {
                out.error("note: the certificate was not signed again, the image will not boot");
            }
            contents = container.contents;
            if (originalSize > contents.length) {
                // the image was padded to a fixed size, keep the padding
                byte[] padded = new byte[originalSize];
                System.arraycopy(contents, 0, padded, 0, contents.length);
                System.arraycopy(original, contents.length, padded, contents.length,
                        originalSize - contents.length);
                contents = padded;
            }
        }

        String target = options.output != null ? options.output
                : image.getAbsolutePath().replaceFirst("\\.bin$", ".unlock.img");
        if (target.equals(image.getAbsolutePath())) {
            target = image.getAbsolutePath() + ".unlock.img";
        }
        result.output = target;
        if (options.dryRun) {
            out.println("");
            out.println("dry run: would write " + target);
            result.ok = true;
            return result;
        }
        try (FileOutputStream stream = new FileOutputStream(target)) {
            stream.write(contents);
        } catch (IOException e) {
            result.error = "cannot write " + target + ": " + e.getMessage();
            return result;
        }
        out.println("");
        out.println("wrote " + target + " (" + human(contents.length) + ")");
        result.ok = true;
        return result;
    }

    /**
     * Signs every partition whose certificate no longer matches its content.
     *
     * @return the names of the partitions that were signed
     */
    private static List<String> resign(LkImage image, String mode, Output out) {
        List<String> signed = new ArrayList<>();
        for (String name : image.partitions.keySet()) {
            LkImage.Partition partition = image.partitions.get(name);
            LkImage.Partition cert2 = partition.cert2();
            if (cert2 == null) continue;

            byte[] cert2Data = cert2.data;
            byte[][] hashes = Certificate.hashesOf(partition);
            byte[] headerHash = hashes[0];
            byte[] imageHash = hashes[1];
            byte[] original = cert2Data;

            Certificate.Wrapped wrapped = Certificate.unwrap(cert2Data);
            if (wrapped != null) {
                if (equal(wrapped.headerHash, headerHash) && equal(wrapped.imageHash, imageHash)) {
                    continue; // already valid for this content
                }
                original = wrapped.certificate;
            } else {
                Certificate certificate;
                try {
                    certificate = Certificate.parse(cert2Data);
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("the certificate of " + name
                            + " cannot be read");
                }
                if (certificate.matches(headerHash, imageHash)) continue;
            }

            Certificate certificate = Certificate.parse(original);
            byte[] newCert = certificate.signFor(headerHash, imageHash, mode);
            if (equal(newCert, cert2Data)) continue;
            cert2.setData(newCert);
            signed.add(name);
        }
        if (!signed.isEmpty()) image.rebuild();
        return signed;
    }

    private static boolean equal(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private static byte[] readFile(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < data.length) {
                int count = in.read(data, read, data.length - read);
                if (count < 0) break;
                read += count;
            }
        }
        return data;
    }

    /** A size in a form a user can read. */
    public static String human(long size) {
        String[] units = {"B", "KiB", "MiB", "GiB"};
        double value = size;
        int unit = 0;
        while (Math.abs(value) >= 1024 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return unit == 0 ? String.format(Locale.US, "%.0f%s", value, units[unit])
                : String.format(Locale.US, "%.1f%s", value, units[unit]);
    }
}
