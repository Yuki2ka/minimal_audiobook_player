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
if [[ -z "${JAVA_HOME:-}" && -x /tmp/toolchain/jdk-17.0.20.1+1/bin/javac ]]; then
    JAVA_HOME=/tmp/toolchain/jdk-17.0.20.1+1
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA="$JAVA_HOME/bin/java"
    JAVAC="$JAVA_HOME/bin/javac"
else
    JAVA="$(command -v java || true)"
    JAVAC="$(command -v javac || true)"
fi
if [[ -z "$JAVAC" || ! -x "$JAVAC" ]]; then
    echo "error: javac not found. Set JAVA_HOME to a JDK 17 installation." >&2
    exit 1
fi

OUT="$PROJECT/build/tests/classes"
rm -rf "$OUT"
mkdir -p "$OUT"

"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d "$OUT" \
    "$SRC/util/TimeFormat.java" \
    "$SRC/util/SeekCurve.java" \
    "$SRC/util/NaturalSort.java" \
    "$SRC/util/Fingerprint.java" \
    "$SRC/model/AudioFile.java" \
    "$SRC/model/FolderNode.java" \
    "$SRC/model/ScanResult.java" \
    "$SRC/playback/PlayQueue.java" \
    "$SRC/scan/Scanner.java" \
    "$PROJECT/tools/tests/LogicTests.java"

"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT" LogicTests
