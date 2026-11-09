#!/usr/bin/env python3
"""Compile API-1 clients against git baseline; execute unchanged bytes with current SDK.
No Gradle mutation, APK, device or network. Run with Python -I.
"""
from pathlib import Path
import argparse, hashlib, json, os, re, subprocess

p = argparse.ArgumentParser()
p.add_argument('--base', default='HEAD')
p.add_argument('--output', type=Path, required=True)
p.add_argument('--java-home', type=Path, required=True)
p.add_argument('--gradle-cache', type=Path, required=True)
a = p.parse_args()
repo = Path(__file__).resolve().parents[1]
out = a.output.resolve(); out.mkdir(parents=True, exist_ok=False)
cache = a.gradle_cache / 'modules-2/files-2.1'
def jar(group, name, version):
    matches = list((cache/group/name/version).glob('*/*.jar'))
    assert len(matches) == 1, (name, matches)
    return matches[0]
stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.20')
deps = [stdlib, jar('org.jetbrains.kotlinx', 'kotlinx-serialization-json-jvm', '1.12.0'),
        jar('org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm', '1.12.0')]
compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.20'), stdlib]
for name, version in [('kotlin-script-runtime', '2.4.20'), ('kotlin-reflect', '1.6.10'), ('kotlin-daemon-embeddable', '2.4.20')]:
    compiler.append(jar('org.jetbrains.kotlin', name, version))
compiler += [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
             jar('org.jetbrains', 'annotations', '13.0')]
def cp(paths): return os.pathsep.join(map(str, paths))
def run(cmd):
    result = subprocess.run(list(map(str, cmd)), cwd=repo, capture_output=True, text=True, encoding='utf-8', errors='replace')
    with (out/'execution.log').open('a', encoding='utf-8') as f:
        f.write(json.dumps(list(map(str, cmd)), ensure_ascii=False)+'\n'+result.stdout+result.stderr+'\n')
    if result.returncode: raise RuntimeError(result.stdout+result.stderr)
    return result.stdout
java = a.java_home/'bin/java.exe'
def compile(sources, dest, dependencies):
    run([java, '-cp', cp(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
         '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp(dependencies), '-d', dest, *sources])
sdk = 'sdk/src/main/kotlin/io/github/bytejuno/thetacore/sdk/'
base = run(['git', 'rev-parse', a.base]).strip()
old = out/'OldTool.kt'; old.write_text(run(['git', 'show', f'{base}:{sdk}Tool.kt']), encoding='utf-8')
context = run(['git', 'show', f'{base}:{sdk}PluginContext.kt'])
extension = out/'Extension.kt'
extension.write_text('package io.github.bytejuno.thetacore.sdk\n'+re.search(r'interface Extension \{[^}]+\}', context).group(), encoding='utf-8')
fixture = out/'OldClient.kt'
fixture.write_text('''import io.github.bytejuno.thetacore.sdk.*
import kotlinx.serialization.json.*
fun main() {
    val value = JsonPrimitive(7)
    val full = ToolResult("original", value, true, ToolEffect.APPLIED)
    check(full.text == "original" && full.structured == value && full.isError && full.effect == ToolEffect.APPLIED)
    check(ToolResult().text == "")
    check(ToolResult(effect = ToolEffect.UNKNOWN).effect == ToolEffect.UNKNOWN)
    check(ToolResult("positional").text == "positional")
    val copied = full.copy("changed", null, false, ToolEffect.NONE)
    check(copied == ToolResult("changed", null, false, ToolEffect.NONE))
    check(full.copy() == full && full.copy() !== full)
    check(full.copy(text = "named").effect == ToolEffect.APPLIED)
    check(full.copy(effect = null).effect == null)
    val (text, structured, error, effect) = full
    check(text == "original" && structured == value && error && effect == ToolEffect.APPLIED)
    check(full.hashCode() == ToolResult("original", value, true, ToolEffect.APPLIED).hashCode())
    check(full.toString() == "ToolResult(text=original, structured=7, isError=true, effect=APPLIED)")
    println("OLD_CLIENT_OK constructors=4 copy=4 components=4 equals_hash_string=3")
}
''', encoding='utf-8')
compile([old, extension], out/'old-sdk', deps)
compile([fixture], out/'client', [out/'old-sdk', *deps])
java_fixture = out/'OldJavaClient.java'
java_fixture.write_text('''import io.github.bytejuno.thetacore.sdk.*;
public class OldJavaClient { public static void main(String[] args) {
  if (!new ToolResult().getText().equals("")) throw new AssertionError();
  ToolResult r = new ToolResult("java", null, false, ToolEffect.NONE);
  if (!r.copy("copy", null, true, ToolEffect.APPLIED).isError()) throw new AssertionError();
  System.out.println("OLD_JAVA_CLIENT_OK noarg positional copy");
}}''', encoding='utf-8')
run([a.java_home/'bin/javac.exe', '--release', '17', '-cp', cp([out/'old-sdk', *deps]), '-d', out/'client', java_fixture])
# Establish the baseline and freeze the exact client bytes before replacing SDK on classpath.
run([java, '-cp', cp([out/'client', out/'old-sdk', *deps]), 'OldClientKt'])
def hashes(): return {str(f.relative_to(out/'client')): hashlib.sha256(f.read_bytes()).hexdigest() for f in (out/'client').rglob('*.class')}
before = hashes()
compile([repo/sdk/'Tool.kt', extension], out/'new-sdk', deps)
results = [run([java, '-cp', cp([out/'client', out/'new-sdk', *deps]), main]).strip()
           for main in ['OldClientKt', 'OldJavaClient']]
assert before == hashes()
# Additional content must participate in value identity and survive old-shape copy operations.
new_fixture = out/'NewClient.kt'
new_fixture.write_text('''import io.github.bytejuno.thetacore.sdk.*
fun main() {
 val a = ToolResult("text").withContent(listOf(ToolContent.Text("attached")))
 check(a.copy() == a && a.copy() !== a)
 check(a.copy(text = "changed").content == a.content)
 check(a != ToolResult("text"))
 check(a == ToolResult("text").withContent(listOf(ToolContent.Text("attached"))))
 check(a.hashCode() == a.copy().hashCode())
 val source = byteArrayOf(1, 2, 3)
 val media = ToolContent.Media(ToolMediaKind.IMAGE, "image/png", source)
 val separate = ToolContent.Media(ToolMediaKind.IMAGE, "image/png", byteArrayOf(1, 2, 3))
 check(media == separate && media.hashCode() == separate.hashCode())
 check(media.copy() == media && media.copy() !== media)
 check(media.copy(bytes = byteArrayOf(1, 2, 3)) == media)
 val hash = media.hashCode()
 source[0] = 9; media.bytes[1] = 9; media.component3()[2] = 9
 check(media == separate && media.hashCode() == hash)
 check(media.copy(mimeType = "image/jpeg") != media)
 check(media.copy(kind = ToolMediaKind.VIDEO) != media)
 check(media.copy(bytes = byteArrayOf(4)) != media)
 val rich = ToolResult("text").withContent(listOf(media))
 val equal = ToolResult("text").withContent(listOf(separate))
 check(rich == equal && rich.hashCode() == equal.hashCode())
 check(rich.copy() == rich && rich.copy(text = "new").content == rich.content)
 check(hashSetOf(media, separate, media.copy()).size == 1)
 println("NEW_CONTENT_VALUE_OK media_checks=11 defensive_mutations=3")
}
''', encoding='utf-8')
compile([new_fixture], out/'new-client', [out/'new-sdk', *deps])
results.append(run([java, '-cp', cp([out/'new-client', out/'new-sdk', *deps]), 'NewClientKt']).strip())
report = dict(base=base, unchanged_client_sha256=before, results=results,
              new_source_sha256=hashlib.sha256((repo/sdk/'Tool.kt').read_bytes()).hexdigest())
(out/'result.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report))
