#!/usr/bin/env python3
"""InstaBox ONLY: derive signature fixtures from already-built normalV1/normalV2.

No Gradle/compiler invocation. Place beside signature-acceptance.py under scripts/.
  S1_INSTABOX=1 python3 -I scripts/signature-artifacts.py V1.apk V2.apk --output DIR
DIR must not exist. Existing debug key required (never generated/replaced):
  S1_DEBUG_KEYSTORE, S1_DEBUG_KEY_ALIAS (androiddebugkey),
  S1_DEBUG_STORE_PASSWORD / S1_DEBUG_KEY_PASSWORD (android).
Defaults key location from ANDROID_USER_HOME or ANDROID_SDK_HOME/.android or
HOME/.android. SDK: ANDROID_SDK_ROOT or ANDROID_HOME; optional
ANDROID_BUILD_TOOLS_VERSION, APKSIGNER, ZIPALIGN, AAPT2, KEYTOOL, JAVA_HOME.
Explicit tool overrides are executable paths, not shell command strings.
S1_INSTABOX=1 is the coordinator's environment attestation, not host detection.
All commands are bounded; results.json, raw command outputs and APK hashes stay
in DIR. Generated other-signer keystore is throwaway TEST material, never release.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import struct
import subprocess
import sys
import time
import zipfile
import zlib

MANIFEST = "assets/theta-plugin.json"
MARKER = "assets/s1-signature-integrity.bin"
MARKER_BYTES = b"S1-signature-acceptance-integrity-A\n"
NAMES = ("baseline-v1", "baseline-v2", "other-v1", "other-v2", "unsigned", "tampered")


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def instabox():
    require(sys.platform.startswith("linux") and os.environ.get("S1_INSTABOX") == "1",
            "Run only on InstaBox/Linux with coordinator-set S1_INSTABOX=1")


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def save(path, obj):
    Path(path).write_text(json.dumps(obj, indent=2) + "\n", encoding="utf-8")


class Commands:
    def __init__(self, output):
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        self.index = 0

    def run(self, args, *, timeout=120, check=True, env=None):
        self.index += 1
        prefix = self.output / f"{self.index:04d}"
        record = {"argv": [str(a) for a in args], "timeout_seconds": timeout}
        save(str(prefix) + ".command.json", record)
        started = time.monotonic()
        stdout_path, stderr_path = Path(str(prefix) + ".stdout"), Path(str(prefix) + ".stderr")
        try:
            # Stream to files so global alarm/interruption retains partial tool output.
            with stdout_path.open("wb") as stdout, stderr_path.open("wb") as stderr:
                result = subprocess.run(record["argv"], stdout=stdout, stderr=stderr,
                                        timeout=timeout, env=env)
        except BaseException as error:
            save(str(prefix) + ".result.json", {"status": "interrupted-or-failed", "error": str(error),
                                              "seconds": time.monotonic() - started})
            raise
        result.stdout, result.stderr = stdout_path.read_bytes(), stderr_path.read_bytes()
        save(str(prefix) + ".result.json", {"returncode": result.returncode, "seconds": time.monotonic() - started})
        if check:
            require(result.returncode == 0, f"Command failed ({result.returncode}); inspect {prefix}.*")
        return result


def tools():
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    version = os.environ.get("ANDROID_BUILD_TOOLS_VERSION")
    build = None
    if sdk:
        base = Path(sdk) / "build-tools"
        if version:
            build = base / version
        elif base.is_dir():
            stable = [p for p in base.iterdir() if p.is_dir() and re.fullmatch(r"\d+(\.\d+)+", p.name)]
            if stable:
                build = max(stable, key=lambda p: tuple(map(int, p.name.split("."))))
    result = {}
    for name in ("apksigner", "zipalign", "aapt2", "keytool"):
        value = os.environ.get(name.upper())
        if not value and name == "keytool" and os.environ.get("JAVA_HOME"):
            value = str(Path(os.environ["JAVA_HOME"]) / "bin" / name)
        if not value and name != "keytool" and build:
            value = str(build / name)
        value = value or shutil.which(name)
        require(value is not None, f"Cannot locate {name}: configure SDK/JDK or {name.upper()}")
        resolved = shutil.which(value)
        require(resolved is not None, f"Tool is not executable: {value}")
        result[name] = str(Path(resolved).resolve())
    return result


def payload(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), f"Duplicate ZIP entries in {path}")
        require(archive.testzip() is None, f"Invalid ZIP CRC in {path}")
        return {n: archive.read(n) for n in names if not signature_entry(n)}


def signature_entry(name):
    upper = name.upper()
    return upper.startswith("META-INF/") and (
        upper == "META-INF/MANIFEST.MF" or upper.endswith((".SF", ".RSA", ".DSA", ".EC"))
        or upper.split("/")[-1].startswith("SIG-"))


def manifest(path):
    entries = payload(path)
    require("AndroidManifest.xml" in entries and "classes.dex" in entries, f"Not a compiled APK: {path}")
    value = json.loads(entries[MANIFEST])
    require(value.get("schema") == 1 and value.get("type") == "dex" and value.get("name") == "Fixture",
            f"Not the Fixture dex manifest: {path}")
    require(value.get("apiLevel") == 1 and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+", value.get("id", "")),
            f"Unexpected fixture ID/API: {path}")
    return value


def verify_tool(path, toolchain, commands, valid):
    result = commands.run([toolchain["apksigner"], "verify", "--verbose", "--print-certs", str(path)], check=False)
    text = (result.stdout + result.stderr).decode("utf-8", "replace")
    if valid:
        require(result.returncode == 0, f"apksigner rejected valid artifact: {path}")
        require(re.search(r"Verified using v2 scheme.*:\s*true", text), f"v2 signature required: {path}")
        require(re.search(r"^Number of signers: 1\s*$", text, re.M), f"Expected exactly one current signer: {path}")
        # Build-tools 37 prints "V2 Signer:" (repeated per verified scheme); earlier ones "Signer #1".
        fingerprints = {value.lower() for value in re.findall(
            r"^(?:Signer #\d+|V\d+(?:\.\d+)? Signer:) certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$", text, re.M)}
        require(len(fingerprints) == 1, f"Expected exactly one current signer: {path}")
        return next(iter(fingerprints))
    require(result.returncode != 0 and "DOES NOT VERIFY" in text,
            f"Expected independent signature rejection (not a tool error): {path}")
    return None


def badging(path, toolchain, commands):
    result = commands.run([toolchain["aapt2"], "dump", "badging", str(path)])
    text = result.stdout.decode("utf-8", "replace")
    target = re.search(r"targetSdkVersion:'(\d+)'", text)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", text)
    require(target is not None and int(target[1]) >= 30 and package is not None,
            f"Need parseable Android manifest with targetSdk >= 30: {path}")
    return {"package": package[1], "versionCode": int(package[2]), "version": package[3], "targetSdk": int(target[1])}


def repack(source, target):
    # Rebuilding ZIP deliberately strips the old APK signing block and v1 signatures.
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(target, "w") as dest:
        require(MARKER not in src.namelist(), "Source already contains test marker")
        for entry in src.infolist():
            if not signature_entry(entry.filename):
                dest.writestr(entry, src.read(entry.filename))
        item = zipfile.ZipInfo(MARKER)
        item.compress_type = zipfile.ZIP_STORED
        dest.writestr(item, MARKER_BYTES)


def tamper(source, target):
    # In-place payload byte change AFTER signing; preserve all entry offsets and
    # the entire signing block. Update only the marker CRC in local+central headers
    # so ordinary ZIP readers and plugin/Android manifest parsing still succeed.
    data = bytearray(Path(source).read_bytes())
    with zipfile.ZipFile(source) as archive:
        entry = archive.getinfo(MARKER)
        require(entry.compress_type == zipfile.ZIP_STORED and not entry.flag_bits & 8,
                "Marker must be stored without a data descriptor")
        local = entry.header_offset
        require(data[local:local + 4] == b"PK\x03\x04", "Invalid local header")
        name_len, extra_len = struct.unpack_from("<HH", data, local + 26)
        offset = local + 30 + name_len + extra_len
        original = bytes(data[offset:offset + entry.file_size])
        require(original == MARKER_BYTES, "Unexpected signed marker")
        data[offset + len(original) - 2] ^= 1
        changed = bytes(data[offset:offset + entry.file_size])
        crc = zlib.crc32(changed) & 0xffffffff
        struct.pack_into("<I", data, local + 14, crc)
        cursor = archive.start_dir
        found = False
        for _ in archive.infolist():
            require(data[cursor:cursor + 4] == b"PK\x01\x02", "Invalid central header")
            n, e, c = struct.unpack_from("<HHH", data, cursor + 28)
            if bytes(data[cursor + 46:cursor + 46 + n]) == MARKER.encode():
                struct.pack_into("<I", data, cursor + 16, crc)
                found = True
            cursor += 46 + n + e + c
        require(found, "Missing marker central entry")
    Path(target).write_bytes(data)
    before, after = payload(source), payload(target)
    require({k for k in before if before[k] != after[k]} == {MARKER}, "Tamper changed more than marker")
    return {"source": Path(source).name, "entry": MARKER, "payload_offset": offset + len(original) - 2,
            "before_payload_sha256": hashlib.sha256(original).hexdigest(),
            "after_payload_sha256": hashlib.sha256(changed).hexdigest(),
            "method": "one stored-asset byte XOR 1; local and central CRC repaired; signatures unchanged"}


def validate_bundle(directory, toolchain, commands):
    directory = Path(directory)
    paths = {n: directory / (n + ".apk") for n in NAMES}
    manifests = {n: manifest(p) for n, p in paths.items()}
    base = manifests["baseline-v1"]
    android_identity = None
    for name, value in manifests.items():
        version = 1 if name in ("baseline-v1", "other-v1") else 2
        require(value["id"] == base["id"] and value["entry"] == base["entry"] and
                value["versionCode"] == version and value["version"] == f"{version}.0",
                f"Fixture identity/version mismatch: {name}")
        info = badging(paths[name], toolchain, commands)
        if android_identity is None:
            android_identity = (info["package"], info["targetSdk"])
        require((info["package"], info["targetSdk"]) == android_identity, f"Android identity/SDK mismatch: {name}")
        require(info["versionCode"] == version and info["version"] == f"{version}.0", f"Android version mismatch: {name}")
    content = {n: payload(p) for n, p in paths.items()}
    require(content["baseline-v1"] == content["other-v1"], "Signer-only v1 payload mismatch")
    require(content["baseline-v2"] == content["other-v2"] == content["unsigned"], "Signer-only v2 payload mismatch")
    original, mutated = content["baseline-v2"], content["tampered"]
    require(original.keys() == mutated.keys() and {k for k in original if original[k] != mutated[k]} == {MARKER},
            "Tampered APK must differ only in the marker payload; manifests/dex must be intact")
    require(original[MARKER] == MARKER_BYTES and len(mutated[MARKER]) == len(MARKER_BYTES) and
            sum(a != b for a, b in zip(original[MARKER], mutated[MARKER])) == 1, "Expected one changed asset byte")
    fingerprints = {n: verify_tool(paths[n], toolchain, commands, n not in ("unsigned", "tampered")) for n in NAMES}
    require(fingerprints["baseline-v1"] == fingerprints["baseline-v2"], "Baseline signer mismatch")
    require(fingerprints["other-v1"] == fingerprints["other-v2"] != fingerprints["baseline-v1"], "Other signer not distinct")
    return {n: {"file": paths[n].name, "sha256": sha(paths[n]), "manifest": manifests[n],
                "signer_sha256": fingerprints[n]} for n in NAMES}


def expired(_signum, _frame):
    raise RuntimeError("Overall signature-artifact deadline exceeded")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("v1", type=Path)
    parser.add_argument("v2", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    instabox()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    commands = Commands(out / "commands")
    report = {"status": "running", "scope": "artifacts only; no device tests", "sources": {}}
    try:
        signal.signal(signal.SIGALRM, expired)
        signal.alarm(int(os.environ.get("S1_ARTIFACT_TIMEOUT", "900")))
        tc = tools()
        report["tools"] = tc
        commands.run([tc["apksigner"], "version"])
        commands.run([tc["aapt2"], "version"])
        home = Path(os.environ.get("ANDROID_USER_HOME") or
                    str(Path(os.environ.get("ANDROID_SDK_HOME", str(Path.home()))) / ".android"))
        key = Path(os.environ.get("S1_DEBUG_KEYSTORE", str(home / "debug.keystore"))).expanduser().resolve()
        require(key.is_file(), f"Existing debug keystore required, not generated: {key}")
        env = dict(os.environ)
        env["S1_BASE_STORE_PASS"] = os.environ.get("S1_DEBUG_STORE_PASSWORD", "android")
        env["S1_BASE_KEY_PASS"] = os.environ.get("S1_DEBUG_KEY_PASSWORD", "android")
        env["S1_OTHER_PASS"] = "s1-test-only-password"
        alias = os.environ.get("S1_DEBUG_KEY_ALIAS", "androiddebugkey")
        cert = commands.run([tc["keytool"], "-exportcert", "-keystore", str(key), "-alias", alias,
                             "-storepass:env", "S1_BASE_STORE_PASS"], env=env)
        baseline_fingerprint = hashlib.sha256(cert.stdout).hexdigest()
        report["baseline_certificate_sha256"] = baseline_fingerprint
        other = out / "other-signer-test-only.p12"
        commands.run([tc["keytool"], "-genkeypair", "-keystore", str(other), "-storetype", "PKCS12",
                      "-alias", "s1other", "-storepass:env", "S1_OTHER_PASS", "-keypass:env", "S1_OTHER_PASS",
                      "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA", "-validity", "30",
                      "-dname", "CN=S1 Throwaway Signature Acceptance", "-noprompt"], env=env)
        other.chmod(0o600)
        report["other_test_keystore_sha256"] = sha(other)
        for version, source in ((1, args.v1.resolve()), (2, args.v2.resolve())):
            require(source.is_file(), f"Missing prebuilt source: {source}")
            source_signer = verify_tool(source, tc, commands, True)
            require(source_signer == baseline_fingerprint, "Source fixture not signed with supplied existing debug key")
            report["sources"][str(source)] = {"sha256": sha(source), "signer_sha256": source_signer}
            raw, aligned = out / f"repacked-v{version}.apk", out / f"aligned-v{version}.apk"
            repack(source, raw)
            commands.run([tc["zipalign"], "-f", "-p", "4", str(raw), str(aligned)])
            for signer, ks, ka, sp, kp in (
                ("baseline", key, alias, "S1_BASE_STORE_PASS", "S1_BASE_KEY_PASS"),
                ("other", other, "s1other", "S1_OTHER_PASS", "S1_OTHER_PASS"),
            ):
                target = out / f"{signer}-v{version}.apk"
                commands.run([tc["apksigner"], "sign", "--ks", str(ks), "--ks-key-alias", ka,
                              "--ks-pass", "env:" + sp, "--key-pass", "env:" + kp,
                              "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
                              "--v3-signing-enabled", "false", "--v4-signing-enabled", "false",
                              "--out", str(target), str(aligned)], env=env)
                commands.run([tc["zipalign"], "-c", "-p", "4", str(target)])
        shutil.copyfile(out / "aligned-v2.apk", out / "unsigned.apk")
        report["tamper"] = tamper(out / "baseline-v2.apk", out / "tampered.apk")
        report["artifacts"] = validate_bundle(out, tc, commands)
        require(report["artifacts"]["baseline-v1"]["signer_sha256"] == baseline_fingerprint, "Generated baseline key mismatch")
        report["all_apk_sha256"] = {p.name: sha(p) for p in sorted(out.glob("*.apk"))}
        report["status"] = "passed"
        save(out / "artifacts.json", report)
        print(f"Artifacts verified independently; device acceptance NOT run: {out}")
        return 0
    except Exception as error:
        report.update(status="failed", error=str(error))
        print(f"FAILED: {error}", file=sys.stderr)
        return 1
    finally:
        signal.alarm(0)
        save(out / "results.json", report)


if __name__ == "__main__":
    sys.exit(main())
