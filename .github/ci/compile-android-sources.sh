#!/usr/bin/env bash
# Compile the Android-facing sources and resources without assembling native payloads.
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
BUILD_DIR="$(mktemp -d "${RUNNER_TEMP:-/tmp}/agentcodi-android-compile.XXXXXX")"
trap 'build_status=$?; if [ "$build_status" -ne 0 ] && [ -f "$BUILD_DIR/badging.txt" ]; then cat "$BUILD_DIR/badging.txt"; fi; rm -rf -- "$BUILD_DIR"; exit "$build_status"' EXIT

: "${ANDROID_HOME:?The GitHub runner Android SDK is required}"
ANDROID_PLATFORM_VERSION="${AGENTCODI_ANDROID_PLATFORM_VERSION:-35}"
ANDROID_JAR="$ANDROID_HOME/platforms/android-$ANDROID_PLATFORM_VERSION/android.jar"
AAPT2="$ANDROID_HOME/build-tools/35.0.0/aapt2"
if [ ! -f "$ANDROID_JAR" ] || [ ! -x "$AAPT2" ]; then
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
    "platforms;android-$ANDROID_PLATFORM_VERSION" "build-tools;35.0.0" >/dev/null
fi
mkdir -p "$BUILD_DIR/generated" "$BUILD_DIR/classes"
python3 -B "$PROJECT_ROOT/scripts/arch-terminal/prepare-resources.py" "$BUILD_DIR/resources"
"$AAPT2" compile --dir "$BUILD_DIR/resources" -o "$BUILD_DIR/resources.zip"
"$AAPT2" link -o "$BUILD_DIR/resources.apk" \
  --manifest "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" \
  --java "$BUILD_DIR/generated" --custom-package de.agentcodi.app --extra-packages de.archterminal:com.termux.view --min-sdk-version 29 --target-sdk-version 28 \
  -I "$ANDROID_JAR" "$BUILD_DIR/resources.zip"
"$AAPT2" dump badging "$BUILD_DIR/resources.apk" > "$BUILD_DIR/badging.txt"
grep -Fq "targetSdkVersion:'28'" "$BUILD_DIR/badging.txt"
grep -Eq "(minSdkVersion|sdkVersion):'29'" "$BUILD_DIR/badging.txt"
grep -Fq "application-label:'AGENTCODI Package'" "$BUILD_DIR/badging.txt"
grep -Fq "package: name='de.agentcodi.pkg'" "$BUILD_DIR/badging.txt"
grep -Fq "launchable-activity: name='de.agentcodi.app.MainActivity'" "$BUILD_DIR/badging.txt"

find "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/modules" "$BUILD_DIR/generated" \
  "$PROJECT_ROOT/third_party/arch-terminal/app/src/main/java" \
  "$PROJECT_ROOT/third_party/arch-terminal/third_party/termux/emulator-src/main/java" \
  "$PROJECT_ROOT/third_party/arch-terminal/third_party/termux/view-src/main/java" \
  -type f -name '*.java' -print | sort > "$BUILD_DIR/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR:$PROJECT_ROOT/third_party/android-lambda-stubs/core-lambda-stubs.jar" -classpath "$PROJECT_ROOT/third_party/androidx-annotation/annotation-1.3.0.jar" -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
# Exercise Java 8 lambda desugaring for the unchanged upstream terminal sources.
mkdir -p "$BUILD_DIR/dex"
jar cf "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/classes" .
"$ANDROID_HOME/build-tools/35.0.0/d8" --min-api 29 --lib "$ANDROID_JAR" \
  --lib "$PROJECT_ROOT/third_party/androidx-annotation/annotation-1.3.0.jar" \
  --output "$BUILD_DIR/dex" "$BUILD_DIR/classes.jar"
test -s "$BUILD_DIR/dex/classes.dex"
# The installation ID differs from the Java namespace. Resolve every manifest
# component against the compiled classes to catch an accidentally relative name.
python3 - "$PROJECT_ROOT" "$BUILD_DIR" <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

project, build = map(Path, sys.argv[1:])
manifest = ET.parse(project / "app/src/main/AndroidManifest.xml").getroot()
android = "{http://schemas.android.com/apk/res/android}"
identity = (project / "modules/core/src/main/java/de/agentcodi/core/BuildIdentity.java").read_text()
builder = (project / "scripts/build-debug-apk.sh").read_text()
badging = (build / "badging.txt").read_text()

for java_key, shell_key, manifest_value, badging_key in (
    ("APPLICATION_ID", "APP_ID", manifest.attrib["package"], "name"),
    ("VERSION_NAME", "APP_VERSION", manifest.attrib[android + "versionName"], "versionName"),
    ("VERSION_CODE", "VERSION_CODE", manifest.attrib[android + "versionCode"], "versionCode"),
):
    java_value = re.search(r"\b" + java_key + r' = "?([^";]+)"?;', identity).group(1)
    shell_value = re.search(r"^" + shell_key + r'="([^"]+)"$', builder, re.M).group(1)
    assert manifest_value == java_value == shell_value, (java_key, manifest_value, java_value, shell_value)
    assert badging_key + "='" + manifest_value + "'" in badging, java_key

application = manifest.find("application")
for component in [application] + list(application):
    if component.tag not in ("application", "activity", "service", "receiver", "provider"):
        continue
    name = component.attrib[android + "name"]
    if name.startswith("."):
        name = manifest.attrib["package"] + name
    elif "." not in name:
        name = manifest.attrib["package"] + "." + name
    assert (build / "classes" / (name.replace(".", "/") + ".class")).is_file(), name
assert (build / "classes/de/agentcodi/app/R.class").is_file(), "Java resource namespace"
print("Package installation identity and all manifest component classes verified.")
PY
# A higher versionCode cannot repair a changed signer. Exercise the real signing
# branch with two valid resource APKs in independent, empty build caches.
python3 - "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" "$BUILD_DIR/signing-upgrade.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
tree = ET.parse(sys.argv[1])
android = "{http://schemas.android.com/apk/res/android}"
manifest = tree.getroot()
manifest.set(android + "versionCode", str(int(manifest.attrib[android + "versionCode"]) + 1))
manifest.set(android + "versionName", "signing-fixture")
tree.write(sys.argv[2], encoding="utf-8", xml_declaration=True)
PY
"$AAPT2" link -o "$BUILD_DIR/signing-upgrade.apk" \
  --manifest "$BUILD_DIR/signing-upgrade.xml" \
  --custom-package de.agentcodi.app --extra-packages de.archterminal:com.termux.view --min-sdk-version 29 --target-sdk-version 28 \
  -I "$ANDROID_JAR" "$BUILD_DIR/resources.zip"
python3 "$PROJECT_ROOT/.github/ci/test-debug-signing.py" \
  "$BUILD_DIR/resources.apk" "$BUILD_DIR/signing-upgrade.apk" \
  "$ANDROID_HOME/build-tools/35.0.0/apksigner"

echo "Android sources and resources compiled against API $ANDROID_PLATFORM_VERSION (target 28, minimum 29)."
