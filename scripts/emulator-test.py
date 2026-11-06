#!/usr/bin/env python3
"""在已启动的设备上验证 S1 的 A 项：R8 正式版宿主安装单独编译的插件，并显示它的 Compose 页面。

    emulator-test.py <host.apk> <probe.apk> [hello.apk]

只依赖 adb，CI 和软件模拟器用同一份脚本。环境变量：
    ADB           adb 路径，默认 adb
    TEST_TIMEOUT  单步等待秒数，默认 180。没有硬件加速的模拟器很慢，不要调太小。
    TEST_OUTPUT   失败时的截图和日志目录，默认 build/emulator-test
"""
import os
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "io.github.bytejuno.thetacore"
ADB = os.environ.get("ADB", "adb").split()
TIMEOUT = int(os.environ.get("TEST_TIMEOUT", "180"))
OUTPUT = pathlib.Path(os.environ.get("TEST_OUTPUT", "build/emulator-test"))
PROBE_PASSED = re.compile(r"probe: (\d+)/\1 ok$")


class TestFailure(Exception):
    pass


def adb(*args, timeout=180, check=True):
    done = subprocess.run([*ADB, *args], capture_output=True, text=True, timeout=timeout)
    if check and done.returncode != 0:
        raise TestFailure(f"adb {' '.join(args)}: {done.stdout}{done.stderr}".strip())
    return done.stdout


def screen():
    """当前窗口的所有节点。系统卡住时 dump 会失败，交给调用方重试。"""
    xml = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
    end = xml.rfind("</hierarchy>")
    if end < 0:
        return []
    return list(ET.fromstring(xml[: end + len("</hierarchy>")]).iter("node"))


def find(nodes, matches):
    for node in nodes:
        if matches(node.get("text", "")):
            return node
    return None


def crashed():
    return PACKAGE in adb("logcat", "-d", "-b", "crash", check=False)


def wait_for(description, matches, fail=None):
    """等界面上出现匹配的文字。`fail` 匹配到时立刻失败，不用等到超时。"""
    deadline = time.monotonic() + TIMEOUT
    texts = []
    while time.monotonic() < deadline:
        nodes = screen()
        texts = [n.get("text") for n in nodes if n.get("text")]
        node = find(nodes, matches)
        if node is not None:
            return node
        if fail is not None and find(nodes, fail) is not None:
            raise TestFailure(f"{description}: {[t for t in texts if fail(t)]}")
        if crashed():
            raise TestFailure(f"{description}: app crashed")
        time.sleep(1)
    raise TestFailure(f"{description}: timed out, screen has {texts}")


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap_text(text):
    tap(wait_for(f"find {text!r}", lambda t: t == text))


def step(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def install_plugin(apk, name):
    remote = f"/data/local/tmp/theta-{name.lower()}.apk"
    adb("push", apk, remote)
    adb("shell", "chmod", "644", remote)
    adb(
        "shell", "am", "start", "-W", "-a", "android.intent.action.VIEW",
        "-d", f"file://{remote}", "-n", f"{PACKAGE}/.MainActivity",
    )
    wait_for(f"confirm {name}", lambda t: t.startswith(f"Install {name} "))
    tap_text("Install")


def run(host, probe, hello):
    step("prepare the device")
    adb("uninstall", PACKAGE, check=False)
    # 软件模拟器上系统进程常常 ANR，弹窗会盖住被测界面。
    for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        adb("shell", "settings", "put", "global", key, "0")
    adb("shell", "settings", "put", "global", "hide_error_dialogs", "1")
    adb("logcat", "-c", "-b", "crash", check=False)

    step("install the R8 host")
    adb("install", "-r", host, timeout=300)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    wait_for("host shows up", lambda t: t == "No plugins installed")

    if hello:
        step("install Hello")
        install_plugin(hello, "Hello")
        tap_text("Hello")
        wait_for("Hello page", lambda t: t == "Hello from a plugin")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        wait_for("back on the list", lambda t: t == "Hello")

    step("install Probe")
    install_plugin(probe, "Probe")
    tap_text("Probe")

    step("wait for every probe check")
    summary = wait_for(
        "probe checks",
        lambda t: PROBE_PASSED.match(t) is not None,
        fail=lambda t: t.startswith("FAIL "),
    )
    print(summary.get("text"), flush=True)

    step("click a plugin button")
    tap_text("Clicked 0")
    wait_for("click count", lambda t: t == "Clicked 1")

    step("open a plugin dialog")
    tap_text("Open dialog")
    wait_for("dialog", lambda t: t == "Dialog body")
    tap_text("Close")
    wait_for("dialog closed", lambda t: t == "Open dialog")

    step("open a plugin popup")
    tap_text("Menu")
    tap_text("Menu item")
    wait_for("menu closed", lambda t: t == "Menu")

    step("leave and restart: the plugin comes back from disk")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    tap_text("Probe")
    wait_for("probe after restart", lambda t: PROBE_PASSED.match(t) is not None, fail=lambda t: t.startswith("FAIL "))

    if crashed():
        raise TestFailure("app crashed")


def collect_evidence():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT / "screen.png", "wb") as image:
        subprocess.run([*ADB, "exec-out", "screencap", "-p"], stdout=image, timeout=120, check=False)
    (OUTPUT / "crash.txt").write_text(adb("logcat", "-d", "-b", "crash", check=False))
    (OUTPUT / "logcat.txt").write_text(adb("logcat", "-d", "-t", "2000", check=False))


def main():
    if len(sys.argv) not in (3, 4):
        print(__doc__, file=sys.stderr)
        return 2
    started = time.monotonic()
    try:
        run(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) == 4 else None)
    except (TestFailure, subprocess.TimeoutExpired) as failure:
        print(f"FAILED: {failure}", file=sys.stderr)
        collect_evidence()
        return 1
    print(f"OK in {int(time.monotonic() - started)}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
