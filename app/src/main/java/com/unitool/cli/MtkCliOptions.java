package com.unitool.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Option parser for the {@code mtkclient} command line.
 *
 * <p>Accepts the same syntax as the reference tool (argparse):
 * <ul>
 *   <li>{@code --option value}</li>
 *   <li>{@code --option=value}</li>
 *   <li>{@code --flag} (boolean, becomes "true")</li>
 *   <li>{@code -x} short flags</li>
 *   <li>{@code --} ends the option list, everything behind it is positional</li>
 * </ul>
 *
 * <p>Unknown options are kept instead of being rejected, the reference tool behaves the
 * same way ({@code parse_known_args}).
 */
public class MtkCliOptions {

    /**
     * Options that are flags in the reference tool (argparse {@code action="store_true"}).
     *
     * <p>They never take a value, so they must not swallow the next token.
     */
    private static final java.util.Set<String> BOOLEAN_FLAGS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "stock", "daa", "brom", "skipwdt", "readsocid", "preloader-da",
                    "debug", "debugmode", "noreconnect", "generatekeys", "crash",
                    "disable-internal-flash", "verifystage2"));

    private final Map<String, String> values = new LinkedHashMap<>();
    private final List<String> positional = new ArrayList<>();

    public static MtkCliOptions parse(List<String> args) {
        MtkCliOptions options = new MtkCliOptions();
        if (args == null) return options;

        boolean optionsEnded = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);

            if (optionsEnded) {
                options.positional.add(arg);
                continue;
            }

            if ("--".equals(arg)) {
                optionsEnded = true;
                continue;
            }

            if (arg.startsWith("--")) {
                String body = arg.substring(2);
                if (body.isEmpty()) continue;
                int eq = body.indexOf('=');
                if (eq >= 0) {
                    options.values.put(normalize(body.substring(0, eq)), body.substring(eq + 1));
                } else if (isFlag(body) || i + 1 >= args.size() || isOption(args.get(i + 1))) {
                    options.values.put(normalize(body), "true");
                } else {
                    options.values.put(normalize(body), args.get(i + 1));
                    i++;
                }
                continue;
            }

            if (arg.startsWith("-") && arg.length() > 1) {
                String body = arg.substring(1);
                int eq = body.indexOf('=');
                if (eq >= 0) {
                    options.values.put(normalize(body.substring(0, eq)), body.substring(eq + 1));
                } else if (i + 1 < args.size() && !isOption(args.get(i + 1))) {
                    options.values.put(normalize(body), args.get(i + 1));
                    i++;
                } else {
                    options.values.put(normalize(body), "true");
                }
                continue;
            }

            options.positional.add(arg);
        }
        return options;
    }

    private static boolean isOption(String arg) {
        return arg.startsWith("-") && arg.length() > 1;
    }

    /** True for the options that are flags in the reference tool. */
    private static boolean isFlag(String key) {
        return BOOLEAN_FLAGS.contains(normalize(key));
    }

    private static String normalize(String key) {
        return key.trim().toLowerCase().replace('_', '-');
    }

    public boolean has(String key) {
        return values.containsKey(normalize(key));
    }

    public String get(String key) {
        return values.get(normalize(key));
    }

    public String get(String key, String def) {
        String value = get(key);
        return value == null ? def : value;
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean def) {
        String value = get(key);
        if (value == null) return def;
        value = value.trim().toLowerCase();
        if (value.isEmpty()) return true;
        return value.equals("1") || value.equals("true") || value.equals("yes") || value.equals("on");
    }

    /** Parses decimal or hexadecimal (0x prefixed) numbers. */
    public long getNumber(String key, long def) {
        String value = get(key);
        if (value == null) return def;
        try {
            String v = value.trim();
            if (v.startsWith("0x") || v.startsWith("0X")) {
                return Long.parseLong(v.substring(2), 16);
            }
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public int getInt(String key, int def) {
        return (int) getNumber(key, def);
    }

    /**
     * Parses a hexadecimal number.
     *
     * <p>The {@code 0x} prefix is optional. The reference tool parses the vendor and
     * product id as hexadecimal, so they use this getter.
     */
    public long getHex(String key, long def) {
        String value = get(key);
        if (value == null) return def;
        try {
            String v = value.trim();
            if (v.startsWith("0x") || v.startsWith("0X")) v = v.substring(2);
            return Long.parseLong(v, 16);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public List<String> positional() {
        return positional;
    }

    public Map<String, String> values() {
        return values;
    }
}
