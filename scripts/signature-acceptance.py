#!/usr/bin/env python3
"""InstaBox-only, destructive dedicated-emulator PLATFORM signature acceptance.

Place under scripts/ beside signature-artifacts.py, lifecycle-acceptance.py and
emulator-test.py. The latter two MUST be the actual reviewed lifecycle helpers.
  S1_INSTABOX=1 S1_DEDICATED_EMULATOR=1 ADB='/sdk/platform-tools/adb -s emulator-5554' \
    python3 -I scripts/signature-acceptance.py HOST.apk ARTIFACT_DIR --output NEW_DIR
  ... python3 -I scripts/signature-acceptance.py --check-device

HOST may be debug or release but MUST contain the staged PlatformPluginSignatureVerifier
implementation and scrollable fingerprint UI. No run-as, root, private-data edits,
mock verifier, or Android installation of fixture APKs. Real import, publication,
cold-start load and UI state exercise the platform verifier in the supplied host.
Host uninstall destroys host data: existing lifecycle.check_device gates EVERY
mutation sequence. No automatic cleanup: retain APKs, results and final device state.
TEST_TIMEOUT=180; S1_SIGNATURE_TOTAL_TIMEOUT=5400; all commands bounded as well.
--case selects partial coverage, recorded explicitly. Output directory must be new.
Tool resolution/environment is shared with signature-artifacts.py.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import sys
import time
import xml.etree.ElementTree as ET

# Import helper source without emitting files into the coordinator's scripts/.
sys.dont_write_bytecode = True


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


art = load("s1_signature_artifacts", "signature-artifacts.py")
CASES = ("signed-upgrade-and-fingerprints", "unsigned", "byte-tampered", "different-signer-update",
         "retained-data-binding", "clear-data-uninstall", "retained-clear-data-recovery")


class AcceptanceDeadline(RuntimeError):
    pass


class Acceptance:
    def __init__(self, lifecycle, output, host, artifacts, report):
        self.lc = lifecycle
        self.em = lifecycle.em
        self.out = output
        self.host = host
        self.artifacts = artifacts
        self.report = report
        self.commands = art.Commands(output / "commands")
        self.current = "preflight"
        self.guarded = False
        self.observations = []
        # Route helper calls through a bounded, raw-output-retaining transport.
        self.lc.adb = self.adb
        self.em.adb = self.adb
        self.lc.OUTPUT = output
        self.em.OUTPUT = output
        self.em.screen = self.screen
        self.lc.scroll = self.scroll
        self.lc.matching = self.matching

    def adb(self, *args, timeout=30, check=True):
        # Before check_device succeeds, only its three read-only probes are legal.
        if not self.guarded:
            art.require(args in (("get-serialno",), ("shell", "getprop", "ro.kernel.qemu")),
                        "Refusing adb before existing dedicated-emulator guard")
        result = self.commands.run([*self.lc.ADB, *args], timeout=timeout, check=check)
        return result.stdout.decode("utf-8", "replace")

    def guard(self):
        serial = self.lc.check_device()
        self.guarded = True
        self.report["serial"] = serial

    def screen(self):
        raw = self.adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False, timeout=20)
        start, end = raw.find("<hierarchy"), raw.rfind("</hierarchy>")
        if start < 0 or end < start:
            return []
        try:
            nodes = list(ET.fromstring(raw[start:end + len("</hierarchy>")]).iter("node"))
        except ET.ParseError:
            return []
        # Do not mistake clipped/offscreen semantic text for a tappable control.
        return [n for n in nodes if self.bounds(n) is not None]

    @staticmethod
    def bounds(node):
        numbers = re.findall(r"-?\d+", node.get("bounds", ""))
        if len(numbers) != 4:
            return None
        x1, y1, x2, y2 = map(int, numbers)
        if x2 <= x1 or y2 <= y1 or x2 <= 0 or y2 <= 0:
            return None
        return x1, y1, x2, y2

    def scroll(self, down):
        # A long AlertDialog has its OWN scrollable body; swiping the activity's
        # 25%-80% range can hit dialog buttons or the obscured list instead.
        nodes = self.screen()
        candidates = [self.bounds(n) for n in nodes if n.get("scrollable") == "true"]
        candidates = [b for b in candidates if b and b[3] - b[1] > 60]
        if candidates:
            x1, y1, x2, y2 = min(candidates, key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))
        else:
            sizes = re.findall(r"(\d+)x(\d+)", self.adb("shell", "wm", "size"))
            art.require(sizes, "Cannot determine display size")
            width, height = map(int, sizes[-1])
            x1, x2, y1, y2 = 0, width, int(height * .28), int(height * .72)
        x = max(1, (x1 + x2) // 2)
        top, bottom = max(1, y1 + (y2 - y1) // 5), y2 - (y2 - y1) // 5
        self.adb("shell", "input", "swipe", str(x), str(bottom if down else top),
                 str(x), str(top if down else bottom), "250")

    def matching(self, description, predicate):
        attempt = 0

        def locate():
            nonlocal attempt
            node = self.em.find(self.screen(), predicate)
            if node is None:
                self.scroll(attempt % 12 < 6)
                attempt += 1
            return node

        return self.lc.wait(description, locate)

    def snapshot(self, label):
        directory = self.out / self.current / label
        directory.mkdir(parents=True, exist_ok=True)
        errors = self.report["evidence_errors"]
        for filename, args in {
            "screen.xml": ("exec-out", "uiautomator", "dump", "/dev/tty"),
            "logcat.txt": ("logcat", "-d", "-t", "4000"),
            "crash.txt": ("logcat", "-d", "-b", "crash"),
            "fixture.txt": ("logcat", "-d", "-v", "raw", "ThetaFixture:I", "*:S"),
            "activity.txt": ("shell", "dumpsys", "activity", "activities"),
            "screen.png": ("exec-out", "screencap", "-p"),
        }.items():
            try:
                result = self.commands.run([*self.lc.ADB, *args], timeout=15, check=False)
                (directory / filename).write_bytes(result.stdout)
                if result.returncode:
                    errors.append(f"{self.current}/{label}/{filename}: exit {result.returncode}")
            except AcceptanceDeadline:
                raise
            except Exception as error:
                errors.append(f"{self.current}/{label}/{filename}: {error}")

    def path(self, name):
        return self.artifacts / (name + ".apk")

    def clean(self):
        self.lc.fresh(str(self.host))

    def fingerprint_dialog(self, name):
        data = self.report["verified_artifacts"][name]
        version = data["manifest"]["version"]
        self.lc.text(f"Install Fixture {version}?")
        # Each required field is searched independently across bounded dialog-body
        # sweeps; verify the exact digest from independent apksigner, not just label.
        for required in (data["manifest"]["id"],
                         "Integrity verified; author identity not authenticated.",
                         "Current signer certificate SHA-256:",
                         "This signer set is pinned until an explicit clear-data uninstall."):
            self.matching(required, lambda t, value=required: value in t)
        digest = data["signer_sha256"]
        node = self.matching("exact displayed certificate SHA-256", lambda t: re.sub(r"\s+", "", t).lower() == digest)
        self.observations.append({"case": self.current, "artifact": name, "expected_fingerprint": digest,
                                  "displayed_text": node.get("text")})
        self.snapshot("fingerprint-" + name)

    def install(self, name):
        self.lc.stage(self.path(name))
        self.fingerprint_dialog(name)
        self.lc.tap("Install")

    def counter(self, flavor, count):
        self.lc.page(flavor, count)
        self.lc.back()

    def reject(self, name, trust=False):
        self.lc.clear_logs()
        self.lc.stage(self.path(name))
        expected = ("APK signer does not match trusted signer set for " +
                    self.report["verified_artifacts"][name]["manifest"]["id"])
        attempts = 0

        def observation():
            nonlocal attempts
            nodes = self.screen()
            texts = [n.get("text", "") for n in nodes]
            art.require(not any(t.startswith("Install Fixture ") and t.endswith("?") for t in texts),
                        "Rejected APK reached confirmation; platform/trust gate bypass")
            for text in texts:
                if (trust and text == expected) or (not trust and (
                    text.startswith("APK signature verification failed") or text == "APK has no verified current signers")):
                    return text
            self.scroll(attempts % 12 < 6)
            attempts += 1
            return False

        actual = self.lc.wait("explicit signature/trust rejection for " + name, observation)
        self.observations.append({"case": self.current, "artifact": name, "rejection": actual})
        # Successful ADB delivery + explicit host diagnostic, not mere unchanged UI.
        self.lc.absent("Install Fixture 2.0?")
        art.require(not self.lc.events("normalV2"), "Rejected candidate executed normalV2 callbacks")
        art.require(not self.lc.events("normalV1"), "Rejection changed/recreated running normalV1 callbacks")
        self.snapshot("rejected-" + name)

    def signed_upgrade(self):
        self.clean()
        self.install("baseline-v1")
        self.lc.page("normalV1", 0)
        self.lc.increment(1)
        self.lc.back()
        self.lc.clear_logs()
        self.install("baseline-v2")
        self.lc.text("Fixture · 2.0")
        self.lc.text("Update installed; restart required")
        art.require(not self.lc.events("normalV2"), "v2 executed before process restart")
        art.require(not self.lc.events("normalV1"), "Update recreated or closed old instance")
        self.counter("normalV1", 1)
        self.lc.restart()
        self.counter("normalV2", 1)
        self.lc.event("normalV2", "enable:return")
        self.lc.restart()
        self.counter("normalV2", 1)

    def invalid(self, name, trust=False):
        self.clean()
        self.install("baseline-v1")
        self.lc.page("normalV1", 0)
        self.lc.increment(1)
        self.lc.back()
        self.reject(name, trust=trust)
        self.lc.text("Fixture · 1.0")
        self.counter("normalV1", 1)
        self.lc.clear_logs()
        self.lc.restart()
        self.counter("normalV1", 1)
        art.require(not self.lc.events("normalV2"), "Rejected candidate survived restart")
        # Positive control after refusal: gate is selective, not a broken importer.
        self.install("baseline-v2")
        self.lc.text("Update installed; restart required")
        self.lc.restart()
        self.counter("normalV2", 1)

    def prepare_retained(self):
        self.clean()
        self.install("baseline-v1")
        self.lc.page("normalV1", 0)
        self.lc.increment(1)
        self.lc.back()
        self.uninstall(clear=False)
        self.lc.text("No plugins installed")
        self.reject("other-v2", trust=True)
        self.lc.restart()
        self.lc.text("No plugins installed")
        self.reject("other-v2", trust=True)

    def uninstall(self, clear):
        # Reuse the real helper's unchecked/checked checkbox assertions and UI.
        self.lc.uninstall(clear=clear)
        # Wait for finite cleanup before force-stop; this case is not crash injection.
        self.lc.event("normalV1", "cleanup:first")
        self.lc.restart()
        self.lc.text("No plugins installed")

    def retained(self):
        self.prepare_retained()
        self.install("baseline-v1")
        self.counter("normalV1", 1)
        self.lc.restart()
        self.counter("normalV1", 1)

    def new_signer_after_clear(self):
        self.install("other-v1")
        self.counter("normalV1", 0)
        self.lc.restart()
        self.counter("normalV1", 0)
        # Explicit deletion resets the binding, then confirms the NEW set is pinned.
        self.reject("baseline-v2", trust=True)
        self.counter("normalV1", 0)
        self.install("other-v2")
        self.lc.text("Update installed; restart required")
        self.lc.restart()
        self.counter("normalV2", 0)

    def clear_installed(self):
        self.clean()
        self.install("baseline-v1")
        self.lc.page("normalV1", 0)
        self.lc.increment(1)
        self.lc.back()
        self.uninstall(clear=True)
        self.new_signer_after_clear()

    def clear_retained(self):
        self.prepare_retained()
        plugin_id = self.report["verified_artifacts"]["baseline-v1"]["manifest"]["id"]
        self.lc.tap("Clear-data uninstall " + plugin_id)
        self.lc.text("Clear-data uninstall " + plugin_id + "?")
        self.lc.tap("Delete package and data")
        # Wait for removal of the recovery action, not the already-present empty label.
        self.lc.wait("retained binding removed via explicit UI", lambda: not any(
            n.get("text", "") in ("Delete package and data", "Clear-data uninstall " + plugin_id)
            for n in self.screen()) and bool(self.screen()))
        self.lc.restart()
        self.lc.text("No plugins installed")
        self.lc.absent("Clear-data uninstall " + plugin_id)
        self.new_signer_after_clear()

    def case(self, name):
        self.current = self.lc.CURRENT = name
        print("CASE " + name, flush=True)
        started = time.monotonic()
        actions = {
            "signed-upgrade-and-fingerprints": self.signed_upgrade,
            "unsigned": lambda: self.invalid("unsigned"),
            "byte-tampered": lambda: self.invalid("tampered"),
            "different-signer-update": lambda: self.invalid("other-v2", trust=True),
            "retained-data-binding": self.retained,
            "clear-data-uninstall": self.clear_installed,
            "retained-clear-data-recovery": self.clear_retained,
        }
        actions[name]()
        art.require(not self.em.crashed(), "Host crash detected")
        self.snapshot("completed")
        self.report["cases"].append({"case": name, "status": "passed", "seconds": time.monotonic() - started})


def expired(_signum, _frame):
    raise AcceptanceDeadline("Overall signature-device deadline exceeded")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("host", type=Path, nargs="?")
    parser.add_argument("artifacts", type=Path, nargs="?")
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--check-device", action="store_true")
    parser.add_argument("--case", action="append", choices=CASES)
    args = parser.parse_args()
    art.instabox()
    output = args.output or Path(os.environ.get("TEST_OUTPUT", "build/signature-acceptance-" + time.strftime("%Y%m%d-%H%M%S")))
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {"status": "running", "cases": [], "evidence_errors": [], "selected_cases": args.case or list(CASES),
              "not_run": [c for c in CASES if args.check_device or (args.case and c not in args.case)],
              "limitations": [
                  "Device/OEM result only, not an all-platform guarantee.",
                  "Host must contain the real PlatformPluginSignatureVerifier; binary source provenance supplied by coordinator.",
                  "No private-storage tamper/TOCTOU injection, signer rotation or multi-signer acceptance claimed.",
                  "v1/v2 case labels denote plugin versions; signed samples use APK Signature Scheme v2 plus v1.",
              ]}
    run = None
    try:
        signal.signal(signal.SIGALRM, expired)
        signal.alarm(int(os.environ.get("S1_SIGNATURE_TOTAL_TIMEOUT", "5400")))
        lifecycle = load("s1_lifecycle", "lifecycle-acceptance.py")
        run = Acceptance(lifecycle, output, args.host.resolve() if args.host else None,
                         args.artifacts.resolve() if args.artifacts else None, report)
        report["helpers_sha256"] = {name: art.sha(Path(__file__).with_name(name)) for name in (
            "signature-acceptance.py", "signature-artifacts.py", "lifecycle-acceptance.py", "emulator-test.py")}
        run.guard()
        if args.check_device:
            report.update(status="guard-passed", selected_cases=[])
            print("Dedicated emulator guard passed; no device mutations or acceptance tests")
            return 0
        art.require(run.host is not None and run.host.is_file() and run.artifacts is not None,
                    "Provide prebuilt HOST.apk and ARTIFACT_DIR")
        report["host"] = {"path": str(run.host), "sha256": art.sha(run.host)}
        tc = art.tools()
        report["tools"] = tc
        run.commands.run([tc["apksigner"], "version"])
        # Host need not use the plugin's debug key, only be a valid installable APK.
        host_verify = run.commands.run([tc["apksigner"], "verify", "--verbose", "--print-certs", str(run.host)])
        art.require(host_verify.returncode == 0, "Invalid host APK signature")
        host_info = art.badging(run.host, tc, run.commands)
        art.require(host_info["package"] == lifecycle.PACKAGE, "Wrong host application ID")
        report["host"]["android_manifest"] = host_info
        declared = json.loads((run.artifacts / "artifacts.json").read_text(encoding="utf-8"))
        art.require(declared.get("status") == "passed", "Artifact generation was not successful")
        # Re-run SDK tools now: neither metadata nor generator's earlier pass is trusted.
        report["verified_artifacts"] = art.validate_bundle(run.artifacts, tc, run.commands)
        art.require(report["verified_artifacts"] == declared["artifacts"], "Artifact hashes/metadata changed after generation")
        (output / "input-artifacts.json").write_bytes((run.artifacts / "artifacts.json").read_bytes())
        for prop in ("ro.build.fingerprint", "ro.build.version.sdk", "ro.build.version.release", "ro.product.cpu.abi"):
            report.setdefault("device", {})[prop] = run.adb("shell", "getprop", prop).strip()
        art.require(int(report["device"]["ro.build.version.sdk"]) >= 33, "Host platform verifier API requires API 33+")
        for name in report["selected_cases"]:
            # Reconfirm target before each fresh host uninstall/install sequence.
            run.guard()
            run.case(name)
        art.require(not report["evidence_errors"], "Acceptance assertions completed but evidence collection failed")
        report["status"] = "passed" if not report["not_run"] else "partial-passed"
        print(f"{report['status']}: {len(report['cases'])} device cases; raw evidence: {output}")
        return 0
    except Exception as error:
        signal.alarm(0)
        report["status"] = "failed"
        current = run.current if run else "preflight"
        report["cases"].append({"case": current, "status": "failed", "error": str(error)})
        print(f"FAILED [{current}]: {error}", file=sys.stderr, flush=True)
        if run and run.guarded:
            run.snapshot("FAILED")
        return 1
    finally:
        signal.alarm(0)
        report["observations"] = run.observations if run else []
        report["not_completed"] = [c for c in CASES if not any(
            r["case"] == c and r["status"] == "passed" for r in report["cases"])]
        art.save(output / "results.json", report)


if __name__ == "__main__":
    sys.exit(main())
