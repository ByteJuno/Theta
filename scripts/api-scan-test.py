#!/usr/bin/env python3
"""Offline parser/database negative controls; additionally accepts real javac + SDK database."""
import importlib.util
from pathlib import Path
import argparse
import subprocess
import tempfile

spec = importlib.util.spec_from_file_location('api_scan', Path(__file__).with_name('api-scan.py'))
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


def require(condition, reason):
    if not condition: raise RuntimeError(reason)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--javac', required=True)
    parser.add_argument('--api-versions', type=Path, required=True)
    parser.add_argument('--public-api33-history', type=Path, required=True)
    parser.add_argument('--sdk-stub', type=Path, required=True)
    args = parser.parse_args()
    database = scanner.ApiDatabase(args.api_versions, 33, args.public_api33_history, args.sdk_stub)
    with tempfile.TemporaryDirectory(prefix='theta-api-scan-') as temporary:
        root = Path(temporary)
        fixtures = {
            'Good': ('return java.time.Instant.now().toEpochMilli();', True, None),
            'NewMember': ('return java.util.List.of(1).getFirst();', False, 'getFirst()Ljava/lang/Object;'),
            'AbsentMember': ('return Thread.ofVirtual();', False, 'ofVirtual()Ljava/lang/Thread$Builder$OfVirtual;'),
            'NewClass': ('return java.util.random.RandomGenerator.getDefault();', False, 'java/util/random/RandomGenerator'),
            'NewArrayClass': ('return new java.util.random.RandomGenerator[1];', False, 'java/util/random/RandomGenerator'),
            'BuilderLength': ('StringBuilder b = new StringBuilder(); b.setLength(0); return b;', True, None),
            'BuilderNewMember': ('return new StringBuilder().compareTo(new StringBuilder());', False, 'compareTo(Ljava/lang/StringBuilder;)I'),
        }
        for name, (body, passed, expected) in fixtures.items():
            source = root / (name + '.java')
            source.write_text('public class ' + name + ' { public Object test() { ' + body + ' } }', encoding='utf-8')
            classes = root / name; classes.mkdir()
            subprocess.run([args.javac, '-d', str(classes), str(source)], check=True, cwd=root)
            result = scanner.scan(classes, database)
            require(result['passed'] == passed, name + ': incorrect scan outcome ' + str(result['violations']))
            if expected:
                require(any(expected in str(item) for item in result['violations']), name + ': expected violation absent')
            print('PASS ' + name)
        # Simulated DEX absence here tests classification only, never production proof.
        absent = {owner for owner, _ in scanner.BOOTSTRAPS}
        bodies = {
            'Concat': ('return "x=" + value;', True),
            'Lambda': ('return (Runnable) () -> System.nanoTime();', True),
            'DirectConcat': ('return java.lang.invoke.StringConcatFactory.makeConcatWithConstants(null, "x", null, "x");', False),
            'DirectLambda': ('return java.lang.invoke.LambdaMetafactory.metafactory(null, "x", null, null, null, null);', False),
            'MixedConcat': ('String s = "x=" + value; return java.lang.invoke.StringConcatFactory.makeConcatWithConstants(null, s, null, "x");', False),
            'MixedLambda': ('Runnable r = () -> System.nanoTime(); r.run(); return java.lang.invoke.LambdaMetafactory.metafactory(null, "x", null, null, null, null);', False),
            'FactoryLiteral': ('String s = "x=" + value; return java.lang.invoke.StringConcatFactory.class;', False),
            'FactoryDescriptor': ('java.lang.invoke.StringConcatFactory f = null; return "x=" + value;', False),
            'FactoryMethodReference': ('return (FactoryCall) java.lang.invoke.StringConcatFactory::makeConcatWithConstants;', False),
            'TableSwitchConcat': ('switch (value) { case 1: return "a=" + value; case 2: return "b=" + value; case 3: return "c=" + value; default: return "d=" + value; }', True),
            'LambdaNewTarget': ('return (java.util.function.Supplier<Object>) Thread::ofVirtual;', False),
            'SwitchConcat': ('switch (value) { case 1: return "a=" + value; case 99: return "b=" + value; default: return "c=" + value; }', True),
        }
        for name, (body, expected) in bodies.items():
            source = root / (name + '.java')
            helper = ('interface FactoryCall { java.lang.invoke.CallSite call(java.lang.invoke.MethodHandles.Lookup l, String n, java.lang.invoke.MethodType t, String r, Object... a) throws Throwable; }' if name == 'FactoryMethodReference' else '')
            source.write_text('public class ' + name + ' { ' + helper + ' public Object test(int value) throws Throwable { ' + body + ' } }', encoding='utf-8')
            classes = root / name; classes.mkdir()
            subprocess.run([args.javac, '-g', '-d', str(classes), str(source)], check=True, cwd=root)
            data = (classes / (name + '.class')).read_bytes()
            names, members, omitted = scanner.references(data, absent)
            passed = all(database.class_ok(c) for c in names) and all(database.member_ok(*m) for m in members)
            require(passed == expected, name + ': direct/new API lost')
            if expected:
                require(bool(omitted), name + ': no bootstrap classified')
                require(not scanner.scan(classes, database)['passed'], name + ': missing APK proof accepted')
            else:
                require(any(not database.class_ok(c) for c in names) or any(not database.member_ok(*m) for m in members), name)
            print('PASS ' + name)
        # Class/handle constants passed to bootstrap code are runtime uses too.
        # javac cannot express arbitrary bootstrap arguments, so isolate this
        # constant-pool classification case using the same parsed-class structure.
        from unittest.mock import patch
        owner, signature = sorted(scanner.BOOTSTRAPS)[0]
        method, descriptor = signature.split('(', 1)
        pool = [None, (1, owner), (7, 1), (1, method), (1, '(' + descriptor),
                (12, 3, 4), (10, 2, 5), (15, 6, 6), (18, 0, 5)]
        for roots, arguments in [(set(), [2]), ({7}, [])]:
            with patch.object(scanner, 'parse_class', return_value=(pool, roots, [(7, arguments)], set(), 1)):
                names, members, omitted = scanner.references(b'', absent)
            require(owner in names, 'Runtime bootstrap class/handle constant lost')
            require(not all(database.class_ok(c) for c in names), 'Runtime factory accepted')
        print('PASS bootstrap class-argument/ldc-handle controls')
        # Validate DEX evidence and hash binding with minimal test containers.
        import json
        import struct
        import zipfile
        dex = bytearray(112); dex[:8] = b'dex\n035\x00'
        struct.pack_into('<I', dex, 32, len(dex))
        apk = root / 'test.apk'
        with zipfile.ZipFile(apk, 'w') as z: z.writestr('classes.dex', dex)
        digest = scanner.sha(apk.read_bytes())
        require(scanner.apk_evidence(apk, digest)[0] == absent, 'Factory absence not detected')
        baseline = root / 'baseline.json'
        baseline.write_text(json.dumps(scanner.scan(root / 'Concat', database)), encoding='utf-8')
        require(scanner.scan(root / 'Concat', database, apk, digest, baseline)['passed'], 'Bound bootstrap not excluded')
        for call in [lambda: scanner.apk_evidence(apk, '0' * 64),
                     lambda: scanner.scan(root / 'Lambda', database, apk, digest, baseline),
                     lambda: scanner.scan(root / 'Concat', database, apk),
                     lambda: scanner.ApiDatabase(args.api_versions, 32, args.public_api33_history, args.sdk_stub)]:
            try:
                call()
            except ValueError: pass
            else: raise RuntimeError('Invalid evidence accepted')
        # Factory in ANY secondary DEX prohibits its exclusion.
        dex.extend(b'Ljava/lang/invoke/StringConcatFactory;')
        struct.pack_into('<I', dex, 32, len(dex))
        with zipfile.ZipFile(apk, 'a') as z: z.writestr('classes2.dex', dex)
        require('java/lang/invoke/StringConcatFactory' not in scanner.apk_evidence(apk, scanner.sha(apk.read_bytes()))[0], 'Secondary DEX ignored')
        require(not scanner.scan(root / 'Concat', database, apk, scanner.sha(apk.read_bytes()), baseline)['passed'], 'Present DEX factory excluded')
        print('PASS APK/multidex/hash/input-binding controls')
        try:
            scanner.scan(root / 'empty', database)
            raise RuntimeError('Empty input was accepted')
        except ValueError:
            print('PASS empty-input rejection')
        # Inherited lookup must succeed; field/method availability and removal must fail closed.
        synthetic = root / 'api.xml'
        synthetic.write_text('<api><class name="java/lang/Object" since="1"><method name="hashCode()I"/></class>'
                             '<class name="java/Child" since="1"><extends name="java/lang/Object"/>'
                             '<field name="NEW" since="34"/><field name="OLD" removed="33"/>'
                             '<method name="newApi()V" since="34"/></class></api>', encoding='utf-8')
        db = scanner.ApiDatabase(synthetic, 33)
        require(db.member_ok('java/Child', 'method', 'hashCode()I'), 'Inherited API not found')
        for kind, symbol in [('field', 'NEW'), ('field', 'OLD'), ('method', 'newApi()V'), ('method', 'missing()V')]:
            require(not db.member_ok('java/Child', kind, symbol), 'Unavailable API accepted: ' + symbol)
        print('PASS inherited/field/removed/unknown controls')


if __name__ == '__main__':
    main()
