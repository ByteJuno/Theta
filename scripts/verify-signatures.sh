#!/usr/bin/env bash
# InstaBox only. No Gradle/compiler/build calls: consumes coordinator-built APKs.
# Place with the two new Python scripts and the existing lifecycle/emulator helpers.
# From repo root (host may be debug OR release):
# S1_INSTABOX=1 S1_DEDICATED_EMULATOR=1 \
# ADB="$ANDROID_HOME/platform-tools/adb -s emulator-5554" \
# S1_HOST_APK=app/build/outputs/apk/release/app-release.apk \
# bash scripts/verify-signatures.sh
# Override S1_FIXTURE_ROOT, S1_FIXTURE_V1, S1_FIXTURE_V2, S1_SIGNATURE_ROOT,
# S1_DEBUG_KEYSTORE, ANDROID_BUILD_TOOLS_VERSION etc. as needed.
# S1_SIGNATURE_ROOT must be NEW. No existing outputs are removed or overwritten.
set -euo pipefail
: "${S1_INSTABOX:?Set S1_INSTABOX=1 only on InstaBox}"
: "${S1_DEDICATED_EMULATOR:?Set S1_DEDICATED_EMULATOR=1 for a disposable emulator}"
: "${ADB:?ADB must explicitly select the dedicated emulator using -s}"
: "${S1_HOST_APK:?Set S1_HOST_APK to a prebuilt signature-patched host APK}"
[[ "$S1_INSTABOX" == 1 && "$S1_DEDICATED_EMULATOR" == 1 ]]
[[ "$(uname -s)" == Linux ]]
scripts_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd -- "$scripts_dir/.." && pwd)"
python="${PYTHON:-python3}"
fixtures="${S1_FIXTURE_ROOT:-$repo/samples/fixtures/build/outputs/apk}"
v1="${S1_FIXTURE_V1:-$fixtures/normalV1/release/fixtures-normalV1-release.apk}"
v2="${S1_FIXTURE_V2:-$fixtures/normalV2/release/fixtures-normalV2-release.apk}"
root="${S1_SIGNATURE_ROOT:-$repo/build/signature-suite-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
[[ ! -e "$root" ]]
test -s "$S1_HOST_APK"
test -s "$v1"
test -s "$v2"
test -s "$scripts_dir/lifecycle-acceptance.py"
test -s "$scripts_dir/emulator-test.py"
# The existing lifecycle.check_device guard runs inside this non-mutating preflight,
# before even generating keys/artifacts. Device runner repeats it before each case.
"$python" -I "$scripts_dir/signature-acceptance.py" --check-device --output "$root/guard"
"$python" -I "$scripts_dir/signature-artifacts.py" "$v1" "$v2" --output "$root/artifacts"
"$python" -I "$scripts_dir/signature-acceptance.py" "$S1_HOST_APK" "$root/artifacts" --output "$root/device"
printf 'Signature suite finished; retained raw results: %s\n' "$root"
