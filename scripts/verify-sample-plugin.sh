#!/usr/bin/env bash
# 检查示例插件的 APK：清单字段正确，入口类在 dex 里。
set -euo pipefail

apk="${1:-samples/hello/build/outputs/apk/debug/hello-debug.apk}"
manifest="$(unzip -p "$apk" assets/theta-plugin.json)"

jq -e '
  .schema == 1
  and .id == "io.github.bytejuno.thetacore.sample.hello"
  and .name == "Hello"
  and .version == "0.1.0"
  and .versionCode == 1
  and .type == "dex"
  and .apiLevel == 1
  and .entry == "io.github.bytejuno.thetacore.sample.hello.HelloPlugin"
  and .provides == ["settings"]
  and .hooks == []
' <<<"$manifest" >/dev/null

entry_path="io/github/bytejuno/thetacore/sample/hello/HelloPlugin"
for dex in $(unzip -Z1 "$apk" | grep -E '^classes[0-9]*\.dex$'); do
  # 不用 grep -q：它提前退出会让 unzip 收到 SIGPIPE，pipefail 下会误判。
  if unzip -p "$apk" "$dex" | grep -a "L${entry_path};" >/dev/null; then
    echo "OK: $apk"
    exit 0
  fi
done
echo "入口类不在 $apk 的 dex 里" >&2
exit 1
