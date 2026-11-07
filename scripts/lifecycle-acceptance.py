#!/usr/bin/env python3
"""S1 device acceptance; run ONLY on a disposable emulator in InstaBox.

ADB='.../adb -s emulator-5554' S1_DEDICATED_EMULATOR=1 python3 -I \
    scripts/lifecycle-acceptance.py HOST.apk FIXTURE_APK_ROOT
FIXTURE_APK_ROOT defaults to samples/fixtures/build/outputs/apk.
Each flavor is <root>/<flavor>/release/fixtures-<flavor>-release.apk.
--check-device checks the destructive-test guard without modifying the device.
TEST_TIMEOUT (default 180), TEST_OUTPUT (default build/lifecycle-acceptance),
S1_TOTAL_TIMEOUT (default 5400 seconds) bound waits and retain evidence.

No run-as, root, host internals, signature gate, or hot unloading assumptions.
A timeout proves bounded host waiting, NOT termination of full-trust dex.
Compose crash isolation and interruption inside filesystem deletion are NOT
covered. The tombstone case kills during cleanup, before cold-start deletion.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import sys
import time
import zipfile

spec = importlib.util.spec_from_file_location("theta_emulator", Path(__file__).with_name("emulator-test.py"))
em = importlib.util.module_from_spec(spec)
spec.loader.exec_module(em)
OUTPUT = Path(os.environ.get("TEST_OUTPUT", "build/lifecycle-acceptance"))
ADB = shlex.split(os.environ.get("ADB", "adb"))
TIMEOUT = int(os.environ.get("TEST_TIMEOUT", "180"))
FLAVORS = ("normalV1", "normalV2", "initThrow", "initBlock", "initThrowV2", "initBlockV2", "disableThrow", "cleanupThrow", "cleanupBlock", "scopeThrow", "scopeInitThrow", "scopeInitThrowV2")
PACKAGE = em.PACKAGE
FAIL = em.TestFailure
RESULTS = []
CURRENT = "preflight"
EVIDENCE_ERRORS = []


def adb(*args, timeout=30, check=True):
    return _adb(*args, timeout=timeout, check=check)


# Keep the existing XML/tap implementation, but bound each adb call and support
# quoted ADB executable paths. Do not reuse the older destructive run() helper.
_adb = em.adb
em.ADB = ADB
em.adb = adb


def require(condition, message):
    if not condition:
        raise FAIL(message)


def check_device():
    require(os.environ.get("S1_DEDICATED_EMULATOR") == "1", "Set S1_DEDICATED_EMULATOR=1 for a disposable emulator")
    require("-s" in ADB and ADB.index("-s") + 1 < len(ADB), "ADB must explicitly select an emulator using -s")
    serial = ADB[ADB.index("-s") + 1]
    require(re.fullmatch(r"emulator-\d+", serial) is not None, "Refusing a non-emulator ADB target")
    require(adb("get-serialno").strip() == serial, "Selected device serial mismatch")
    require(adb("shell", "getprop", "ro.kernel.qemu").strip() == "1", "Selected device is not an emulator")
    return serial


def logs():
    return adb("logcat", "-d", "-v", "raw", "ThetaFixture:I", "*:S")


def events(flavor):
    prefix = flavor + "|"
    return [line.strip()[len(prefix):] for line in logs().splitlines() if line.strip().startswith(prefix)]


def wait(description, predicate, timeout=None):
    deadline = time.monotonic() + (TIMEOUT if timeout is None else timeout)
    while time.monotonic() < deadline:
        result = predicate()
        if time.monotonic() > deadline:
            raise FAIL(description + ": observation exceeded deadline")
        # ElementTree leaf nodes are falsey even when they matched.
        if result is not None and result is not False:
            return result
        if em.crashed():
            raise FAIL(description + ": host crashed")
        time.sleep(0.3)
    raise FAIL(description + ": timed out")


def scroll(down):
    # One card can still exceed a small screen when failure messages wrap.
    size = re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1]
    width, height = map(int, size)
    top, bottom = int(height * 0.25), int(height * 0.80)
    adb("shell", "input", "swipe", str(width // 2), str(bottom if down else top), str(width // 2), str(top if down else bottom), "200")


def matching(description, predicate, timeout=None):
    attempts = 0

    def locate():
        nonlocal attempts
        node = em.find(em.screen(), predicate)
        if node is None:
            # Sweep down then up; bounded by wait(), never an unbounded search.
            scroll(attempts % 6 < 3)
            attempts += 1
        return node

    return wait(description, locate, timeout)


def text(value):
    return matching(repr(value), lambda t: t == value)


def tap(value):
    em.tap(text(value))


def absent(value):
    # Cases use a single plugin card, but wrapping diagnostics can push its page
    # below the fold. Inspect both ends rather than treating offscreen as absent.
    for down in (False, False, True, True):
        require(em.find(em.screen(), lambda t: t == value) is None, f"Unexpected visible text: {value}")
        scroll(down)
    require(em.find(em.screen(), lambda t: t == value) is None, f"Unexpected visible text: {value}")


def event(flavor, value, timeout=None):
    wait(f"{flavor}|{value}", lambda: value in events(flavor), timeout)


def ordered(flavor, expected):
    actual = events(flavor)
    require(actual == expected, f"{flavor} events: expected {expected}, got {actual}")


def back():
    adb("shell", "input", "keyevent", "KEYCODE_BACK")


def start(recovery=False):
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity", "--ez", "skip_plugins", str(recovery).lower())


def restart(recovery=False):
    adb("shell", "am", "force-stop", PACKAGE)
    start(recovery)


def clear_logs():
    # Preserve callback markers BEFORE clearing them for the next assertion.
    checkpoint = OUTPUT / "checkpoints"
    checkpoint.mkdir(parents=True, exist_ok=True)
    index = len(list(checkpoint.glob("*.txt")))
    (checkpoint / f"{index:03d}-{CURRENT}.txt").write_text(adb("logcat", "-d", "-t", "4000"), encoding="utf-8")
    adb("logcat", "-c")


def fresh(host):
    adb("shell", "am", "force-stop", PACKAGE, check=False)
    adb("uninstall", PACKAGE, check=False)
    # Prove the old install is gone before using -r; otherwise data assertions lie.
    require("package:" not in adb("shell", "pm", "path", PACKAGE, check=False), "Host uninstall did not complete")
    installed = adb("install", host, timeout=300)
    require("Success" in installed, "Host APK installation failed: " + installed)
    clear_logs()
    adb("logcat", "-c", "-b", "crash")
    start()
    text("No plugins installed")


def stage(apk):
    remote = "/data/local/tmp/theta-s1-fixture.apk"
    adb("push", str(apk), remote, timeout=120)
    adb("shell", "chmod", "644", remote)
    adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", f"file://{remote}", "-n", f"{PACKAGE}/.MainActivity")
    # Shared by lifecycle AND signature acceptance, including rejection cases.
    # Missing selector is a hard bounded UI failure, not an optional dialog.
    tap("Code APK")


def install(apk, version="1.0"):
    stage(apk)
    text(f"Install Fixture {version}?")
    tap("Install")


def page(flavor, count):
    tap("Fixture page")
    text(f"Fixture runtime {flavor}")
    text(f"Counter {count}")


def increment(count):
    tap("Increment counter")
    text(f"Counter {count}")


def responsive_while_blocked(flavor, end_marker):
    require(end_marker not in events(flavor), "Blocking callback finished before responsiveness check")
    # Picker is a real user action independent of the serialized plugin operations.
    tap("Import code APK")
    wait("system file picker opened", lambda: any("documentsui" in n.get("package", "").lower() for n in em.screen()))
    require(end_marker not in events(flavor), "Inconclusive: picker observation missed blocking window")
    back()
    text("Import code APK")


def uninstall(clear=False, check_page=True):
    tap("Uninstall Fixture")
    text("Uninstall Fixture?")
    if clear:
        nodes = em.screen()
        box = next((n for n in nodes if n.get("checkable") == "true"), None)
        require(box is not None and box.get("checked") == "false", "Expected unchecked delete-data checkbox")
        em.tap(box)
        wait("delete-data checked", lambda: any(n.get("checkable") == "true" and n.get("checked") == "true" for n in em.screen()))
    tap("Confirm uninstall")
    text("Uninstall pending; restart required")
    if check_page:
        absent("Fixture page")


def upgrade_data(host, apks):
    fresh(host)
    install(apks["normalV1"])
    page("normalV1", 0)
    increment(1)
    back()
    clear_logs()
    install(apks["normalV2"], "2.0")
    text("Fixture · 2.0")
    text("Update installed; restart required")
    require(not events("normalV2"), "v2 executed before process restart")
    require(not events("normalV1"), "Upgrade recreated or closed the running v1 instance")
    page("normalV1", 1)  # Installed metadata is v2; running dex MUST still be v1.
    increment(2)
    restart()
    page("normalV2", 2)
    increment(3)
    back()
    # Equal and lower versions must not replace the active v2 package or data.
    for flavor, version in (("normalV2", "2.0"), ("normalV1", "1.0")):
        install(apks[flavor], version)
        text("Install requires a higher version than 2")
        page("normalV2", 3)
        back()
    clear_logs()
    uninstall(clear=False)
    event("normalV2", "cleanup:first")
    ordered("normalV2", ["disable:start", "disable:end", "cleanup:second:start", "cleanup:second:end", "cleanup:first"])
    restart()
    text("No plugins installed")
    # Reinstalling v1 also proves deferred deletion removed ALL old package versions.
    install(apks["normalV1"])
    page("normalV1", 3)
    back()
    uninstall(clear=True)
    restart()
    text("No plugins installed")
    install(apks["normalV1"])
    page("normalV1", 0)


def disabled_restart():
    restart()
    text("Enable Fixture")
    absent("Fixture page")


def cleanup_fault(host, apks, flavor):
    fresh(host)
    install(apks[flavor])
    page(flavor, 0)
    back()
    clear_logs()
    tap("Disable Fixture")
    if flavor == "cleanupBlock":
        event(flavor, "cleanup:block:start")
        responsive_while_blocked(flavor, "cleanup:block:end")
    text("Disabled; cleanup failed; restart required")
    if flavor == "cleanupBlock":
        require("cleanup:block:end" not in events(flavor), "Inconclusive: timeout observed after callback return")
    absent("Fixture page")
    if flavor == "cleanupBlock":
        observed = events(flavor)
        require("cleanup:first" not in observed or "cleanup:block:end" in observed,
                "Later cleanup ran before blocking callback returned")
        event(flavor, "cleanup:block:end", timeout=90)
    event(flavor, "cleanup:first")
    expected = ["disable:start", "disable:throw"] if flavor == "disableThrow" else ["disable:start", "disable:end"]
    expected += ["cleanup:second:start"]
    expected += ["cleanup:throw"] if flavor == "cleanupThrow" else (["cleanup:block:start", "cleanup:block:end", "cleanup:second:end"] if flavor == "cleanupBlock" else ["cleanup:second:end"])
    ordered(flavor, expected + ["cleanup:first"])
    # An eventual callback return must not resurrect registration or erase failure.
    text("Disabled; cleanup failed; restart required")
    absent("Fixture page")
    clear_logs()
    disabled_restart()
    require(not events(flavor), "Disabled plugin executed on cold start")


def init_fault(host, apks, flavor):
    fresh(host)
    install(apks[flavor])
    event(flavor, "enable:throw" if flavor == "initThrow" else "enable:block:start")
    if flavor == "initBlock":
        responsive_while_blocked(flavor, "enable:block:end")
    text("Initialization failed; restart required")
    if flavor == "initBlock":
        require("enable:block:end" not in events(flavor), "Inconclusive: initialization timeout observed after return")
    absent("Fixture page")
    if flavor == "initBlock":
        matching("observable initialization timeout", lambda t: "Initialization timed out" in t)
        event(flavor, "late-register:rejected", timeout=90)
        event(flavor, "late-cleanup:rejected")
    event(flavor, "cleanup:first")
    prefix = ["enable:start", "enable:throw"] if flavor == "initThrow" else ["enable:start", "enable:block:start", "enable:block:end", "late-register:rejected", "late-cleanup:rejected", "enable:return"]
    ordered(flavor, prefix + ["disable:start", "disable:end", "cleanup:second:start", "cleanup:second:end", "cleanup:first"])
    text("Initialization failed; restart required")
    absent("Fixture page")
    clear_logs()
    restart()
    text("Previous initialization interrupted; disabled")
    text("Enable Fixture")
    absent("Fixture page")
    require(not events(flavor), "Failure marker did not prevent cold-start execution")


def interrupted_initialization(host, apks):
    fresh(host)
    install(apks["initBlock"])
    event("initBlock", "enable:block:start")
    # Do not wait for a 60s callback or rely on release run-as to forge a marker.
    require("enable:block:end" not in events("initBlock"), "Missed interrupted-initialization window")
    adb("shell", "am", "force-stop", PACKAGE)
    stopped = events("initBlock")
    require("enable:block:start" in stopped and "enable:block:end" not in stopped,
            "Inconclusive: initialization returned before force-stop completed")
    clear_logs()
    start()
    text("Previous initialization interrupted; disabled")
    text("Enable Fixture")
    absent("Fixture page")
    require(not events("initBlock"), "Interrupted plugin executed again")
    # A disabled plugin cannot prove recovery skips enabled plugins.
    fresh(host)
    install(apks["normalV1"])
    page("normalV1", 0)
    adb("shell", "am", "force-stop", PACKAGE)
    clear_logs()
    start(recovery=True)
    text("Safe mode; loading skipped until restart")
    absent("Fixture page")
    require(not events("normalV1"), "Recovery mode executed enabled plugin")
    restart()
    page("normalV1", 0)
    event("normalV1", "enable:return")


def interrupted_uninstall(host, apks):
    fresh(host)
    install(apks["cleanupBlock"])
    page("cleanupBlock", 0)
    increment(1)
    back()
    clear_logs()
    uninstall(clear=True, check_page=False)
    event("cleanupBlock", "cleanup:block:start")
    require("cleanup:block:end" not in events("cleanupBlock"), "Missed interrupted-uninstall window")
    adb("shell", "am", "force-stop", PACKAGE)
    stopped = events("cleanupBlock")
    require("cleanup:block:start" in stopped and "cleanup:block:end" not in stopped,
            "Inconclusive: cleanup returned before force-stop completed")
    clear_logs()
    start()
    text("No plugins installed")
    install(apks["normalV1"])
    page("normalV1", 0)  # Serialized install completes only after startup recovery.
    require(not events("cleanupBlock"), "Tombstoned plugin executed during startup recovery")


def rejected_packages(host, apks):
    fresh(host)
    invalid = OUTPUT / "missing-manifest.apk"
    with zipfile.ZipFile(invalid, "w") as archive:
        archive.writestr("not-a-plugin.txt", "invalid fixture")
    truncated = OUTPUT / "truncated.apk"
    truncated.write_bytes(apks["normalV1"].read_bytes()[:128])
    for apk, expected in ((invalid, "Missing assets/theta-plugin.json"), (truncated, None)):
        stage(apk)
        if expected:
            text(expected)
        else:
            # Android versions differ in ZipException wording; require a ZIP error,
            # not just an unchanged list (which could be a dropped import intent).
            matching("truncated ZIP rejected", lambda t: any(s in t.lower() for s in ("zip", "central directory", "end header")))
        text("No plugins installed")
        absent("Install Fixture 1.0?")
        absent("Fixture page")
        require(not events("normalV1"), "Invalid package activated fixture code")
        restart()
        text("No plugins installed")
    install(apks["normalV1"])
    page("normalV1", 0)  # Rejection must leave installation usable.


def update_to_broken(host, apks, flavor):
    fresh(host)
    install(apks["normalV1"])
    page("normalV1", 0)
    increment(1)
    back()
    install(apks[flavor], "2.0")
    text("Update installed; restart required")
    clear_logs()


def rolled_back(flavor):
    """The fixture wrote 99 over the counter, so 1 proves a snapshot restore, not merely v1 code."""
    matching("rollback report", lambda t: "rolled back to version 1" in t)
    page("normalV1", 1)
    require(not events(flavor), "Broken update executed after rollback")
    back()
    absent("Roll back Fixture")


def rollback_failed_update(host, apks, flavor):
    update_to_broken(host, apks, flavor)
    restart()
    event(flavor, "data:damaged")
    text("Update failed; rollback on next start")
    absent("Fixture page")
    clear_logs()
    restart()
    rolled_back(flavor)


def rollback_interrupted(host, apks):
    flavor = "initBlockV2"
    update_to_broken(host, apks, flavor)
    restart()
    event(flavor, "enable:block:start")
    require("enable:block:end" not in events(flavor), "Missed interrupted-update window")
    adb("shell", "am", "force-stop", PACKAGE)
    stopped = events(flavor)
    require("data:damaged" in stopped and "enable:block:end" not in stopped,
            "Inconclusive: update initialization returned before force-stop completed")
    clear_logs()
    start()
    rolled_back(flavor)


def rollback_manual(host, apks):
    fresh(host)
    install(apks["normalV1"])
    page("normalV1", 0)
    increment(1)
    back()
    install(apks["normalV2"], "2.0")
    text("Update installed; restart required")
    install(apks["normalV2"], "2.0")  # A second update must wait for the first to activate.
    text("Update or rollback pending; restart required")
    restart()
    page("normalV2", 1)
    increment(2)
    back()
    tap("Roll back Fixture")
    tap("Confirm rollback")
    text("Rollback scheduled; restart required")
    absent("Fixture page")
    clear_logs()
    restart()
    rolled_back("normalV2")
    # The rolled-back version stays installable: rollback must leave no stale package behind.
    install(apks["normalV2"], "2.0")
    text("Update installed; restart required")
    restart()
    page("normalV2", 1)


def managed_scope_fault(host, apks, hello, flavor):
    fresh(host)
    stage(hello)
    text("Install Hello 0.1.0?")
    tap("Install")
    tap("Hello")
    text("Hello from a plugin")
    back()
    pid = adb("shell", "pidof", PACKAGE).strip()
    require(bool(pid), "Host PID missing before managed fault")
    install(apks[flavor])
    if flavor == "scopeThrow":
        page(flavor, 0)
        tap("Throw in managed scope")
    event(flavor, "scope:throw" if flavor == "scopeThrow" else "scope:init:throw", timeout=20)
    if flavor == "scopeInitThrow":
        event(flavor, "late-register:rejected", timeout=20)
    # An independent short deadline, not the generic 180s test timeout. The
    # Data packages section pushes the fixture card below the fold, so sweep.
    matching("managed scope disabled promptly",
             lambda t: t == "Managed scope failed; disabled; restart required", timeout=20)
    require(adb("shell", "pidof", PACKAGE).strip() == pid, "Managed fault restarted/killed host")
    absent("Fixture page")
    tap("Hello")
    text("Hello from a plugin")
    back()
    require(events(flavor).count("enable:start") == 1, "Repeated instantiation after fault")
    tap("Enable Fixture")
    text("Enabled for next start; restart required")
    require(events(flavor).count("enable:start") == 1, "Re-enable instantiated faulty ID")
    tap("Disable Fixture")
    text("Disabled; restart required before loading again")
    # Keep gate assertions separate from automatic-fault persistence evidence.
    fresh(host)
    install(apks[flavor])
    if flavor == "scopeThrow":
        page(flavor, 0)
        tap("Throw in managed scope")
    matching("automatic scope disable",
             lambda t: t == "Managed scope failed; disabled; restart required", timeout=20)
    clear_logs()
    restart()
    text("Enable Fixture")
    absent("Fixture page")
    require(not events(flavor), "Disabled scope fixture executed on cold start")
    (OUTPUT / (CURRENT + "-pid.json")).write_text(json.dumps({"before": pid,
        "fault_handled_same_pid": True}), encoding="utf-8")


# Same ID as the code fixture on purpose: data ownership must refuse that code APK.
DATA_ID = "io.github.bytejuno.thetacore.sample.fixture"


def data_archive(version, body, extra=None):
    path = OUTPUT / f"data-v{version}{'-' + extra if extra else ''}.zip"
    manifest = {"schema": 1, "type": "data", "id": DATA_ID, "name": "Fixture data",
                "version": f"{version}.0", "versionCode": version, "apiLevel": 1}
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("theta-plugin.json", json.dumps(manifest))
        archive.writestr("skills/demo/SKILL.md", body)
        if extra:
            archive.writestr(extra, b"dex\n035\0")
    return path


def stage_data(path):
    remote = "/data/local/tmp/theta-s1-data.zip"
    adb("push", str(path), remote, timeout=120)
    adb("shell", "chmod", "644", remote)
    adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", f"file://{remote}", "-n", f"{PACKAGE}/.MainActivity")
    tap("Data ZIP")


def rect(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def acknowledgement_locator():
    """Locate a tap point on the visible part of the acknowledgement checkbox.

    The checkbox sits at the end of the dialog's own scroll body, which clips it
    on a small screen, so its centre can fall outside the body and hit nothing.
    Swipe the body (never the activity behind the dialog) once towards its end,
    then tap the middle of whatever part of the box is visible inside it.
    """
    swiped = False

    def locate():
        nonlocal swiped
        nodes = em.screen()
        box = next((n for n in nodes if n.get("checkable") == "true"), None)
        if box is None:
            return None
        x1, y1, x2, y2 = rect(box)
        body = next((n for n in nodes if n.get("scrollable") == "true"), None)
        if body is None:
            return (x1 + x2) // 2, (y1 + y2) // 2
        bx1, by1, bx2, by2 = rect(body)
        if (y1 < by1 or y2 > by2) and not swiped:
            swiped = True
            x = (bx1 + bx2) // 2
            adb("shell", "input", "swipe", str(x), str(by2 - 20), str(x), str(by1 + 20), "300")
            return None  # look again at the scrolled body
        top, bottom = max(y1, by1), min(y2, by2)
        if bottom - top < 16:
            return None
        return (max(x1, bx1) + min(x2, bx2)) // 2, (top + bottom) // 2

    return locate


def install_data(path, version):
    stage_data(path)
    text("Confirm data ZIP / unsigned update")
    # The dialog must show the digest of these exact bytes.
    text("SHA-256: " + hashlib.sha256(path.read_bytes()).hexdigest())
    point = wait("unsigned-data acknowledgement", acknowledgement_locator())
    adb("shell", "input", "tap", *map(str, point))
    wait("acknowledgement checked", lambda: any(n.get("checkable") == "true" and n.get("checked") == "true" for n in em.screen()))
    tap("Confirm this ZIP")
    text(f"Fixture data · {version}.0")


def data_content(body):
    tap("View data content")
    text("Untrusted plain text / JSON. No links, execution, authentication or network requests.")
    tap("skills/demo/SKILL.md")
    matching("data file content", lambda t: body in t)
    tap("Back")


def code_refused():
    stage(next(iter(APKS.values())))
    matching("code APK refused for data-owned ID", lambda t: "Data-owned ID; code action refused" in t)
    absent("Install Fixture 1.0?")
    require(not events("normalV1"), "Code fixture ran under a data-owned ID")


def data_zip(host, apks):
    APKS.clear()
    APKS["normalV1"] = apks["normalV1"]
    fresh(host)
    text("No selected data packages")
    stage_data(data_archive(1, "Fixture skill one", extra="classes.dex"))
    matching("code payload rejected", lambda t: "Unsupported data ZIP payload: classes.dex" in t)
    text("No selected data packages")
    install_data(data_archive(1, "Fixture skill one"), 1)
    data_content("Fixture skill one")
    code_refused()
    install_data(data_archive(2, "Fixture skill two"), 2)
    restart()
    text("Fixture data · 2.0")
    data_content("Fixture skill two")
    tap("Disable data")
    text("Enable data")
    absent("View data content")
    restart()
    text("Enable data")
    absent("View data content")
    tap("Enable data")
    data_content("Fixture skill two")
    tap("Uninstall data")
    text(f"Uninstall data {DATA_ID}?")
    em.tap(wait("uninstall confirmation", lambda: ([n for n in em.screen() if n.get("text") == "Uninstall data"] or [None])[-1]))
    text("No selected data packages")
    restart()
    text("No selected data packages")
    code_refused()  # Ownership survives uninstall and cold start.


APKS = {}


def evidence(label):
    directory = OUTPUT / label
    directory.mkdir(parents=True, exist_ok=True)
    # Independently best effort: a broken screenshot must not discard logs/results.
    commands = {
        "screen.xml": ("exec-out", "uiautomator", "dump", "/dev/tty"),
        "crash.txt": ("logcat", "-d", "-b", "crash"),
        "logcat.txt": ("logcat", "-d", "-t", "4000"),
        "fixture.txt": ("logcat", "-d", "-v", "raw", "ThetaFixture:I", "*:S"),
        "activity.txt": ("shell", "dumpsys", "activity", "activities"),
    }
    for filename, args in commands.items():
        try:
            (directory / filename).write_text(adb(*args, check=False, timeout=15), encoding="utf-8")
        except FAIL:
            raise
        except Exception as error:
            EVIDENCE_ERRORS.append(f"{label}/{filename}: {error}")
    try:
        result = subprocess.run([*ADB, "exec-out", "screencap", "-p"], capture_output=True, timeout=15)
        (directory / "screen.png").write_bytes(result.stdout)
        if result.returncode:
            EVIDENCE_ERRORS.append(f"{label}/screen.png: adb exit {result.returncode}")
    except FAIL:
        raise
    except Exception as error:
        EVIDENCE_ERRORS.append(f"{label}/screen.png: {error}")


def case(name, action):
    global CURRENT
    CURRENT = name
    em.step(name)
    begun = time.monotonic()
    action()
    require(not em.crashed(), "Host crash detected")
    evidence(name)
    RESULTS.append({"case": name, "status": "passed", "seconds": round(time.monotonic() - begun, 2)})


def expired(_signal, _frame):
    raise FAIL("Overall S1 lifecycle deadline exceeded")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host", nargs="?")
    parser.add_argument("fixtures", nargs="?", default="samples/fixtures/build/outputs/apk")
    parser.add_argument("--hello", default="samples/hello/build/outputs/apk/release/hello-release.apk")
    parser.add_argument("--check-device", action="store_true")
    parser.add_argument("--case", action="append", choices=(
        "upgrade-and-data", "disableThrow", "cleanupThrow", "cleanupBlock",
        "initThrow", "initBlock", "interrupted-initialization", "interrupted-uninstall",
        "rejected-packages", "rollback-init-throw", "rollback-init-block", "rollback-interrupted",
        "rollback-manual", "scopeThrow", "scopeInitThrow", "rollback-scope-init", "data-zip"), help="Run only named cases; report explicitly records partial coverage")
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {"cases": RESULTS, "limitations": [
        "Full-trust dex: timeouts do not terminate noncooperative code; restart is required.",
        "Compose throwing fixture omitted: no promised per-page crash containment in this host.",
        "Interrupted uninstall tests durable tombstone during cleanup, not a kill inside filesystem deletion.",
        "No signatures, prefs/secrets snapshots, observation-period expiry or hot unloading acceptance claimed.",
    ], "evidence_errors": EVIDENCE_ERRORS}
    try:
        report["serial"] = check_device()
        if args.check_device:
            print("Dedicated emulator guard passed", flush=True)
            return 0
        require(args.host is not None, "HOST.apk is required")
        apks = {f: Path(args.fixtures) / f / "release" / f"fixtures-{f}-release.apk" for f in FLAVORS}
        paths = [Path(args.host), Path(args.hello), *apks.values()]
        require(all(p.is_file() for p in paths), f"Missing APKs: {[str(p) for p in paths if not p.is_file()]}")
        report["apk_sha256"] = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
        # InstaBox/Linux: global deadline also bounds command loops and XML retries.
        signal.signal(signal.SIGALRM, expired)
        signal.alarm(int(os.environ.get("S1_TOTAL_TIMEOUT", "5400")))
        cases = {"upgrade-and-data": lambda: upgrade_data(args.host, apks)}
        for flavor in ("disableThrow", "cleanupThrow", "cleanupBlock"):
            cases[flavor] = lambda f=flavor: cleanup_fault(args.host, apks, f)
        for flavor in ("initThrow", "initBlock"):
            cases[flavor] = lambda f=flavor: init_fault(args.host, apks, f)
        cases.update({
            "interrupted-initialization": lambda: interrupted_initialization(args.host, apks),
            "interrupted-uninstall": lambda: interrupted_uninstall(args.host, apks),
            "rejected-packages": lambda: rejected_packages(args.host, apks),
            "rollback-init-throw": lambda: rollback_failed_update(args.host, apks, "initThrowV2"),
            "rollback-init-block": lambda: rollback_failed_update(args.host, apks, "initBlockV2"),
            "rollback-interrupted": lambda: rollback_interrupted(args.host, apks),
            "rollback-manual": lambda: rollback_manual(args.host, apks),
            "scopeThrow": lambda: managed_scope_fault(args.host, apks, args.hello, "scopeThrow"),
            "scopeInitThrow": lambda: managed_scope_fault(args.host, apks, args.hello, "scopeInitThrow"),
            "rollback-scope-init": lambda: rollback_failed_update(args.host, apks, "scopeInitThrowV2"),
            "data-zip": lambda: data_zip(args.host, apks),
        })
        selected = args.case or list(cases)
        report["selected_cases"] = selected
        report["not_run"] = [name for name in cases if name not in selected]
        for name in selected:
            case(name, cases[name])
        report["status"] = "passed"
        print(f"OK: {len(selected)} selected lifecycle cases passed; not run: {report['not_run']}", flush=True)
        return 0
    except Exception as error:
        if hasattr(signal, "SIGALRM"):
            signal.alarm(0)
        report["status"] = "failed"
        RESULTS.append({"case": CURRENT, "status": "failed", "error": str(error)})
        print(f"FAILED [{CURRENT}]: {error}", file=sys.stderr, flush=True)
        # No device mutations before the guard; even failure evidence is read-only.
        if "serial" in report:
            try:
                evidence("FAILED-" + CURRENT)
            except Exception as evidence_error:
                EVIDENCE_ERRORS.append(str(evidence_error))
        return 1
    finally:
        if hasattr(signal, "SIGALRM"):
            signal.alarm(0)
        if not args.check_device:
            (OUTPUT / "results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
