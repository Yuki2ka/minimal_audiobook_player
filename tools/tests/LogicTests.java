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
import io.github.yuki2ka.audiobookplayer.model.FileListRows;
import io.github.yuki2ka.audiobookplayer.model.FolderNode;
import io.github.yuki2ka.audiobookplayer.model.QueueEntry;
import io.github.yuki2ka.audiobookplayer.model.ScanResult;
import io.github.yuki2ka.audiobookplayer.model.TreeOrder;
import io.github.yuki2ka.audiobookplayer.playback.PlayQueue;
import io.github.yuki2ka.audiobookplayer.scan.Scanner;
import io.github.yuki2ka.audiobookplayer.util.DiscreteDrag;
import io.github.yuki2ka.audiobookplayer.util.Fingerprint;
import io.github.yuki2ka.audiobookplayer.util.SeekCurve;
import io.github.yuki2ka.audiobookplayer.util.NaturalSort;
import io.github.yuki2ka.audiobookplayer.util.PlaybackSpeed;
import io.github.yuki2ka.audiobookplayer.util.TimeFormat;
import io.github.yuki2ka.audiobookplayer.util.VolumeGesture;

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
        testVolumeGesture();
        testSeekZoneMapping();
        testPlaybackSpeed();
        testDiscreteDrag();
        testTreeOrder();
        testFileListRows();
        testNaturalSortBasics();
        testNaturalSortUnicode();
        testDisplayName();
        testFingerprint();
        testPlayQueue();
        testQueueRotationKeepsFolders();
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

        // Step seek (volume keys, -N/+N buttons) is clamped to the track.
        eq("step forward", 60000, SeekCurve.steppedPosition(50000, 1, 10000, 300000));
        eq("step back", 40000, SeekCurve.steppedPosition(50000, -1, 10000, 300000));
        eq("step back clamps at 0", 0, SeekCurve.steppedPosition(3000, -1, 10000, 300000));
        eq("step forward clamps at the end",
                300000, SeekCurve.steppedPosition(295000, 1, 10000, 300000));
        eq("unknown duration keeps the position",
                50000, SeekCurve.steppedPosition(50000, 1, 10000, 0));
        eq("zero direction counts as forward",
                60000, SeekCurve.steppedPosition(50000, 0, 10000, 300000));
        eq("huge step clamps", 300000, SeekCurve.steppedPosition(0, 1, Integer.MAX_VALUE, 300000));

        // Pad geometry: 0 next to the play button, 1 at the outer edge, on both
        // sides. The back pad is the forward pad mirrored.
        eq("forward at inner edge", 0f, SeekCurve.fraction(1, 0f, 200));
        eq("forward at outer edge", 1f, SeekCurve.fraction(1, 200f, 200));
        eq("forward in the middle", 0.5f, SeekCurve.fraction(1, 100f, 200));
        eq("back at inner edge", 0f, SeekCurve.fraction(-1, 200f, 200));
        eq("back at outer edge", 1f, SeekCurve.fraction(-1, 0f, 200));
        eq("back in the middle", 0.5f, SeekCurve.fraction(-1, 100f, 200));
        for (int i = 0; i <= 20; i++) {
            float x = i * 10f;
            check("mirrored at x=" + x,
                    Math.abs(SeekCurve.fraction(1, x, 200) - SeekCurve.fraction(-1, 200 - x, 200)) < 1e-6);
        }
        eq("fraction clamps negative x", 0f, SeekCurve.fraction(1, -50f, 200));
        eq("fraction clamps past the edge", 1f, SeekCurve.fraction(1, 500f, 200));
        eq("fraction with zero width", 0f, SeekCurve.fraction(-1, 10f, 0));
        // The tap nearest the play button must be the smallest seek on both sides.
        eq("back: inner edge = min", SeekCurve.MIN_SECONDS,
                SeekCurve.secondsFor(SeekCurve.fraction(-1, 200f, 200)));
        eq("back: outer edge = max", SeekCurve.MAX_SECONDS,
                SeekCurve.secondsFor(SeekCurve.fraction(-1, 0f, 200)));
        eq("forward: inner edge = min", SeekCurve.MIN_SECONDS,
                SeekCurve.secondsFor(SeekCurve.fraction(1, 0f, 200)));
        eq("forward: outer edge = max", SeekCurve.MAX_SECONDS,
                SeekCurve.secondsFor(SeekCurve.fraction(1, 200f, 200)));
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

    /**
     * The volume-key gesture must never misfire: a tap seeks, a hold changes the
     * volume, and nothing at all may ever be silently swallowed.
     */
    private static void testVolumeGesture() {
        // Short press = a down and a quick release.
        VolumeGesture tap = new VolumeGesture();
        eq("tap down does nothing yet", VolumeGesture.NONE, tap.onAdjust(1, 0));
        check("tap is pressing", tap.isPressing());
        eq("tap release seeks", VolumeGesture.SEEK, tap.onAdjust(0, 100));
        check("tap released", !tap.isPressing());
        eq("tap seeks forward", 1, tap.direction());

        VolumeGesture back = new VolumeGesture();
        eq("back down", VolumeGesture.NONE, back.onAdjust(-1, 0));
        eq("back release seeks", VolumeGesture.SEEK, back.onAdjust(0, 120));
        eq("back seeks backward", -1, back.direction());

        // A release with no press in flight is ignored.
        eq("stray release", VolumeGesture.NONE, new VolumeGesture().onAdjust(0, 10));

        // Hold on a device that repeats: the repeat is the volume gesture, and
        // the release must not seek afterwards.
        VolumeGesture hold = new VolumeGesture();
        eq("hold down", VolumeGesture.NONE, hold.onAdjust(1, 0));
        eq("first repeat changes volume", VolumeGesture.VOLUME, hold.onAdjust(1, 500));
        eq("second repeat changes volume", VolumeGesture.VOLUME, hold.onAdjust(1, 550));
        eq("hold release does nothing", VolumeGesture.NONE, hold.onAdjust(0, 900));
        check("hold finished", !hold.isPressing());

        // Hold on a device that does not repeat: only the duration is left.
        VolumeGesture slowHold = new VolumeGesture();
        eq("slow down", VolumeGesture.NONE, slowHold.onAdjust(-1, 0));
        eq("slow release changes volume", VolumeGesture.VOLUME, slowHold.onAdjust(0, 800));
        eq("slow hold direction", -1, slowHold.direction());

        // The threshold itself belongs to the hold, not to the tap.
        VolumeGesture edge = new VolumeGesture();
        edge.onAdjust(1, 0);
        eq("just under threshold seeks", VolumeGesture.SEEK,
                edge.onAdjust(0, VolumeGesture.SHORT_PRESS_MS - 1));
        VolumeGesture edgeHold = new VolumeGesture();
        edgeHold.onAdjust(1, 0);
        eq("threshold changes volume", VolumeGesture.VOLUME,
                edgeHold.onAdjust(0, VolumeGesture.SHORT_PRESS_MS));

        // Two taps in a row are two seeks, not one hold.
        VolumeGesture repeatTap = new VolumeGesture();
        repeatTap.onAdjust(1, 0);
        eq("first of two taps", VolumeGesture.SEEK, repeatTap.onAdjust(0, 100));
        repeatTap.onAdjust(1, 300);
        eq("second of two taps", VolumeGesture.SEEK, repeatTap.onAdjust(0, 400));

        // A swallowed release must still leave the key doing something.
        VolumeGesture lost = new VolumeGesture();
        eq("lost down", VolumeGesture.NONE, lost.onAdjust(1, 0));
        eq("timeout changes volume", VolumeGesture.VOLUME, lost.onTimeout());
        check("timeout clears press", !lost.isPressing());
        eq("late release ignored", VolumeGesture.NONE, lost.onAdjust(0, 3000));

        // reset() (screen on, pause) forgets the gesture in flight.
        VolumeGesture reset = new VolumeGesture();
        reset.onAdjust(1, 0);
        reset.reset();
        check("reset clears press", !reset.isPressing());
        eq("release after reset", VolumeGesture.NONE, reset.onAdjust(0, 100));
        eq("reset direction", 0, reset.direction());
    }

    /** The seek band maps a tap above the slider to the same spot on it. */
    private static void testPlaybackSpeed() {
        // Labels: what the button and the settings show.
        eq("label(100)", "x1", PlaybackSpeed.label(100));
        eq("label(110)", "x1.1", PlaybackSpeed.label(110));
        eq("label(130)", "x1.3", PlaybackSpeed.label(130));
        eq("label(200)", "x2", PlaybackSpeed.label(200));
        eq("label(125)", "x1.25", PlaybackSpeed.label(125));
        eq("label(float 1.3)", "x1.3", PlaybackSpeed.label(1.3f));
        eq("label(float 1.2999999)", "x1.3", PlaybackSpeed.label(1.2999999f));
        eq("label(-5) clamps", "x0", PlaybackSpeed.label(-5));

        // Percent <-> float round trips must be exact for every slider step.
        for (int percent = 100; percent <= 200; percent += 10) {
            eq("toPercent(toFloat(" + percent + "))", percent,
                    PlaybackSpeed.toPercent(PlaybackSpeed.toFloat(percent)));
        }
        eq("NORMAL_PERCENT is x1", "x1", PlaybackSpeed.label(PlaybackSpeed.NORMAL_PERCENT));
        check("100 is not fast", !PlaybackSpeed.isFast(100));
        check("110 is fast", PlaybackSpeed.isFast(110));

        // Press logic: paused → play, same button → pause, other → switch.
        eq("paused + normal button", PlaybackSpeed.ACTION_PLAY,
                PlaybackSpeed.actionFor(false, 100, 100));
        eq("paused + fast button", PlaybackSpeed.ACTION_PLAY,
                PlaybackSpeed.actionFor(false, 100, 130));
        eq("paused at fast speed + fast button", PlaybackSpeed.ACTION_PLAY,
                PlaybackSpeed.actionFor(false, 130, 130));
        eq("playing x1 + normal button", PlaybackSpeed.ACTION_PAUSE,
                PlaybackSpeed.actionFor(true, 100, 100));
        eq("playing x1.3 + fast button", PlaybackSpeed.ACTION_PAUSE,
                PlaybackSpeed.actionFor(true, 130, 130));
        eq("playing x1.3 + normal button", PlaybackSpeed.ACTION_SWITCH,
                PlaybackSpeed.actionFor(true, 130, 100));
        eq("playing x1 + fast button", PlaybackSpeed.ACTION_SWITCH,
                PlaybackSpeed.actionFor(true, 100, 130));
        // Same button twice: play, pause, play, pause (never a double action).
        boolean playing = false;
        int speed = 100;
        for (int i = 0; i < 6; i++) {
            int action = PlaybackSpeed.actionFor(playing, speed, 130);
            if (action == PlaybackSpeed.ACTION_PAUSE) {
                playing = false;
            } else {
                playing = true;
                speed = 130;
            }
            eq("toggle " + i + " playing", i % 2 == 0, playing);
        }

        // Settings slider grid: 1.1 … 2.0 in 0.1 steps.
        eq("min speed", 110, PlaybackSpeed.MIN_FAST_PERCENT);
        eq("max speed", 200, PlaybackSpeed.MAX_FAST_PERCENT);
        eq("default speed", 130, PlaybackSpeed.DEFAULT_FAST_PERCENT);
        eq("step count", 9, PlaybackSpeed.FAST_STEP_COUNT);
        eq("step 0", 110, PlaybackSpeed.fromStep(0));
        eq("step 2 (default)", 130, PlaybackSpeed.fromStep(2));
        eq("step 9", 200, PlaybackSpeed.fromStep(9));
        eq("step below 0 clamps", 110, PlaybackSpeed.fromStep(-4));
        eq("step above 9 clamps", 200, PlaybackSpeed.fromStep(42));
        // Every step is reachable in both directions, and non-grid values snap.
        for (int step = 0; step <= PlaybackSpeed.FAST_STEP_COUNT; step++) {
            int percent = PlaybackSpeed.fromStep(step);
            eq("round trip step " + step, step, PlaybackSpeed.toStep(percent));
            eq("label of step " + step, "x1.1".equals(PlaybackSpeed.label(percent)) || step > 0,
                    true);
        }
        eq("snap 134 -> 130", 130, PlaybackSpeed.snap(134));
        eq("snap 135 -> 140", 140, PlaybackSpeed.snap(135));
        eq("snap 300 clamps to max", 200, PlaybackSpeed.snap(300));
        eq("snap 5 clamps to min", 110, PlaybackSpeed.snap(5));
        eq("toStep(100) clamps to 0", 0, PlaybackSpeed.toStep(100));
        eq("default label", "x1.3", PlaybackSpeed.label(PlaybackSpeed.DEFAULT_FAST_PERCENT));
        eq("default slider position", 2, PlaybackSpeed.toStep(PlaybackSpeed.DEFAULT_FAST_PERCENT));
    }

    private static void testSeekZoneMapping() {
        float width = 1000f;
        float start = 14f;
        float end = 14f;

        // Edges of the usable track.
        eq("left edge", 0f, SeekCurve.fractionForX(0f, width, start, end));
        eq("track start", 0f, SeekCurve.fractionForX(start, width, start, end));
        eq("middle", 0.5f, SeekCurve.fractionForX(500f, width, start, end));
        eq("track end", 1f, SeekCurve.fractionForX(width - end, width, start, end));
        eq("right edge clamps", 1f, SeekCurve.fractionForX(width, width, start, end));
        eq("past left clamps", 0f, SeekCurve.fractionForX(-50f, width, start, end));

        // Progress follows the slider maximum (the app uses the duration in ms).
        eq("progress zero", 0, SeekCurve.progressForX(0f, width, start, end, 1000));
        eq("progress half", 500, SeekCurve.progressForX(500f, width, start, end, 1000));
        eq("progress full", 1000, SeekCurve.progressForX(width, width, start, end, 1000));
        eq("progress unknown duration", 0, SeekCurve.progressForX(500f, width, start, end, 0));

        // A zero-width view (before layout) must not divide by zero.
        eq("unlaid-out width", 0f, SeekCurve.fractionForX(10f, 0f, start, end));
        eq("insets wider than the view", 0f, SeekCurve.fractionForX(10f, 10f, 14f, 14f));

        // Without insets the mapping is a plain ratio, so a tap above the bar
        // lands on the same time as a tap on the bar at the same x.
        for (int x = 0; x <= 1000; x += 125) {
            float withInsets = SeekCurve.fractionForX(x, width, 0f, 0f);
            eq("plain ratio at " + x, x / width, withInsets);
        }
    }

    private static void testDiscreteDrag() {
        DiscreteDrag drag = new DiscreteDrag();
        eq("partial row does not update", 0, drag.update(-63, 64));
        eq("first crossed row updates during drag", 1, drag.update(-64, 64));
        eq("same row is not applied twice", 0, drag.update(-100, 64));
        eq("second crossed row is another delta", 1, drag.update(-128, 64));
        eq("release does not repeat delivered rows", 0, drag.finish(-128, 64));

        drag.reset();
        eq("two rows at once", 2, drag.update(-128, 64));
        eq("reversal reports inverse delta", -3, drag.update(64, 64));
        eq("reversal release is idempotent", 0, drag.finish(64, 64));

        drag.reset();
        eq("short upward swipe moves one on release", 1, drag.finish(-12, 64));
        drag.reset();
        eq("short downward swipe moves one on release", -1, drag.finish(12, 64));
        drag.reset();
        eq("zero distance does not move", 0, drag.finish(0, 64));
        eq("invalid extent is safe", 0, drag.update(-100, 0));
    }

    private static AudioFile inFolder(String folder, String name) {
        return new AudioFile("/root/" + folder + "/" + name + ".mp3",
                name, folder, 1L, 1L);
    }

    /** A library shaped like a real one: books, a sub-folder, single files. */
    private static FolderNode sampleTree() {
        FolderNode root = new FolderNode("/root", "root");
        FolderNode bookOne = new FolderNode("/root/Book 1", "Book 1");
        FolderNode partA = new FolderNode("/root/Book 1/Part A", "Part A");
        FolderNode bookTwo = new FolderNode("/root/Book 2", "Book 2");

        partA.files.add(new AudioFile("/root/Book 1/Part A/a1.mp3", "a1", "Part A", 1L, 1L));
        bookOne.files.add(new AudioFile("/root/Book 1/b1.mp3", "b1", "Book 1", 1L, 1L));
        bookOne.files.add(new AudioFile("/root/Book 1/b2.mp3", "b2", "Book 1", 1L, 1L));
        bookTwo.files.add(new AudioFile("/root/Book 2/c1.mp3", "c1", "Book 2", 1L, 1L));

        bookOne.folders.add(partA);
        root.folders.add(bookOne);
        root.folders.add(bookTwo);
        return root;
    }

    /** Compact shape of the rows: "Book 1/" opens, "/" closes, names otherwise. */
    private static List<String> shape(List<QueueEntry> entries) {
        List<String> rows = new ArrayList<>();
        for (QueueEntry entry : entries) {
            if (entry.isFile()) {
                rows.add(entry.file.name);
            } else if (entry.isFolderEnd()) {
                rows.add("/");
            } else {
                rows.add(entry.name + "/");
            }
        }
        return rows;
    }

    private static void testTreeOrder() {
        FolderNode root = sampleTree();
        List<QueueEntry> entries = TreeOrder.entries(root);

        List<String> expected = Arrays.asList(
                "Book 1/",          // opens Book 1
                "Part A/",          // sub-folder first, one level deeper
                "a1",               // Part A's own file
                "/",                // closes Part A
                "b1", "b2",         // Book 1's own files come after its sub-folders
                "/",                // closes Book 1
                "Book 2/",
                "c1",
                "/");
        eq("tree order", expected, shape(entries));

        eq("nested depth", 1, entries.get(1).depth);
        eq("own file depth", 0, entries.get(4).depth);
        eq("nested file depth", 1, entries.get(2).depth);
        eq("closing rows carry no name", null, entries.get(3).name);
        eq("closing row keeps the folder path", "/root/Book 1/Part A", entries.get(3).path);
        eq("root is not named", false, entries.get(0).isFolderEnd());
        eq("opening row names its folder", "Book 1", entries.get(0).name);

        // The play sequence is the visible order without the separators.
        List<AudioFile> files = TreeOrder.files(root);
        eq("play order", Arrays.asList("a1", "b1", "b2", "c1"), names(files));

        // A folder with no sub-folders still gets both separators.
        FolderNode flat = new FolderNode("/root", "root");
        FolderNode only = new FolderNode("/root/Only", "Only");
        only.files.add(new AudioFile("/root/Only/x.mp3", "x", "Only", 1L, 1L));
        flat.folders.add(only);
        eq("single folder shape", Arrays.asList("Only/", "x", "/"), shape(TreeOrder.entries(flat)));

        // A folder without any audio (a covers/text folder, or a folder whose
        // last file was just deleted) gets no separators at all.
        FolderNode withEmpty = sampleTree();
        FolderNode emptyFolder = new FolderNode("/root/Book 1/Notes", "Notes");
        withEmpty.folders.get(0).folders.add(emptyFolder);
        eq("empty folders are skipped", expected, shape(TreeOrder.entries(withEmpty)));

        FolderNode emptied = sampleTree();
        emptied.folders.get(0).folders.get(0).files.clear();
        eq("an emptied sub-folder disappears", Arrays.asList(
                "Book 1/", "b1", "b2", "/", "Book 2/", "c1", "/"),
                shape(TreeOrder.entries(emptied)));

        // Files directly in the library root are listed without a separator.
        FolderNode bare = new FolderNode("/root", "root");
        bare.files.add(new AudioFile("/root/y.mp3", "y", "root", 1L, 1L));
        eq("root files have no separator", Collections.singletonList("y"),
                shape(TreeOrder.entries(bare)));

        eq("null tree has no rows", 0, TreeOrder.entries(null).size());
        eq("empty tree has no files", 0, TreeOrder.files(new FolderNode("/root", "root")).size());
    }

    private static List<String> names(List<AudioFile> files) {
        List<String> names = new ArrayList<>();
        for (AudioFile file : files) {
            names.add(file.name);
        }
        return names;
    }

    private static void testFileListRows() {
        FolderNode root = sampleTree();
        FileListRows rows = new FileListRows();
        rows.rebuild(TreeOrder.entries(root));

        eq("rows are the queue rows", 10, rows.size());
        eq("first row names the folder", FileListRows.TYPE_FOLDER, rows.typeAt(0));
        eq("first row name", "Book 1", rows.folderNameAt(0));
        eq("second row is the nested folder", "Part A", rows.folderNameAt(1));
        eq("nested row depth", 1, rows.depthAt(1));
        eq("file row", "a1", rows.fileAt(2).name);
        check("closing row is a line", rows.isFolderEnd(3));
        eq("closing row has no name", null, rows.folderNameAt(3));
        check("file row is not a closing line", !rows.isFolderEnd(2));
        eq("file row has no folder name", null, rows.folderNameAt(2));

        // Rows map one-to-one onto the queue, which is what discrete scrolling
        // and the row selection rely on.
        for (int i = 0; i < rows.size(); i++) {
            eq("row " + i + " maps to itself", i, rows.queueIndexAtOrAfter(i));
        }
        eq("row past the end clamps", 9, rows.queueIndexAtOrAfter(40));

        FileListRows empty = new FileListRows();
        empty.rebuild(Collections.<QueueEntry>emptyList());
        eq("empty list has no rows", 0, empty.size());
        eq("empty list has no file", null, empty.fileAt(0));
        eq("empty list clamps to nothing", -1, empty.queueIndexAtOrAfter(0));
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

        // One folder with four files: two separators, then the files.
        FolderNode root = new FolderNode("/b", "b");
        FolderNode only = new FolderNode("/b/f", "f");
        only.files.addAll(Arrays.asList(a, b, c, d));
        root.folders.add(only);

        PlayQueue queue = new PlayQueue();
        queue.reset(root, a.path);
        List<AudioFile> files = queue.files();
        eq("anchor stays first", Arrays.asList(a, b, c, d), files);
        eq("anchor path", a.path, queue.anchor());
        eq("rows hold both separators", 6, queue.size());
        eq("file count ignores separators", 4, queue.fileCount());
        eq("first file row follows the folder separator", 1, queue.firstFileIndex());
        eq("separator above the playing file", "f", queue.entries().get(0).name);

        queue.reset(root, c.path);
        eq("middle of a folder becomes the head", Arrays.asList(c, d, a, b), queue.files());
        eq("a wrapped head starts with the file itself", 0, queue.firstFileIndex());
        check("the folder closes right below the wrapped head",
                queue.entries().get(2).isFolderEnd());

        AudioFile next = queue.advanceFrom(c.path);
        eq("advance returns next", d, next);
        eq("finished file moved to bottom", Arrays.asList(d, a, b, c), queue.files());
        eq("anchor updated", d.path, queue.anchor());

        AudioFile previous = queue.retreatFrom(d.path);
        eq("retreat returns previous", c, previous);
        eq("retreat wraps to the last file", Arrays.asList(c, d, a, b), queue.files());

        List<String> beforeSwipe = shape(queue.entries());
        queue.rotateViewBackward();
        // A browse swipe moves the head, so a different file is first, but the
        // rows themselves are unchanged: this is a pure rotation.
        eq("backward swipe moves the last row to the front", beforeSwipe.get(beforeSwipe.size() - 1),
                shape(queue.entries()).get(0));
        eq("backward swipe keeps every row", beforeSwipe.size(), shape(queue.entries()).size());
        queue.rotateViewForward();
        eq("forward swipe restores the rows", beforeSwipe, shape(queue.entries()));
        AudioFile afterBrowse = queue.advanceFrom(c.path);
        eq("playback advance re-anchors after browsing", d, afterBrowse);

        queue.reset(root, null);
        eq("missing anchor keeps tree order", Arrays.asList(a, b, c, d), queue.files());
        queue.promoteToFront(queue.indexOf(c.path));
        eq("promote rotates the queue", Arrays.asList(c, d, a, b), queue.files());
        eq("promote anchor", c.path, queue.anchor());

        PlayQueue single = new PlayQueue();
        FolderNode one = new FolderNode("/b", "b");
        FolderNode oneFolder = new FolderNode("/b/f", "f");
        oneFolder.files.add(a);
        one.folders.add(oneFolder);
        single.reset(one, a.path);
        eq("single item advance", null, single.advanceFrom(a.path));
        eq("single item retreat", null, single.retreatFrom(a.path));
        eq("single item keeps its separators", Arrays.asList("f/", a.name, "/"),
                shape(single.entries()));

        PlayQueue empty = new PlayQueue();
        empty.reset(new FolderNode("/b", "b"), null);
        eq("empty anchor", null, empty.anchor());
        eq("empty advance", null, empty.advanceFrom(null));
        check("empty queue", empty.isEmpty());

        // Advancing from a file that is not the head rotates to it first.
        PlayQueue other = new PlayQueue();
        other.reset(root, null);
        AudioFile fromB = other.advanceFrom(b.path);
        eq("advance from b", c, fromB);
        eq("order after advance from b", Arrays.asList(c, d, a, b), other.files());
    }

    /**
     * The whole point of the separators: a rotation must never make a folder
     * look like it belongs to a different one. Every rotation is still a
     * rotation of the tree walk, so the names open their own groups.
     */
    private static void testQueueRotationKeepsFolders() {
        FolderNode root = sampleTree();
        List<String> tree = shape(TreeOrder.entries(root));

        // Rotating to the last file of the last folder keeps that folder's
        // opening line directly above it.
        PlayQueue queue = new PlayQueue();
        queue.reset(root, "/root/Book 2/c1.mp3");
        eq("rotation starts with the folder opening", Arrays.asList(
                "Book 2/", "c1", "/", "Book 1/", "Part A/", "a1", "/", "b1", "b2", "/"),
                shape(queue.entries()));

        // Rotating to a nested file keeps both of its folders' opening rows.
        queue.reset(root, "/root/Book 1/Part A/a1.mp3");
        eq("nested rotation opens both folders", Arrays.asList(
                "Book 1/", "Part A/", "a1", "/", "b1", "b2", "/", "Book 2/", "c1", "/"),
                shape(queue.entries()));

        // Rotating to the middle of a folder puts only that folder's opening
        // row in front of the file: the chapters after it follow, and the
        // folder's closing line ends the wrapped block.
        queue.reset(root, "/root/Book 1/b2.mp3");
        eq("mid-folder rotation", Arrays.asList(
                "b2", "/", "Book 2/", "c1", "/", "Book 1/", "Part A/", "a1", "/", "b1"),
                shape(queue.entries()));

        // Whatever the anchor, the playing file is the first file of the list:
        // the separators that name its folder lead it only when they sit
        // directly above it, so no file is ever pushed below another one.
        for (String anchor : Arrays.asList(
                "/root/Book 1/Part A/a1.mp3", "/root/Book 1/b1.mp3",
                "/root/Book 1/b2.mp3", "/root/Book 2/c1.mp3")) {
            PlayQueue anchored = new PlayQueue();
            anchored.reset(root, anchor);
            check("anchor " + anchor + " is the first file",
                    anchored.firstFile() != null && anchor.equals(anchored.firstFile().path));
        }
        PlayQueue named = new PlayQueue();
        named.reset(root, "/root/Book 1/Part A/a1.mp3");
        eq("a folder's first file keeps its name above it", "Part A",
                named.entries().get(1).name);

        // Every rotation is exactly a rotation of the tree walk — same rows,
        // same order, only the cut moves (no row is re-grouped or re-sorted).
        for (int i = 0; i < tree.size(); i++) {
            PlayQueue rotated = new PlayQueue();
            rotated.resetEntries(TreeOrder.entries(root), null);
            for (int step = 0; step < i; step++) {
                rotated.rotateViewForward();
            }
            List<String> rowsNow = shape(rotated.entries());
            List<String> expectedRotation = new ArrayList<>(tree.subList(i, tree.size()));
            expectedRotation.addAll(tree.subList(0, i));
            eq("rotation " + i + " is a rotation of the tree", expectedRotation, rowsNow);
        }

        // Walking the whole queue with "next" visits every file once, in tree
        // order, and comes back to the start.
        PlayQueue walk = new PlayQueue();
        walk.reset(root, null);
        List<String> visited = new ArrayList<>();
        AudioFile current = walk.firstFile();
        for (int i = 0; i < walk.fileCount(); i++) {
            visited.add(current.name);
            current = walk.advanceFrom(current.path);
        }
        eq("next walks the tree", Arrays.asList("a1", "b1", "b2", "c1"), visited);
        eq("next wraps around", "a1", current == null ? null : current.name);

        // "Previous" walks backwards and wraps to the last file of the queue.
        PlayQueue back = new PlayQueue();
        back.reset(root, null);
        AudioFile previous = back.retreatFrom("a1".equals(back.firstFile().name)
                ? back.firstFile().path : back.firstFile().path);
        eq("previous wraps to the last file", "c1", previous == null ? null : previous.name);
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
