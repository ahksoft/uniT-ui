package com.unitool.adb;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;

public class AdbClient {
    private static final String TAG = "AdbClient";

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private boolean connected = false;

    private PrivateKey privateKey;
    private PublicKey publicKey;

    public AdbClient() {
        generateKeyPair();
    }

    private void generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            KeyPair pair = gen.generateKeyPair();
            privateKey = pair.getPrivate();
            publicKey = pair.getPublic();
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate RSA key: " + e.getMessage());
        }
    }

    public boolean isConnected() { return connected; }

    public String connect(String host, int port) {
        try {
            socket = new Socket(host, port);
            socket.setSoTimeout(10000);
            input = socket.getInputStream();
            output = socket.getOutputStream();

            // Send CNXN (connect)
            String systemType = "host::";
            byte[] payload = systemType.getBytes(StandardCharsets.UTF_8);
            sendPacket(0x4e584e43, 0x01000001, 4096, payload);

            // Read response
            AdbPacket resp = readPacket();
            if (resp == null) return "Connection failed: no response";

            if (resp.command == 0x4e584e43) {
                // CNXN - connected successfully
                connected = true;
                String banner = new String(resp.data, resp.data != null ? 0 : 0, resp.dataLength);
                return "connected:" + banner;
            } else if (resp.command == 0x48545541) {
                // AUTH required
                return handleAuth(resp);
            } else {
                return "Unexpected command: " + Integer.toHexString(resp.command);
            }
        } catch (Exception e) {
            Log.e(TAG, "Connect error: " + e.getMessage());
            disconnect();
            return "error:" + e.getMessage();
        }
    }

    private String handleAuth(AdbPacket resp) {
        try {
            int retries = 0;
            while (resp.command == 0x48545541 && retries < 3) {
                int authType = resp.arg0;
                byte[] token = resp.data;

                if (authType == AUTH_TOKEN && token != null && privateKey != null) {
                    // Sign the token
                    java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
                    sig.initSign(privateKey);
                    sig.update(token);
                    byte[] signature = sig.sign();
                    sendPacket(0x48545541, AUTH_SIGNATURE, 0, signature);
                } else if (authType == AUTH_RSAPUBLICKEY && publicKey != null) {
                    // Send public key
                    byte[] pubKeyBytes = getPublicKeyBytes();
                    sendPacket(0x48545541, AUTH_RSAPUBLICKEY, 0, pubKeyBytes);
                } else {
                    return "auth error: unexpected type " + authType;
                }

                resp = readPacket();
                if (resp == null) return "Connection failed during auth";

                if (resp.command == 0x4e584e43) {
                    connected = true;
                    String banner = new String(resp.data, 0,
                            resp.data != null ? resp.dataLength : 0);
                    return "connected:" + banner;
                }
                retries++;
            }
            return "auth failed after " + retries + " retries";
        } catch (Exception e) {
            Log.e(TAG, "Auth error: " + e.getMessage());
            return "error:" + e.getMessage();
        }
    }

    private byte[] getPublicKeyBytes() {
        try {
            byte[] keyBytes = publicKey.getEncoded();
            // Convert to Android ADB format (base64 + space + user@host)
            String b64 = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP);
            String keyStr = b64 + " unitool@android";
            return keyStr.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "getPublicKeyBytes error: " + e.getMessage());
            return new byte[0];
        }
    }

    public String shell(String command) {
        if (!connected) return "Not connected";

        try {
            String service = "shell:" + command;
            byte[] svcBytes = service.getBytes(StandardCharsets.UTF_8);
            int localId = new SecureRandom().nextInt(0x7FFFFFFF);
            int remoteId = 0;

            // OPEN
            sendPacket(0x4e45504f, localId, 0, svcBytes);
            AdbPacket pkt = readPacket();
            if (pkt == null) return "Error: no response";
            if (pkt.command == 0x59414b4f) {
                remoteId = pkt.arg0;
            } else if (pkt.command == 0x45534c43) {
                return "Error: service refused (closed)";
            } else {
                return "Error: unexpected " + Integer.toHexString(pkt.command);
            }

            // Wait for OKAY
            pkt = readPacket();
            if (pkt == null) return "Error: no response";
            if (pkt.command == 0x59414b4f && pkt.arg0 == localId) {
                // OK
            } else if (pkt.command == 0x45534c43) {
                return "";
            }

            // Read WRTE packets
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            long startTime = System.currentTimeMillis();
            boolean reading = true;

            while (reading && (System.currentTimeMillis() - startTime) < 10000) {
                pkt = readPacket();
                if (pkt == null) break;

                switch (pkt.command) {
                    case 0x45545257: // WRTE
                        if (pkt.data != null && pkt.dataLength > 0) {
                            baos.write(pkt.data, 0, pkt.dataLength);
                        }
                        sendPacket(0x59414b4f, pkt.arg0, pkt.arg1, null);
                        break;
                    case 0x45534c43: // CLSE
                        reading = false;
                        break;
                    default:
                        reading = false;
                        break;
                }

                // Check if output is complete (shell prompt or specific patterns)
                String currentOutput = baos.toString(StandardCharsets.UTF_8.name());
                if (!reading) break;

                // If the command finished, shell will close the stream
                if (baos.size() > 0 && pkt != null && pkt.dataLength < 4096) {
                    try {
                        Thread.sleep(100);
                        if (input.available() == 0) reading = false;
                    } catch (Exception e) {
                        reading = false;
                    }
                }
            }

            sendPacket(0x45534c43, localId, remoteId, null);
            return baos.toString(StandardCharsets.UTF_8.name()).trim();
        } catch (Exception e) {
            Log.e(TAG, "Shell error: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        try { if (input != null) input.close(); } catch (Exception ignored) {}
        try { if (output != null) output.close(); } catch (Exception ignored) {}
    }

    public String getPublicKeyHash() {
        try {
            byte[] keyBytes = publicKey.getEncoded();
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(keyBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "getPublicKeyHash error: " + e.getMessage());
            return null;
        }
    }

    public String pair(String host, int port, String code) {
        Socket pairSocket = null;
        try {
            pairSocket = new Socket(host, port);
            pairSocket.setSoTimeout(10000);
            InputStream pairInput = pairSocket.getInputStream();
            OutputStream pairOutput = pairSocket.getOutputStream();

            // Read the AUTH token from the device
            byte[] header = new byte[24];
            int offset = 0;
            while (offset < 24) {
                int n = pairInput.read(header, offset, 24 - offset);
                if (n < 0) return "Pairing failed: no response";
                offset += n;
            }

            ByteBuffer buf = ByteBuffer.wrap(header);
            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int command = buf.getInt();
            int arg0 = buf.getInt();
            int arg1 = buf.getInt();
            int dataLength = buf.getInt();
            int dataCrc = buf.getInt();
            int magic = buf.getInt();

            byte[] token = null;
            if (dataLength > 0 && dataLength < 65536) {
                token = new byte[dataLength];
                offset = 0;
                while (offset < dataLength) {
                    int n = pairInput.read(token, offset, dataLength - offset);
                    if (n < 0) return "Pairing failed: incomplete token";
                    offset += n;
                }
            }

            // Send "PW<code>\n" followed by public key
            String pwMsg = "PW" + code + "\n";
            byte[] pwBytes = pwMsg.getBytes(StandardCharsets.UTF_8);
            byte[] pubKeyBytes = getPublicKeyBytes();
            ByteArrayOutputStream payloadBaos = new ByteArrayOutputStream();
            payloadBaos.write(pwBytes);
            payloadBaos.write(pubKeyBytes);
            byte[] payload = payloadBaos.toByteArray();

            // Send AUTH (type 2 = signature with our payload)
            ByteBuffer pktBuf = ByteBuffer.allocate(24 + payload.length);
            pktBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            pktBuf.putInt(0x48545541); // AUTH
            pktBuf.putInt(2); // AUTH_SIGNATURE
            pktBuf.putInt(0);
            pktBuf.putInt(payload.length);
            pktBuf.putInt(calcCrc(payload, payload.length));
            pktBuf.putInt(0x48545541 ^ 0xFFFFFFFF);
            pktBuf.put(payload);
            pairOutput.write(pktBuf.array());
            pairOutput.flush();

            // Read response
            byte[] respHeader = new byte[24];
            offset = 0;
            while (offset < 24) {
                int n = pairInput.read(respHeader, offset, 24 - offset);
                if (n < 0) return "Pairing failed: no response after key";
                offset += n;
            }

            buf = ByteBuffer.wrap(respHeader);
            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int respCommand = buf.getInt();
            int respArg0 = buf.getInt();
            int respArg1 = buf.getInt();
            int respDataLen = buf.getInt();
            int respCrc = buf.getInt();
            int respMagic = buf.getInt();

            if (respCommand == 0x4e584e43) {
                // CNXN - paired successfully
                return "success";
            } else if (respCommand == 0x48545541 && respArg0 == 1) {
                // Still in auth - might need to send key again
                return "pairing: additional auth required";
            } else {
                return "Unexpected response during pairing: " + Integer.toHexString(respCommand);
            }
        } catch (Exception e) {
            Log.e(TAG, "Pair error: " + e.getMessage());
            return "error:" + e.getMessage();
        } finally {
            try { if (pairSocket != null) pairSocket.close(); } catch (Exception ignored) {}
        }
    }

    // ========== ADB Packet ==========

    private static class AdbPacket {
        int command;
        int arg0;
        int arg1;
        int dataLength;
        int dataCrc;
        int magic;
        byte[] data;
    }

    private void sendPacket(int command, int arg0, int arg1, byte[] data) throws Exception {
        int dataLen = (data != null) ? data.length : 0;
        int crc = 0;
        if (data != null && dataLen > 0) {
            crc = calcCrc(data, dataLen);
        }
        int magic = command ^ 0xFFFFFFFF;

        ByteBuffer buf = ByteBuffer.allocate(24 + dataLen);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putInt(command);
        buf.putInt(arg0);
        buf.putInt(arg1);
        buf.putInt(dataLen);
        buf.putInt(crc);
        buf.putInt(magic);
        if (data != null) buf.put(data);

        output.write(buf.array());
        output.flush();
    }

    private AdbPacket readPacket() throws Exception {
        byte[] header = new byte[24];
        int offset = 0;
        while (offset < 24) {
            int n = input.read(header, offset, 24 - offset);
            if (n < 0) return null;
            offset += n;
        }

        ByteBuffer buf = ByteBuffer.wrap(header);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        AdbPacket pkt = new AdbPacket();
        pkt.command = buf.getInt();
        pkt.arg0 = buf.getInt();
        pkt.arg1 = buf.getInt();
        pkt.dataLength = buf.getInt();
        pkt.dataCrc = buf.getInt();
        pkt.magic = buf.getInt();

        if (pkt.dataLength > 0 && pkt.dataLength < 65536) {
            pkt.data = new byte[pkt.dataLength];
            offset = 0;
            while (offset < pkt.dataLength) {
                int n = input.read(pkt.data, offset, pkt.dataLength - offset);
                if (n < 0) return null;
                offset += n;
            }
        }

        return pkt;
    }

    private int calcCrc(byte[] data, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc = (crc + (data[i] & 0xFF)) & 0xFFFFFFFF;
        }
        return crc;
    }
}
