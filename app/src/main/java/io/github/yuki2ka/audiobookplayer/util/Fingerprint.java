package io.github.yuki2ka.audiobookplayer.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Content fingerprint used to recognise an audio file independently of its path.
 *
 * <p>Hashing a whole audiobook (hundreds of MB) on every open would be far too
 * slow, so the digest covers the file length plus the first and the last
 * 64 KiB. Audiobook files are split by chapter, so a re-encoded or replaced
 * file practically always changes in one of those regions, while a renamed or
 * moved file keeps an identical digest.</p>
 *
 * <p>Cost: at most 128 KiB of reads, typically well under 10 ms on phone
 * storage; allocation is a single 64 KiB buffer.</p>
 */
public final class Fingerprint {

    /** SHA-256 over (version tag, file length, first 64 KiB, last 64 KiB). */
    private static final int CHUNK = 64 * 1024;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Fingerprint() {
    }

    public static String of(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Android version ships SHA-256; this is unreachable.
            throw new IOException("SHA-256 unavailable", e);
        }

        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            long length = raf.length();
            digest.update((byte) 'v');
            digest.update((byte) '1');
            for (int shift = 56; shift >= 0; shift -= 8) {
                digest.update((byte) (length >>> shift));
            }

            byte[] buffer = new byte[CHUNK];
            int first = (int) Math.min(CHUNK, length);
            if (first > 0) {
                raf.readFully(buffer, 0, first);
                digest.update(buffer, 0, first);
            }
            if (length > CHUNK) {
                long offset = Math.max(first, length - CHUNK);
                raf.seek(offset);
                int last = (int) (length - offset);
                raf.readFully(buffer, 0, last);
                digest.update(buffer, 0, last);
            }
        } finally {
            try {
                raf.close();
            } catch (IOException ignored) {
                // Nothing useful to do.
            }
        }

        byte[] hash = digest.digest();
        char[] out = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            int value = hash[i] & 0xFF;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(out);
    }

    /** Convenience wrapper: returns {@code null} when the file cannot be read. */
    public static String ofPath(String path) {
        try {
            return of(new File(path));
        } catch (IOException e) {
            return null;
        }
    }
}
