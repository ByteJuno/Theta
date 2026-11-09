package io.github.bytejuno.thetacore.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fixed signed payload copies for androidTest, with an AGP generated-assets owner. */
abstract class StageS1PublicationAssets : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val versionOne: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val versionTwo: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun stage() {
        val directory = outputDir.get().asFile.apply { mkdirs() }
        versionOne.get().asFile.copyTo(directory.resolve("code-v1.apk"), overwrite = true)
        versionTwo.get().asFile.copyTo(directory.resolve("code-v2.apk"), overwrite = true)
    }
}
