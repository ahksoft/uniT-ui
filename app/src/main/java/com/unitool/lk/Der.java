package com.unitool.lk;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The little part of DER that the certificate of an LK image needs.
 *
 * <p>A certificate is a tree of elements: one tag byte, a length and the content.
 * Only the elements themselves are read, the meaning of the content is left to the
 * caller.
 */
public final class Der {
    /** One element of a DER structure. */
    public static final class Element {
        /** The tag, e.g. 0x30 for a sequence or 0x03 for a bit string. */
        public final int tag;
        /** Offset of the element in the buffer it was read from. */
        public final int start;
        /** Offset of the content in that buffer. */
        public final int contentStart;
        /** Offset behind the content in that buffer. */
        public final int contentEnd;
        /** The complete element, tag, length and content. */
        public final byte[] raw;
        /** The content without tag and length. */
        public final byte[] content;

        Element(int tag, int start, int contentStart, int contentEnd, byte[] raw, byte[] content) {
            this.tag = tag;
            this.start = start;
            this.contentStart = contentStart;
            this.contentEnd = contentEnd;
            this.raw = raw;
            this.content = content;
        }

        /** True when the element holds other elements. */
        public boolean isConstructed() {
            return (tag & 0x20) != 0;
        }
    }

    private Der() {
    }

    /** Reads the elements of a buffer. */
    public static List<Element> parseAll(byte[] data) {
        return parseRange(data, 0, data.length);
    }

    /** Reads the elements of a part of a buffer, their offsets are absolute. */
    public static List<Element> parseRange(byte[] data, int from, int to) {
        List<Element> out = new ArrayList<>();
        int offset = from;
        while (offset < to) {
            Element element = parseAt(data, offset, to);
            out.add(element);
            offset = element.contentEnd;
        }
        return out;
    }

    /** Reads the elements inside a constructed element. */
    public static List<Element> children(Element element) {
        if (!element.isConstructed()) return new ArrayList<>();
        // the content is a copy, the elements inside it are read from that copy
        return parseAll(element.content);
    }

    /** Reads the elements inside a constructed element that was read from {@code source}. */
    public static List<Element> childrenOf(byte[] source, Element element) {
        if (!element.isConstructed()) return new ArrayList<>();
        return parseRange(source, element.contentStart, element.contentEnd);
    }

    /** Reads one element at an offset. */
    public static Element parseAt(byte[] data, int offset, int limit) {
        if (offset + 2 > limit) {
            throw new IllegalArgumentException("no der element at " + offset);
        }
        int tag = data[offset] & 0xFF;
        int pos = offset + 1;
        int first = data[pos++] & 0xFF;
        int length;
        if ((first & 0x80) == 0) {
            length = first;
        } else {
            int count = first & 0x7F;
            if (count == 0 || count > 4) {
                throw new IllegalArgumentException("unsupported der length at " + offset);
            }
            length = 0;
            for (int i = 0; i < count; i++) {
                if (pos >= limit) throw new IllegalArgumentException("truncated der length");
                length = (length << 8) | (data[pos++] & 0xFF);
            }
        }
        int contentStart = pos;
        int contentEnd = contentStart + length;
        if (length < 0 || contentEnd > limit) {
            throw new IllegalArgumentException("the der element at " + offset
                    + " is longer than the data");
        }
        byte[] raw = new byte[contentEnd - offset];
        System.arraycopy(data, offset, raw, 0, raw.length);
        byte[] content = new byte[length];
        System.arraycopy(data, contentStart, content, 0, length);
        return new Element(tag, offset, contentStart, contentEnd, raw, content);
    }

    /** Reads one element at an offset of a complete buffer. */
    public static Element parseAt(byte[] data, int offset) {
        return parseAt(data, offset, data.length);
    }

    /** The DER length octets of a length. */
    public static byte[] encodeLength(int length) {
        if (length < 0x80) return new byte[]{(byte) length};
        int size = 0;
        int value = length;
        while (value > 0) {
            size++;
            value >>= 8;
        }
        byte[] out = new byte[size + 1];
        out[0] = (byte) (0x80 | size);
        for (int i = 0; i < size; i++) {
            out[size - i] = (byte) ((length >>> (8 * i)) & 0xFF);
        }
        return out;
    }

    /**
     * A bit string with a whole number of bytes, the unused bits are zero.
     *
     * <p>The content starts with the count of the unused bits, which is why it is one byte
     * longer than the payload.
     */
    public static byte[] bitString(byte[] payload) {
        byte[] content = new byte[payload.length + 1];
        System.arraycopy(payload, 0, content, 1, payload.length);
        byte[] length = encodeLength(content.length);
        byte[] out = new byte[1 + length.length + content.length];
        out[0] = 0x03;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(content, 0, out, 1 + length.length, content.length);
        return out;
    }

    /** The bytes of a bit string, without the count of the unused bits. */
    public static byte[] octets(Element element) {
        if (element.tag != 0x03 || element.content.length == 0) return new byte[0];
        byte[] out = new byte[element.content.length - 1];
        System.arraycopy(element.content, 1, out, 0, out.length);
        return out;
    }

    /** A context specific element with the number zero, used for the override block. */
    public static byte[] contextZero(byte[] content) {
        byte[] length = encodeLength(content.length);
        byte[] out = new byte[1 + length.length + content.length];
        out[0] = (byte) 0xA0;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(content, 0, out, 1 + length.length, content.length);
        return out;
    }

    /** Joins byte arrays. */
    public static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            if (part != null) out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    /** A part of a buffer. */
    public static byte[] slice(byte[] data, int from, int to) {
        if (from < 0 || to > data.length || from > to) return new byte[0];
        byte[] out = new byte[to - from];
        System.arraycopy(data, from, out, 0, out.length);
        return out;
    }
}
