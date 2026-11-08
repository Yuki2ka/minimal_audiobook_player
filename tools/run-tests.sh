#!/usr/bin/env bash
#
# Desktop JVM tests for the Android-free logic: time formatting, natural
# sorting (including Unicode), content fingerprints, the rotating queue and the
# folder scanner. No JUnit, no network, no emulator needed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="$PROJECT/app/src/main/java/io/github/yuki2ka/audiobookplayer"

# The tests create files with Cyrillic / Japanese / accented names. A POSIX
# (non-UTF-8) locale makes the JDK mangle those names, so force UTF-8 when the
# current locale is not already Unicode.
case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in
    *UTF-8*|*utf8*|*UTF8*) ;;
    *)
        if locale -a 2>/dev/null | grep -qiE '^C\.(utf-?8)$'; then
            export LC_ALL=C.UTF-8
        fi
        ;;
esac

# Fall back to the sandbox toolchain, then to PATH, then fail loudly instead of
# dying silently inside a command substitution (set -e swallows that).
#
# Two JDK layouts are supported:
#   * a full JDK (JAVA_HOME with bin/javac) — the normal case;
#   * a runtime-only JDK plus a standalone Eclipse compiler jar — used in the
#     build sandbox, where the JDK ships java but not javac. Set ECJ_JAR to that
#     jar and the tests compile through it instead.
if [[ -z "${JAVA_HOME:-}" && -x /tmp/toolchain/jdk-17.0.20.1+1/bin/javac ]]; then
    JAVA_HOME=/tmp/toolchain/jdk-17.0.20.1+1
fi
if [[ -z "${JAVA_HOME:-}" && -x /tmp/toolchain/jdk/jdk4py/java-runtime/bin/java ]]; then
    JAVA_HOME=/tmp/toolchain/jdk/jdk4py/java-runtime
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA="$JAVA_HOME/bin/java"
    JAVAC="$JAVA_HOME/bin/javac"
else
    JAVA="$(command -v java || true)"
    JAVAC="$(command -v javac || true)"
fi
if [[ -z "${ECJ_JAR:-}" && -f /tmp/sdk/build-tools-34/ecj-3.45.0.jar ]]; then
    ECJ_JAR=/tmp/sdk/build-tools-34/ecj-3.45.0.jar
fi
if [[ ! -x "$JAVAC" && -n "${ECJ_JAR:-}" && -f "$ECJ_JAR" ]]; then
    JAVAC=""
fi
if [[ -z "$JAVA" || ! -x "$JAVA" ]]; then
    echo "error: java not found. Set JAVA_HOME to a JDK 17 installation." >&2
    exit 1
fi
if [[ -z "$JAVAC" && -z "${ECJ_JAR:-}" ]]; then
    echo "error: javac not found. Set JAVA_HOME to a JDK 17 installation, or\n\
       ECJ_JAR to an Eclipse compiler jar when the JDK has no javac." >&2
    exit 1
fi

OUT="$PROJECT/build/tests/classes"
rm -rf "$OUT"
mkdir -p "$OUT"

SOURCES=(
    "$SRC/util/TimeFormat.java"
    "$SRC/util/SeekCurve.java"
    "$SRC/util/PlaybackSpeed.java"
    "$SRC/util/VolumeGesture.java"
    "$SRC/util/DiscreteDrag.java"
    "$SRC/util/NaturalSort.java"
    "$SRC/util/Fingerprint.java"
    "$SRC/model/AudioFile.java"
    "$SRC/model/QueueEntry.java"
    "$SRC/model/TreeOrder.java"
    "$SRC/model/FileListRows.java"
    "$SRC/model/FolderNode.java"
    "$SRC/model/ScanResult.java"
    "$SRC/playback/PlayQueue.java"
    "$SRC/scan/Scanner.java"
    "$PROJECT/tools/tests/LogicTests.java"
)

if [[ -n "$JAVAC" ]]; then
    "$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d "$OUT" \
        "${SOURCES[@]}"
else
    # Runtime-only JDK: compile through the Eclipse batch compiler.
    "$JAVA" -cp "$ECJ_JAR" org.eclipse.jdt.internal.compiler.batch.Main \
        -encoding UTF-8 -source 8 -target 8 -nowarn -proc:none -d "$OUT" \
        "${SOURCES[@]}"
fi

"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT" LogicTests
