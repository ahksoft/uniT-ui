package com.unitool.lk;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * The {@code cert2} certificate of a partition and the way to make it accept a patched
 * image.
 *
 * <p>The certificate holds the SHA-256 of the header (field 14) and of the data (field 10)
 * of its partition; the two identifiers in front of them (fields 13 and 9) tell the loader
 * which digest they are.
 *
 * <p>A changed partition invalidates the certificate and the vendor signature cannot be
 * made again. An override block is therefore put in front of the certificate: it carries
 * the new digests and the loader reads it before the certificate. That is the
 * {@code wrap-override} mode of the reference implementation.
 */
public final class Certificate {
    /** The hashes are made over the data padded to this size. */
    public static final int HASH_ALIGNMENT = 16;

    private static final int IMAGE_HASH_INDEX = 10;
    private static final int HEADER_HASH_INDEX = 14;
    private static final int IMAGE_HASH_OID_INDEX = IMAGE_HASH_INDEX - 1;
    private static final int HEADER_HASH_OID_INDEX = HEADER_HASH_INDEX - 1;

    private final byte[] raw;
    private final List<Der.Element> fields;

    private Certificate(byte[] raw, List<Der.Element> fields) {
        this.raw = raw;
        this.fields = fields;
    }

    /**
     * Reads a certificate.
     *
     * @throws IllegalArgumentException when the data is not a certificate of this format
     */
    public static Certificate parse(byte[] raw) {
        List<Der.Element> root;
        try {
            root = Der.parseAll(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the certificate cannot be read: " + e.getMessage());
        }
        if (root.size() != 1) {
            throw new IllegalArgumentException("the certificate has " + root.size()
                    + " parts, expected one");
        }
        Der.Element outer = root.get(0);
        if (!outer.isConstructed()) {
            throw new IllegalArgumentException("the certificate is not a sequence");
        }
        List<Der.Element> fields = Der.childrenOf(raw, outer);
        if (fields.isEmpty() || !fields.get(0).isConstructed()) {
            throw new IllegalArgumentException("the certificate has no fields");
        }
        List<Der.Element> inner = Der.childrenOf(raw, fields.get(0));
        if (inner.size() <= HEADER_HASH_INDEX) {
            throw new IllegalArgumentException("the certificate has only " + inner.size()
                    + " fields");
        }
        if (inner.get(IMAGE_HASH_INDEX).tag != 0x03 || inner.get(HEADER_HASH_INDEX).tag != 0x03) {
            throw new IllegalArgumentException("the hashes are not where they are expected");
        }
        return new Certificate(raw, inner);
    }

    /** True when the certificate still belongs to the given hashes. */
    public boolean matches(byte[] headerHash, byte[] imageHash) {
        return equal(Der.octets(fields.get(HEADER_HASH_INDEX)), headerHash)
                && equal(Der.octets(fields.get(IMAGE_HASH_INDEX)), imageHash);
    }

    /** The digests the certificate carries, header first. */
    public byte[][] hashes() {
        return new byte[][]{
                Der.octets(fields.get(HEADER_HASH_INDEX)),
                Der.octets(fields.get(IMAGE_HASH_INDEX))};
    }

    /**
     * The certificate with new digests and nothing else changed.
     *
     * <p>A digest is 32 bytes in a bit string of 35 bytes, so the encoding keeps its
     * length and the fields around it keep their place.
     */
    public byte[] encodeWithHashes(byte[] headerHash, byte[] imageHash) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (int index = 0; index < fields.size(); index++) {
            Der.Element field = fields.get(index);
            byte[] bytes;
            if (index == HEADER_HASH_INDEX) {
                bytes = Der.bitString(headerHash);
            } else if (index == IMAGE_HASH_INDEX) {
                bytes = Der.bitString(imageHash);
            } else {
                bytes = field.raw;
            }
            if ((index == HEADER_HASH_INDEX || index == IMAGE_HASH_INDEX)
                    && bytes.length != field.raw.length) {
                bytes = field.raw;
            }
            content.write(bytes, 0, bytes.length);
        }
        byte[] inner = content.toByteArray();
        List<Der.Element> root = Der.parseAll(raw);
        Der.Element outer = root.get(0);
        List<Der.Element> top = Der.childrenOf(raw, outer);
        Der.Element sequence = top.get(0);
        byte[] newSequence = Der.join(new byte[]{(byte) sequence.tag},
                Der.encodeLength(inner.length), inner);
        return Der.join(
                Der.slice(raw, 0, sequence.start),
                newSequence,
                Der.slice(raw, sequence.start + sequence.raw.length, raw.length));
    }

    /** The new digests in a block that is put in front of the certificate. */
    public byte[] buildOverrideBlock(byte[] headerHash, byte[] imageHash) {
        byte[] headerOid = fields.get(HEADER_HASH_OID_INDEX).raw;
        byte[] imageOid = fields.get(IMAGE_HASH_OID_INDEX).raw;
        byte[] content = Der.join(headerOid, Der.bitString(headerHash),
                imageOid, Der.bitString(imageHash));
        return Der.contextZero(content);
    }

    /** A certificate for a changed partition, in the given mode. */
    public byte[] signFor(byte[] headerHash, byte[] imageHash, String mode) {
        if ("wrap-override".equals(mode)) {
            return Der.join(Der.bitString(raw), buildOverrideBlock(headerHash, imageHash), raw);
        }
        if ("wrap".equals(mode)) {
            return Der.join(Der.bitString(raw), encodeWithHashes(headerHash, imageHash));
        }
        if ("override".equals(mode)) {
            return Der.join(buildOverrideBlock(headerHash, imageHash), raw);
        }
        throw new IllegalArgumentException("unknown mode " + mode);
    }

    /**
     * Splits an override wrapped certificate into the vendor certificate and the digests
     * that were advertised.
     *
     * @return the parts, or {@code null} when the certificate is not wrapped that way
     */
    public static Wrapped unwrap(byte[] cert2) {
        Der.Element block;
        try {
            block = Der.parseAt(cert2, 0);
        } catch (RuntimeException e) {
            return null;
        }
        if (block.tag != 0xA0) return null;
        List<Der.Element> fields;
        try {
            fields = Der.childrenOf(cert2, block);
        } catch (RuntimeException e) {
            return null;
        }
        if (fields.size() != 4) return null;
        for (int i = 0; i < 4; i += 2) {
            if (fields.get(i).tag != 0x06) return null;
            Der.Element bits = fields.get(i + 1);
            if (bits.tag != 0x03 || bits.content.length != 33 || bits.content[0] != 0) return null;
        }
        byte[] headerHash = Der.octets(fields.get(1));
        byte[] imageHash = Der.octets(fields.get(3));
        byte[] inner = Der.slice(cert2, block.contentEnd, cert2.length);
        try {
            parse(inner);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new Wrapped(inner, headerHash, imageHash);
    }

    /** The parts of a wrapped certificate. */
    public static final class Wrapped {
        public final byte[] certificate;
        public final byte[] headerHash;
        public final byte[] imageHash;

        Wrapped(byte[] certificate, byte[] headerHash, byte[] imageHash) {
            this.certificate = certificate;
            this.headerHash = headerHash;
            this.imageHash = imageHash;
        }
    }

    /** The sha256 of the header of a partition and of its data. */
    public static byte[][] hashesOf(LkImage.Partition partition) {
        byte[] headerHash = sha256(partition.header.toBytes());
        int padded = partition.data.length;
        if (padded % HASH_ALIGNMENT != 0) padded += HASH_ALIGNMENT - padded % HASH_ALIGNMENT;
        byte[] data = new byte[padded];
        System.arraycopy(partition.data, 0, data, 0, partition.data.length);
        return new byte[][]{headerHash, sha256(data)};
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha-256 is missing", e);
        }
    }

    private static boolean equal(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }
}
