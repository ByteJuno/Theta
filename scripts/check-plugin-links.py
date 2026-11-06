#!/usr/bin/env python3
"""检查插件能不能和宿主链接上，不用启动设备。

    check-plugin-links.py <host.apk> <plugin.apk> [android.jar]

插件单独编译，运行时才和宿主的共享库链接。R8 改掉或删掉的符号，要等代码真的执行到才会抛
NoSuchMethodError，只跑一遍页面发现不了。这里逐条比对插件 dex 里的类型、方法和字段引用。

失败条件：
  - 插件的 dex 里有共享包的类（不该打包）
  - 插件引用的共享库符号在宿主里不存在
  - 插件引用的 androidx/kotlinx 类哪里都没有：宿主过滤器不放行，运行时一定找不到

给了 android.jar 时，其余找不到的类只作提示。可选依赖常有这种引用（比如 OkHttp 引用 Conscrypt）。
"""
import struct
import sys
import zipfile

# 和 PluginClassLoader.kt 的 SHARED_PACKAGES 保持同步。
SHARED = (
    "Landroidx/collection/",
    "Landroidx/compose/",
    "Landroidx/lifecycle/",
    "Landroidx/savedstate/",
    "Lio/github/bytejuno/thetacore/sdk/",
    "Lkotlin/",
    "Lkotlinx/coroutines/",
    "Lkotlinx/serialization/",
)
MUST_RESOLVE = ("Landroidx/", "Lkotlinx/")
NO_INDEX = 0xFFFFFFFF


class Class:
    def __init__(self, superclass, interfaces):
        self.superclass = superclass
        self.interfaces = interfaces
        self.methods = set()
        self.fields = set()

    def parents(self):
        if self.superclass:
            yield self.superclass
        yield from self.interfaces


class Dex:
    """dex 文件里和链接有关的部分。格式见 source.android.com/docs/core/runtime/dex-format。"""

    def __init__(self, data):
        self.data = data
        if data[:3] != b"dex":
            raise ValueError("not a dex file")
        sizes = struct.unpack_from("<12I", data, 0x38)
        strings, types, protos, fields, methods, classes = (
            (sizes[i], sizes[i + 1]) for i in range(0, 12, 2)
        )
        self.strings = [self._string(self._u32(strings[1] + 4 * i)) for i in range(strings[0])]
        self.types = [self.strings[self._u32(types[1] + 4 * i)] for i in range(types[0])]
        self.protos = [self._proto(protos[1] + 12 * i) for i in range(protos[0])]
        self.field_refs = [self._member(fields[1] + 8 * i, self.types) for i in range(fields[0])]
        self.method_refs = [self._member(methods[1] + 8 * i, self.protos) for i in range(methods[0])]
        self.classes = dict(self._class(classes[1] + 32 * i) for i in range(classes[0]))

    def _u32(self, offset):
        return struct.unpack_from("<I", self.data, offset)[0]

    def _u16(self, offset):
        return struct.unpack_from("<H", self.data, offset)[0]

    def _uleb(self, offset):
        value = shift = 0
        while True:
            byte = self.data[offset]
            offset += 1
            value |= (byte & 0x7F) << shift
            shift += 7
            if byte < 0x80:
                return value, offset

    def _string(self, offset):
        _, offset = self._uleb(offset)
        end = self.data.index(0, offset)
        return self.data[offset:end].decode("utf-8", "replace")

    def _type_list(self, offset):
        if offset == 0:
            return []
        return [self.types[self._u16(offset + 4 + 2 * i)] for i in range(self._u32(offset))]

    def _proto(self, offset):
        result = self.types[self._u32(offset + 4)]
        return "(" + "".join(self._type_list(self._u32(offset + 8))) + ")" + result

    def _member(self, offset, signatures):
        owner = self.types[self._u16(offset)]
        signature = signatures[self._u16(offset + 2)]
        return owner, self.strings[self._u32(offset + 4)], signature

    def _class(self, offset):
        name = self.types[self._u32(offset)]
        superclass = self._u32(offset + 8)
        info = Class(
            None if superclass == NO_INDEX else self.types[superclass],
            self._type_list(self._u32(offset + 12)),
        )
        data_offset = self._u32(offset + 24)
        if data_offset:
            self._class_data(data_offset, info)
        return name, info

    def _class_data(self, offset, info):
        counts = []
        for _ in range(4):
            value, offset = self._uleb(offset)
            counts.append(value)
        static_fields, instance_fields, direct_methods, virtual_methods = counts

        for count in (static_fields, instance_fields):
            index = 0
            for _ in range(count):
                diff, offset = self._uleb(offset)
                _, offset = self._uleb(offset)
                index += diff
                _, name, signature = self.field_refs[index]
                info.fields.add((name, signature))

        for count in (direct_methods, virtual_methods):
            index = 0
            for _ in range(count):
                diff, offset = self._uleb(offset)
                _, offset = self._uleb(offset)
                _, offset = self._uleb(offset)
                index += diff
                _, name, signature = self.method_refs[index]
                info.methods.add((name, signature))


def read_apk(path):
    with zipfile.ZipFile(path) as apk:
        names = [n for n in apk.namelist() if n.startswith("classes") and n.endswith(".dex")]
        return [Dex(apk.read(name)) for name in sorted(names)]


def merged_classes(dexes):
    classes = {}
    for dex in dexes:
        classes.update(dex.classes)
    return classes


def is_shared(descriptor):
    return descriptor.startswith(SHARED)


def resolves(owner, member, kind, plugin, host):
    """沿继承链找成员。链走出插件和宿主就到了平台类，这里不再判断。"""
    pending = [owner]
    seen = set()
    while pending:
        name = pending.pop()
        if name in seen:
            continue
        seen.add(name)
        info = plugin.get(name) or host.get(name)
        if info is None:
            if not is_shared(name):
                return True
            continue
        if member in getattr(info, kind):
            return True
        pending.extend(info.parents())
    return False


def platform_classes(android_jar):
    if android_jar is None:
        return None
    with zipfile.ZipFile(android_jar) as jar:
        return {"L" + n[:-6] + ";" for n in jar.namelist() if n.endswith(".class")}


def main():
    if len(sys.argv) not in (3, 4):
        print(__doc__, file=sys.stderr)
        return 2

    host = merged_classes(read_apk(sys.argv[1]))
    dexes = read_apk(sys.argv[2])
    plugin = merged_classes(dexes)
    platform = platform_classes(sys.argv[3] if len(sys.argv) == 4 else None)

    bundled = sorted(name for name in plugin if is_shared(name))
    missing = set()
    unknown = set()
    checked = 0

    for dex in dexes:
        for raw in dex.types:
            descriptor = raw.lstrip("[")
            if not descriptor.startswith("L") or descriptor in plugin:
                continue
            if is_shared(descriptor):
                checked += 1
                if descriptor not in host:
                    missing.add(f"class {descriptor}")
            elif platform is not None and descriptor not in platform:
                if descriptor.startswith(MUST_RESOLVE):
                    missing.add(f"class {descriptor} (not shared, not bundled)")
                else:
                    unknown.add(descriptor)

        for refs, kind in ((dex.method_refs, "methods"), (dex.field_refs, "fields")):
            for owner, name, signature in refs:
                if owner.startswith("["):
                    continue
                # 自己定义的类也要查：成员可能继承自宿主。
                if owner in plugin or is_shared(owner):
                    checked += 1
                    if not resolves(owner, (name, signature), kind, plugin, host):
                        missing.add(f"{kind[:-1]} {owner}->{name}{signature}")

    for name in bundled[:20]:
        print(f"BUNDLED {name}")
    for name in sorted(missing)[:50]:
        print(f"MISSING {name}")
    for name in sorted(unknown)[:10]:
        print(f"note: unresolved optional class {name}")
    print(
        f"{sys.argv[2]}: {len(plugin)} classes, {checked} references checked, "
        f"{len(bundled)} bundled, {len(missing)} missing, {len(unknown)} optional"
    )
    return 1 if bundled or missing else 0


if __name__ == "__main__":
    sys.exit(main())
