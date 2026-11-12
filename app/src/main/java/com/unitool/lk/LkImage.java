package com.unitool.lk;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A MediaTek LK image: a list of partitions, every partition has a header of 512 bytes
 * and may carry certificates behind its data.
 *
 * <p>The format follows the library of the reference implementation
 * ({@code liblk/image.py}, {@code liblk/structures/header.py} and {@code partition.py}).
 */
public final class LkImage {
    private static final int MAGIC = 0x58881688;
    private static final int EXT_MAGIC = 0x58891689;
    /** Size of a header of the older format, the newer one writes its own size. */
    private static final int HEADER_SIZE = 512;

    /** The header of a partition. */
    public static final class Header {
        public int magic;
        public int dataSizeLow;
        public byte[] nameBytes = new byte[32];
        public int memoryAddressLow;
        public int mode;
        public int extMagic;
        public int headerSize;
        public int headerVersion;
        public int imageTypeId;
        public int imageTypeReserved0;
        public int imageTypeReserved1;
        public int imageTypeGroup;
        public int imageListEnd;
        public int alignment;
        public int dataSizeHigh;
        public int memoryAddressHigh;
        /** The 432 bytes of the header that have no meaning for us. */
        public byte[] padding = new byte[432];

        public boolean isExtended() {
            return extMagic == EXT_MAGIC;
        }

        public long dataSize() {
            if (isExtended()) {
                return ((long) (dataSizeHigh & 0xFFFFFFFFL) << 32) | (dataSizeLow & 0xFFFFFFFFL);
            }
            return dataSizeLow & 0xFFFFFFFFL;
        }

        public void setDataSize(long size) {
            dataSizeLow = (int) (size & 0xFFFFFFFFL);
            if (isExtended()) dataSizeHigh = (int) ((size >>> 32) & 0xFFFFFFFFL);
        }

        public long memoryAddress() {
            if (isExtended()) {
                return ((long) (memoryAddressHigh & 0xFFFFFFFFL) << 32)
                        | (memoryAddressLow & 0xFFFFFFFFL);
            }
            return memoryAddressLow & 0xFFFFFFFFL;
        }

        /** Size of this header in the image. */
        public int size() {
            return isExtended() ? headerSize : HEADER_SIZE;
        }

        public String name() {
            int end = 0;
            while (end < nameBytes.length && nameBytes[end] != 0) end++;
            return new String(nameBytes, 0, end, StandardCharsets.US_ASCII);
        }

        /** The 512 bytes of the header, the hash of the certificate is made over them. */
        public byte[] toBytes() {
            byte[] out = new byte[HEADER_SIZE];
            putInt(out, 0, magic);
            putInt(out, 4, dataSizeLow);
            System.arraycopy(nameBytes, 0, out, 8, Math.min(32, nameBytes.length));
            putInt(out, 40, memoryAddressLow);
            putInt(out, 44, mode);
            putInt(out, 48, extMagic);
            putInt(out, 52, headerSize);
            putInt(out, 56, headerVersion);
            out[60] = (byte) imageTypeId;
            out[61] = (byte) imageTypeReserved0;
            out[62] = (byte) imageTypeReserved1;
            out[63] = (byte) imageTypeGroup;
            putInt(out, 64, imageListEnd);
            putInt(out, 68, alignment);
            putInt(out, 72, dataSizeHigh);
            putInt(out, 76, memoryAddressHigh);
            System.arraycopy(padding, 0, out, 80, Math.min(padding.length, 432));
            return out;
        }

        public static Header fromBytes(byte[] data, int offset) {
            Header header = new Header();
            header.magic = getInt(data, offset);
            header.dataSizeLow = getInt(data, offset + 4);
            System.arraycopy(data, offset + 8, header.nameBytes, 0, 32);
            header.memoryAddressLow = getInt(data, offset + 40);
            header.mode = getInt(data, offset + 44);
            header.extMagic = getInt(data, offset + 48);
            header.headerSize = getInt(data, offset + 52);
            header.headerVersion = getInt(data, offset + 56);
            header.imageTypeId = data[offset + 60] & 0xFF;
            header.imageTypeReserved0 = data[offset + 61] & 0xFF;
            header.imageTypeReserved1 = data[offset + 62] & 0xFF;
            header.imageTypeGroup = data[offset + 63] & 0xFF;
            header.imageListEnd = getInt(data, offset + 64);
            header.alignment = getInt(data, offset + 68);
            header.dataSizeHigh = getInt(data, offset + 72);
            header.memoryAddressHigh = getInt(data, offset + 76);
            System.arraycopy(data, offset + 80, header.padding, 0, 432);
            return header;
        }

        private static void putInt(byte[] data, int offset, int value) {
            data[offset] = (byte) value;
            data[offset + 1] = (byte) (value >>> 8);
            data[offset + 2] = (byte) (value >>> 16);
            data[offset + 3] = (byte) (value >>> 24);
        }

        private static int getInt(byte[] data, int offset) {
            return (data[offset] & 0xFF)
                    | ((data[offset + 1] & 0xFF) << 8)
                    | ((data[offset + 2] & 0xFF) << 16)
                    | ((data[offset + 3] & 0xFF) << 24);
        }
    }

    /** One partition and the certificates that belong to it. */
    public static final class Partition {
        public final Header header;
        public byte[] data;
        public final List<Partition> certs = new ArrayList<>();
        public int endOffset;

        public Partition(Header header, byte[] data) {
            this.header = header;
            this.data = data;
        }

        public Partition cert1() {
            return certs.size() > 0 ? certs.get(0) : null;
        }

        public Partition cert2() {
            return certs.size() > 1 ? certs.get(1) : null;
        }

        public void setData(byte[] value) {
            data = value;
            header.setDataSize(value.length);
        }

        /** The bytes of this partition, its padding and its certificates. */
        public byte[] toBytes() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] headerBytes = header.toBytes();
            out.write(headerBytes, 0, headerBytes.length);
            out.write(data, 0, data.length);
            int alignment = header.isExtended() ? header.alignment : 8;
            if (alignment > 0 && data.length % alignment != 0) {
                int pad = alignment - (data.length % alignment);
                out.write(new byte[pad], 0, pad);
            }
            for (Partition cert : certs) {
                byte[] certBytes = cert.toBytes();
                out.write(certBytes, 0, certBytes.length);
            }
            return out.toByteArray();
        }
    }

    public final Map<String, Partition> partitions = new LinkedHashMap<>();
    public byte[] contents;
    public int version = 1;

    public LkImage(byte[] data) {
        this.contents = data;
        parsePartitions();
        version = partitions.containsKey("aee") || partitions.containsKey("bl2_ext") ? 2 : 1;
    }

    private void parsePartitions() {
        int offset = 0;
        String lastName = "";
        while (offset < contents.length) {
            Partition partition;
            try {
                partition = readPartition(offset);
            } catch (RuntimeException e) {
                if (!partitions.isEmpty()) break;
                throw new IllegalArgumentException("this is not an lk image: " + e.getMessage());
            }
            if (partition.header.magic != MAGIC) {
                if (!partitions.isEmpty()) break;
                throw new IllegalArgumentException("wrong magic 0x"
                        + Integer.toHexString(partition.header.magic));
            }
            String name = partition.header.name();
            if (name.startsWith("cert")) {
                Partition owner = partitions.get(lastName);
                if (owner == null) break;
                owner.certs.add(partition);
            } else if (!partitions.containsKey(name)) {
                lastName = name;
                partitions.put(name, partition);
            } else {
                break;
            }
            if (partition.header.isExtended() && partition.header.imageListEnd == 1) break;
            offset = partition.endOffset;
            if (!partition.header.isExtended()
                    || (partition.header.imageListEnd != 0 && partition.header.imageListEnd != 1)) {
                if (isEndOfPartitions(offset)) break;
            }
        }
        if (partitions.isEmpty()) {
            throw new IllegalArgumentException("the image has no partition");
        }
    }

    private Partition readPartition(int offset) {
        byte[] slice = contents;
        int start = 0;
        if (contents.length >= 4 && contents[0] == 'B' && contents[1] == 'F'
                && contents[2] == 'B' && contents[3] == 'F') {
            start = 0x4040;
        }
        int headerOffset = offset + start;
        if (headerOffset + HEADER_SIZE > slice.length) {
            throw new IllegalArgumentException("header at 0x" + Integer.toHexString(headerOffset)
                    + " is outside the image");
        }
        Header header = Header.fromBytes(slice, headerOffset);
        if (header.magic != MAGIC) {
            throw new IllegalArgumentException("wrong magic at 0x"
                    + Integer.toHexString(headerOffset));
        }
        long dataSize = header.dataSize();
        if (dataSize < 0 || headerOffset + header.size() + dataSize > slice.length) {
            throw new IllegalArgumentException("partition at 0x"
                    + Integer.toHexString(headerOffset) + " is longer than the image");
        }
        byte[] data = new byte[(int) dataSize];
        System.arraycopy(slice, headerOffset + header.size(), data, 0, data.length);

        Partition partition = new Partition(header, data);
        int alignment = header.isExtended() ? header.alignment : 8;
        long end = offset + (long) header.size() + dataSize;
        if (alignment > 0 && end % alignment != 0) {
            end += alignment - (end % alignment);
        }
        partition.endOffset = (int) end;
        return partition;
    }

    private boolean isEndOfPartitions(int offset) {
        if (offset >= contents.length) return true;
        if (contents.length - offset < 512) return true;
        int magic = (contents[offset] & 0xFF)
                | ((contents[offset + 1] & 0xFF) << 8)
                | ((contents[offset + 2] & 0xFF) << 16)
                | ((contents[offset + 3] & 0xFF) << 24);
        return magic != MAGIC;
    }

    /** Rebuilds the image out of the partitions, the end flag is set on the last one. */
    public void rebuild() {
        if (partitions.isEmpty()) {
            contents = new byte[0];
            return;
        }
        for (Partition partition : partitions.values()) {
            partition.header.imageListEnd = 0;
            for (Partition cert : partition.certs) cert.header.imageListEnd = 0;
        }
        Partition last = null;
        for (Partition partition : partitions.values()) last = partition;
        if (last != null) {
            if (!last.certs.isEmpty()) {
                last.certs.get(last.certs.size() - 1).header.imageListEnd = 1;
            } else {
                last.header.imageListEnd = 1;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Partition partition : partitions.values()) {
            byte[] bytes = partition.toBytes();
            partition.endOffset = out.size() + bytes.length;
            out.write(bytes, 0, bytes.length);
        }
        contents = out.toByteArray();
    }
}
