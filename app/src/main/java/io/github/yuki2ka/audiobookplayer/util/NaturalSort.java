package io.github.yuki2ka.audiobookplayer.util;

import java.text.Collator;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;

/**
 * Natural ("human") ordering for file and folder names.
 *
 * <p>Differences to plain {@link String#compareTo(String)}:</p>
 * <ul>
 *   <li>digit runs are compared numerically: {@code Chapter 2 < Chapter 10};</li>
 *   <li>comparison uses a {@link Collator} at PRIMARY strength, so case and
 *       accents do not split the list ({@code café} and {@code cafe} are
 *       adjacent) and Cyrillic, Greek, CJK or Arabic names are ordered by the
 *       rules of the user's locale instead of UTF-16 code units;</li>
 *   <li>a final code-point comparison makes the order total and stable even
 *       when the collator considers two names equal (for example
 *       {@code Chapter 01} vs {@code Chapter 1}).</li>
 * </ul>
 *
 * <p>All inputs are expected to be NFC normalised (see {@link #normalize(String)});
 * the scanner and the folder browser do this once per name.</p>
 *
 * <p>Thread safety: a single {@link Collator} instance is not thread-safe, so one
 * instance per thread is kept. The public comparator can therefore be shared
 * between the scanner thread and the UI thread.</p>
 */
public final class NaturalSort {

    private static final ThreadLocal<Collator> COLLATOR = new ThreadLocal<Collator>() {
        @Override
        protected Collator initialValue() {
            Collator collator = Collator.getInstance();
            collator.setStrength(Collator.PRIMARY);
            collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
            return collator;
        }
    };

    /** Shared comparator for strings. Safe to use from multiple threads. */
    public static final Comparator<String> STRINGS = new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            return NaturalSort.compare(a, b);
        }
    };

    private NaturalSort() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        // NFC makes composed and decomposed spellings of the same name compare
        // and render identically (e.g. "é" as U+00E9 vs "e" + U+0301).
        return Normalizer.isNormalized(value, Normalizer.Form.NFC)
                ? value
                : Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    /** Sorts a list in place using natural, locale-aware ordering. */
    public static void sort(List<String> values) {
        java.util.Collections.sort(values, STRINGS);
    }

    public static int compare(String a, String b) {
        if (a == null) {
            return b == null ? 0 : -1;
        }
        if (b == null) {
            return 1;
        }
        int i = 0;
        int j = 0;
        int n = a.length();
        int m = b.length();

        while (i < n && j < m) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            boolean digitA = isAsciiDigit(ca);
            boolean digitB = isAsciiDigit(cb);

            if (digitA && digitB) {
                int startA = i;
                int startB = j;
                while (i < n && isAsciiDigit(a.charAt(i))) {
                    i++;
                }
                while (j < m && isAsciiDigit(b.charAt(j))) {
                    j++;
                }
                // Ignore leading zeros: "007" == "7".
                int sigA = startA;
                while (sigA < i - 1 && a.charAt(sigA) == '0') {
                    sigA++;
                }
                int sigB = startB;
                while (sigB < j - 1 && b.charAt(sigB) == '0') {
                    sigB++;
                }
                int lengthA = i - sigA;
                int lengthB = j - sigB;
                if (lengthA != lengthB) {
                    return lengthA < lengthB ? -1 : 1;
                }
                for (int k = 0; k < lengthA; k++) {
                    char digitAChar = a.charAt(sigA + k);
                    char digitBChar = b.charAt(sigB + k);
                    if (digitAChar != digitBChar) {
                        return digitAChar < digitBChar ? -1 : 1;
                    }
                }
                // Same number, continue with the following runs.
            } else if (!digitA && !digitB) {
                int startA = i;
                int startB = j;
                while (i < n && !isAsciiDigit(a.charAt(i))) {
                    i++;
                }
                while (j < m && !isAsciiDigit(b.charAt(j))) {
                    j++;
                }
                int result = COLLATOR.get().compare(a.substring(startA, i), b.substring(startB, j));
                if (result != 0) {
                    return result;
                }
            } else {
                // A digit run against a letter run: digits sort first, which is
                // what file managers do ("1 - Intro" before "A - Intro").
                return digitA ? -1 : 1;
            }
        }

        if (i < n) {
            return 1;
        }
        if (j < m) {
            return -1;
        }
        // Total, deterministic order for cases the collator treats as equal.
        return a.compareTo(b);
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
