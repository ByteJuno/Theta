#!/usr/bin/env python3
"""Offline synthetic tests only: no adb, builds, downloads or production artifacts."""
import argparse
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("s1_performance", Path(__file__).with_name("s1-performance.py"))
perf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(perf)


def frames(rows, deadline=True):
    header = "Flags,IntendedVsync,FrameCompleted" + (",FrameDeadline" if deadline else "")
    return "arbitrary gfxinfo preamble\n---PROFILEDATA---\n" + header + "\n" + "\n".join(rows) + "\n---PROFILEDATA---\n"


class PerformanceTest(unittest.TestCase):
    def test_metrics_and_actual_deadlines(self):
        result = perf.frame_metrics(frames(["0,1000000,11000000,17000000", "0,20000000,40000000,36000000"]))
        self.assertEqual(2, result["frame_count"])
        self.assertEqual(15, result["duration_ms"]["mean"])
        self.assertEqual(20, result["duration_ms"]["p95"])
        self.assertEqual(.5, result["deadline_miss_fraction"])

    def test_no_assumed_refresh_budget(self):
        result = perf.frame_metrics(frames(["0,1,30000001"], deadline=False))
        self.assertIsNone(result["deadline_miss_fraction"])

    def test_flags_invalid_sentinel_and_truncation(self):
        result = perf.frame_metrics(frames(["0,1,10000001,16000001", "1,0,0,0", "0,2,1,0", "0,1,9223372036854775807,0"]))
        self.assertEqual(1, result["frame_count"])
        self.assertEqual(1, result["excluded_flagged_rows"])
        self.assertEqual(2, result["invalid_rows"])
        with self.assertRaises(ValueError):
            perf.frame_metrics("---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted\n0,1,2\n")
        with self.assertRaises(ValueError):
            perf.frame_metrics("No frames")

    def test_emulator_rejected_even_with_attestation(self):
        meta = self.metadata()
        self.assertEqual([], perf.physical_problems(meta))
        for mutate in (lambda m: m.update(serial="emulator-5554"),
                       lambda m: m["properties"].update({"ro.kernel.qemu": "1"}),
                       lambda m: m["properties"].update({"ro.hardware": "ranchu"}),
                       lambda m: m.update(physical_device_attested=False)):
            changed = json.loads(json.dumps(meta))
            mutate(changed)
            self.assertTrue(perf.physical_problems(changed))

    def metadata(self):
        return {"status": "MEASURED", "serial": "physical-synthetic", "physical_device_attested": True,
                "properties": {"ro.kernel.qemu": "", "ro.boot.qemu": "", "ro.hardware": "synthetic-hardware",
                               "ro.product.model": "Synthetic physical claim", "ro.build.fingerprint": "synthetic/fingerprint"},
                "pid_before": "123", "pid_after": "123", "package": "test.app", "scenario": "synthetic", "run_id": "1"}

    def fixture(self, root):
        manifest = {"schema": 1, "only_keep_rules_differ_attested": True, "matched_frame_conditions_attested": True}
        for index, name in enumerate(("baseline", "candidate")):
            apk = root / (name + ".apk")
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", "synthetic-not-installable")
                archive.writestr("classes.dex", b"x" * (index + 1))
            digest = perf.sha256(apk)
            capture = root / name
            capture.mkdir()
            raw = capture / "framestats.txt"
            raw.write_text(frames(["0,1000000,11000000,17000000"]), encoding="utf-8")
            meta = self.metadata()
            meta.update(installed_sha256_before=digest, installed_sha256_after=digest, framestats_sha256=perf.sha256(raw))
            perf.save(capture / "capture.json", meta)
            manifest[name] = {"apk": apk.name, "sha256": digest, "captures": [name + "/capture.json"],
                              "build": {**{field: "synthetic" for field in ("source_revision", "toolchain", "dependencies", "variant", "signing_identity", "ci_run")},
                                        "keep_rules_sha256": str(index) * 64, "effective_r8_config_sha256": str(index) * 64}}
        return manifest

    def analyze(self, root, manifest):
        perf.save(root / "manifest.json", manifest)
        perf.analyze(argparse.Namespace(manifest=str(root / "manifest.json"), output=str(root / "result.json")))
        return json.loads((root / "result.json").read_text(encoding="utf-8"))

    def test_complete_evidence_is_measured_never_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            result = self.analyze(root, self.fixture(root))
            self.assertEqual("MEASURED", result["status"])
            self.assertEqual("UNVERIFIED", result["acceptance_verdict"])
            self.assertEqual(1, result["apk_delta"]["bytes"])
            self.assertEqual(0, result["frame_run_summary_deltas"]["p95"]["delta_ms"])

    def test_missing_frames_or_build_control_is_unverified(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest = self.fixture(root)
            manifest["baseline"]["captures"] = []
            manifest["candidate"]["build"]["toolchain"] = "different"
            self.assertEqual("UNVERIFIED", self.analyze(root, manifest)["status"])

    def test_tampered_apk_or_frames_is_invalid(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest = self.fixture(root)
            manifest["candidate"]["sha256"] = "a" * 64
            self.assertEqual("INVALID", self.analyze(root, manifest)["status"])
            manifest["candidate"]["sha256"] = perf.sha256(root / "candidate.apk")
            (root / "candidate/framestats.txt").write_text("tampered", encoding="utf-8")
            self.assertEqual("INVALID", self.analyze(root, manifest)["status"])


if __name__ == "__main__":
    unittest.main()
