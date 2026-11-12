package com.unitool.terminal;

/**
 * Removes the escape sequences of a terminal from the output of a program.
 *
 * <p>The programs are started in a pseudo terminal, so they may use colors and they may move
 * the cursor. The terminal of the app shows plain text, the sequences are taken out. A
 * carriage return becomes a line break, a program that shows a progress bar writes a line
 * for every step.
 */
public final class AnsiFilter {

    private static final int NORMAL = 0;
    private static final int ESCAPE = 1;
    private static final int CSI = 2;
    private static final int OSC = 3;
    private static final int OSC_ESCAPE = 4;
    private static final int STRING = 5;
    private static final int STRING_ESCAPE = 6;
    private static final int CHARSET = 7;

    private int state = NORMAL;
    private boolean pendingReturn;

    /**
     * Takes a piece of the output of the program.
     *
     * @return the text that can be shown in the terminal
     */
    public String feed(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (state) {
                case NORMAL:
                    if (c == 0x1b) {
                        state = ESCAPE;
                    } else if (c == '\r') {
                        // the terminal writes a carriage return before the line feed
                        pendingReturn = true;
                    } else {
                        if (pendingReturn) {
                            // the carriage return was a line break on its own
                            pendingReturn = false;
                            if (c == '\n') {
                                // the line feed belongs to the same line break
                                out.append('\n');
                                break;
                            }
                            out.append('\n');
                        }
                        out.append(c);
                    }
                    break;
                case ESCAPE:
                    switch (c) {
                        case '[':
                            state = CSI;
                            break;
                        case ']':
                            state = OSC;
                            break;
                        case 'P':
                        case '^':
                        case '_':
                            state = STRING;
                            break;
                        case '(':
                        case ')':
                        case '*':
                        case '+':
                            state = CHARSET;
                            break;
                        default:
                            // a short sequence, only the escape itself is dropped
                            state = NORMAL;
                            break;
                    }
                    break;
                case CSI:
                    // the sequence ends with a byte between 0x40 and 0x7e
                    if (c >= 0x40 && c < 0x7f) state = NORMAL;
                    break;
                case OSC:
                    if (c == 0x07) {
                        state = NORMAL;
                    } else if (c == 0x1b) {
                        state = OSC_ESCAPE;
                    }
                    break;
                case OSC_ESCAPE:
                    state = c == '\\' ? NORMAL : OSC;
                    break;
                case STRING:
                    if (c == 0x1b) state = STRING_ESCAPE;
                    break;
                case STRING_ESCAPE:
                    state = c == '\\' ? NORMAL : STRING;
                    break;
                case CHARSET:
                    state = NORMAL;
                    break;
                default:
                    state = NORMAL;
                    break;
            }
        }
        return out.toString();
    }

    /** The text that is still held back, at the end of the output. */
    public String flush() {
        if (pendingReturn) {
            pendingReturn = false;
            return "\n";
        }
        return "";
    }
}
