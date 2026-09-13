#!/usr/bin/env bash
set -euo pipefail

# This suite validates plugin-local discovery/binding and its caller boundary.
# A matching signed AutoJs6 host is still required for positive host execution.
page_size="$(adb shell 'getconf PAGE_SIZE 2>/dev/null || getconf PAGESIZE 2>/dev/null' | tr -d '\r')"
test "$page_size" = "${EXPECTED_PAGE_SIZE:?Expected emulator page size is required}"
test "$(adb shell getprop ro.product.cpu.abi | tr -d '\r')" = "x86_64"
bash ./gradlew --no-daemon --max-workers=2 \
  -Pautojs.gradle.build.number.auto.increment.enabled=false \
  -Pautojs.gradle.build.time.update.enabled=false \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.supermonster003.autojs6.plugin.lua.runtime.WakeContractInstrumentedTest \
  :app:connectedProviderDebugAndroidTest --stacktrace
