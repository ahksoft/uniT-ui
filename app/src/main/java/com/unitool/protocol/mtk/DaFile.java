package com.unitool.protocol.mtk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * A MediaTek download agent file.
 *
 * <p>The layout follows the reference implementation
 * ({@code Library/DA/daconfig.py}):
 *
 * <pre>
 *  0x00  header, 0x68 bytes ("MTK_DOWNLOAD_AGENT", "MTK_DA_v6_..." for version 6)
 *  0x68  number of entries, 4 bytes little endian
 *  0x6C  first entry
 * </pre>
 *
 * <p>Every entry is either 0xD8 or 0xDC bytes. The first 0x2C bytes are the header of the
 * entry, the rest belongs to the program. An entry older than version 6 has one half word
 * less in its header, the size of the entry tells which one it is.
 */
public final class DaFile {
    /** Size of the header of one entry without the program. */
    private static final int ENTRY_OLD = 0xD8;
    private static final int ENTRY_NEW = 0xDC;

    /** One block of the loader: the stage 1 and the stage 2 of a download agent. */
    public static final class Region {
        public final int buf;
        public final int len;
        public final int address;
        public final int offset;
        public final int signatureLength;

        Region(int buf, int len, int address, int offset, int signatureLength) {
            this.buf = buf;
            this.len = len;
            this.address = address;
            this.offset = offset;
            this.signatureLength = signatureLength;
        }

        @Override
        public String toString() {
            return "buf=0x" + Integer.toHexString(buf)
                    + " len=0x" + Integer.toHexString(len)
                    + " addr=0x" + Integer.toHexString(address)
                    + " sig=0x" + Integer.toHexString(signatureLength);
        }
    }

    public final int hwCode;
    public final int hwSubCode;
    public final int hwVersion;
    public final int swVersion;
    public final int pageSize;
    public final int regionIndex;
    public final List<Region> regions;
    /** True for the version 6 loaders, the newer protocol. */
    public final boolean version6;
    /** The bytes of the file, the regions point into them. */
    public final byte[] file;

    private DaFile(byte[] file, int hwCode, int hwSubCode, int hwVersion, int swVersion,
                   int pageSize, int regionIndex, List<Region> regions, boolean version6) {
        this.file = file;
        this.hwCode = hwCode;
        this.hwSubCode = hwSubCode;
        this.hwVersion = hwVersion;
        this.swVersion = swVersion;
        this.pageSize = pageSize;
        this.regionIndex = regionIndex;
        this.regions = regions;
        this.version6 = version6;
    }

    /** The data of one region, without the signature at its end. */
    public byte[] data(int index) {
        if (index < 0 || index >= regions.size()) return new byte[0];
        Region region = regions.get(index);
        byte[] out = new byte[region.len];
        System.arraycopy(file, region.buf, out, 0, Math.min(region.len, file.length - region.buf));
        return out;
    }

    /** The stage 1 of the download agent, the part that is uploaded into the boot rom. */
    public Region stage1() {
        if (regions.size() > 1) return regions.get(1);
        return regions.isEmpty() ? null : regions.get(0);
    }

    /** The stage 2 of the download agent. */
    public Region stage2() {
        return regions.size() > 2 ? regions.get(2) : null;
    }

    @Override
    public String toString() {
        return "hw=0x" + Integer.toHexString(hwCode)
                + " sub=0x" + Integer.toHexString(hwSubCode)
                + " ver=0x" + Integer.toHexString(hwVersion)
                + " sw=" + swVersion
                + " v6=" + version6;
    }

    /**
     * Reads every entry of a loader file.
     *
     * @throws IllegalArgumentException when the file is not a loader
     */
    public static List<DaFile> parse(byte[] file) {
        if (file.length < 0x6C + 4) {
            throw new IllegalArgumentException("the file is too small for a loader");
        }
        ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        byte[] header = new byte[0x68];
        System.arraycopy(file, 0, header, 0, header.length);
        boolean version6 = contains(header, "MTK_DA_v6");
        int count = buffer.getInt(0x68);
        if (count <= 0 || count > 64) {
            throw new IllegalArgumentException("the loader does not list its entries ("
                    + count + ")");
        }
        int entrySize = entrySize(file, version6);
        List<DaFile> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int base = 0x6C + i * entrySize;
            if (base + 0x2C > file.length) break;
            try {
                result.add(readEntry(file, buffer, base, version6));
            } catch (RuntimeException e) {
                // one broken entry must not stop the others
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("the loader has no usable entry");
        }
        return result;
    }

    /**
     * The size of one entry.
     *
     * <p>A version 6 loader uses 0xDC, an older one 0xD8. The marker of the older layout
     * {@code DA DA} at the end of the header of the first entry tells it too.
     */
    private static int entrySize(byte[] file, boolean version6) {
        if (!version6) return ENTRY_OLD;
        if (file.length >= 0x6C + ENTRY_OLD + 2) {
            if ((file[0x6C + ENTRY_OLD] & 0xFF) == 0xDA
                    && (file[0x6C + ENTRY_OLD + 1] & 0xFF) == 0xDA) {
                return ENTRY_OLD;
            }
        }
        return ENTRY_NEW;
    }

    private static DaFile readEntry(byte[] file, ByteBuffer buffer, int base, boolean version6) {
        // 0: magic, 2: hw code, 4: hw sub code, 6: hw version, 8: sw version, 10: reserved
        // 12: page size, 14: reserved, 16: region index, 18: count
        int hwCode = buffer.getShort(base + 2) & 0xFFFF;
        int hwSubCode = buffer.getShort(base + 4) & 0xFFFF;
        int hwVersion = buffer.getShort(base + 6) & 0xFFFF;
        int swVersion = version6 ? buffer.getShort(base + 8) & 0xFFFF : 0;
        int pageSize = buffer.getShort(base + 12) & 0xFFFF;
        int regionIndex = buffer.getShort(base + 16) & 0xFFFF;
        int regionCount = buffer.getShort(base + 18) & 0xFFFF;
        if (regionCount <= 0 || regionCount > 16) {
            throw new IllegalArgumentException("entry " + Integer.toHexString(hwCode)
                    + " has " + regionCount + " regions");
        }
        List<Region> regions = new ArrayList<>();
        for (int i = 0; i < regionCount; i++) {
            // the header of an entry is ten half words, the regions follow it
            int at = base + 0x14 + i * 20;
            if (at + 20 > file.length) break;
            regions.add(new Region(
                    buffer.getInt(at),
                    buffer.getInt(at + 4),
                    buffer.getInt(at + 8),
                    buffer.getInt(at + 12),
                    buffer.getInt(at + 16)));
        }
        return new DaFile(file, hwCode, hwSubCode, hwVersion, swVersion, pageSize, regionIndex,
                regions, version6);
    }

    /**
     * Picks the entry that belongs to the chip.
     *
     * <p>The reference implementation first looks for the hardware code, then for the
     * sub code, the hardware version and the software version. A loader that only has an
     * entry for the hardware code is used when nothing better is found.
     */
    public static DaFile select(List<DaFile> files, int hwCode, int hwSubCode, int hwVersion,
                                int swVersion) {
        DaFile best = null;
        int bestScore = -1;
        for (DaFile file : files) {
            if (file.hwCode == 0) continue;
            if (file.hwCode != hwCode) continue;
            int score = 1;
            if (file.hwSubCode == hwSubCode) score++;
            if (file.hwVersion == hwVersion) score++;
            if (file.swVersion == swVersion) score++;
            if (score > bestScore) {
                bestScore = score;
                best = file;
            }
        }
        return best;
    }

    private static boolean contains(byte[] data, String text) {
        byte[] pattern = text.getBytes();
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
