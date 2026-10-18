#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/../android"
: "${ANDROID_HOME:?Set ANDROID_HOME to Android SDK with platform 35 and build-tools 35.0.0}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or 21}"
task="${1:-assembleRelease}"
if [[ "$task" == *Release* ]]; then
  : "${SAKURA_SIGNING_PROPERTIES:?Set an absolute signing properties path outside the repository}"
else
  bash ../scripts/qa-certificate.sh
fi
if [ -x ./gradlew ]; then
  ./gradlew --no-daemon "$task"
elif [ -x ../.tools/gradle-8.11.1/bin/gradle ]; then
  ../.tools/gradle-8.11.1/bin/gradle --no-daemon "$task"
else
  echo 'Gradle 8.11.1 is required.' >&2
  exit 1
fi
