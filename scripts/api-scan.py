#!/usr/bin/env python3
"""Narrow agent-core direct JDK compatibility scan, not whole-app certification.
Checks java/javax constant-pool classes/members against Android API metadata.
Only exclusive invokedynamic compiler linkage may be excluded, with matching
compiled-input hashes and factory absence in EVERY DEX of an explicitly bound APK.
Android lint is separate. Dependencies, reflection, JNI, guarded calls and general
desugaring correctness are not certified. No upstream definition of api-scan exists.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
import zipfile


# Exact compiler protocols, NOT runtime API allowlists.
BOOTSTRAPS = {
    ('java/lang/invoke/StringConcatFactory', 'makeConcatWithConstants(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;'),
    ('java/lang/invoke/LambdaMetafactory', 'metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;'),
}
HISTORY_SHA256 = '51cccd84d6838f9178b6165944f2f605d8a9ab0bcaa42eeeec886ccd72536f6f'
HISTORY_URL = 'https://android.googlesource.com/platform/prebuilts/sdk/+/refs/heads/main/33/public/api/android.txt?format=TEXT'


def sha(data):
    return hashlib.sha256(data).hexdigest()


class Reader:
    def __init__(self, data):
        self.data, self.offset = data, 0

    def take(self, size):
        end = self.offset + size
        if end > len(self.data):
            raise ValueError('Truncated class file')
        result = self.data[self.offset:end]
        self.offset = end
        return result

    def u1(self): return self.take(1)[0]
    def u2(self): return struct.unpack('>H', self.take(2))[0]
    def u4(self): return struct.unpack('>I', self.take(4))[0]


def bytecode_refs(code):
    """Walk instruction boundaries; never mistake operand bytes for opcodes."""
    refs, pos = set(), 0
    one = {0x10, 0x12, 0xa9, 0xbc, *range(0x15, 0x1a), *range(0x36, 0x3b)}
    two = {0x11, 0x13, 0x14, 0x84, *range(0x99, 0xa9), *range(0xb2, 0xb9),
           0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7}
    cp2 = {0x13, 0x14, *range(0xb2, 0xbb), 0xbb, 0xbd, 0xc0, 0xc1, 0xc5}
    while pos < len(code):
        op, start = code[pos], pos
        pos += 1
        if op > 0xc9: raise ValueError('Unsupported JVM opcode')
        if op == 0x12:
            refs.add(code[pos])
        elif op in cp2:
            refs.add(struct.unpack_from('>H', code, pos)[0])
        if op in (0xaa, 0xab):
            pos = (pos + 3) & ~3
            if op == 0xaa:
                low, high = struct.unpack_from('>ii', code, pos + 4)
                if high < low: raise ValueError('Invalid tableswitch')
                pos += 12 + 4 * (high - low + 1)
            else:
                count = struct.unpack_from('>i', code, pos + 4)[0]
                if count < 0: raise ValueError('Invalid lookupswitch')
                pos += 8 + 8 * count
        elif op == 0xc4:
            modified = code[pos]
            if modified not in {0x84, 0xa9, *range(0x15, 0x1a), *range(0x36, 0x3b)}:
                raise ValueError('Invalid wide opcode')
            pos += 5 if modified == 0x84 else 3
        elif op in (0xb9, 0xba, 0xc8, 0xc9): pos += 4
        elif op == 0xc5: pos += 3
        elif op in two: pos += 2
        elif op in one: pos += 1
        if pos > len(code) or pos <= start: raise ValueError('Truncated bytecode')
    return refs


def parse_class(data):
    reader = Reader(data)
    if reader.take(4) != b'\xca\xfe\xba\xbe': raise ValueError('Not a JVM class')
    reader.take(4)
    pool = [None] * reader.u2()
    index = 1
    while index < len(pool):
        tag = reader.u1()
        if tag == 1:
            entry = (tag, reader.take(reader.u2()).decode('utf-8', errors='replace'))
        elif tag in (7, 8, 16, 19, 20): entry = (tag, reader.u2())
        elif tag in (9, 10, 11, 12, 17, 18): entry = (tag, reader.u2(), reader.u2())
        elif tag in (3, 4): entry = (tag, reader.take(4))
        elif tag in (5, 6): entry = (tag, reader.take(8))
        elif tag == 15: entry = (tag, reader.u1(), reader.u2())
        else: raise ValueError('Unknown constant-pool tag: ' + str(tag))
        pool[index] = entry
        index += 2 if tag in (5, 6) else 1
    roots, bootstraps, declared = set(), [], set()
    def utf(i): return pool[i][1]
    def attributes(r):
        for _ in range(r.u2()):
            name, body = utf(r.u2()), Reader(r.take(r.u4()))
            if name == 'Code':
                body.take(4)
                roots.update(bytecode_refs(body.take(body.u4())))
                for _ in range(body.u2()):
                    body.take(6)
                    roots.add(body.u2())
                attributes(body)
            elif name == 'BootstrapMethods':
                for _ in range(body.u2()):
                    bootstraps.append((body.u2(), [body.u2() for _ in range(body.u2())]))
            elif name == 'Exceptions':
                roots.update(body.u2() for _ in range(body.u2()))
            elif name == 'StackMapTable':
                def verification():
                    tag = body.u1()
                    if tag == 7: roots.add(body.u2())
                    elif tag == 8: body.u2()
                    elif tag > 8: raise ValueError('Invalid verification type')
                for _ in range(body.u2()):
                    frame = body.u1()
                    if frame <= 63: pass
                    elif frame <= 127: verification()
                    elif frame == 247: body.u2(); verification()
                    elif 248 <= frame <= 251: body.u2()
                    elif 252 <= frame <= 254:
                        body.u2()
                        for _ in range(frame - 251): verification()
                    elif frame == 255:
                        body.u2()
                        for _ in range(body.u2()): verification()
                        for _ in range(body.u2()): verification()
                    else: raise ValueError('Invalid stack map frame')
    access = reader.u2()
    this_class = reader.u2()
    roots.update((this_class, reader.u2()))
    roots.update(reader.u2() for _ in range(reader.u2()))
    for kind in ('field', 'method'):
        for _ in range(reader.u2()):
            flags, name, descriptor = reader.u2(), utf(reader.u2()), utf(reader.u2())
            declared.add((kind, name if kind == 'field' else name + descriptor, flags))
            attributes(reader)
    attributes(reader)
    if reader.offset != len(data): raise ValueError('Trailing class bytes')
    return pool, roots, bootstraps, declared, access


def references(data, absent_factories=frozenset()):
    pool, roots, bootstraps, _, _ = parse_class(data)
    def member(entry):
        owner = pool[pool[entry[1]][1]][1]
        pair = pool[entry[2]]
        name, descriptor = pool[pair[1]][1], pool[pair[2]][1]
        return owner, 'field' if entry[0] == 9 else 'method', name if entry[0] == 9 else name + descriptor
    indy = {e[1] for e in pool if e and e[0] == 18}
    condy = {e[1] for e in pool if e and e[0] == 17}
    args = {a for _, values in bootstraps for a in values}
    excluded = set()
    for b, (handle, _) in enumerate(bootstraps):
        h = pool[handle]
        if b not in indy or b in condy or handle in roots or handle in args: continue
        if any(i not in indy or i in condy for i, (other, _) in enumerate(bootstraps) if other == handle): continue
        if not h or h[:2] != (15, 6): continue
        ref = h[2]
        if pool[ref][0] != 10 or ref in roots: continue
        owner, _, signature = member(pool[ref])
        if (owner, signature) not in BOOTSTRAPS or owner not in absent_factories: continue
        # Another handle to this member might be an ordinary ldc/method reference.
        if any(i != handle and e and e[0] == 15 and e[2] == ref for i, e in enumerate(pool)): continue
        excluded.add(ref)
    classes, members, omitted = set(), set(), []
    descriptor_classes = set()
    for e in pool:
        if e and e[0] == 1:
            descriptor_classes.update(re.findall(r'L((?:java|javax)/[\w/$]+);', e[1]))
    for i, e in enumerate(pool):
        if not e: continue
        if e[0] == 7:
            name = pool[e[1]][1]
            owners = {j for j, x in enumerate(pool) if x and x[0] in (9, 10, 11) and x[1] == i}
            other_users = any(x and x[0] in (7, 8, 16, 19, 20) and x[1] == i for x in pool)
            if owners and owners <= excluded and i not in roots and i not in args and not other_users and name not in descriptor_classes:
                continue
            if not name.startswith('['): classes.add(name)
        elif e[0] in (9, 10, 11):
            m = member(e)
            if i in excluded: omitted.append({'owner': m[0], 'member': m[2], 'reason': 'exclusive invokedynamic bootstrap; factory absent from bound APK DEX'})
            else: members.add(m)
    classes.update(descriptor_classes)
    return ({c for c in classes if c.startswith(('java/', 'javax/'))},
            {m for m in members if m[0].startswith(('java/', 'javax/'))}, omitted)


class ApiDatabase:
    def __init__(self, path, minimum, public_history=None, sdk_stub=None):
        self.minimum = minimum
        self.classes = {node.attrib['name']: node for node in ET.parse(path).getroot().findall('class')}
        self.supplement = None
        if public_history is not None or sdk_stub is not None:
            if public_history is None or sdk_stub is None or minimum != 33:
                raise ValueError('History supplement requires API33 and both history + SDK stub')
            history = Path(public_history).read_bytes()
            if sha(history) != HISTORY_SHA256: raise ValueError('Unexpected API33 public signature history')
            text = history.decode('utf-8').split('package java.lang {', 1)[1]
            block = text.split('public final class StringBuilder implements ', 1)[1].split('\n  }', 1)[0]
            if 'method public void setLength(int);' not in block: raise ValueError('History lacks setLength')
            with zipfile.ZipFile(sdk_stub) as jar:
                stub = jar.read('java/lang/StringBuilder.class')
            _, _, _, declared, access = parse_class(stub)
            if not access & 1 or not any(k == 'method' and n == 'setLength(I)V' and f & 1 and not f & 8 for k, n, f in declared):
                raise ValueError('Public SDK stub lacks public instance setLength(I)V')
            self.supplement = dict(owner='java/lang/StringBuilder', member='setLength(I)V',
                                   api=33, history_url=HISTORY_URL, history_sha256=sha(history),
                                   sdk_stub_sha256=sha(Path(sdk_stub).read_bytes()), stub_class_sha256=sha(stub),
                                   reason='API metadata omits inherited public member; API33 public signature and Android SDK stub both confirm it')

    def available(self, node, inherited_since=1):
        return int(node.get('since', inherited_since)) <= self.minimum and int(node.get('removed', 1000000)) > self.minimum

    def class_ok(self, name):
        node = self.classes.get(name)
        return node is not None and self.available(node)

    def member_ok(self, owner, kind, signature, seen=None):
        seen = set() if seen is None else seen
        if owner in seen or not self.class_ok(owner): return False
        seen.add(owner)
        node = self.classes[owner]
        for child in node.findall(kind):
            if child.get('name') == signature:
                return self.available(child, int(node.get('since', 1)))
        if (self.supplement and (owner, kind, signature) == ('java/lang/StringBuilder', 'method', 'setLength(I)V')):
            return True
        if signature.startswith('<init>'): return False
        return any(self.available(parent) and self.member_ok(parent.attrib['name'], kind, signature, seen)
                   for parent in list(node.findall('extends')) + list(node.findall('implements')))


def apk_evidence(path, expected_hash):
    data = Path(path).read_bytes()
    if sha(data) != expected_hash: raise ValueError('APK SHA256 mismatch')
    evidence, absent = [], {owner for owner, _ in BOOTSTRAPS}
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if not name.endswith('.dex'): continue
            dex = apk.read(name)
            if dex[:4] != b'dex\n' or len(dex) < 112 or struct.unpack_from('<I', dex, 32)[0] != len(dex):
                raise ValueError('Unsupported/truncated DEX: ' + name)
            # A symbolic class reference MUST have its descriptor in DEX string data.
            # Search all bytes, not just invoke opcodes: covers types, handles and call sites.
            present = [owner for owner, _ in BOOTSTRAPS if ('L' + owner + ';').encode() in dex]
            absent.difference_update(present)
            evidence.append(dict(file=name, sha256=sha(dex), bytes=len(dex), factory_descriptors=present))
    if not evidence: raise ValueError('APK contains no DEX')
    return absent, dict(apk_sha256=sha(data), dex=evidence)


def scan(classes, database, apk=None, apk_sha256=None, baseline=None):
    files = sorted(Path(classes).rglob('*.class'))
    if not files: raise ValueError('No compiled main classes to scan')
    hashes = {str(f.relative_to(classes)).replace('\\', '/'): sha(f.read_bytes()) for f in files}
    absent, dex_proof = set(), None
    if any(x is not None for x in (apk, apk_sha256, baseline)):
        if any(x is None for x in (apk, apk_sha256, baseline)):
            raise ValueError('Bootstrap exclusions require APK, SHA256 and compiled-input baseline')
        if hashes != json.loads(Path(baseline).read_text(encoding='utf-8'))['compiled_class_hashes']:
            raise ValueError('Compiled inputs differ from baseline')
        absent, dex_proof = apk_evidence(apk, apk_sha256)
        dex_proof['compiled_baseline_sha256'] = sha(Path(baseline).read_bytes())
        dex_proof['binding_limit'] = 'Caller supplies matching build provenance; byte equality to baseline and APK identity are verified, source-to-DEX semantic equivalence is not certified.'
    violations, class_refs, member_refs, exclusions, resolutions = [], set(), set(), [], []
    for file in files:
        names, members, omitted = references(file.read_bytes(), absent)
        exclusions.extend(dict(file=str(file), **item) for item in omitted)
        class_refs.update(names); member_refs.update(members)
        for name in sorted(names):
            if not database.class_ok(name): violations.append({'file': str(file), 'class': name})
        for owner, kind, signature in sorted(members):
            if not database.member_ok(owner, kind, signature):
                violations.append({'file': str(file), 'owner': owner, 'kind': kind, 'member': signature})
            elif database.supplement and (owner, kind, signature) == ('java/lang/StringBuilder', 'method', 'setLength(I)V'):
                resolutions.append(dict(file=str(file), **database.supplement))
    return dict(coverage=__doc__, min_sdk=database.minimum, classes=len(files),
                jdk_classes=len(class_refs), jdk_members=len(member_refs),
                compiled_class_hashes=hashes, violations=violations, passed=not violations,
                bootstrap_exclusions=exclusions, public_api_resolutions=resolutions, dex_proof=dex_proof)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classes', type=Path, required=True)
    parser.add_argument('--api-versions', type=Path, required=True)
    parser.add_argument('--min-sdk', type=int, default=33)
    parser.add_argument('--public-api33-history', type=Path)
    parser.add_argument('--sdk-stub', type=Path)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--apk-sha256')
    parser.add_argument('--compiled-baseline', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    database = ApiDatabase(args.api_versions, args.min_sdk, args.public_api33_history, args.sdk_stub)
    report = scan(args.classes, database, args.apk, args.apk_sha256, args.compiled_baseline)
    report['api_database_sha256'] = sha(args.api_versions.read_bytes())
    report['scanner_sha256'] = sha(Path(__file__).read_bytes())
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(report, stream, indent=2)
    print(json.dumps({k: report[k] for k in ('min_sdk', 'classes', 'jdk_classes', 'jdk_members', 'passed')}))
    print(json.dumps(dict(violations=len(report['violations']), bootstrap_exclusions=len(report['bootstrap_exclusions']), public_api_resolutions=len(report['public_api_resolutions']))))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
