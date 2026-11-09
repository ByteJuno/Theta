package io.github.bytejuno.thetacore.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.util.Random

/** Deterministic inert test asset, independent of Gradle script objects at execution. */
abstract class GenerateS1PublicationPadding : DefaultTask() {
    @get:Input abstract val byteCount: Property<Int>
    @get:Input abstract val seed: Property<Long>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun generate() {
        val target = outputDir.get().file("s1-write-padding.bin").asFile
        target.parentFile.mkdirs()
        val random = Random(seed.get())
        val buffer = ByteArray(8192)
        var remaining = byteCount.get()
        target.outputStream().use { output ->
            while (remaining > 0) {
                random.nextBytes(buffer)
                val count = minOf(buffer.size, remaining)
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }
}
