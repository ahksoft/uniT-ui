package com.unitool.lk;

import com.unitool.terminal.TerminalOutput;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code ubl} command: patches the LK bootloader of a Transsion phone (Tecno, Infinix,
 * itel) so that the bootloader can be unlocked.
 *
 * <pre>
 *   ubl &lt;lk.bin&gt; [--analyze] [-o out] [--mode wrap-override|wrap|override]
 *   ubl pull [lk_a] [--da file] [--out dir]
 *   ubl flash &lt;image&gt; [lk_a] [--da file]
 *   ubl all [lk_a] [--da file]
 * </pre>
 */
public final class UblCommand {
    /** Where the download agent is looked for, the first one that exists is used. */
    public static final String DEFAULT_DA = "/sdcard/UniTool/da.bin";
    private static final String[] DA_FILES = {
            "/sdcard/UniTool/da.bin",
            "/sdcard/UniTool/da.bin".replace("/sdcard/", "/storage/emulated/0/"),
            "/sdcard/da.bin",
    };

    private UblCommand() {
    }

    /** Runs the command, {@code run} starts a native tool and gives back its exit code. */
    public interface ToolRunner {
        int run(String tool, List<String> args, TerminalOutput out);
    }

    public static void run(List<String> args, File workingDir, ToolRunner runner, TerminalOutput out) {
        if (args.isEmpty()) {
            usage(out);
            return;
        }
        String action = args.get(0).toLowerCase();
        switch (action) {
            case "pull":
                pull(args.subList(1, args.size()), workingDir, runner, out);
                return;
            case "flash":
                flash(args.subList(1, args.size()), workingDir, runner, out);
                return;
            case "all":
            case "auto":
                pullAndFlash(args.subList(1, args.size()), workingDir, runner, out);
                return;
            case "-h":
            case "--help":
            case "help":
                usage(out);
                return;
            default:
                patch(args, workingDir, out);
        }
    }

    private static void usage(TerminalOutput out) {
        out.println("ubl - unlock the bootloader of a Transsion phone (Tecno, Infinix, itel)");
        out.println("");
        out.println("  ubl <lk.bin> [options]     patch an lk image of the phone");
        out.println("  ubl pull [partition]       read the bootloader from the phone");
        out.println("  ubl flash <image> [part]   write a patched bootloader back");
        out.println("  ubl all [partition]        pull, patch and flash in one go");
        out.println("");
        out.println("options:");
        out.println("  --analyze         only show what would be patched");
        out.println("  -o <file>         where the patched image is written");
        out.println("  --mode <mode>     certificate mode: wrap-override (default), wrap, override");
        out.println("  --da <file>       download agent for the device (" + DEFAULT_DA + ")");
        out.println("  --out <dir>       directory for pull and all (default: the working directory)");
        out.println("  --dry-run         do not write the image");
        out.println("");
        out.println("the partition is lk_a on most phones, lk on older ones");
    }

    // ------------------------------------------------------------------
    // image patching
    // ------------------------------------------------------------------

    private static void patch(List<String> args, File workingDir, TerminalOutput out) {
        String path = null;
        LkPatch.Options options = new LkPatch.Options();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--analyze":
                case "-a":
                    options.analyze = true;
                    break;
                case "--dry-run":
                    options.dryRun = true;
                    break;
                case "--no-resign":
                    options.resign = false;
                    break;
                case "-o":
                case "--output":
                    if (i + 1 < args.size()) options.output = resolve(workingDir, args.get(++i));
                    break;
                case "--mode":
                case "--cert-mode":
                    if (i + 1 < args.size()) options.certMode = args.get(++i);
                    break;
                case "-v":
                case "--verbose":
                    options.verbose = true;
                    break;
                default:
                    if (path == null && !arg.startsWith("-")) path = arg;
                    break;
            }
        }
        if (path == null) {
            out.error("ubl: missing the lk image");
            return;
        }
        File image = new File(resolve(workingDir, path));
        if (!image.isFile()) {
            out.error("ubl: no such file: " + image.getAbsolutePath());
            return;
        }
        boolean valid = false;
        for (String mode : LkPatch.CERT_MODES) {
            if (mode.equals(options.certMode)) valid = true;
        }
        if (!valid) {
            out.error("ubl: unknown certificate mode '" + options.certMode + "'");
            return;
        }
        LkPatch.Result result = LkPatch.run(image, options, new LkPatch.Output() {
            @Override
            public void println(String text) {
                out.println(text);
            }

            @Override
            public void error(String text) {
                out.error(text);
            }
        });
        if (!result.ok) {
            out.error("ubl: " + (result.error == null ? "the patch failed" : result.error));
        }
    }

    // ------------------------------------------------------------------
    // the device flow
    // ------------------------------------------------------------------

    private static void pull(List<String> args, File workingDir, ToolRunner runner, TerminalOutput out) {
        String partition = firstPositional(args, "lk_a");
        String da = option(args, "--da", DEFAULT_DA);
        File dir = new File(option(args, "--out", workingDir.getAbsolutePath()));
        File target = new File(dir, partition + ".bin");

        out.println("[ubl] reading " + partition + " from the phone...");
        List<String> command = new ArrayList<>();
        // core mtk/umbrage: read <partition> <output>
        command.add("read");
        command.add(partition);
        command.add(target.getAbsolutePath());
        addDa(command, da);
        if (runner.run("mtk", command, out) != 0) {
            out.error("[ubl] the bootloader could not be read");
            return;
        }
        if (!target.isFile() || target.length() == 0) {
            out.error("[ubl] no image was written");
            return;
        }
        LkPatch.Options options = new LkPatch.Options();
        options.output = new File(dir, partition + ".unlock.img").getAbsolutePath();
        LkPatch.Result result = LkPatch.run(target, options, new LkPatch.Output() {
            @Override
            public void println(String text) {
                out.println(text);
            }

            @Override
            public void error(String text) {
                out.error(text);
            }
        });
        if (result.ok && result.output != null) {
            out.println("[ubl] patched image: " + result.output);
            out.println("[ubl] flash it with: ubl flash " + result.output + " " + partition);
        }
    }

    private static void flash(List<String> args, File workingDir, ToolRunner runner, TerminalOutput out) {
        if (args.isEmpty()) {
            out.error("ubl: flash needs an image");
            return;
        }
        String image = args.get(0);
        String partition = "lk_a";
        for (int i = 1; i < args.size(); i++) {
            if (!args.get(i).startsWith("-")) {
                partition = args.get(i);
                break;
            }
        }
        String da = option(args, "--da", DEFAULT_DA);
        File file = new File(resolve(workingDir, image));
        if (!file.isFile()) {
            out.error("ubl: no such file: " + file.getAbsolutePath());
            return;
        }
        out.println("[ubl] writing " + file.getName() + " to " + partition);
        out.println("[ubl] a wrong image here can stop the phone from starting");
        List<String> command = new ArrayList<>();
        // core mtk/umbrage: write <image> <partition>
        command.add("write");
        command.add(file.getAbsolutePath());
        command.add(partition);
        addDa(command, da);
        if (runner.run("mtk", command, out) != 0) {
            out.error("[ubl] the bootloader was not written");
        } else {
            out.println("[ubl] done, the phone can be started again");
        }
    }

    private static void pullAndFlash(List<String> args, File workingDir, ToolRunner runner,
                                     TerminalOutput out) {
        String partition = firstPositional(args, "lk_a");
        String da = option(args, "--da", DEFAULT_DA);
        File dir = new File(option(args, "--out", workingDir.getAbsolutePath()));
        File pulled = new File(dir, partition + ".bin");

        out.println("[ubl] reading " + partition + " from the phone...");
        List<String> read = new ArrayList<>();
        read.add("read");
        read.add(partition);
        read.add(pulled.getAbsolutePath());
        addDa(read, da);
        if (runner.run("mtk", read, out) != 0 || !pulled.isFile()) {
            out.error("[ubl] the bootloader could not be read");
            return;
        }
        LkPatch.Options options = new LkPatch.Options();
        options.output = new File(dir, partition + ".unlock.img").getAbsolutePath();
        LkPatch.Result result = LkPatch.run(pulled, options, new LkPatch.Output() {
            @Override
            public void println(String text) {
                out.println(text);
            }

            @Override
            public void error(String text) {
                out.error(text);
            }
        });
        if (!result.ok) {
            out.error("[ubl] the patch failed, nothing was written to the phone");
            return;
        }
        File patched = new File(result.output);
        out.println("[ubl] writing the patched bootloader back...");
        List<String> write = new ArrayList<>();
        write.add("write");
        write.add(patched.getAbsolutePath());
        write.add(partition);
        addDa(write, da);
        if (runner.run("mtk", write, out) != 0) {
            out.error("[ubl] the patched bootloader was not written");
            out.println("[ubl] the original image is at " + pulled.getAbsolutePath());
            return;
        }
        out.println("[ubl] done, start the phone and try: fastboot flashing unlock");
    }

    private static void addDa(List<String> command, String da) {
        String found = findDa(da);
        if (found == null) return;
        command.add("--da");
        command.add(found);
    }

    /** The download agent to use, {@code null} when there is none. */
    private static String findDa(String wanted) {
        if (wanted != null && !wanted.isEmpty()) {
            File file = new File(wanted);
            if (file.isFile()) return file.getAbsolutePath();
            if (!wanted.equals(DEFAULT_DA)) return null;
        }
        for (String path : DA_FILES) {
            File file = new File(path);
            if (file.isFile()) return file.getAbsolutePath();
        }
        return null;
    }

    private static String firstPositional(List<String> args, String fallback) {
        for (String arg : args) {
            if (!arg.startsWith("-")) return arg;
        }
        return fallback;
    }

    /** The value of an option that takes one argument. */
    private static String option(List<String> args, String name, String fallback) {
        for (int i = 0; i < args.size() - 1; i++) {
            if (args.get(i).equals(name)) return args.get(i + 1);
        }
        return fallback;
    }

    private static String resolve(File workingDir, String path) {
        File file = new File(path);
        return file.isAbsolute() ? path : new File(workingDir, path).getAbsolutePath();
    }
}
