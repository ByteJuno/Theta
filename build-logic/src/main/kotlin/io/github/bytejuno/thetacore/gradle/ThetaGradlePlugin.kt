package io.github.bytejuno.thetacore.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 这些库由宿主提供，不进插件包。Kotlin 内置支持和第三方依赖都会把它们带进运行时类路径，
 * 所以只写 compileOnly 不够，要在这里排除。
 *
 * 和宿主的 `SHARED_PACKAGES`（app 模块）、`proguard-rules.pro` 保持同步。
 */
private val SHARED_GROUPS = listOf(
    "androidx.collection",
    "androidx.compose.animation",
    "androidx.compose.foundation",
    "androidx.compose.material",
    "androidx.compose.material3",
    "androidx.compose.runtime",
    "androidx.compose.ui",
    "androidx.lifecycle",
    "androidx.savedstate",
)

private val SHARED_MODULES = listOf(
    "org.jetbrains.kotlin" to "kotlin-stdlib",
    "org.jetbrains.kotlin" to "kotlin-stdlib-common",
    "org.jetbrains.kotlin" to "kotlin-stdlib-jdk7",
    "org.jetbrains.kotlin" to "kotlin-stdlib-jdk8",
    "org.jetbrains.kotlinx" to "kotlinx-coroutines-android",
    "org.jetbrains.kotlinx" to "kotlinx-coroutines-bom",
    "org.jetbrains.kotlinx" to "kotlinx-coroutines-core",
    "org.jetbrains.kotlinx" to "kotlinx-coroutines-core-jvm",
    "org.jetbrains.kotlinx" to "kotlinx-serialization-bom",
    "org.jetbrains.kotlinx" to "kotlinx-serialization-core",
    "org.jetbrains.kotlinx" to "kotlinx-serialization-core-jvm",
    "org.jetbrains.kotlinx" to "kotlinx-serialization-json",
    "org.jetbrains.kotlinx" to "kotlinx-serialization-json-jvm",
)

/** 插件作者用的 Gradle 插件：`id("io.github.bytejuno.theta-plugin")`。 */
class ThetaGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val theta = project.extensions.create("theta", ThetaExtension::class.java)
        theta.name.convention(project.name)

        var androidApplied = false
        project.pluginManager.withPlugin("com.android.application") {
            androidApplied = true
            val android = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            android.onVariants { variant ->
                // 只动打包用的类路径。单元测试有自己的类路径，还需要这些库。
                SHARED_GROUPS.forEach { group ->
                    variant.runtimeConfiguration.exclude(mapOf("group" to group))
                }
                SHARED_MODULES.forEach { (group, module) ->
                    variant.runtimeConfiguration.exclude(mapOf("group" to group, "module" to module))
                }

                val output = variant.outputs.single()
                val taskName = "generate${variant.name.replaceFirstChar(Char::uppercase)}ThetaManifest"
                val task = project.tasks.register(taskName, GenerateThetaManifest::class.java) {
                    pluginId.set(theta.id)
                    pluginName.set(theta.name)
                    pluginVersion.set(output.versionName.map { name -> requireNotNull(name) })
                    pluginVersionCode.set(output.versionCode.map { code -> requireNotNull(code) })
                    apiLevel.set(theta.apiLevel)
                    entry.set(theta.entry)
                    provides.set(theta.provides)
                    declares.set(theta.declares)
                }
                variant.sources.assets?.addGeneratedSourceDirectory(task, GenerateThetaManifest::outputDir)
            }
        }
        project.afterEvaluate {
            if (!androidApplied) {
                throw GradleException("theta-plugin 要和 com.android.application 一起使用")
            }
        }
    }
}
