package io.github.bytejuno.thetacore.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** 插件工程里的 `theta { }` 配置，对应 PLUGIN_API.md 第 2 节的清单字段。 */
abstract class ThetaExtension {
    abstract val id: Property<String>
    abstract val name: Property<String>
    abstract val apiLevel: Property<Int>
    abstract val entry: Property<String>
    abstract val provides: ListProperty<String>
    abstract val declares: ListProperty<String>
}
