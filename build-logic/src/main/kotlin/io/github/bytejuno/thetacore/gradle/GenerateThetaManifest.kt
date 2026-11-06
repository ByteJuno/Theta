package io.github.bytejuno.thetacore.gradle

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** 生成 `assets/theta-plugin.json`。 */
@CacheableTask
abstract class GenerateThetaManifest : DefaultTask() {
    @get:Input abstract val pluginId: Property<String>
    @get:Input abstract val pluginName: Property<String>
    @get:Input abstract val pluginVersion: Property<String>
    @get:Input abstract val pluginVersionCode: Property<Int>
    @get:Input abstract val apiLevel: Property<Int>
    @get:Input abstract val entry: Property<String>
    @get:Input abstract val provides: ListProperty<String>
    @get:Input abstract val declares: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val id = pluginId.get()
        if (!ID_PATTERN.matches(id)) throw GradleException("theta.id 要用反向域名，不能是 \"$id\"")
        if (apiLevel.get() < 1) throw GradleException("theta.apiLevel 从 1 开始")

        val manifest = linkedMapOf(
            "schema" to 1,
            "id" to id,
            "name" to pluginName.get(),
            "version" to pluginVersion.get(),
            "versionCode" to pluginVersionCode.get(),
            "type" to "dex",
            "apiLevel" to apiLevel.get(),
            "entry" to entry.get(),
            "provides" to provides.get(),
            "declares" to declares.get(),
            "requires" to linkedMapOf("root" to false, "lsposed" to false),
            "hooks" to emptyList<Any>(),
        )
        val json = JsonOutput.prettyPrint(JsonOutput.toJson(manifest))
        outputDir.file(MANIFEST_NAME).get().asFile.writeText(json + "\n")
    }

    private companion object {
        const val MANIFEST_NAME = "theta-plugin.json"
        val ID_PATTERN = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
    }
}
