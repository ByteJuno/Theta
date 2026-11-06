package io.github.bytejuno.thetacore.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

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
