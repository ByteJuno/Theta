#!/usr/bin/env bash
# 在已配置 Android SDK、JDK 和设备的 InstaBox 中执行；从仓库根目录调用。
# ADB='.../adb -s emulator-5554' S1_DEDICATED_EMULATOR=1 bash scripts/verify-s1.sh
set -euo pipefail

# Guard before the older probe runner can uninstall anything. Never target a daily device.
python3 -I scripts/lifecycle-acceptance.py --check-device

./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.workers.max=3 assembleDebug assembleRelease lintDebug testDebugUnitTest :agent-core:test

scripts/verify-plugins.sh

host=app/build/outputs/apk/release/app-release.apk
probe=samples/probe/build/outputs/apk/release/probe-release.apk
hello=samples/hello/build/outputs/apk/release/hello-release.apk
fixtures=samples/fixtures/build/outputs/apk

# 在专用测试模拟器运行：脚本会卸载宿主并清空其数据，不用于日常设备。
python3 -I scripts/emulator-test.py "$host" "$probe" "$hello"
TEST_OUTPUT="${TEST_OUTPUT:-build/lifecycle-acceptance}" \
  python3 -I scripts/lifecycle-acceptance.py "$host" "$fixtures"
