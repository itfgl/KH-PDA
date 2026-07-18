package com.kaihang.scanner.plugins;

/** Normalizes scanner-driver protocol decorations without changing business codes. */
public final class ScanValueNormalizer {
    private static final int ENCODED_PREFIX_DIGITS = 6;
    private static final int ENCODED_PREFIX_LENGTH = 1 + ENCODED_PREFIX_DIGITS;

    private ScanValueNormalizer() {}

    public static String normalize(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (!hasEncodedChinesePrefix(value)) return value;
        return value.substring(ENCODED_PREFIX_LENGTH).trim();
    }

    private static boolean hasEncodedChinesePrefix(String value) {
        if (value.length() <= ENCODED_PREFIX_LENGTH || value.charAt(0) != '\\') return false;
        for (int i = 1; i < ENCODED_PREFIX_LENGTH; i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        for (int offset = ENCODED_PREFIX_LENGTH; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (isCjkCodePoint(codePoint)) return true;
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean isCjkCodePoint(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4DBF)
            || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
            || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
            || (codePoint >= 0x20000 && codePoint <= 0x2FA1F);
    }
}
