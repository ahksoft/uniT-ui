package com.unitool.cli;

import android.content.Context;

import com.unitool.protocol.mtk.MtkProtocol;
import com.unitool.terminal.TerminalOutput;
import com.unitool.usb.UsbDeviceInfo;
import com.unitool.usb.UsbHostManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code mtkclient} command of the terminal.
 *
 * <p>Mirrors the command line of the reference tool
 * ({@code UniTool/MTK/mtkclient/mtk.py}): a sub command plus options. The reference
 * implementation is the source of truth for the command and option names, so that the
 * same commands can be used with both tools.
 */
public class MtkCli {

    private static final String TAG = "MtkCli";

    /** Time the connect command waits for a device, in seconds. */
    private static final long DEFAULT_CONNECT_TIMEOUT = 60;

    private final Context context;
    private final UsbHostManager usbHostManager;
    private final MtkProtocol mtkProtocol;
    private final TerminalOutput out;

    /** Remembers the last waiting message, so it is not printed twice. */
    private int lastWaitingSeconds = -1;

    public MtkCli(Context context, UsbHostManager usbHostManager,
                  MtkProtocol mtkProtocol, TerminalOutput out) {
        this.context = context.getApplicationContext();
        this.usbHostManager = usbHostManager;
        this.mtkProtocol = mtkProtocol;
        this.out = out;
    }

    // ------------------------------------------------------------------
    // entry point
    // ------------------------------------------------------------------

    public void run(List<String> args) {
        if (args == null || args.isEmpty()) {
            usage();
            return;
        }

        String cmd = args.get(0).toLowerCase();
        MtkCliOptions options = MtkCliOptions.parse(args.subList(1, args.size()));

        switch (cmd) {
            case "help":
            case "--help":
            case "-h":
                usage();
                break;
            case "devices":
                cmdDevices();
                break;
            case "connect":
            case "brom":
                cmdConnect(options, true);
                break;
            case "info":
                cmdInfo();
                break;
            case "handshake":
                cmdConnect(options, false);
                break;
            case "targetconfig":
            case "gettargetconfig":
                cmdTargetConfig(options);
                break;
            case "meid":
                cmdMeId(options);
                break;
            case "disconnect":
                cmdDisconnect();
                break;
            case "da":
                cmdDa(options);
                break;
            case "daparse":
            case "da-parse":
                cmdDaParse(options);
                break;
            case "printgpt":
            case "gpt":
                cmdGpt();
                break;
            case "r":
            case "read":
                cmdRead(options);
                break;
            case "w":
            case "write":
                cmdWrite(options);
                break;
            case "e":
            case "erase":
                cmdErase(options);
                break;
            case "reset":
                cmdReset(options);
                break;
            case "crash":
                cmdCrash(options);
                break;
            case "peek":
                cmdPeek(options);
                break;
            case "payload":
                cmdPayload(options);
                break;
            case "stage":
            case "boot":
            case "plstage":
                cmdBoot(options);
                break;
            case "log":
            case "logs":
                cmdLog(options);
                break;
            case "meta":
            case "meta2":
                cmdMeta(options);
                break;
            case "daa":
            case "auth":
                cmdAuth(options);
                break;
            case "kamakiri":
                cmdKamakiri(options);
                break;
            case "dumpbrom":
            case "dumppreloader":
            case "dumpsram":
                cmdDump(options, cmd);
                break;
            case "brute":
                cmdBrute(options);
                break;
            case "script":
                cmdScript(options);
                break;
            case "multi":
                cmdMulti(options);
                break;
            default:
                out.error("mtkclient: unknown command '" + cmd + "'");
                out.println("");
                usage();
                break;
        }
    }

    // ------------------------------------------------------------------
    // commands
    // ------------------------------------------------------------------

    private void cmdDevices() {
        usbHostManager.rescan();
        List<UsbDeviceInfo> devices = usbHostManager.listDevices();
        if (devices.isEmpty()) {
            out.println("No USB devices attached.");
            return;
        }
        out.println("Attached USB devices:");
        for (UsbDeviceInfo info : devices) {
            StringBuilder line = new StringBuilder();
            line.append(String.format("  %s  %-28s", info.getVidPidHex(), info.getDisplayName()));
            if (info.isMediaTek()) {
                line.append("MTK ").append(info.getMtkMode());
            } else if (info.isSpreadtrum()) {
                line.append("SPD ").append(info.detectMode());
            } else {
                line.append("unknown");
            }
            line.append(usbHostManager.hasPermission(info.device)
                    ? "  [permission granted]" : "  [no permission]");
            out.println(line.toString());
        }
    }

    private void cmdConnect(MtkCliOptions options, boolean full) {
        Map<String, String> flags = new LinkedHashMap<>();
        if (options.getBoolean("debug") || options.getBoolean("debugmode")) flags.put("debug", "");
        if (options.getBoolean("skipwdt")) flags.put("skipwdt", "");
        String device = deviceSelector(options);
        if (device != null) flags.put("device", device);
        String loader = options.get("loader");
        if (loader != null) flags.put("loader", resolvePath(loader));
        String daAddress = options.get("da-address");
        if (daAddress == null) daAddress = options.get("da_address");
        if (daAddress != null) flags.put("da_address", daAddress);

        // the command waits for a device, the default is one minute
        long timeout = options.getNumber("timeout", DEFAULT_CONNECT_TIMEOUT);
        if (timeout < 0) timeout = 0;
        lastWaitingSeconds = -1;

        out.println("[mtkclient] looking for a MediaTek device...");
        Map<String, Object> result = mtkProtocol.connect(flags, timeout * 1000L,
                remaining -> reportWaiting(remaining));
        Object status = result.get("status");
        if (!"connected".equals(status)) {
            out.error("[mtkclient] " + result.get("msg"));
            printTrace(result.get("trace"));
            return;
        }
        out.println("[mtkclient] handshake succeeded");
        printInfo(result);
        if (options.getBoolean("debug")) {
            printTrace(result.get("trace"));
        }
        if (!full) {
            out.println("[mtkclient] done");
        }
    }

    /**
     * Prints the state of the connect command while it waits for a device.
     *
     * <p>The message is repeated every ten seconds, so the terminal is not flooded.
     */
    private void reportWaiting(long remainingMs) {
        int seconds = (int) ((remainingMs + 999) / 1000);
        if (seconds == lastWaitingSeconds) return;
        if (lastWaitingSeconds >= 0 && seconds % 10 != 0) return;
        lastWaitingSeconds = seconds;
        out.println("[mtkclient] waiting for a MediaTek device (" + seconds + " s left)...");
    }

    private void cmdInfo() {
        Map<String, Object> info = mtkProtocol.getInfo();
        if (info.isEmpty()) {
            out.println("Not connected - run 'mtkclient connect' first.");
            return;
        }
        printInfo(info);
    }

    private void cmdTargetConfig(MtkCliOptions options) {
        Map<String, Object> result = ensureConnection(options);
        if (result == null) return;
        out.println("[mtkclient] target config: "
                + result.get("targetConfigHex") + " (" + result.get("targetConfig") + ")");
        out.println("[mtkclient] daa required: " + result.get("daa")
                + ", sla required: " + result.get("sla"));
    }

    private void cmdMeId(MtkCliOptions options) {
        Map<String, Object> result = ensureConnection(options);
        if (result == null) return;
        if (mtkProtocol.getBromHandler() == null) {
            out.error("[mtkclient] no connection");
            return;
        }
        mtkProtocol.getBromHandler().getMeId();
        byte[] meid = mtkProtocol.getBromHandler().readMeId();
        if (meid == null) {
            out.error("[mtkclient] MEID not available");
            return;
        }
        out.println("[mtkclient] MEID: " + bytesToHex(meid));
    }

    private void cmdDisconnect() {
        mtkProtocol.disconnect();
        out.println("[mtkclient] disconnected");
    }

    private void cmdDa(MtkCliOptions options) {
        String loader = options.get("loader");
        if (loader == null) {
            out.error("[mtkclient] 'da' needs a download agent: --loader <file>");
            return;
        }
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("loader", resolvePath(loader));
        String daAddress = options.get("da-address");
        if (daAddress == null) daAddress = options.get("da_address");
        if (daAddress != null) flags.put("da_address", daAddress);
        if (options.getBoolean("debug")) flags.put("debug", "");

        Map<String, Object> result = mtkProtocol.connect(flags);
        if (!"connected".equals(result.get("status"))) {
            out.error("[mtkclient] " + result.get("msg"));
            return;
        }
        Object loaderInfo = result.get("daLoader");
        if (loaderInfo != null) out.println("[mtkclient] loader : " + loaderInfo);
        if (Boolean.TRUE.equals(result.get("da"))) {
            out.println("[mtkclient] download agent uploaded and started");
        } else {
            out.error("[mtkclient] DA upload failed: " + result.get("daError"));
        }
        if (options.getBoolean("debug")) {
            Object trace = result.get("trace");
            if (trace instanceof java.util.List) {
                for (Object step : (java.util.List<?>) trace) out.println("  " + step);
            }
        }
        out.println("[mtkclient] note: the read/write/gpt commands of the download agent are "
                + "not implemented in the Android port yet");
    }

    /** Shows what the app reads out of a loader file, this needs no device. */
    private void cmdDaParse(MtkCliOptions options) {
        String path = options.get("loader");
        if (path == null) path = firstPositional(options, "file");
        if (path == null) {
            out.error("[mtkclient] daparse needs a loader file");
            return;
        }
        String resolved = resolvePath(path);
        java.io.File file = new java.io.File(resolved);
        if (!file.isFile()) {
            out.error("[mtkclient] no such file: " + resolved);
            return;
        }
        try {
            byte[] data = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int read = 0;
                while (read < data.length) {
                    int count = in.read(data, read, data.length - read);
                    if (count < 0) break;
                    read += count;
                }
            }
            java.util.List<com.unitool.protocol.mtk.DaFile> files =
                    com.unitool.protocol.mtk.DaFile.parse(data);
            out.println("[mtkclient] " + file.getName() + " holds " + files.size() + " entries");
            int index = 0;
            for (com.unitool.protocol.mtk.DaFile loader : files) {
                out.println("  #" + index++ + " hw=0x" + Integer.toHexString(loader.hwCode)
                        + " sub=0x" + Integer.toHexString(loader.hwSubCode)
                        + " ver=0x" + Integer.toHexString(loader.hwVersion)
                        + " sw=" + loader.swVersion + (loader.version6 ? " v6" : ""));
                int region = 0;
                for (com.unitool.protocol.mtk.DaFile.Region part : loader.regions) {
                    out.println("     region " + region++ + " buf=0x" + Integer.toHexString(part.buf)
                            + " len=0x" + Integer.toHexString(part.len)
                            + " addr=0x" + Integer.toHexString(part.address)
                            + " sig=0x" + Integer.toHexString(part.signatureLength));
                }
                com.unitool.protocol.mtk.DaFile.Region stage1 = loader.stage1();
                if (stage1 != null) {
                    out.println("     stage 1 -> address 0x" + Integer.toHexString(stage1.address)
                            + ", " + stage1.len + " bytes");
                }
            }
        } catch (Exception e) {
            out.error("[mtkclient] " + e.getMessage());
        }
    }

    private void cmdGpt() {
        notImplemented("printgpt", "reading the GPT needs the download agent protocol");
    }

    private void cmdRead(MtkCliOptions options) {
        String partition = firstPositional(options, "partitionname");
        String filename = options.get("filename");
        if (filename == null && options.positional().size() > 1) {
            filename = options.positional().get(1);
        }
        out.println("[mtkclient] read " + (partition != null ? partition : "?")
                + (filename != null ? " -> " + filename : ""));
        notImplemented("r", "reading a partition needs the download agent protocol");
    }

    private void cmdWrite(MtkCliOptions options) {
        String partition = firstPositional(options, "partitionname");
        String filename = options.get("filename");
        if (filename == null && options.positional().size() > 1) {
            filename = options.positional().get(1);
        }
        out.println("[mtkclient] write " + (filename != null ? filename : "?")
                + " -> " + (partition != null ? partition : "?"));
        notImplemented("w", "writing a partition needs the download agent protocol");
    }

    private void cmdErase(MtkCliOptions options) {
        String partition = firstPositional(options, "partitionname");
        out.println("[mtkclient] erase " + (partition != null ? partition : "?"));
        notImplemented("e", "erasing a partition needs the download agent protocol");
    }

    private void cmdReset(MtkCliOptions options) {
        out.println("[mtkclient] reset needs the download agent protocol");
        notImplemented("reset", "resetting the device needs the download agent protocol");
    }

    private void cmdCrash(MtkCliOptions options) {
        out.println("[mtkclient] entering BROM mode is done by the device itself "
                + "(hold volume up/down while plugging in the cable)");
        notImplemented("crash", "the crash sequence needs a device in preloader mode");
    }

    private void cmdPeek(MtkCliOptions options) {
        long address = options.getNumber("address", 0);
        long length = options.getNumber("length", 0);
        out.println("[mtkclient] peek 0x" + Long.toHexString(address)
                + " (" + length + " bytes)");
        notImplemented("peek", "peeking memory needs the download agent protocol");
    }

    private void cmdPayload(MtkCliOptions options) {
        String payload = options.get("payload");
        out.println("[mtkclient] payload " + (payload != null ? payload : "<default>"));
        notImplemented("payload", "sending a payload needs a device in preloader mode");
    }

    private void cmdBoot(MtkCliOptions options) {
        String payload = options.get("payload");
        String partition = options.get("startpartition");
        out.println("[mtkclient] boot "
                + (partition != null ? partition : (payload != null ? payload : "<payload>")));
        notImplemented("boot", "booting needs a device in preloader mode");
    }

    private void cmdLog(MtkCliOptions options) {
        out.println("[mtkclient] dumping the BROM log");
        notImplemented("log", "the BROM log can only be read in preloader mode");
    }

    private void cmdMeta(MtkCliOptions options) {
        String mode = options.get("metamode");
        out.println("[mtkclient] meta mode " + (mode != null ? mode : "<not given>"));
        notImplemented("meta", "meta mode needs a device in preloader mode");
    }

    private void cmdAuth(MtkCliOptions options) {
        out.println("[mtkclient] SLA/DAA bypass needs the security certificate "
                + "(--cert) and is not implemented in the Android port");
    }

    private void cmdKamakiri(MtkCliOptions options) {
        out.println("[mtkclient] kamakiri (MTK6789) is not implemented in the Android port");
    }

    private void cmdDump(MtkCliOptions options, String cmd) {
        String filename = options.get("filename");
        out.println("[mtkclient] " + cmd + (filename != null ? " -> " + filename : ""));
        notImplemented(cmd, "dumping the " + cmd.substring(4)
                + " needs a device in preloader mode");
    }

    private void cmdBrute(MtkCliOptions options) {
        out.println("[mtkclient] brute forcing the SLA/DAA authentication needs the "
                + "security certificate");
        notImplemented("brute", "the SLA brute force is not implemented in the Android port");
    }

    private void cmdScript(MtkCliOptions options) {
        String script = options.get("script");
        if (script == null) {
            out.error("[mtkclient] 'script' needs --script <file>");
            return;
        }
        File file = new File(resolvePath(script));
        if (!file.exists()) {
            out.error("[mtkclient] script not found: " + file.getAbsolutePath());
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                out.println("$ " + line);
                run(tokenize(line));
            }
        } catch (Exception e) {
            out.error("[mtkclient] " + e.getMessage());
        }
    }

    private void cmdMulti(MtkCliOptions options) {
        String commands = options.get("commands");
        if (commands == null) {
            out.error("[mtkclient] 'multi' needs --commands \"cmd1;cmd2\"");
            return;
        }
        for (String command : commands.split(";")) {
            command = command.trim();
            if (command.isEmpty()) continue;
            out.println("$ " + command);
            run(tokenize(command));
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Makes sure a device is connected, connects on demand. */
    private Map<String, Object> ensureConnection(MtkCliOptions options) {
        if (mtkProtocol.isConnected()) {
            return mtkProtocol.getInfo();
        }
        Map<String, String> flags = new LinkedHashMap<>();
        if (options.getBoolean("debug")) flags.put("debug", "");
        String device = options.get("device");
        if (device != null) flags.put("device", device);
        Map<String, Object> result = mtkProtocol.connect(flags);
        if (!"connected".equals(result.get("status"))) {
            out.error("[mtkclient] " + result.get("msg"));
            return null;
        }
        return result;
    }

    private String firstPositional(MtkCliOptions options, String optionName) {
        String value = options.get(optionName);
        if (value != null) return value;
        if (!options.positional().isEmpty()) return options.positional().get(0);
        return null;
    }

    /**
     * Builds the "vid:pid" selector of a device.
     *
     * <p>Either {@code --device 0e8d:0003} or the pair {@code --vid 0e8d --pid 0003}
     * can be used, the latter is the syntax of the reference tool.
     */
    private String deviceSelector(MtkCliOptions options) {
        String device = options.get("device");
        if (device != null && !device.isEmpty()) return device;
        String vid = options.get("vid");
        String pid = options.get("pid");
        if (vid != null && pid != null) {
            return String.format("%04x:%04x", options.getHex("vid", 0), options.getHex("pid", 0));
        }
        if (vid != null) return String.format("%04x", options.getHex("vid", 0));
        return null;
    }

    private void printInfo(Map<String, Object> info) {
        out.println("  device        : " + info.get("device") + "  " + info.get("name"));
        out.println("  mtk mode      : " + info.get("mtkMode"));
        out.println("  protocol mode : " + info.get("mode"));
        out.println("  hw code       : " + info.get("hwCodeHex") + " ("
                + info.get("hwCode") + ")");
        out.println("  hw sub code   : " + info.get("hwSubCode"));
        out.println("  hw version    : " + info.get("hwVersion"));
        out.println("  sw version    : " + info.get("swVersion"));
        out.println("  bl version    : " + info.get("blVersion"));
        out.println("  brom version  : " + info.get("bromVersion"));
        out.println("  target config : " + info.get("targetConfigHex")
                + "  daa=" + info.get("daa") + " sla=" + info.get("sla"));
    }

    private void printTrace(Object trace) {
        if (!(trace instanceof List)) return;
        for (Object line : (List<?>) trace) {
            out.println("    " + line);
        }
    }

    private void notImplemented(String cmd, String reason) {
        out.println("[mtkclient] '" + cmd + "' is not available: " + reason + ".");
        out.println("            The Android port implements the BROM layer, the DA/flash "
                + "commands live in the");
        out.println("            bundled Python tool (UniTool/MTK/mtkclient/mtk.py).");
    }

    private String resolvePath(String path) {
        if (path == null) return null;
        File file = new File(path);
        if (file.exists()) return file.getAbsolutePath();
        File inFiles = new File(context.getFilesDir(), path);
        if (inFiles.exists()) return inFiles.getAbsolutePath();
        File inAssets = new File(context.getFilesDir(), "loaders/" + path);
        if (inAssets.exists()) return inAssets.getAbsolutePath();
        return path;
    }

    private static List<String> tokenize(String line) {
        return com.unitool.terminal.CommandLine.tokenize(line);
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // help
    // ------------------------------------------------------------------

    public void usage() {
        out.println("mtkclient - MediaTek device tool (Android port)");
        out.println("");
        out.println("usage: mtkclient <command> [options]");
        out.println("");
        out.println("commands:");
        out.println("  devices            list the attached USB devices");
        out.println("  connect            detect a MediaTek device and run the BROM handshake");
        out.println("                      waits up to 60 s for a device to be connected");
        out.println("  brom               connect in BROM mode (alias of connect)");
        out.println("  handshake          run the handshake only and print the trace");        out.println("  info               show the information of the current connection");
        out.println("  targetconfig       read the target configuration");
        out.println("  meid               read the MEID");
        out.println("  disconnect         close the connection");
        out.println("  da                 upload and start a download agent (--loader)");
        out.println("  printgpt           print the GPT partition table      [DA mode]");
        out.println("  r <part> [file]    read a partition                    [DA mode]");
        out.println("  w <part> <file>    write a partition                   [DA mode]");
        out.println("  e <part>           erase a partition                   [DA mode]");
        out.println("  reset              reset the device                    [DA mode]");
        out.println("  crash              enter BROM mode                    [preloader]");
        out.println("  peek               read memory  (--address --length)  [DA mode]");
        out.println("  payload            send a payload                     [preloader]");
        out.println("  boot               boot to a partition or payload     [preloader]");
        out.println("  log                dump the BROM log                  [preloader]");
        out.println("  meta               set meta mode                      [preloader]");
        out.println("  daa / auth         SLA/DAA bypass");
        out.println("  kamakiri           kamakiri exploit (MTK6789)");
        out.println("  dumpbrom           dump the BROM binary            [preloader]");
        out.println("  dumppreloader      dump the preloader binary       [preloader]");
        out.println("  brute              brute force the SLA/DAA auth");
        out.println("  script             run commands from a file  (--script <file>)");
        out.println("  multi              run ';' separated commands (--commands \"a;b\")");
        out.println("");
        out.println("options:");
        out.println("  --device <vid:pid>  use a specific device");
        out.println("  --timeout <sec>     time the connect command waits for a device (60)");
        out.println("  --loader <file>     download agent / preloader to use");
        out.println("  --da-address <adr>  load address of the download agent");
        out.println("  --debug             verbose protocol logging");
        out.println("  --skipwdt           do not disable the watchdog timer");
        out.println("  --offset <hex>      offset for read/write");
        out.println("  --length <hex>      length for read/write");
        out.println("  --filename <file>   file to read into / write from");
        out.println("  --partitionname <p> partition name");
        out.println("  --parttype <type>   partition type");
        out.println("  --address <hex>     memory address");
        out.println("  --payload <file>    payload file");
        out.println("  --script <file>     script with commands");
        out.println("  --commands <list>   ';' separated commands");
        out.println("  --metamode <mode>   meta mode");
        out.println("  --sector <hex>      sector number");
        out.println("  --sectors <hex>     sector count");
        out.println("  --cert <file>       certificate for SLA/DAA");
        out.println("  --auth <file>       auth file for SLA/DAA");
        out.println("  --loglevel <n>      log level");
        out.println("");
        out.println("examples:");
        out.println("  mtkclient devices");
        out.println("  mtkclient connect --debug");
        out.println("  mtkclient connect --loader /sdcard/MTK_DA_V6.bin --da-address 0x40001000");
        out.println("  mtkclient multi --commands \"connect;info\"");
    }
}
