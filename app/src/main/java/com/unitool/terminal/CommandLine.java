package com.unitool.terminal;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal shell style tokenizer.
 *
 * <p>Supports single quotes, double quotes, backslash escapes and splits a line into
 * several commands when a {@code ;} is used outside of quotes.
 */
public final class CommandLine {

    private CommandLine() {
    }

    /** Splits a line into tokens, honouring quotes and escapes. */
    public static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        if (line == null) return tokens;

        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        char quote = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < line.length()) {
                    current.append(line.charAt(++i));
                } else {
                    current.append(c);
                }
                continue;
            }

            switch (c) {
                case '\'':
                case '"':
                    quote = c;
                    inToken = true;
                    break;
                case '\\':
                    if (i + 1 < line.length()) {
                        current.append(line.charAt(++i));
                        inToken = true;
                    }
                    break;
                case ' ':
                case '\t':
                    if (inToken) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        inToken = false;
                    }
                    break;
                default:
                    current.append(c);
                    inToken = true;
                    break;
            }
        }

        if (inToken) tokens.add(current.toString());
        return tokens;
    }

    /** Splits a line into single commands, using {@code ;} as separator. */
    public static List<String> splitCommands(String line) {
        List<String> commands = new ArrayList<>();
        if (line == null) return commands;

        StringBuilder current = new StringBuilder();
        char quote = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == ';') {
                commands.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        commands.add(current.toString());
        return commands;
    }
}
