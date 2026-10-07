#!/bin/sh
# Gradle start up script
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
# Resolve script directory
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_PROPS="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.properties"
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "ERROR: Could not find gradle-wrapper.jar at $WRAPPER_JAR" >&2
    exit 1
fi
if [ ! -f "$WRAPPER_PROPS" ]; then
    echo "ERROR: Could not find gradle-wrapper.properties at $WRAPPER_PROPS" >&2
    exit 1
fi
# Read wrapper properties
WRAPPER_DIST_URL=$(grep 'distributionUrl' "$WRAPPER_PROPS" | sed 's/.*=//;s/\\//g' | tr -d '[:space:]')
WRAPPER_DIST_PATH=$(echo "$WRAPPER_DIST_URL" | sed 's|.*/||')
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
TARGET_DIR="$GRADLE_USER_HOME/wrapper/dists/$WRAPPER_DIST_PATH"
if [ ! -d "$TARGET_DIR" ]; then
    mkdir -p "$TARGET_DIR"
    echo "Downloading $WRAPPER_DIST_URL" >&2
    curl -fsSL -o "$TARGET_DIR/gradle.zip" "$WRAPPER_DIST_URL" 2>/dev/null || \
        wget -q -O "$TARGET_DIR/gradle.zip" "$WRAPPER_DIST_URL"
    if [ -f "$TARGET_DIR/gradle.zip" ]; then
        (cd "$TARGET_DIR" && unzip -q -o gradle.zip && rm gradle.zip)
    fi
fi
GRADLE_BIN="$TARGET_DIR/gradle-8.9/bin/gradle"
if [ -x "$GRADLE_BIN" ]; then
    exec "$GRADLE_BIN" "$@"
fi
echo "ERROR: Gradle 8.9 not found at $GRADLE_BIN" >&2
exit 1
