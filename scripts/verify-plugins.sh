#!/usr/bin/env bash
# 不用设备的插件检查：清单、入口类和宿主链接。PR Check 和 InstaBox 共用。
# 先构建 assembleDebug assembleRelease，再从仓库根目录调用。
set -euo pipefail

scripts/verify-sample-plugin.sh
scripts/verify-sample-plugin.sh samples/hello/build/outputs/apk/release/hello-release.apk

host=app/build/outputs/apk/release/app-release.apk
probe=samples/probe/build/outputs/apk/release/probe-release.apk
hello=samples/hello/build/outputs/apk/release/hello-release.apk
fixtures=samples/fixtures/build/outputs/apk
android_jar="${ANDROID_HOME:?ANDROID_HOME must be set}/platforms/android-37.0/android.jar"
python3 -I scripts/check-plugin-links.py "$host" "$hello" "$android_jar"
python3 -I scripts/check-plugin-links.py "$host" "$probe" "$android_jar"
for flavor in normalV1 normalV2 initThrow initBlock initThrowV2 initBlockV2 disableThrow cleanupThrow cleanupBlock; do
  apk="$fixtures/$flavor/release/fixtures-$flavor-release.apk"
  test -s "$apk"
  python3 -I scripts/check-plugin-links.py "$host" "$apk" "$android_jar"
done
