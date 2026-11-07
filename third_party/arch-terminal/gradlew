#!/usr/bin/env sh
set -eu
APP_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
else
    JAVA_CMD=java
fi
exec "$JAVA_CMD" -Xmx64m -Xms64m -classpath "$APP_DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"

