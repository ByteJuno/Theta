#!/usr/bin/env bash
# 在已配置 Android SDK、JDK 和设备的 InstaBox 中执行；从仓库根目录调用。
set -euo pipefail

./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.workers.max=3 assembleDebug assembleRelease lintDebug testDebugUnitTest

scripts/verify-sample-plugin.sh
scripts/verify-sample-plugin.sh samples/hello/build/outputs/apk/release/hello-release-unsigned.apk

host=app/build/outputs/apk/release/app-release.apk
probe=samples/probe/build/outputs/apk/release/probe-release.apk
hello=samples/hello/build/outputs/apk/release/hello-release-unsigned.apk
android_jar="${ANDROID_HOME:?ANDROID_HOME must be set}/platforms/android-37.0/android.jar"
python3 -I scripts/check-plugin-links.py "$host" "$hello" "$android_jar"
python3 -I scripts/check-plugin-links.py "$host" "$probe" "$android_jar"

# 在专用测试模拟器运行：脚本会卸载宿主并清空其数据，不用于日常设备。
python3 -I scripts/emulator-test.py "$host" "$probe" "$hello"
