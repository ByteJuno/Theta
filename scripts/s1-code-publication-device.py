"""Real host UI/process-death checks on the uniquely owned disposable AVD only."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import time
import uuid
import zipfile

HOST = "io.github.bytejuno.thetacore"
PLUGIN = HOST + ".sample.fixture"
COMPONENT = HOST + ".test/" + HOST + ".S1PublicationInstrumentation"
OWNER = "theta_s1_code_20261109_owned"
STAGES = ("INTENT", "TEMP_CREATED", "PAYLOAD_READY", "PARENT_READY", "MOVED", "PARTIAL_WRITE")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True, choices=["emulator-5590"])
    parser.add_argument("--adb", default=r"D:\Android\Sdk\platform-tools\adb.exe")
    parser.add_argument("--host-apk", required=True, type=Path)
    parser.add_argument("--test-apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--stage", action="append", choices=STAGES)
    parser.add_argument("--enabled", choices=["false", "true", "both"], default="both")
    parser.add_argument("--controls-only", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    report = {"status": "RUNNING", "serial": args.serial, "avd": "theta_s1_code_20261109", "cases": []}
    case_dir = None

    def event(data):
        with (args.output / "commands.jsonl").open("a", encoding="utf-8") as log:
            log.write(json.dumps({"monotonic": time.monotonic(), **data}) + "\n")

    def adb(*parts, check=True, timeout=40, binary=False):
        try:
            completed = subprocess.run([args.adb, "-s", args.serial, *map(str, parts)], capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as failure:
            event({"args": list(map(str, parts)), "timeout_seconds": timeout,
                   "stdout": (failure.stdout or b"").decode("utf-8", "replace"),
                   "stderr": (failure.stderr or b"").decode("utf-8", "replace")})
            raise
        event({"args": list(map(str, parts)), "exit": completed.returncode,
               "stdout": {"bytes": len(completed.stdout), "sha256": hashlib.sha256(completed.stdout).hexdigest()} if binary else completed.stdout.decode("utf-8", "replace"),
               "stderr": completed.stderr.decode("utf-8", "replace")})
        if check and completed.returncode:
            raise RuntimeError("Device command failed: " + str(parts))
        return completed.stdout if binary else completed.stdout.decode("utf-8", "replace").strip()

    def guard():
        assert adb("shell", "getprop", "ro.boot.qemu.avd_name") == "theta_s1_code_20261109"
        assert adb("shell", "cat", "/data/local/tmp/theta-s1-code-owner") == OWNER

    def record(request):
        raw = adb("shell", "run-as", HOST, "cat", f"files/s1-code-evidence/{request}.json", check=False)
        return json.loads(raw) if raw.startswith("{") else None

    def command(case_dir, name, point, enabled, run_id, previous=None, control=False):
        guard()
        request = uuid.uuid4().hex
        arguments = ["shell", "am", "instrument", "-w", "-e", "command", name, "-e", "stage", point,
                     "-e", "enabled", str(enabled).lower(), "-e", "runId", run_id, "-e", "requestId", request,
                     "-e", "deviceReceipt", OWNER]
        if previous:
            arguments += ["-e", "previousRequestId", previous]
        if control:
            arguments += ["-e", "control", "true"]
        started = adb(*arguments, COMPONENT, check=False, timeout=150)
        (case_dir / (name + "-instrumentation.txt")).write_text(started, encoding="utf-8")
        result = record(request)
        if result:
            (case_dir / (name + ".json")).write_text(json.dumps(result, indent=2), encoding="utf-8")
        expected = "CUTPOINT_REACHED" if name == "CRASH" else "PASS"
        if not result or result.get("requestId") != request or result.get("runId") != run_id or result.get("status") != expected:
            raise RuntimeError(f"{point}/{enabled}/{name} failed: {result or started}")
        if name == "CRASH":
            deadline = time.monotonic() + 10
            while adb("shell", "pidof", HOST, check=False):
                if time.monotonic() >= deadline:
                    raise RuntimeError("Host did not disappear naturally; no external kill is allowed here")
                time.sleep(.2)
        return result

    def snapshot(case_dir, label):
        guard()
        data = adb("exec-out", "run-as", HOST, "tar", "-cf", "-", "files", "cache", binary=True)
        (case_dir / (label + ".tar")).write_bytes(data)
        files = {}
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as archive:
            for member in archive.getmembers():
                if member.isfile():
                    name = member.name.removeprefix("./")
                    assert not name.startswith("/") and ".." not in Path(name).parts
                    content = archive.extractfile(member).read()
                    files[name] = {"bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}
                    if name.endswith((".properties", ".txt")) and len(content) < 16384:
                        files[name]["text"] = content.decode("utf-8", "replace")
        (case_dir / (label + "-files.json")).write_text(json.dumps(files, indent=2), encoding="utf-8")
        return files

    def verify_snapshot(files, baseline, expected_version, point=None):
        base = f"files/plugins/{PLUGIN}/"
        v1 = base + "1/plugin.apk"
        v2 = base + "2/plugin.apk"
        assert files[v1]["sha256"] == payload_hashes[1]
        counter = f"files/plugin-data/{PLUGIN}/counter.txt"
        assert files[counter]["text"] == "1" and files[counter]["sha256"] == baseline[counter]["sha256"]
        if expected_version == 2:
            assert files[v2]["sha256"] == payload_hashes[2]
        else:
            assert v2 not in files
        if point == "PARTIAL_WRITE":
            temporary = [(name, value) for name, value in files.items() if name.startswith("files/plugin-code-publication/") and name.endswith("/plugin.apk")]
            assert len(temporary) == 1
            assert 0 < temporary[0][1]["bytes"] < payload_sizes[2]
            assert temporary[0][1]["sha256"] != payload_hashes[2]

    try:
        guard()
        report["host_sha256"] = hashlib.sha256(args.host_apk.read_bytes()).hexdigest()
        report["instrumentation_sha256"] = hashlib.sha256(args.test_apk.read_bytes()).hexdigest()
        payload_hashes = {}
        payload_sizes = {}
        with zipfile.ZipFile(args.test_apk) as archive:
            for version in (1, 2):
                content = archive.read(f"assets/code-v{version}.apk")
                payload_hashes[version] = hashlib.sha256(content).hexdigest()
                payload_sizes[version] = len(content)
        report["payload_sha256"] = payload_hashes
        report["payload_bytes"] = payload_sizes
        assert payload_sizes[2] > 8 * 1024 * 1024
        adb("install", "-r", args.host_apk, timeout=90)
        adb("install", "-r", args.test_apk, timeout=90)
        installed = adb("shell", "pm", "path", HOST).splitlines()[0].removeprefix("package:")
        assert adb("shell", "sha256sum", installed).split()[0] == report["host_sha256"]
        settings = (False, True) if args.enabled == "both" else (args.enabled == "true",)
        stages = args.stage or STAGES
        for enabled in settings:
            scenarios = [(point, "CRASH") for point in stages] if not args.controls_only else []
            scenarios += [("INTENT", "DIGEST"), ("MOVED", "CONTROL")]
            for point, operation in scenarios:
                guard()
                case_dir = args.output / f"{operation}-{point}-{str(enabled).lower()}"
                case_dir.mkdir()
                # This reset is restricted to a newly created, receipt-guarded AVD's host.
                assert adb("shell", "pm", "clear", HOST) == "Success"
                run_id = uuid.uuid4().hex
                baseline = command(case_dir, "PREPARE", point, enabled, run_id)
                before = snapshot(case_dir, "baseline")
                outcome = command(case_dir, operation, point, enabled, run_id)
                during = snapshot(case_dir, "interrupted")
                moved = point == "MOVED" and operation != "DIGEST"
                verify_snapshot(during, before, 2 if moved else 1, point if operation == "CRASH" else None)
                recovered = command(case_dir, "RECOVER", point, enabled, run_id, outcome["requestId"], operation == "CONTROL")
                after = snapshot(case_dir, "recovered")
                verify_snapshot(after, before, 2 if moved else 1)
                assert not any(name.startswith(("files/plugin-code-publication/", "cache/plugin-staging/")) for name in after)
                assert recovered["freshProcess"] and recovered["processToken"] != outcome["processToken"]
                assert recovered["modelEnabled"] == enabled and recovered["selectedVersion"] == (2 if moved else 1)
                case = {"operation": operation, "stage": point, "enabled": enabled, "runId": run_id,
                        "baseline": baseline, "outcome": outcome, "recovered": recovered,
                        "raw_filesystem_verified": True, "partial_destination_verified": point == "PARTIAL_WRITE"}
                report["cases"].append(case)
                (args.output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
                print(json.dumps({"operation": operation, "stage": point, "enabled": enabled, "status": "PASS", "recovered_version": recovered["selectedVersion"]}), flush=True)
        report["status"] = "PASS_IN_DEDICATED_EMULATOR_HOST_UI_SCOPE"
    except BaseException as failure:
        report.update(status="FAILED", error=repr(failure))
        if case_dir is not None:
            try:
                snapshot(case_dir, "failed")
            except Exception as capture_failure:
                report["failure_capture_error"] = repr(capture_failure)
        raise
    finally:
        (args.output / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps({"status": report["status"], "completed_cases": len(report["cases"]), "result": str(args.output / "result.json")}), flush=True)


if __name__ == "__main__":
    main()
