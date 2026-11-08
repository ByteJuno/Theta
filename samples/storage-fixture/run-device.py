"""Run ONLY by the sole device operator after the parent builds the frozen APK.
No install, force-stop, host access, or external fixture payloads. Python stdlib only.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time
import uuid

PACKAGE = "io.github.bytejuno.thetacore.sample.storagefixture"
ACTIVITY = PACKAGE + "/io.github.bytejuno.thetacore.plugin.StorageFixtureActivity"
STAGES = ("INTENT", "TEMP_CREATED", "PAYLOAD_READY", "PARENT_READY", "MOVED")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    # Keep previous runs immutable; refuse an existing evidence directory.
    args.output.mkdir(parents=True, exist_ok=False)

    def adb(*parts, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *parts], check=check,
                              capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20)

    def read_record(case, record):
        # The disposable fixture's own debuggable filesDir only. No candidate9/host package.
        response = adb("shell", "run-as", PACKAGE, "cat",
                       f"files/s1-storage-evidence/{case}-{record}.json", check=False)
        if response.returncode or len(response.stdout) > 8192:
            return None
        try:
            return json.loads(response.stdout)
        except json.JSONDecodeError:
            return None

    def command(name, stage, enabled):
        case = f"{stage}-{'enabled' if enabled else 'disabled'}"
        request_id = uuid.uuid4().hex
        started = adb("shell", "am", "start", "-W", "-n", ACTIVITY,
                      "--es", "command", name, "--es", "stage", stage,
                      "--ez", "enabled", str(enabled).lower(), "--es", "requestId", request_id)
        (args.output / f"{case}-{name}-start.txt").write_text(
            started.stdout + started.stderr, encoding="utf-8")
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            for record_name in ("ERROR", name):
                result = read_record(case, record_name)
                if not result or result.get("requestId") != request_id:
                    continue
                (args.output / f"{case}-{name}.json").write_text(
                    json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                expected = "CUTPOINT_REACHED" if name == "CRASH" else "PASS"
                if result.get("status") != expected:
                    raise RuntimeError(f"{case}/{name}: {result}")
                print(json.dumps(result, ensure_ascii=False), flush=True)
                return result
            time.sleep(0.25)
        raise TimeoutError(f"No matching requestId evidence: {case}/{name}")

    results = []
    for enabled in (False, True):
        for stage in STAGES:
            baseline = command("PREPARE", stage, enabled)
            mismatch = command("MISMATCH", stage, enabled)
            crash = command("CRASH", stage, enabled)
            # Evidence is emitted BEFORE SIGKILL; do not race the next Activity launch.
            deadline = time.monotonic() + 15
            while adb("shell", "pidof", PACKAGE, check=False).stdout.strip():
                if time.monotonic() >= deadline:
                    raise TimeoutError("Fixture did not exit; do not force-stop to manufacture a pass")
                time.sleep(0.25)
            recovered = command("RECOVER", stage, enabled)
            assert len({r["runId"] for r in (baseline, mismatch, crash, recovered)}) == 1
            assert recovered["processToken"] != crash["processToken"]
            assert recovered["previousProcessToken"] == crash["processToken"]
            assert recovered["selectedVersion"] == 1 and recovered["enabled"] == enabled
            assert recovered["updatePublished"] == (stage == "MOVED")
            assert recovered["updateSelected"] is False and recovered["baselineBytesIntact"] is True
            results.append(recovered)
    (args.output / "summary.json").write_text(json.dumps({
        "scope": "DATA", "status": "PASS", "recoveryCases": len(results),
        "digestMismatchCases": len(results), "codePublication": "NOT_TESTED",
        "physicalPowerLoss": "NOT_TESTED", "results": results,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
