#!/bin/sh
#
# Gradle start up script for POSIX compatible shells (Linux / macOS).
# Generated for Gradle 8.7 — replace gradle/wrapper/gradle-wrapper.jar by running:
#   gradle wrapper --gradle-version 8.7
# in a machine that has Gradle installed, or let Android Studio regenerate it.
#

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Resolve the script's own directory
PRG="$0"
while [ -h "$PRG" ]; do
  ls=$(ls -ld "$PRG")
  link=$(expr "$ls" : '.*-> \(.*\)$')
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=$(dirname "$PRG")/"$link"
  fi
done
APP_HOME=$(cd "$(dirname "$PRG")" && pwd)

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# ── JVM options ──────────────────────────────────────────────────────────────
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Determine the Java executable
if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
  if [ ! -x "$JAVACMD" ]; then
    echo "ERROR: JAVA_HOME is set to $JAVA_HOME but java executable was not found." >&2
    exit 1
  fi
else
  JAVACMD="java"
  which java >/dev/null 2>&1 || {
    echo "ERROR: JAVA_HOME is not set and no 'java' executable found in PATH." >&2
    exit 1
  }
fi

eval set -- $DEFAULT_JVM_OPTS '"$@"'

exec "$JAVACMD" \
  $JAVA_OPTS \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
