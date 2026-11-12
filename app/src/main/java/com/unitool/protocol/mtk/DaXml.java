package com.unitool.protocol.mtk;

import android.util.Log;

import com.unitool.usb.UsbReader;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

/**
 * Talking to a download agent, the XML protocol of the version 6 loaders.
 *
 * <p>Every message has a header of three little endian words: the magic
 * {@code FEEEEEEF}, the type of the data and its length. Strings carry a zero byte at
 * their end, the length includes it. The agent sends log messages of its own as messages
 * of the type 2, they are written to the terminal.
 *
 * <p>The reference implementation is {@code Library/DA/xmlflash/xml_lib.py}.
 */
public final class DaXml {
    private static final String TAG = "MTK-DA";

    private static final int MAGIC = 0xFEEEEEEF;
    private static final int TYPE_FLOW = 1;
    private static final int TYPE_MESSAGE = 2;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Receives the log messages of the agent. */
    public interface MessageLog {
        void message(String text);
    }

    private final UsbWriter writer;
    private final UsbReader reader;
    private final MessageLog log;
    private final boolean debug;

    /** Sends bytes to the device. */
    public interface UsbWriter {
        int write(byte[] data);
    }

    public DaXml(UsbWriter writer, UsbReader reader, MessageLog log, boolean debug) {
        this.writer = writer;
        this.reader = reader;
        this.log = log;
        this.debug = debug;
    }

    // ------------------------------------------------------------------
    // framing
    // ------------------------------------------------------------------

    /** Sends a message and waits until the device has it. */
    private boolean send(String text) {
        byte[] payload = text.getBytes(UTF8);
        byte[] framed = new byte[payload.length + 1];
        System.arraycopy(payload, 0, framed, 0, payload.length);
        framed[payload.length] = 0;
        return sendFrame(TYPE_FLOW, framed);
    }

    private boolean sendFrame(int type, byte[] payload) {
        ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(MAGIC);
        header.putInt(type);
        header.putInt(payload.length);
        int sent = writer.write(header.array());
        if (sent < 0) {
            Log.e(TAG, "the message header was not sent");
            return false;
        }
        sent = writer.write(payload);
        if (sent < 0) {
            Log.e(TAG, "the message was not sent");
            return false;
        }
        if (debug) Log.d(TAG, "tx " + payload.length + " bytes: " + shorten(payload));
        return true;
    }

    /** Confirms a message the device sent. */
    private void ack() {
        send("OK");
    }

    /**
     * Reads one message of the agent.
     *
     * @return the text of a message of the type flow, {@code null} when the connection is
     *         broken, an empty text when the agent sent log messages only
     */
    private String receive() {
        while (true) {
            byte[] header = reader.read(12, 10000);
            if (header.length < 12) {
                Log.e(TAG, "no answer from the agent (" + header.length + " bytes)");
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int magic = buffer.getInt();
            int type = buffer.getInt();
            int length = buffer.getInt();
            if (magic != MAGIC) {
                Log.e(TAG, "wrong magic 0x" + Integer.toHexString(magic) + " from the agent");
                return null;
            }
            if (type == TYPE_MESSAGE) {
                byte[] extra = reader.read(4, 5000);
                if (extra.length == 4) {
                    length -= 4;
                }
                byte[] text = reader.read(Math.max(0, length), 5000);
                String message = new String(text, UTF8).replace("\u0000", "");
                if (debug) Log.d(TAG, "[DA] " + message);
                if (log != null && !message.trim().isEmpty()) log.message("[DA] " + message);
                continue;
            }
            if (type != TYPE_FLOW) {
                Log.w(TAG, "unknown message type " + type);
                return null;
            }
            byte[] data = reader.read(length, 10000);
            String text = new String(data, UTF8).replace("\u0000", "");
            if (debug) Log.d(TAG, "rx " + length + " bytes: " + shorten(data));
            return text;
        }
    }

    /** Reads the payload of a message, the answer of a command that moves data. */
    public byte[] receiveData() {
        byte[] header = reader.read(12, 10000);
        if (header.length < 12) return new byte[0];
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != MAGIC) return new byte[0];
        int type = buffer.getInt();
        int length = buffer.getInt();
        if (type != TYPE_FLOW) return new byte[0];
        return reader.read(length, 20000);
    }

    // ------------------------------------------------------------------
    // commands
    // ------------------------------------------------------------------

    /**
     * Runs one command of the agent.
     *
     * @return the text of the answer or {@code null} when it failed
     */
    public String command(String xml) {
        if (!send(xml)) return null;
        return receive();
    }

    /**
     * Sends a command and waits for the end of it.
     *
     * <p>The agent answers a command with "OK", then with the result and then with
     * "CMD:END".
     *
     * @return the answer of the command, {@code null} when the agent refused it
     */
    public String simpleCommand(String xml) {
        if (!send(xml)) return null;
        String answer = receive();
        if (answer == null) return null;
        if (!answer.contains("OK")) {
            // a refusal is often the result of the command itself
            if (debug) Log.d(TAG, "answer: " + shorten(answer.getBytes(UTF8)));
            return answer;
        }
        String result = receive();
        if (result == null) return null;
        String end = receive();
        if (end != null && end.contains("CMD:END")) {
            ack();
        }
        return result;
    }

    /** The value of a field of an xml answer. */
    public static String field(String data, String name) {
        if (data == null) return "";
        String open = "<" + name + ">";
        String close = "</" + name + ">";
        int start = data.indexOf(open);
        if (start < 0) return "";
        start += open.length();
        int end = data.indexOf(close, start);
        if (end < 0) return "";
        return data.substring(start, end);
    }

    // ------------------------------------------------------------------
    // commands of the agent
    // ------------------------------------------------------------------

    private static String envelope(String command, String version, String content) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><da><version>" + version
                + "</version><command>CMD:" + command + "</command>"
                + (content == null ? "" : content) + "</da>";
    }

    public String setRuntimeParameter(String logLevel, String logChannel, String systemOs,
                                      boolean initializeDram) {
        String content = "<arg>"
                + "<checksum_level>NONE</checksum_level>"
                + "<battery_exist>AUTO-DETECT</battery_exist>"
                + "<da_log_level>" + logLevel + "</da_log_level>"
                + "<log_channel>" + logChannel + "</log_channel>"
                + "<system_os>" + systemOs + "</system_os>"
                + "</arg><adv><initialize_dram>" + (initializeDram ? "YES" : "NO")
                + "</initialize_dram></adv>";
        return simpleCommand(envelope("SET-RUNTIME-PARAMETER", "1.1", content));
    }

    public String hostSupportedCommands() {
        String content = "<arg><host_capability>CMD:DOWNLOAD-FILE^1@CMD:FILE-SYS-OPERATION^1@"
                + "CMD:PROGRESS-REPORT^1@CMD:UPLOAD-FILE^1@</host_capability></arg>";
        return simpleCommand(envelope("HOST-SUPPORTED-COMMANDS", "1.0", content));
    }

    public String notifyInitHw() {
        return simpleCommand(envelope("NOTIFY-INIT-HW", "1.0", null));
    }

    public String setHostInfo(String info) {
        String content = "<arg><host_info>" + info + "</host_info></arg>";
        return simpleCommand(envelope("SET-HOST-INFO", "1.0", content));
    }

    /** Asks for the partition table, the answer holds the table of the storage. */
    public String readPartitionTable() {
        String content = "<arg><target_file>MEM://0x8000000:0x200000</target_file></arg>";
        return simpleCommand(envelope("READ-PARTITION-TABLE", "1.0", content));
    }

    private static String shorten(byte[] data) {
        StringBuilder text = new StringBuilder();
        int limit = Math.min(data.length, 300);
        for (int i = 0; i < limit; i++) {
            char c = (char) (data[i] & 0xFF);
            text.append(c >= 32 && c < 127 ? c : '.');
        }
        if (data.length > limit) text.append("...");
        return text.toString();
    }

    /**
     * Waits for the first message of the agent.
     *
     * @return the text of the message or {@code null} when there is none
     */
    public String receiveStart() {
        return receive();
    }

    /** Reads everything the agent says until the terminal is quiet. */
    public String drain() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String text = receive();
            if (text == null || text.isEmpty()) return new String(out.toByteArray(), UTF8);
            out.write(text.getBytes(UTF8), 0, text.length());
        }
    }
}
