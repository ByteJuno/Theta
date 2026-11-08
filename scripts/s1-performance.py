#!/usr/bin/env python3
"""S1 G evidence only. Standard library; no builds, installs or automatic PASS verdicts."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
from datetime import datetime, timezone
import zipfile


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def utc():
    return datetime.now(timezone.utc).isoformat()


def save(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def apk_metrics(path, expected):
    path = Path(path)
    actual = sha256(path)
    if not re.fullmatch(r"[0-9a-f]{64}", expected) or actual != expected:
        raise ValueError("APK SHA-256 mismatch: " + str(path))
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if "AndroidManifest.xml" not in archive.namelist():
            raise ValueError("Not an APK: missing AndroidManifest.xml")
        dex = [entry for entry in entries if re.fullmatch(r"classes(?:\d+)?\.dex", entry.filename)]
        return {"path": str(path.resolve()), "sha256": actual, "bytes": path.stat().st_size,
                "dex_uncompressed_bytes": sum(entry.file_size for entry in dex),
                "dex_compressed_bytes": sum(entry.compress_size for entry in dex),
                "zip_entry_count": len(entries)}


def percentile(values, quantile):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(quantile * len(ordered)) - 1)]


def frame_metrics(text):
    """Parse only PROFILEDATA CSV sections; durations are ns, not assumed 60-Hz budgets."""
    inside = False
    header = None
    durations = []
    missed = 0
    deadlines = 0
    excluded = 0
    invalid = 0
    sections = 0
    for line in text.splitlines():
        line = line.strip()
        if line == "---PROFILEDATA---":
            inside = not inside
            header = None
            if inside:
                sections += 1
            continue
        if not inside or not line:
            continue
        row = next(csv.reader([line]))
        if header is None:
            header = [cell.strip() for cell in row]
            if not {"Flags", "IntendedVsync", "FrameCompleted"}.issubset(header):
                raise ValueError("Missing required framestats columns")
            if len(set(header)) != len(header):
                raise ValueError("Duplicate framestats columns")
            continue
        if len(row) != len(header):
            invalid += 1
            continue
        fields = dict(zip(header, row))
        try:
            flags = int(fields["Flags"])
            start = int(fields["IntendedVsync"])
            end = int(fields["FrameCompleted"])
            if flags != 0:
                excluded += 1
                continue
            if start <= 0 or end <= start or end >= 2**63 - 1:
                raise ValueError("Unfinished/invalid frame")
            deadline = int(fields["FrameDeadline"]) if fields.get("FrameDeadline") else None
            durations.append((end - start) / 1_000_000)
            if deadline is not None and start < deadline < 2**63 - 1:
                deadlines += 1
                missed += end > deadline
        except ValueError:
            invalid += 1
    if inside:
        raise ValueError("Truncated PROFILEDATA section")
    if not durations:
        raise ValueError("No valid completed frames")
    return {"profile_sections": sections, "frame_count": len(durations),
            "excluded_flagged_rows": excluded, "invalid_rows": invalid,
            "duration_ms": {"mean": sum(durations) / len(durations),
                            "p50": percentile(durations, .5), "p90": percentile(durations, .9),
                            "p95": percentile(durations, .95), "max": max(durations)},
            "deadline_frame_count": deadlines, "deadline_miss_count": missed,
            "deadline_miss_fraction": missed / deadlines if deadlines else None,
            "definition": "FrameCompleted - IntendedVsync; nearest-rank percentiles; not CPU-only or input latency"}


def physical_problems(meta):
    problems = []
    props = meta.get("properties", {})
    serial = meta.get("serial", "")
    if not serial or serial.lower().startswith("emulator-"):
        problems.append("missing serial or emulator serial")
    if props.get("ro.kernel.qemu") not in ("", "0") or props.get("ro.boot.qemu") not in ("", "0"):
        problems.append("emulator/unknown qemu properties")
    identity = " ".join(props.get(key, "") for key in
                        ("ro.product.model", "ro.product.device", "ro.hardware", "ro.build.fingerprint")).lower()
    if any(word in identity for word in ("emulator", "goldfish", "ranchu", "sdk_gphone", "cuttlefish")):
        problems.append("emulator device identity")
    if not props.get("ro.build.fingerprint") or not props.get("ro.product.model"):
        problems.append("missing device identity")
    if meta.get("physical_device_attested") is not True:
        problems.append("operator has not attested physical hardware")
    return problems


def collect(args):
    # Explicit serial is mandatory for every adb command. No installs/launches/builds.
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+", args.package):
        raise ValueError("Invalid package name")
    if not args.serial or args.serial.startswith("-") or args.seconds <= 0:
        raise ValueError("Explicit serial and positive duration required")
    artifact = apk_metrics(args.apk, args.sha256)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    meta = {"schema": 1, "status": "INVALID", "serial": args.serial, "package": args.package,
            "scenario": args.scenario, "run_id": args.run_id, "started_utc": utc(),
            "physical_device_attested": args.physical_device, "apk": artifact,
            "requested_seconds": args.seconds, "commands": []}

    def adb(*parts, binary=False):
        command = [args.adb, "-s", args.serial, *parts]
        meta["commands"].append(command)
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        if result.returncode:
            raise ValueError("adb failed: " + result.stderr.decode("utf-8", errors="replace"))
        return result.stdout if binary else result.stdout.decode("utf-8", errors="strict")

    def installed_hash():
        paths = [line.removeprefix("package:").strip() for line in
                 adb("shell", "pm", "path", args.package).splitlines() if line.startswith("package:")]
        if len(paths) != 1 or not paths[0].startswith("/data/app/"):
            raise ValueError("Expected exactly one installed base APK; split APKs unsupported")
        content = adb("exec-out", "cat " + shlex.quote(paths[0]), binary=True)
        actual = hashlib.sha256(content).hexdigest()
        if actual != artifact["sha256"]:
            raise ValueError("Installed APK differs from supplied exact APK")
        return actual

    try:
        if adb("get-state").strip() != "device":
            raise ValueError("Device unavailable")
        keys = ("ro.kernel.qemu", "ro.boot.qemu", "ro.product.model", "ro.product.device",
                "ro.hardware", "ro.build.fingerprint")
        meta["properties"] = {key: adb("shell", "getprop", key).strip() for key in keys}
        meta["physical_problems"] = physical_problems(meta)
        meta["installed_sha256_before"] = installed_hash()
        meta["pid_before"] = adb("shell", "pidof", args.package).strip()
        if not meta["pid_before"]:
            raise ValueError("App must already be running on the intended screen")
        for name, command in (("display.txt", ("shell", "dumpsys", "display")),
                              ("thermal.txt", ("shell", "dumpsys", "thermalservice")),
                              ("battery.txt", ("shell", "dumpsys", "battery"))):
            (output / name).write_text(adb(*command), encoding="utf-8")
        (output / "reset.txt").write_text(adb("shell", "dumpsys", "gfxinfo", args.package, "reset"), encoding="utf-8")
        print("Frame window open: perform only the documented scenario now.", flush=True)
        begin = time.monotonic()
        time.sleep(args.seconds)
        raw = adb("shell", "dumpsys", "gfxinfo", args.package, "framestats")
        meta["elapsed_seconds"] = time.monotonic() - begin
        (output / "framestats.txt").write_text(raw, encoding="utf-8")
        meta["framestats_sha256"] = sha256(output / "framestats.txt")
        meta["metrics"] = frame_metrics(raw)
        meta["pid_after"] = adb("shell", "pidof", args.package).strip()
        meta["installed_sha256_after"] = installed_hash()
        if meta["pid_after"] != meta["pid_before"]:
            raise ValueError("Process changed during measurement")
        if meta["metrics"]["invalid_rows"]:
            raise ValueError("Malformed/incomplete frame rows")
        meta["status"] = "UNVERIFIED" if meta["physical_problems"] else "MEASURED"
    except Exception as error:
        meta["error"] = str(error)
        raise
    finally:
        meta["finished_utc"] = utc()
        save(output / "capture.json", meta)


def analyze(args):
    manifest_path = Path(args.manifest).resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    root = manifest_path.parent
    evidence = {"schema": 1, "created_utc": utc(), "status": "INVALID", "acceptance_verdict": "UNVERIFIED",
                "acceptance": "DESIGN G: quantify keep-rule APK size and frame time; CI + physical device",
                "manifest_sha256": sha256(manifest_path), "problems": [], "variants": {}}
    problems = evidence["problems"]
    try:
        if manifest.get("schema") != 1:
            raise ValueError("Unsupported manifest schema")
        builds = []
        run_contexts = []
        for name in ("baseline", "candidate"):
            variant = manifest[name]
            artifact = apk_metrics(root / variant["apk"], variant["sha256"])
            build = variant.get("build", {})
            builds.append(build)
            captures = []
            for capture_path in variant.get("captures", []):
                path = root / capture_path
                meta = json.loads(path.read_text(encoding="utf-8"))
                raw = path.parent / "framestats.txt"
                if sha256(raw) != meta.get("framestats_sha256"):
                    raise ValueError("framestats hash mismatch")
                if meta.get("status") == "INVALID" or meta.get("error"):
                    raise ValueError("Invalid capture: " + str(path))
                if any(meta.get(field) != artifact["sha256"] for field in
                       ("installed_sha256_before", "installed_sha256_after")):
                    raise ValueError("Capture does not match supplied APK")
                if not meta.get("pid_before") or meta.get("pid_before") != meta.get("pid_after"):
                    raise ValueError("Missing/changed capture process")
                problems.extend(name + ": " + problem for problem in physical_problems(meta))
                if not meta.get("scenario") or not meta.get("run_id"):
                    problems.append(name + ": missing scenario/run identity")
                metrics = frame_metrics(raw.read_text(encoding="utf-8"))
                if metrics["invalid_rows"]:
                    raise ValueError("Invalid frame rows in " + str(path))
                run_contexts.append((meta.get("serial"), meta.get("properties", {}).get("ro.build.fingerprint"),
                                     meta.get("scenario"), meta.get("package")))
                captures.append({"capture": str(path.resolve()), "capture_sha256": sha256(path),
                                 "run_id": meta.get("run_id"), "metrics": metrics})
            if not captures:
                problems.append(name + ": physical frame measurements absent")
            evidence["variants"][name] = {"apk": artifact, "build": build, "captures": captures}
        baseline = evidence["variants"]["baseline"]["apk"]
        candidate = evidence["variants"]["candidate"]["apk"]
        evidence["apk_delta"] = {"bytes": candidate["bytes"] - baseline["bytes"],
                                 "percent": (candidate["bytes"] / baseline["bytes"] - 1) * 100,
                                 "dex_uncompressed_bytes": candidate["dex_uncompressed_bytes"] - baseline["dex_uncompressed_bytes"]}
        fields = ("source_revision", "toolchain", "dependencies", "variant", "signing_identity", "ci_run")
        for field in fields:
            if not all(build.get(field) for build in builds) or builds[0].get(field) != builds[1].get(field):
                problems.append("Uncontrolled/missing build provenance: " + field)
        for name, build in zip(("baseline", "candidate"), builds):
            for field in ("keep_rules_sha256", "effective_r8_config_sha256"):
                if not re.fullmatch(r"[0-9a-f]{64}", build.get(field, "")):
                    problems.append(name + ": missing " + field)
        if builds[0].get("keep_rules_sha256") == builds[1].get("keep_rules_sha256"):
            problems.append("Keep rule inputs are identical or unspecified")
        if builds[0].get("effective_r8_config_sha256") == builds[1].get("effective_r8_config_sha256"):
            problems.append("Effective R8 configurations are identical or unspecified")
        if manifest.get("only_keep_rules_differ_attested") is not True:
            problems.append("Isolated keep-only build difference not attested")
        if manifest.get("matched_frame_conditions_attested") is not True:
            problems.append("Matched scenario/display/thermal/warmup conditions not attested")
        if len(set(run_contexts)) > 1:
            problems.append("Frame runs differ in device, firmware, scenario or package")
        if baseline["sha256"] == candidate["sha256"]:
            problems.append("Identical APKs cannot quantify keep-rule difference")
        frame_delta = {}
        for metric in ("mean", "p50", "p90", "p95", "max"):
            samples = [[capture["metrics"]["duration_ms"][metric] for capture in
                        evidence["variants"][name]["captures"]] for name in ("baseline", "candidate")]
            if all(samples):
                # Compare run summaries, never pretend pooled percentiles equal per-run means.
                means = [sum(values) / len(values) for values in samples]
                frame_delta[metric] = {"baseline_run_mean_ms": means[0], "candidate_run_mean_ms": means[1],
                                       "delta_ms": means[1] - means[0]}
        evidence["frame_run_summary_deltas"] = frame_delta
        evidence["status"] = "UNVERIFIED" if problems else "MEASURED"
        evidence["notes"] = ["MEASURED is evidence completeness, not PASS or an approved performance budget.",
                             "gfxinfo is a bounded recent-frame buffer; frame_count is the retained sample, not all rendered frames.",
                             "Provenance attestations are operator claims, not cryptographic device authentication."]
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, TypeError) as error:
        problems.append(str(error))
        evidence["status"] = "INVALID"
    save(args.output, evidence)
    return 2 if evidence["status"] == "INVALID" else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    capture = sub.add_parser("collect", help="Explicit opt-in adb collection; never installs or builds")
    for name in ("serial", "package", "apk", "sha256", "scenario", "run-id", "output"):
        capture.add_argument("--" + name, required=True)
    capture.add_argument("--seconds", type=float, default=20)
    capture.add_argument("--adb", default="adb")
    capture.add_argument("--physical-device", action="store_true", help="Operator attests actual physical hardware")
    analysis = sub.add_parser("analyze", help="Offline exact APK and supplied capture comparison")
    analysis.add_argument("--manifest", required=True)
    analysis.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        return analyze(args) if args.command == "analyze" else collect(args) or 0
    except Exception as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
