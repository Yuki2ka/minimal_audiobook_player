#!/usr/bin/env bash
#
# Builds a signed debug APK with the Android SDK command line tools only
# (aapt2 -> javac -> d8 -> zipalign -> apksigner). No Gradle, no network.
#
# Usage:
#   ANDROID_HOME=/path/to/sdk tools/build-apk.sh
#   JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/sdk tools/build-apk.sh
#
# The APK ends up in artifacts/. The regular Android Studio path is
# `./gradlew assembleDebug`; both compile exactly the same sources.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$SCRIPT_DIR/.." && pwd)"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" && -f "$PROJECT/local.properties" ]]; then
    SDK="$(sed -n 's/^sdk\.dir=//p' "$PROJECT/local.properties" | head -n 1)"
fi
if [[ -z "$SDK" ]]; then
    echo "error: ANDROID_HOME / ANDROID_SDK_ROOT is not set (and local.properties has no sdk.dir)" >&2
    exit 1
fi

if [[ -n "${BUILD_TOOLS:-}" ]]; then
    BUILD_TOOLS_DIR="$BUILD_TOOLS"
elif [[ -d "$SDK/build-tools/34.0.0" ]]; then
    BUILD_TOOLS_DIR="$SDK/build-tools/34.0.0"
else
    BUILD_TOOLS_DIR="$(find "$SDK/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1)"
fi

if [[ -n "${PLATFORM_JAR:-}" ]]; then
    ANDROID_JAR="$PLATFORM_JAR"
elif [[ -f "$SDK/platforms/android-34/android.jar" ]]; then
    ANDROID_JAR="$SDK/platforms/android-34/android.jar"
else
    ANDROID_JAR="$(find "$SDK/platforms" -maxdepth 2 -name android.jar | sort -V | tail -n 1)"
fi

# Fall back to the sandbox toolchain so the script works in a fresh shell.
if [[ -z "${JAVA_HOME:-}" && -x /tmp/toolchain/jdk-17.0.20.1+1/bin/javac ]]; then
    JAVA_HOME=/tmp/toolchain/jdk-17.0.20.1+1
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA="$JAVA_HOME/bin/java"
    JAVAC="$JAVA_HOME/bin/javac"
    KEYTOOL="$JAVA_HOME/bin/keytool"
    # d8 and apksigner are shell wrappers that call `java` from PATH.
    export PATH="$JAVA_HOME/bin:$PATH"
else
    JAVA="$(command -v java)"
    JAVAC="$(command -v javac)"
    KEYTOOL="$(command -v keytool)"
fi

# Tools come in two flavours:
#   * native build-tools binaries plus a full JDK (a normal SDK install);
#   * Java jars (d8, apksigner) plus a standalone Eclipse compiler when the
#     sandbox only has a runtime JDK and no native build-tools. The jars are
#     looked up by name in the build-tools directory, then through D8_JAR /
#     APKSIGNER_JAR / ECJ_JAR; aapt2 always has to be a real binary (AAPT2=...).
AAPT2="${AAPT2:-$BUILD_TOOLS_DIR/aapt2}"
D8="${D8:-$BUILD_TOOLS_DIR/d8}"
ZIPALIGN="${ZIPALIGN:-$BUILD_TOOLS_DIR/zipalign}"
APKSIGNER="${APKSIGNER:-$BUILD_TOOLS_DIR/apksigner}"
D8_JAR="${D8_JAR:-$BUILD_TOOLS_DIR/d8.jar}"
APKSIGNER_JAR="${APKSIGNER_JAR:-$BUILD_TOOLS_DIR/apksigner.jar}"
ECJ_JAR="${ECJ_JAR:-$BUILD_TOOLS_DIR/ecj-3.45.0.jar}"

for tool in "$AAPT2" "$JAVA" "$KEYTOOL"; do
    if [[ ! -x "$tool" ]]; then
        echo "error: missing tool: $tool" >&2
        echo "       (set AAPT2=/path/to/aapt2 when the SDK has no native build-tools)" >&2
        exit 1
    fi
done
if [[ ! -x "$JAVAC" && ! -f "$ECJ_JAR" ]]; then
    echo "error: neither javac nor an Eclipse compiler jar was found" >&2
    echo "       (set ECJ_JAR=/path/to/ecj.jar)" >&2
    exit 1
fi
if [[ ! -x "$D8" && ! -f "$D8_JAR" ]]; then
    echo "error: missing d8 (set D8_JAR=/path/to/d8.jar)" >&2
    exit 1
fi
if [[ ! -x "$ZIPALIGN" ]]; then
    echo "note: no native zipalign; using tools/zipalign.py" >&2
    ZIPALIGN="$PROJECT/tools/zipalign.py"
fi
if [[ ! -x "$APKSIGNER" && ! -f "$APKSIGNER_JAR" ]]; then
    echo "error: missing apksigner (set APKSIGNER_JAR=/path/to/apksigner.jar)" >&2
    exit 1
fi
if [[ ! -f "$ANDROID_JAR" ]]; then
    echo "error: android.jar not found (looked at $ANDROID_JAR)" >&2
    exit 1
fi

APP_NAME="AudiobooksMinimalPlayer"
VERSION_CODE="12"
VERSION_NAME="1.7.3"
MIN_SDK="24"
TARGET_SDK="34"

MANIFEST="$PROJECT/app/src/main/AndroidManifest.xml"
RES_DIR="$PROJECT/app/src/main/res"
SRC_DIR="$PROJECT/app/src/main/java"

WORK="$PROJECT/build/nogradle"
DIST="$PROJECT/artifacts"
GEN="$WORK/gen"
CLASSES="$WORK/classes"
DEX="$WORK/dex"

rm -rf "$WORK"
mkdir -p "$GEN" "$CLASSES" "$DEX" "$DIST"

echo "== resource check"
python3 "$PROJECT/tools/check-resources.py"

echo "== aapt2 compile"
"$AAPT2" compile --dir "$RES_DIR" -o "$WORK/res.zip"

# AGP derives the manifest package from `namespace`; plain aapt2 needs it in
# the manifest itself. Inject it into a build copy so the checked-in manifest
# stays AGP-8 compatible.
sed '0,/<manifest /s//<manifest package="io.github.yuki2ka.audiobookplayer" /' \
    "$MANIFEST" > "$WORK/AndroidManifest.xml"

echo "== aapt2 link"
"$AAPT2" link \
    -o "$WORK/app-unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$WORK/AndroidManifest.xml" \
    -R "$WORK/res.zip" \
    --java "$GEN" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --auto-add-overlay

echo "== compile"
find "$SRC_DIR" "$GEN" -name '*.java' -print > "$WORK/sources.txt"
if [[ -x "$JAVAC" ]]; then
    "$JAVAC" \
        -encoding UTF-8 \
        -source 8 -target 8 \
        -Xlint:-options \
        -bootclasspath "$ANDROID_JAR" \
        -classpath "$ANDROID_JAR" \
        -d "$CLASSES" \
        @"$WORK/sources.txt"
else
    # Runtime-only JDK: the Eclipse batch compiler takes the same -source /
    # -target / -classpath flags and compiles against android.jar.
    "$JAVA" -cp "$ECJ_JAR" org.eclipse.jdt.internal.compiler.batch.Main \
        -encoding UTF-8 \
        -source 8 -target 8 \
        -proceedOnError:Fatal \
        -nowarn -proc:none \
        -bootclasspath "$ANDROID_JAR" \
        -classpath "$ANDROID_JAR" \
        -d "$CLASSES" \
        @"$WORK/sources.txt"
fi

echo "== d8"
find "$CLASSES" -name '*.class' -print > "$WORK/classes.txt"
if [[ -x "$D8" ]]; then
    "$D8" --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
        --output "$DEX" @"$WORK/classes.txt"
else
    "$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api "$MIN_SDK" \
        --lib "$ANDROID_JAR" --output "$DEX" @"$WORK/classes.txt"
fi

if [[ -f "$WORK/app-unsigned.apk" ]]; then
    (cd "$DEX" && zip -q -0 "$WORK/app-unsigned.apk" classes.dex)
else
    echo "error: aapt2 did not produce an APK" >&2
    exit 1
fi

echo "== zipalign"
if [[ "$ZIPALIGN" == *.py ]]; then
    python3 "$ZIPALIGN" --alignment 4 "$WORK/app-unsigned.apk" "$WORK/app-aligned.apk"
else
    "$ZIPALIGN" -f -p 4 "$WORK/app-unsigned.apk" "$WORK/app-aligned.apk"
fi

KEYSTORE="$PROJECT/build/debug.keystore"
if [[ ! -f "$KEYSTORE" ]]; then
    echo "== generating debug keystore"
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass android -keypass android \
        -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

APK="$DIST/$APP_NAME-$VERSION_NAME.apk"
echo "== apksigner"
if [[ -x "$APKSIGNER" ]]; then
    "$APKSIGNER" sign \
        --ks "$KEYSTORE" \
        --ks-key-alias androiddebugkey \
        --ks-pass pass:android \
        --key-pass pass:android \
        --out "$APK" \
        "$WORK/app-aligned.apk"
    "$APKSIGNER" verify --verbose "$APK" >/dev/null
else
    "$JAVA" -jar "$APKSIGNER_JAR" sign \
        --ks "$KEYSTORE" \
        --ks-key-alias androiddebugkey \
        --ks-pass pass:android \
        --key-pass pass:android \
        --out "$APK" \
        "$WORK/app-aligned.apk"
    "$JAVA" -jar "$APKSIGNER_JAR" verify --verbose "$APK" \
        | sed -n '/Verified using v[23] /p'
fi

echo
echo "== done"
"$AAPT2" dump badging "$APK" | sed -n '1,4p'
echo "APK: $APK ($(du -h "$APK" | cut -f1))"
