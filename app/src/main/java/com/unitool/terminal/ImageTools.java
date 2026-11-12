package com.unitool.terminal;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Image tools of the terminal.
 *
 * <p>They use the native tools of the app to build a super image out of the partition
 * images and to unpack an OTA payload.
 */
public final class ImageTools {

    private ImageTools() {
    }

    /**
     * Builds a super image out of the partition images of a directory.
     *
     * <pre>
     * super &lt;dir&gt; [output] [--size=&lt;bytes&gt;] [--slot=&lt;suffix&gt;] [--group=&lt;name&gt;]
     * </pre>
     */
    public static boolean buildSuper(Context context, List<String> args, File workingDir,
                                     TerminalOutput out) {
        if (args.isEmpty()) {
            out.println("usage: super <dir> [output] [--size=<bytes>] [--slot=<suffix>] "
                    + "[--group=<name>]");
            out.println("");
            out.println("  <dir>       directory with the partition images");
            out.println("  output      the super image (default <dir>/super.img)");
            out.println("  --size      size of the super partition (default: automatic)");
            out.println("  --slot      slot suffix of the partitions (a, b, ...)");
            out.println("  --group     name of the dynamic partition group");
            return false;
        }

        File dir = resolve(workingDir, args.get(0));
        if (!dir.isDirectory()) {
            out.error("super: no such directory: " + dir.getAbsolutePath());
            return false;
        }
        File output = new File(dir, "super.img");
        String slot = "";
        String group = "qti_dynamic_partitions";
        long size = 0;
        long metadataSize = 65536;
        int metadataSlots = 3;

        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.startsWith("--size=")) {
                size = parseSize(arg.substring(7));
            } else if (arg.startsWith("--slot=")) {
                slot = arg.substring(7);
                if (!slot.isEmpty() && !slot.startsWith("_")) slot = "_" + slot;
            } else if (arg.startsWith("--group=")) {
                group = arg.substring(8);
            } else if (arg.startsWith("--metadata-size=")) {
                metadataSize = parseSize(arg.substring(16));
            } else if (arg.startsWith("--metadata-slots=")) {
                metadataSlots = (int) parseSize(arg.substring(17));
            } else if (!arg.startsWith("-")) {
                output = resolve(workingDir, arg);
            }
        }
        if (group.endsWith("_")) group = group.substring(0, group.length() - 1);
        String groupWithSlot = group + slot;

        File[] images = dir.listFiles(file -> file.isFile() && !file.getName().startsWith("."));
        if (images == null || images.length == 0) {
            out.error("super: no partition images found in " + dir.getAbsolutePath());
            return false;
        }
        Arrays.sort(images);

        // every size of a logical partition has to be a multiple of the block size
        long blockSize = 4096;
        long total = 0;
        for (File image : images) {
            total += roundUp(image.length(), blockSize);
        }
        long deviceSize = size > 0 ? roundUp(size, blockSize)
                : roundUp(total + metadataSize * metadataSlots + 8 * 1024 * 1024, blockSize);

        List<String> command = new ArrayList<>();
        command.add("--device-size=" + deviceSize);
        command.add("--metadata-size=" + metadataSize);
        command.add("--metadata-slots=" + metadataSlots);
        command.add("--super-name=super");
        command.add("--group=" + groupWithSlot + ":" + total);
        for (File image : images) {
            String name = partitionName(image);
            command.add("--partition=" + name + slot + ":readonly:"
                    + roundUp(image.length(), blockSize) + ":" + groupWithSlot);
            command.add("--image=" + name + slot + "=" + image.getAbsolutePath());
        }
        command.add("--output=" + output.getAbsolutePath());

        out.println("[super] " + images.length + " partitions, " + total / (1024 * 1024)
                + " MB");
        NativeTools.Tool tool = NativeTools.find("lpmake");
        File executable = NativeTools.executable(context, tool);
        if (executable == null) {
            out.error("super: lpmake is not installed");
            return false;
        }
        try {
            int code = NativeRunner.run(context, executable, command, workingDir, out, null);
            if (code == 0 && output.exists()) {
                out.println("[super] " + output.getAbsolutePath() + " ("
                        + output.length() / (1024 * 1024) + " MB)");
                return true;
            }
            out.error("super: lpmake failed with " + code);
            return false;
        } catch (Exception e) {
            out.error("super: " + e.getMessage());
            return false;
        }
    }

    private static long roundUp(long value, long unit) {
        return (value + unit - 1) / unit * unit;
    }

    /**
     * Lists or extracts the partitions of an OTA payload.
     *
     * <pre>
     * payload list &lt;payload.bin&gt;
     * payload extract &lt;payload.bin&gt; [outdir] [partitions]
     * </pre>
     */
    public static boolean payload(Context context, List<String> args, File workingDir,
                                   TerminalOutput out) {
        if (args.isEmpty()) {
            out.println("usage: payload list <payload.bin>");
            out.println("       payload extract <payload.bin> [outdir] [partitions]");
            out.println("");
            out.println("  partitions  comma separated list of the partitions to extract");
            return false;
        }
        String action = args.get(0).toLowerCase(Locale.US);
        if (args.size() < 2) {
            out.error("payload: missing payload file");
            return false;
        }
        File payload = resolve(workingDir, args.get(1));
        if (!payload.exists()) {
            out.error("payload: no such file: " + payload.getAbsolutePath());
            return false;
        }
        NativeTools.Tool tool = NativeTools.find("payload-dumper-go");
        File executable = NativeTools.executable(context, tool);
        if (executable == null) {
            out.error("payload: payload-dumper-go is not installed");
            return false;
        }
        List<String> command = new ArrayList<>();
        if ("list".equals(action)) {
            command.add("list");
            command.add(payload.getAbsolutePath());
        } else if ("extract".equals(action)) {
            command.add("extract");
            command.add(payload.getAbsolutePath());
            File outDir = args.size() > 2 ? resolve(workingDir, args.get(2))
                    : new File(workingDir, "output");
            command.add("--out=" + outDir.getAbsolutePath());
            if (args.size() > 3) {
                command.add("--partitions=" + args.get(3));
            } else {
                // without a list the tool asks in a menu, which a terminal cannot show
                command.add("--all");
            }
            out.println("[payload] extracting into " + outDir.getAbsolutePath());
        } else {
            out.error("payload: unknown action '" + action + "' (list or extract)");
            return false;
        }
        try {
            int code = NativeRunner.run(context, executable, command, workingDir, out, null);
            if (code != 0) {
                out.error("payload: payload-dumper-go failed with " + code);
                return false;
            }
            return true;
        } catch (Exception e) {
            out.error("payload: " + e.getMessage());
            return false;
        }
    }

    /** The name of the partition, the extension of the file is not part of it. */
    private static String partitionName(File image) {
        String name = image.getName();
        int dot = name.indexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name;
    }

    /** A relative path is relative to the working directory of the terminal. */
    static File resolve(File workingDir, String path) {
        File file = new File(path);
        if (file.isAbsolute() || workingDir == null) return file;
        return new File(workingDir, path);
    }

    /** Reads a size, a plain number of bytes or a number with a unit (K, M, G). */
    static long parseSize(String value) {
        if (value == null) return 0;
        String text = value.trim().toLowerCase(Locale.US);
        long factor = 1;
        if (text.endsWith("k")) {
            factor = 1024L;
        } else if (text.endsWith("m")) {
            factor = 1024L * 1024L;
        } else if (text.endsWith("g")) {
            factor = 1024L * 1024L * 1024L;
        }
        text = text.replaceAll("[^0-9]", "");
        if (text.isEmpty()) return 0;
        try {
            return Long.parseLong(text) * factor;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
