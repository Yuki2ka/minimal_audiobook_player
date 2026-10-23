import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import io.github.yuki2ka.audiobookplayer.model.AudioFile;
import io.github.yuki2ka.audiobookplayer.model.FolderNode;
import io.github.yuki2ka.audiobookplayer.model.ScanResult;
import io.github.yuki2ka.audiobookplayer.playback.PlayQueue;
import io.github.yuki2ka.audiobookplayer.scan.Scanner;
import io.github.yuki2ka.audiobookplayer.util.Fingerprint;
import io.github.yuki2ka.audiobookplayer.util.SeekCurve;
import io.github.yuki2ka.audiobookplayer.util.NaturalSort;
import io.github.yuki2ka.audiobookplayer.util.TimeFormat;

/**
 * Plain-Java test runner for everything that does not depend on Android.
 * Run with tools/run-tests.sh. Exits non-zero if a test fails.
 */
public final class LogicTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.out.println("Running logic tests (locale: " + java.util.Locale.getDefault() + ")");
        testTimeFormat();
        testSeekCurve();
        testNaturalSortBasics();
        testNaturalSortUnicode();
        testDisplayName();
        testFingerprint();
        testPlayQueue();
        testScanner();

        System.out.println();
        System.out.println(passed + " assertions passed, " + failures.size() + " failed");
        for (String failure : failures) {
            System.out.println("  FAILED: " + failure);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ helpers

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failures.add(name);
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        check(name + " (expected <" + expected + ">, got <" + actual + ">)",
                Objects.equals(expected, actual));
    }

    private static void less(String a, String b) {
        int ab = NaturalSort.compare(a, b);
        int ba = NaturalSort.compare(b, a);
        check("'" + a + "' < '" + b + "' (got " + ab + ")", ab < 0);
        check("antisymmetry for '" + a + "' / '" + b + "'", Integer.signum(ab) == -Integer.signum(ba));
    }

    private static File writeFile(File dir, String name, String content) throws IOException {
        File file = new File(dir, name);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            check("mkdirs " + parent, parent.mkdirs());
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        return file;
    }

    private static File tempDir() throws IOException {
        return Files.createTempDirectory("abp-test").toFile();
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    // ------------------------------------------------------------ tests

    private static void testTimeFormat() {
        eq("clock(0)", "0:00", TimeFormat.clock(0));
        eq("clock(999)", "0:00", TimeFormat.clock(999));
        eq("clock(1000)", "0:01", TimeFormat.clock(1000));
        eq("clock(59000)", "0:59", TimeFormat.clock(59000));
        eq("clock(60000)", "1:00", TimeFormat.clock(60000));
        eq("clock(3599000)", "59:59", TimeFormat.clock(3599000));
        eq("clock(3600000)", "1:00:00", TimeFormat.clock(3600000));
        eq("clock(3661000)", "1:01:01", TimeFormat.clock(3661000));
        eq("clock(36000000)", "10:00:00", TimeFormat.clock(36000000));
        eq("clock(-5)", "0:00", TimeFormat.clock(-5));
        eq("clamp long", 60L, TimeFormat.clamp(99L, 0L, 60L));
        eq("clamp int", 1, TimeFormat.clamp(-3, 1, 60));
    }

    private static void testSeekCurve() {
        // The pad maps "how far from the play button" to 2..60 s.
        eq("curve min at 0", SeekCurve.MIN_SECONDS, SeekCurve.secondsFor(0f));
        eq("curve max at 1", SeekCurve.MAX_SECONDS, SeekCurve.secondsFor(1f));
        eq("curve clamps below 0", SeekCurve.MIN_SECONDS, SeekCurve.secondsFor(-3f));
        eq("curve clamps above 1", SeekCurve.MAX_SECONDS, SeekCurve.secondsFor(7f));

        // Inner edge stays fine-grained, the middle is a useful jump.
        int quarter = SeekCurve.secondsFor(0.25f);
        int half = SeekCurve.secondsFor(0.5f);
        int threeQuarters = SeekCurve.secondsFor(0.75f);
        eq("curve at 0.25 is 6 s", 6, quarter);
        eq("curve at 0.5 is 17 s", 17, half);
        eq("curve at 0.75 is 35 s", 35, threeQuarters);
        check("0.1 is a small nudge (" + SeekCurve.secondsFor(0.1f) + " s)",
                SeekCurve.secondsFor(0.1f) <= 4);
        check("first fifth is fine", SeekCurve.secondsFor(0.2f) <= 8);

        // Monotonic, never out of range, and always whole seconds.
        int previous = -1;
        for (int i = 0; i <= 100; i++) {
            int value = SeekCurve.secondsFor(i / 100f);
            check("curve monotonic at " + i, value >= previous);
            check("curve in range at " + i,
                    value >= SeekCurve.MIN_SECONDS && value <= SeekCurve.MAX_SECONDS);
            previous = value;
        }
        eq("curve milliseconds", 60000L, SeekCurve.millisecondsFor(1f));
        // The default 10 s step is reachable with a comfortable touch.
        boolean reachable = false;
        for (int i = 0; i <= 100; i++) {
            if (SeekCurve.secondsFor(i / 100f) == 10) {
                reachable = true;
                break;
            }
        }
        check("10 s is reachable on the pad", reachable);
    }

    private static void testNaturalSortBasics() {
        less("Chapter 1", "Chapter 2");
        less("Chapter 2", "Chapter 10");
        less("Chapter 9", "Chapter 10");
        less("Chapter 10", "Chapter 100");
        less("track 001", "track 01a");
        less("track 9", "track 10");

        List<String> list = new ArrayList<>(Arrays.asList(
                "Chapter 10.mp3", "Chapter 2.mp3", "Chapter 1.mp3"));
        NaturalSort.sort(list);
        eq("sort chapters", Arrays.asList(
                "Chapter 1.mp3", "Chapter 2.mp3", "Chapter 10.mp3"), list);

        // Case-insensitivity plus deterministic tie-break.
        check("case-insensitive ordering", NaturalSort.compare("alpha", "Alpha") != 0);
        check("ordering is deterministic",
                Integer.signum(NaturalSort.compare("cafe", "café"))
                        == -Integer.signum(NaturalSort.compare("café", "cafe")));

        // Accents stay adjacent instead of ending up after 'z'.
        List<String> accents = new ArrayList<>(Arrays.asList("café", "cafe", "cafz"));
        NaturalSort.sort(accents);
        eq("accent placement", Arrays.asList("cafe", "café", "cafz"), accents);

        // Empty and null-safe
        check("empty < non-empty", NaturalSort.compare("", "a") < 0);
        eq("both null", 0, NaturalSort.compare(null, null));
        check("null < value", NaturalSort.compare(null, "a") < 0);
    }

    private static void testNaturalSortUnicode() {
        // Cyrillic
        less("Анна Каренина", "Борис Годунов");
        less("Глава 2", "Глава 10");
        // Greek (accent-insensitive at PRIMARY strength)
        less("άλφα", "βήτα");
        // Japanese kana
        less("あ", "い");
        less("トラック 2", "トラック 10");
        // Korean
        less("가", "나");
        // Hebrew
        less("א", "ב");

        List<String> sorted = new ArrayList<>(Arrays.asList(
                "ファイル 10", "ファイル 2", "ファイル 1"));
        NaturalSort.sort(sorted);
        eq("Japanese numeric sort", Arrays.asList("ファイル 1", "ファイル 2", "ファイル 10"), sorted);

        // Determinism for scripts where collation may be ambiguous.
        String[] tricky = {"الأول", "الثاني", "📗 Book", "📘 Book", "北", "東"};
        for (String a : tricky) {
            for (String b : tricky) {
                int ab = Integer.signum(NaturalSort.compare(a, b));
                int ba = Integer.signum(NaturalSort.compare(b, a));
                check("total order " + a + " / " + b, ab == -ba);
            }
        }

        // Normalisation helper (NFC) - decomposed "e" + acute becomes "é".
        eq("NFC normalisation", "é", NaturalSort.normalize("e\u0301"));
        check("NFC is idempotent",
                NaturalSort.normalize(NaturalSort.normalize("Über")).equals("Über"));
    }

    private static void testDisplayName() {
        eq("strip extension", "01 - Kapitel", AudioFile.displayName("01 - Kapitel.mp3"));
        eq("m4b", "Chapter", AudioFile.displayName("Chapter.m4b"));
        eq("no extension", "noext", AudioFile.displayName("noext"));
        eq("dot inside name", "book 1.2", AudioFile.displayName("book 1.2.mp3"));
        eq("cyrillic", "Книга 1", AudioFile.displayName("Книга 1.mp3"));
        eq("NFC", "é", AudioFile.displayName("e\u0301.mp3"));
        eq("empty-ish", ".mp3", AudioFile.displayName(".mp3"));
    }

    private static void testFingerprint() throws Exception {
        File dir = tempDir();
        try {
            File a = writeFile(dir, "a.mp3", "hello world");
            File copy = writeFile(dir, "a - копия.mp3", "hello world");
            String fa = Fingerprint.of(a);
            String fc = Fingerprint.of(copy);
            eq("fingerprint length", 64, fa.length());
            eq("same content, different name", fa, fc);

            File changed = writeFile(dir, "b.mp3", "hello worlD");
            check("different content", !fa.equals(Fingerprint.of(changed)));

            File empty1 = writeFile(dir, "empty1.mp3", "");
            File empty2 = writeFile(dir, "empty2.mp3", "");
            eq("empty files equal", Fingerprint.of(empty1), Fingerprint.of(empty2));

            // Large file: first and last 64 KiB are hashed, the middle is not.
            byte[] big = new byte[300 * 1024];
            new Random(7).nextBytes(big);
            File big1 = new File(dir, "big1.flac");
            FileOutputStream out = new FileOutputStream(big1);
            out.write(big);
            out.close();
            String fBig = Fingerprint.of(big1);

            byte[] middleChanged = big.clone();
            middleChanged[150 * 1024] ^= 0x7F;
            File big2 = new File(dir, "big2.flac");
            out = new FileOutputStream(big2);
            out.write(middleChanged);
            out.close();
            eq("middle change is outside the sampled range", fBig, Fingerprint.of(big2));

            byte[] startChanged = big.clone();
            startChanged[10] ^= 0x7F;
            File big3 = new File(dir, "big3.flac");
            out = new FileOutputStream(big3);
            out.write(startChanged);
            out.close();
            check("first chunk change detected", !fBig.equals(Fingerprint.of(big3)));

            byte[] endChanged = big.clone();
            endChanged[endChanged.length - 10] ^= 0x7F;
            File big4 = new File(dir, "big4.flac");
            out = new FileOutputStream(big4);
            out.write(endChanged);
            out.close();
            check("last chunk change detected", !fBig.equals(Fingerprint.of(big4)));

            eq("unreadable path", null, Fingerprint.ofPath("/does/not/exist.mp3"));
        } finally {
            deleteRecursively(dir);
        }
    }

    private static AudioFile audio(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return new AudioFile(path, AudioFile.displayName(name), "folder", 1L, 1L);
    }

    private static void testPlayQueue() {
        AudioFile a = audio("/b/a.mp3");
        AudioFile b = audio("/b/b.mp3");
        AudioFile c = audio("/b/c.mp3");
        AudioFile d = audio("/b/d.mp3");
        List<AudioFile> sorted = new ArrayList<>(Arrays.asList(a, b, c, d));

        PlayQueue queue = new PlayQueue();
        queue.reset(sorted, c.path);
        List<AudioFile> items = queue.items();
        eq("anchor rotated to front", Arrays.asList(c, d, a, b), items);
        eq("anchor path", c.path, queue.anchor());
        check("list instance reused", items == queue.items());

        AudioFile next = queue.advanceFrom(c.path);
        eq("advance returns next", d, next);
        eq("finished file moved to bottom", Arrays.asList(d, a, b, c), queue.items());
        eq("anchor updated", d.path, queue.anchor());

        AudioFile previous = queue.retreatFrom(d.path);
        eq("retreat returns last", c, previous);
        eq("retreat moves last to front", Arrays.asList(c, d, a, b), queue.items());

        queue.reset(sorted, null);
        eq("missing anchor keeps sorted order", sorted, queue.items());
        queue.promoteToFront(2);
        eq("promote rotates list", Arrays.asList(c, d, a, b), queue.items());
        eq("promote anchor", c.path, queue.anchor());

        PlayQueue single = new PlayQueue();
        single.reset(Collections.singletonList(a), a.path);
        eq("single item advance", null, single.advanceFrom(a.path));
        eq("single item retreat", null, single.retreatFrom(a.path));
        eq("single item order", Collections.singletonList(a), single.items());

        PlayQueue empty = new PlayQueue();
        empty.reset(Collections.<AudioFile>emptyList(), null);
        eq("empty anchor", null, empty.anchor());
        eq("empty advance", null, empty.advanceFrom(null));
        check("empty queue", empty.isEmpty());

        // Advancing from a file that is not the head rotates to it first.
        PlayQueue other = new PlayQueue();
        other.reset(sorted, null);
        AudioFile fromB = other.advanceFrom(b.path);
        eq("advance from b", c, fromB);
        eq("order after advance from b", Arrays.asList(c, d, a, b), other.items());
    }

    private static void testScanner() throws Exception {
        File root = tempDir();
        try {
            writeFile(root, "Книга 1/Глава 1.mp3", "1");
            writeFile(root, "Книга 1/Глава 2.mp3", "2");
            writeFile(root, "Книга 1/Глава 10.mp3", "10");
            writeFile(root, "Книга 2/01 - Intro.m4b", "i");
            writeFile(root, "Книга 2/cover.jpg", "img");
            writeFile(root, "Книга 2/notes.txt", "text");
            writeFile(root, ".hidden/secret.mp3", "s");
            writeFile(root, "Über/Ödön.flac", "f");
            writeFile(root, "Über/Прочитано 3.ogg", "o");

            ScanResult result = Scanner.scan(root.getAbsolutePath(), null);
            eq("scan error", null, result.error);
            eq("file count", 6, result.count());

            FolderNode book1 = result.nodes.get(new File(root, "Книга 1").getAbsolutePath());
            check("book1 node exists", book1 != null);
            if (book1 != null) {
                List<String> names = new ArrayList<>();
                for (AudioFile file : book1.files) {
                    names.add(file.name);
                }
                eq("natural order inside folder",
                        Arrays.asList("Глава 1", "Глава 2", "Глава 10"), names);
                eq("recursive file count", 3, book1.totalFiles);
                eq("parent name uses folder display name", "Книга 1", book1.files.get(0).parentName);
            }

            FolderNode rootNode = result.rootNode;
            eq("root recursive count", 6, rootNode.totalFiles);
            eq("subfolder count", 3, rootNode.folders.size());

            // Hidden entries are skipped.
            for (AudioFile file : result.allFiles) {
                check("no hidden file " + file.path, !file.path.contains(".hidden"));
            }

            // Unicode folder names are preserved, not mangled.
            boolean foundUber = false;
            for (FolderNode node : rootNode.folders) {
                if ("Über".equals(node.name)) {
                    foundUber = true;
                    eq("unicode folder file count", 2, node.totalFiles);
                }
            }
            check("unicode folder found", foundUber);

            // Non-art directories are not included.
            eq("no cover/notes", 6, result.allFiles.size());

            // Nonexistent root produces a clean error instead of throwing.
            ScanResult missing = Scanner.scan(new File(root, "nope").getAbsolutePath(), null);
            eq("missing root error", "missing", missing.error);
            eq("missing root count", 0, missing.count());

            File notDir = writeFile(root, "plain.mp3", "x");
            ScanResult wrong = Scanner.scan(notDir.getAbsolutePath(), null);
            eq("not a directory error", "not_a_directory", wrong.error);

            // Progress callback is invoked with increasing counts.
            final int[] lastProgress = {-1};
            File many = new File(root, "Много");
            check("mkdir many", many.mkdirs());
            for (int i = 0; i < 300; i++) {
                writeFile(many, "Файл " + i + ".mp3", "x");
            }
            ScanResult big = Scanner.scan(root.getAbsolutePath(), new Scanner.Progress() {
                @Override
                public void onProgress(int files) {
                    if (files <= lastProgress[0]) {
                        failures.add("progress not increasing: " + files);
                    }
                    lastProgress[0] = files;
                }
            });
            eq("large scan count", 307, big.count());
            check("progress reached 250", lastProgress[0] >= 250);
        } finally {
            deleteRecursively(root);
        }
    }
}
