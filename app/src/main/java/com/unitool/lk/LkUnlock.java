package com.unitool.lk;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Finds and applies the patches that remove the unlock checks of a Transsion LK.
 *
 * <p>This is a port of the reference script {@code Tran-UBL-LKpatch.py}, the ARM64
 * instructions are compared as numbers, no disassembler is needed.
 */
public final class LkUnlock {
    private static final int NOP = 0xD503201F;
    private static final int MOV_W0_WZR = 0x2A1F03E0;

    private static final int[] STUB_UNLOCK_ALLOWED = {
            0x52800028, // mov w8, #1
            0xB9000008, // str w8, [x0]
            0xD65F03C0, // ret
            NOP,
    };
    private static final int[] STUB_LOCK_STATE = {
            0x52800068, // mov w8, #3
            0xB9000008, // str w8, [x0]
            MOV_W0_WZR,
            0xD65F03C0, // ret
            NOP, NOP, NOP, NOP,
    };
    private static final int[] STUB_NOT_LOCKED = {
            0xB900001F, // str wzr, [x0]
            MOV_W0_WZR,
            0xD65F03C0, // ret
            NOP, NOP, NOP, NOP, NOP,
    };

    /** The bytes of `is_device_locked`, the wildcards are -1. */
    private static final int[] NEEDLE_IS_LOCKED_TAIL = parseNeedle(
            "?? ?? ?? b9 e0 03 1f 2a 1f 0d 00 71 e8 07 9f 1a 68 02 00 b9");

    private static final int STP_FRAME = 0xFFC07FFF;
    private static final int STP_FRAME_VALUE = 0xA9807BFD;

    private static final byte[] STRING_KEY_VERIFY_FAIL =
            "Unlock key verify failed".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STRING_IS_UNLOCK_ALLOWED =
            "is_unlock_allowed = 0x%x".getBytes(StandardCharsets.US_ASCII);
    private static final byte[][] ANCHORS = {
            "Start unlock flow".getBytes(StandardCharsets.US_ASCII),
            "Unlock auth ok!".getBytes(StandardCharsets.US_ASCII),
            "Unlock auth failed!".getBytes(StandardCharsets.US_ASCII),
            "frp".getBytes(StandardCharsets.US_ASCII),
    };

    /** One change of the image, the length never changes. */
    public static final class Patch {
        public final String name;
        public final int offset;
        public final byte[] original;
        public final byte[] replacement;
        public final String note;

        Patch(String name, int offset, byte[] original, byte[] replacement, String note) {
            if (original.length != replacement.length) {
                throw new IllegalArgumentException("patch " + name + " changes the length");
            }
            this.name = name;
            this.offset = offset;
            this.original = original;
            this.replacement = replacement;
            this.note = note;
        }

        void apply(byte[] data) {
            for (int i = 0; i < original.length; i++) {
                if (data[offset + i] != original[i]) {
                    throw new IllegalStateException("the image changed at 0x"
                            + Integer.toHexString(offset + i) + " while patching");
                }
            }
            System.arraycopy(replacement, 0, data, offset, replacement.length);
        }
    }

    /** The result of looking at an image. */
    public static final class Analysis {
        public final List<Patch> patches = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
    }

    /** Raised when the image already carries the patch. */
    public static final class AlreadyPatched extends RuntimeException {
        public AlreadyPatched(String message) {
            super(message);
        }
    }

    private final byte[] data;

    public LkUnlock(byte[] data) {
        this.data = data;
    }

    // ------------------------------------------------------------------
    // arm64 helpers
    // ------------------------------------------------------------------

    private static int sx(int value, int bits) {
        int sign = 1 << (bits - 1);
        return (value ^ sign) - sign;
    }

    static boolean isCbz(int word) {
        return (word & 0x7E000000) == 0x34000000;
    }

    static boolean isCbnz(int word) {
        return (word & 0x7E000000) == 0x35000000;
    }

    static boolean isTbzTbnz(int word) {
        return (word & 0x7E000000) == 0x36000000;
    }

    static boolean isBl(int word) {
        return (word & 0xFC000000) == 0x94000000;
    }

    static boolean isB(int word) {
        return (word & 0xFC000000) == 0x14000000;
    }

    static boolean isRet(int word) {
        return word == 0xD65F03C0;
    }

    static int blTarget(int word, int pc) {
        return pc + (sx(word & 0x03FFFFFF, 26) << 2);
    }

    static int cbzTarget(int word, int pc) {
        return pc + (sx((word >>> 5) & 0x7FFFF, 19) << 2);
    }

    static int encodeB(int target, int pc) {
        return 0x14000000 | (((target - pc) >> 2) & 0x03FFFFFF);
    }

    static int cbzToB(int word, int pc) {
        if ((word & 0x1F) != 0) {
            throw new IllegalArgumentException("the branch tests a register that is not w0");
        }
        return encodeB(cbzTarget(word, pc), pc);
    }

    /** A text of the instruction, only used for the report. */
    static String describe(int word, int pc) {
        if (word == NOP) return "nop";
        if (isRet(word)) return "ret";
        if (isBl(word)) return String.format("bl #0x%x", blTarget(word, pc));
        if (isB(word)) {
            int offset = sx(word & 0x03FFFFFF, 26) << 2;
            return String.format("b #0x%x", pc + offset);
        }
        if (isCbz(word)) return String.format("cbz w%d, #0x%x", word & 0x1F, cbzTarget(word, pc));
        if (isCbnz(word)) return String.format("cbnz w%d, #0x%x", word & 0x1F, cbzTarget(word, pc));
        if (isTbzTbnz(word)) {
            boolean nonzero = ((word >>> 24) & 1) != 0;
            int bit = ((word >>> 19) & 0x1F) | (((word >>> 31) & 1) << 5);
            String name = nonzero ? "tbnz" : "tbz";
            int offset = sx((word >>> 5) & 0x3FFF, 14) << 2;
            return String.format("%s w%d, #%d, #0x%x", name, word & 0x1F, bit, pc + offset);
        }
        return String.format("0x%08x", word);
    }

    // ------------------------------------------------------------------
    // matching
    // ------------------------------------------------------------------

    private static int[] parseNeedle(String spec) {
        String clean = spec.replaceAll("[\\s_]", "");
        if (clean.length() % 2 != 0) throw new IllegalArgumentException("odd needle");
        int[] out = new int[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            String pair = clean.substring(i * 2, i * 2 + 2);
            out[i] = pair.equals("??") ? -1 : Integer.parseInt(pair, 16);
        }
        return out;
    }

    /** All offsets where the needle matches, the wildcards are -1. */
    private static List<Integer> findNeedle(byte[] data, int[] needle) {
        List<Integer> out = new ArrayList<>();
        if (needle.length == 0 || needle.length > data.length) return out;
        outer:
        for (int base = 0; base <= data.length - needle.length; base++) {
            for (int i = 0; i < needle.length; i++) {
                if (needle[i] >= 0 && (data[base + i] & 0xFF) != needle[i]) continue outer;
            }
            out.add(base);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // low level
    // ------------------------------------------------------------------

    private int word(int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private int findString(byte[] needle) {
        if (needle.length == 0 || needle.length > data.length) return -1;
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** Offsets of the `adrp` that load the address of a string. */
    private List<Integer> findRefs(int target) {
        int page = target & ~0xFFF;
        List<Integer> out = new ArrayList<>();
        for (int off = 0; off < data.length - 8; off += 4) {
            int w = word(off);
            if ((w & 0x9F000000) != 0x90000000) continue;
            int immlo = (w >>> 29) & 0x3;
            int immhi = (w >>> 5) & 0x7FFFF;
            int imm = sx((immhi << 2) | immlo, 19) << 12;
            int pcPage = off & ~0xFFF;
            if (((pcPage + imm) & ~0xFFF) != page) continue;
            int next = word(off + 4);
            if ((next & 0xFF800000) != 0x91000000) continue;
            int lo = (next >>> 10) & 0xFFF;
            int hi = (next >>> 22) & 1;
            if (lo == 0 && hi == 0) continue;
            if ((pcPage + imm + lo + (hi << 12)) == target) out.add(off);
        }
        return out;
    }

    private List<Integer> findCalls(int target) {
        List<Integer> out = new ArrayList<>();
        for (int off = 0; off < data.length - 3; off += 4) {
            int w = word(off);
            if (isBl(w) && blTarget(w, off) == target) out.add(off);
        }
        return out;
    }

    /** Walks back from an instruction to the start of its function. */
    private int funcStart(int before, int limit) {
        int lo = Math.max(0, before - limit);
        for (int off = before; off >= lo; off -= 4) {
            int w = word(off);
            if ((w & STP_FRAME) == STP_FRAME_VALUE) return off;
            if (isRet(w) || isCbz(w) || isCbnz(w)) return -1;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // the patches
    // ------------------------------------------------------------------

    /** S1 - neutralise the two failure branches of `flashing unlock`. */
    private List<Patch> patchUnlockHandler() {
        List<Patch> patches = new ArrayList<>();
        int s = findString(STRING_KEY_VERIFY_FAIL);
        if (s < 0) throw new IllegalStateException("the text of the unlock check is missing");
        List<Integer> refs = findRefs(s);
        if (refs.isEmpty()) throw new IllegalStateException("no code uses the unlock check");
        int ref = refs.get(0);

        int cbOff = ref - 4;
        int cb = word(cbOff);
        if (!isCbz(cb)) {
            if (isB(cb)) {
                throw new AlreadyPatched("the image already carries the unlock patch (at 0x"
                        + Integer.toHexString(cbOff) + ")");
            }
            throw new IllegalStateException("expected a cbz at 0x" + Integer.toHexString(cbOff));
        }
        int newWord = cbzToB(cb, cbOff);
        patches.add(new Patch("S1a", cbOff, le(cb), le(newWord),
                "unlock: ignore 'unlock key verify' ("
                        + describe(cb, cbOff) + " -> " + describe(newWord, cbOff) + ")"));

        int tbOff = -1;
        for (int off = ref + 4; off < Math.min(ref + 0x60, data.length - 4); off += 4) {
            int w = word(off);
            if (isTbzTbnz(w) && ((w >>> 31) & 1) == 0) {
                tbOff = off;
                break;
            }
        }
        if (tbOff < 0) throw new IllegalStateException("the unlock auth branch was not found");
        int tb = word(tbOff);
        patches.add(new Patch("S1b", tbOff, le(tb), le(NOP),
                "unlock: ignore 'unlock auth' (" + describe(tb, tbOff) + " -> nop)"));
        return patches;
    }

    /** S2 - make get_unlock_ability() always answer "allowed". */
    private List<Patch> patchUnlockAbility() {
        int s = findString(STRING_IS_UNLOCK_ALLOWED);
        if (s < 0) throw new IllegalStateException("the text of the unlock state is missing");
        List<Integer> refs = findRefs(s);
        if (refs.isEmpty()) throw new IllegalStateException("no code uses the unlock state");
        int ref = refs.get(0);
        int callerBl = ref - 8;
        if (!isBl(word(callerBl))) {
            throw new IllegalStateException("expected a call at 0x" + Integer.toHexString(callerBl));
        }
        int target = blTarget(word(callerBl), callerBl);
        if (target < 0 || target >= data.length) {
            throw new IllegalStateException("the function ends outside the image");
        }
        if ((word(target) & STP_FRAME) != STP_FRAME_VALUE) {
            throw new IllegalStateException("0x" + Integer.toHexString(target)
                    + " does not start with a stack frame");
        }
        return Collections.singletonList(new Patch("S2", target,
                slice(target, 16), words(STUB_UNLOCK_ALLOWED),
                "get_unlock_ability(): always return allowed=1"));
    }

    /** S3 - seccfg_get_lock_state() always returns LKS_UNLOCKED (3). */
    private List<Patch> patchLockState(int isLockedOff) {
        int end = isLockedOff;
        while (end < Math.min(isLockedOff + 0x80, data.length - 4)) {
            end += 4;
            if (isRet(word(end))) break;
        }
        List<Integer> callees = new ArrayList<>();
        for (int off = isLockedOff; off < end; off += 4) {
            if (isBl(word(off))) callees.add(blTarget(word(off), off));
        }
        if (callees.isEmpty()) {
            throw new IllegalStateException("is_device_locked calls nothing");
        }
        int first = callees.get(0);
        for (int callee : callees) {
            if (callee != first) {
                throw new IllegalStateException("is_device_locked calls more than one function");
            }
        }
        int off = first;
        if (!looksLikeLockState(off)) {
            throw new IllegalStateException("0x" + Integer.toHexString(off)
                    + " does not look like seccfg_get_lock_state");
        }
        int stop = off;
        while (stop < Math.min(off + 0x40, data.length - 4)) {
            stop += 4;
            if (isRet(word(stop))) break;
        }
        int span = stop + 4 - off;
        if (span < STUB_LOCK_STATE.length * 4 || span % 4 != 0) {
            throw new IllegalStateException("seccfg_get_lock_state has an unexpected length");
        }
        int[] replacement = new int[span / 4];
        for (int i = 0; i < replacement.length; i++) {
            replacement[i] = i < STUB_LOCK_STATE.length ? STUB_LOCK_STATE[i] : NOP;
        }
        return Collections.singletonList(new Patch("S3", off, slice(off, span), words(replacement),
                "seccfg_get_lock_state(): always return LKS_UNLOCKED(3)"));
    }

    private boolean looksLikeLockState(int off) {
        if (off + 20 > data.length) return false;
        int adrp = word(off);
        int ldrb = word(off + 4);
        int cb = word(off + 8);
        int br = word(off + 12);
        int mov = word(off + 16);
        if ((adrp & 0x9F000000) != 0x90000000) return false;
        if ((ldrb & 0xFFC00000) != 0x39400000) return false;
        if ((ldrb & 0x1F) != 8 || ((ldrb >>> 5) & 0x1F) != 8) return false;
        if (!isCbz(cb) || (cb & 0x1F) != 8) return false;
        if (!isB(br)) return false;
        if ((mov & 0xFFE00000) != 0x52800000) return false;
        if ((mov & 0x1F) != 8) return false;
        return ((mov >>> 5) & 0xFFFF) == 4;
    }

    /** S4 - is_device_locked() always answers "not locked". */
    private ResultWithOffset patchIsLocked() {
        List<Integer> hits = findNeedle(data, NEEDLE_IS_LOCKED_TAIL);
        if (hits.isEmpty()) throw new IllegalStateException("is_device_locked was not found");
        if (hits.size() > 1) {
            throw new IllegalStateException("is_device_locked is not unique ("
                    + hits.size() + " hits)");
        }
        int tail = hits.get(0);
        int start = funcStart(tail, 0x80);
        if (start < 0) throw new IllegalStateException("the start of is_device_locked is missing");
        Patch patch = new Patch("S4", start, slice(start, 32), words(STUB_NOT_LOCKED),
                "is_device_locked(): always report not-locked");
        return new ResultWithOffset(patch, start);
    }

    /** S5 - nop every call of is_device_locked. */
    private List<Patch> patchLockedCallers(int isLockedOff) {
        List<Patch> patches = new ArrayList<>();
        for (int off : findCalls(isLockedOff)) {
            int w = word(off);
            patches.add(new Patch("S5@0x" + Integer.toHexString(off), off, le(w), le(NOP),
                    "ignore is_device_locked (" + describe(w, off) + " -> nop)"));
        }
        return patches;
    }

    /** Looks at the image and collects every patch that would be made. */
    public Analysis analyse() {
        Analysis analysis = new Analysis();
        analysis.patches.addAll(patchUnlockHandler());
        analysis.patches.addAll(patchUnlockAbility());
        ResultWithOffset s4 = patchIsLocked();
        analysis.patches.add(s4.patch);
        analysis.patches.addAll(patchLockState(s4.offset));
        analysis.patches.addAll(patchLockedCallers(s4.offset));
        analysis.patches.sort(Comparator.comparingInt(p -> p.offset));
        for (byte[] anchor : ANCHORS) {
            analysis.notes.add("anchor '" + new String(anchor, StandardCharsets.US_ASCII) + "' "
                    + (findString(anchor) >= 0 ? "present" : "ABSENT"));
        }
        return analysis;
    }

    /** Writes the patches into the image. */
    public void apply(List<Patch> patches) {
        for (Patch patch : patches) patch.apply(data);
    }

    private byte[] slice(int offset, int length) {
        byte[] out = new byte[Math.min(length, data.length - offset)];
        System.arraycopy(data, offset, out, 0, out.length);
        return out;
    }

    private static byte[] le(int word) {
        return new byte[]{(byte) word, (byte) (word >>> 8), (byte) (word >>> 16), (byte) (word >>> 24)};
    }

    private static byte[] words(int[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int value : values) {
            byte[] bytes = le(value);
            out.write(bytes, 0, bytes.length);
        }
        return out.toByteArray();
    }

    private static final class ResultWithOffset {
        final Patch patch;
        final int offset;

        ResultWithOffset(Patch patch, int offset) {
            this.patch = patch;
            this.offset = offset;
        }
    }
}
