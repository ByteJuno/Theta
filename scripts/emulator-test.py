#!/usr/bin/env python3
"""在已启动的设备上验证 S1 的 A 项：R8 正式版宿主安装单独编译的插件，并显示它的 Compose 页面。

    emulator-test.py <host.apk> <probe.apk> [hello.apk]

只依赖 adb，CI 和软件模拟器用同一份脚本。环境变量：
    ADB           adb 路径，默认 adb
    TEST_TIMEOUT  单步等待秒数，默认 180。没有硬件加速的模拟器很慢，不要调太小。
    TEST_OUTPUT   失败时的截图和日志目录，默认 build/emulator-test
"""
import os
import json
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


def scroll(down, nodes=None):
    """Use the lifecycle acceptance runner's slow, overlapping viewport drags."""
    nodes = screen() if nodes is None else nodes
    bodies = []
    for node in nodes:
        values = re.findall(r"-?\d+", node.get("bounds", ""))
        if node.get("scrollable") != "true" or len(values) != 4:
            continue
        left, top, right, bottom = map(int, values)
        if 0 <= left < right and 0 <= top < bottom and bottom - top > 60:
            bodies.append((left, top, right, bottom))
    if not bodies:
        return False
    left, top, right, bottom = min(bodies, key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))
    x = (left + right) // 2
    distance = min(400, (bottom - top) // 4)
    start = (top + bottom) // 2 - distance // 2
    end = start + distance
    adb("shell", "input", "swipe", str(x), str(end if down else start),
        str(x), str(start if down else end), "800")
    return True


def viewport(nodes):
    return tuple((n.get("text", ""), n.get("bounds", "")) for n in nodes)


def search_failure(message, observations):
    # A final screenshot alone cannot show which intermediate viewports were searched.
    try:
        OUTPUT.mkdir(parents=True, exist_ok=True)
        (OUTPUT / "search.json").write_text(json.dumps({"error": message, "timeout": TIMEOUT,
            "observations": observations}, ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError as error:
        print(f"Could not save search evidence: {error}", file=sys.stderr)
    raise TestFailure(message)


def tap_target(node, nodes):
    def bounds(element):
        values = re.findall(r"-?\d+", element.get("bounds", ""))
        if len(values) != 4:
            return None
        left, top, right, bottom = map(int, values)
        return (left, top, right, bottom) if left < right and top < bottom else None

    visible = bounds(node)
    if visible is None:
        return None
    parents = {child: parent for parent in nodes for child in parent}
    ancestor = parents.get(node)
    while ancestor is not None:
        clip = bounds(ancestor)
        if clip is None:
            return None
        visible = (max(visible[0], clip[0]), max(visible[1], clip[1]),
                   min(visible[2], clip[2]), min(visible[3], clip[3]))
        ancestor = parents.get(ancestor)
    left, top, right, bottom = visible
    if left < 0 or top < 0 or left >= right or top >= bottom:
        return None
    return ET.Element("node", bounds=f"[{left},{top}][{right},{bottom}]")


def wait_for(description, matches, fail=None, sweep=False, for_tap=False):
    """等界面上出现匹配的文字。`fail` 匹配到时立刻失败，不用等到超时。

    `sweep` 只用于宿主列表上的卡片：没找到就先往下、再往上来回滑。弹窗按钮
    不要用，滑动落在弹窗外会把它关掉。
    """
    deadline = time.monotonic() + TIMEOUT
    texts = []
    down, previous, steps = True, None, 0
    observations = []
    while time.monotonic() < deadline:
        nodes = screen()
        texts = [n.get("text") for n in nodes if n.get("text")]
        observations.append({"at": time.monotonic(), "down": down,
            "nodes": [{k: n.get(k, "") for k in ("text", "bounds", "scrollable")} for n in nodes]})
        del observations[:-40]
        if time.monotonic() > deadline:
            break
        node = find(nodes, matches)
        if node is not None and for_tap:
            node = tap_target(node, nodes)
        if node is not None:
            return node
        if fail is not None and find(nodes, fail) is not None:
            raise TestFailure(f"{description}: {[t for t in texts if fail(t)]}")
        if crashed():
            raise TestFailure(f"{description}: app crashed")
        if sweep and nodes:
            current = viewport(nodes)
            if current == previous:
                down = not down
                steps = 0
                if down:
                    time.sleep(min(2, max(0, deadline - time.monotonic())))
            if steps >= 30:
                search_failure(f"{description}: could not reach list end", observations)
            if time.monotonic() < deadline and scroll(down, nodes):
                observations[-1]["swipe_down"] = down
                previous = current
                steps += 1
        elif not nodes:
            previous = None
        time.sleep(min(1, max(0, deadline - time.monotonic())))
    search_failure(f"{description}: timed out, screen has {texts}", observations)


def absent(description, matches):
    """先回到列表顶部，再逐屏检查到底部。屏幕外不算不存在。"""
    deadline = time.monotonic() + TIMEOUT
    for down in (False, True):
        previous = None
        steps = 0
        while time.monotonic() < deadline:
            nodes = screen()
            if time.monotonic() > deadline:
                break
            if find(nodes, matches) is not None:
                raise TestFailure(description)
            if crashed():
                raise TestFailure(f"{description}: app crashed")
            if not nodes:
                previous = None
                time.sleep(1)
                continue
            current = viewport(nodes)
            if current == previous:
                break
            if steps >= 30 or not scroll(down, nodes):
                raise TestFailure(f"{description}: could not inspect whole list")
            previous = current
            steps += 1
        else:
            raise TestFailure(f"{description}: timed out inspecting whole list")
        if time.monotonic() >= deadline:
            raise TestFailure(f"{description}: timed out inspecting whole list")


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap_text(text, sweep=False):
    tap(wait_for(f"find {text!r}", lambda t: t == text, sweep=sweep, for_tap=True))


def tap_card(text):
    tap_text(text, sweep=True)


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
    tap_text("Code APK")  # ACTION_VIEW always requires an explicit package-kind choice.
    wait_for(f"confirm {name}", lambda t: t.startswith(f"Install {name} "))
    tap_text("Install")


def plugin_entry_ready():
    nodes = screen()
    entry = [n for n in nodes if n.get("text") == "Plugins and diagnostics"]
    if len(entry) > 1:
        raise TestFailure("Ambiguous plugin navigation")
    if entry:
        tap(entry[0])
        return False
    if any(n.get("text") == "Import code APK" for n in nodes):
        return True
    menu = [n for n in nodes if n.get("content-desc") == "Open navigation menu"]
    if len(menu) > 1:
        raise TestFailure("Ambiguous navigation menu")
    if menu:
        tap(menu[0])
    return False


def open_plugins():
    deadline = time.monotonic() + TIMEOUT
    while time.monotonic() < deadline:
        if plugin_entry_ready():
            return
        if crashed():
            raise TestFailure("Host crashed opening plugin management")
        time.sleep(1)
    raise TestFailure("Timed out opening plugin management from chat")


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
    open_plugins()
    wait_for("host shows up", lambda t: t == "No plugins installed", sweep=True)

    if hello:
        step("install Hello")
        install_plugin(hello, "Hello")
        tap_card("Hello")
        wait_for("Hello page", lambda t: t == "Hello from a plugin")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        wait_for("back on the list", lambda t: t == "Hello", sweep=True)

    step("install Probe")
    install_plugin(probe, "Probe")
    tap_card("Probe")

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
    open_plugins()
    tap_card("Probe")
    wait_for("probe after restart", lambda t: PROBE_PASSED.match(t) is not None, fail=lambda t: t.startswith("FAIL "))

    step("disable Probe and verify registration removal")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    adb("logcat", "-c")
    tap_card("Disable Probe")
    wait_for("Probe disabled", lambda t: t == "Enable Probe", sweep=True)
    deadline = time.monotonic() + TIMEOUT
    expected = ["onDisable", "cleanup:second", "cleanup:first"]
    while time.monotonic() < deadline:
        logs = adb("logcat", "-d", "-v", "raw", "ThetaProbeLifecycle:I", "*:S")
        events = [line.strip() for line in logs.splitlines() if line.strip() in expected]
        if events == expected:
            break
        time.sleep(1)
    else:
        raise TestFailure(f"lifecycle order: expected {expected}, got {events}")
    absent("disabled Probe still exposes its page", lambda t: t == "Probe")

    step("disabled preference survives process restart")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    open_plugins()
    wait_for("disabled after restart", lambda t: t == "Enable Probe", sweep=True)
    absent("disabled Probe loaded after restart", lambda t: t == "Probe")

    step("enable a not-yet-loaded plugin in the fresh process")
    tap_card("Enable Probe")
    tap_card("Probe")
    wait_for("re-enabled Probe", lambda t: PROBE_PASSED.match(t) is not None, fail=lambda t: t.startswith("FAIL "))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap_card("Disable Probe")
    wait_for("second disable finished", lambda t: t == "Disabled; restart required before loading again", sweep=True)
    tap_card("Enable Probe")
    wait_for("reenable deferred", lambda t: t == "Enabled for next start; restart required", sweep=True)
    absent("closed plugin reloaded in the same process", lambda t: t == "Probe")

    step("recovery cold-start skips enabled plugins")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity", "--ez", "skip_plugins", "true")
    wait_for("recovery controls", lambda t: t == "Disable Probe", sweep=True)
    absent("recovery launched a plugin page", lambda t: t in ("Probe", "Hello"))

    step("normal restart restores enabled Probe")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity", "--ez", "skip_plugins", "false")
    open_plugins()
    tap_card("Probe")
    wait_for("Probe after recovery", lambda t: PROBE_PASSED.match(t) is not None, fail=lambda t: t.startswith("FAIL "))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")

    step("uninstall loaded Probe and finish deletion on restart")
    tap_card("Uninstall Probe")
    tap_text("Confirm uninstall")
    wait_for("uninstall pending", lambda t: "restart" in t.lower() and "uninstall" in t.lower(), sweep=True)
    absent("pending uninstall still exposes its page", lambda t: t == "Probe")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    open_plugins()
    wait_for("host restored after deletion", lambda t: t == ("Hello" if hello else "No plugins installed"), sweep=True)
    absent("Probe still installed after deferred deletion", lambda t: "Probe" in t)

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
