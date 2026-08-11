#!/usr/bin/env bash
set -euo pipefail

if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle غير مثبت. ثبّت Gradle 8.13 أو افتح المشروع في Android Studio أولًا." >&2
  exit 1
fi

gradle wrapper --gradle-version 8.13 --distribution-type bin
printf '\nتم إنشاء Gradle Wrapper. ارفع gradlew وgradlew.bat ومجلد gradle/wrapper إلى GitHub.\n'
