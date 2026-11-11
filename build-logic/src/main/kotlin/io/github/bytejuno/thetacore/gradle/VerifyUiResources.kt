package io.github.bytejuno.thetacore.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.*
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Fail closed in UI files; inspect known UI sinks everywhere else. Kotlin PSI handles
 * comments, nested expressions, templates, raw strings and named arguments. Protocol,
 * parser and logging messages outside UI files are not translated. Dynamic plugin text
 * is supplied by its author; this check governs host-authored text, not arbitrary data.
 */
abstract class VerifyUiResources : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty
    @get:org.gradle.api.tasks.Classpath
    abstract val parserClasspath: org.gradle.api.file.ConfigurableFileCollection

    @TaskAction fun verify() {
        // Isolate PSI from Gradle/AGP's different shaded IntelliJ versions.
        val ownJar = VerifyUiResources::class.java.protectionDomain.codeSource.location
        java.net.URLClassLoader((parserClasspath.files.map { it.toURI().toURL() } + ownJar).toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            try {
                loader.loadClass("io.github.bytejuno.thetacore.gradle.UiResourceCheck").getMethod("verify", File::class.java)
                    .invoke(null, sourceDirectory.get().asFile)
            } catch (failure: java.lang.reflect.InvocationTargetException) {
                throw GradleException(failure.targetException.message ?: "UI resource check failed", failure.targetException)
            }
        }
    }
}

object UiResourceCheck {
    // Java Formatter grammar. Formatter itself validates conversions/flag combinations;
    // this scanner only resolves ordinary, explicit and relative argument identities.
    private val formatToken = Regex("%([0-9]+\\$)?([-#+ 0,(<]*)([0-9]+)?(\\.[0-9]+)?([tT])?([a-zA-Z%])")
    internal fun formatArguments(text: String): List<String> {
        val result = mutableListOf<String>()
        var ordinary = 0
        var previous: Int? = null
        var offset = 0
        while (true) {
            val start = text.indexOf('%', offset)
            if (start < 0) break
            val token = formatToken.matchAt(text, start)
                ?: throw IllegalArgumentException("Malformed Formatter token at $start")
            val (index, flags, width, precision, date, conversion) = token.destructured
            val explicit = index.removeSuffix("$").takeIf { it.isNotEmpty() }?.toInt()
            require(explicit == null || explicit > 0) { "Argument index must be positive" }
            val consumes = date.isNotEmpty() || conversion !in setOf("%", "n")
            require('<' !in flags || consumes) { "Relative flag on non-argument token" }
            // Preserve repeated flags for Formatter to reject; remove only a single reuse flag.
            require(flags.count { it == '<' } <= 1) { "Duplicate relative flag" }
            val normalized = "%" + flags.replace("<", "") + width + precision + date + conversion
            java.util.Formatter(java.util.Locale.ROOT).use { formatter ->
                formatter.format(normalized, null as Any?)
            }
            if (consumes) {
                val argument = if ('<' in flags) requireNotNull(previous) { "No previous argument" }
                    else explicit ?: ++ordinary
                previous = argument
                val family = if (date.isNotEmpty()) "date" else when (conversion.lowercase()) {
                    "d", "o", "x" -> "integer"
                    "e", "f", "g" -> "float"
                    "a" -> "hex-float"
                    else -> conversion.lowercase()
                }
                result += "$argument:$family"
            }
            offset = token.range.last + 1
        }
        return result.sorted()
    }

    @JvmStatic
    fun verify(root: File) {
        val errors = mutableListOf<String>()
        fun strings(dir: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            root.resolve("res/$dir").walkTopDown().filter { it.extension == "xml" }.forEach { file ->
                val factory = DocumentBuilderFactory.newInstance()
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i)
                    val key = node.attributes.getNamedItem("name").nodeValue
                    if (result.put(key, node.textContent) != null) errors += "Duplicate $dir key: $key"
                }
            }
            return result
        }
        val english = strings("values")
        val chinese = strings("values-zh")
        (english.keys - chinese.keys).forEach { errors += "Missing Chinese key: $it" }
        (chinese.keys - english.keys).forEach { errors += "Missing English key: $it" }
        (english.keys intersect chinese.keys).forEach { key ->
            try {
                if (formatArguments(english.getValue(key)) != formatArguments(chinese.getValue(key))) {
                    errors += "Mismatched format arguments: $key"
                }
            } catch (invalid: IllegalArgumentException) {
                errors += "Invalid format arguments: $key: ${invalid.message}"
            }
        }
        val disposable = Disposer.newDisposable()
        try {
            val environment = KotlinCoreEnvironment.createForProduction(disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
            val factory = KtPsiFactory(environment.project, false)
            root.resolve("kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                val parsed = factory.createFile(file.name, file.readText())
                // A file declaring composables is a UI boundary. Do not allow a new label to
                // evade checking by assigning it to a local variable before Text(label).
                val uiFile = parsed.text.contains("@Composable")
                parsed.accept(object : KtTreeVisitorVoid() {
                    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
                        super.visitStringTemplateExpression(expression)
                        val value = expression.text.removeSurrounding("\"\"\"").removeSurrounding("\"")
                        if (value.isEmpty()) return
                        val parents = generateSequence(expression.parent) { it.parent }.toList()
                        val calls = parents.filterIsInstance<KtCallExpression>().map { it.calleeExpression?.text }
                        val function = parents.filterIsInstance<KtNamedFunction>().firstOrNull()?.name
                        // Explicit non-UI contracts inside UI files: MIME/Intent tags, JSON
                        // seed, LocaleManager tags and JSON-parser diagnostic exceptions.
                        val allowed = when (file.name) {
                            "MainActivity.kt" -> value in setOf("skip_plugins", "*/*")
                            "PluginDiagnosticsUi.kt" -> value == "{}" || function == "parseHostToolArguments"
                            "HookDiagnosticsPanel.kt" -> value in setOf("application/vnd.android.package-archive", "application/octet-stream", "package:\$HOOK_TARGET")
                            "UiStrings.kt" -> value in setOf("zh", "en")
                            else -> false
                        }
                        val sink = calls.any { it in setOf("Text", "BasicText", "makeText", "setText", "setTitle", "setMessage", "setContentTitle", "setContentText", "setPositiveButton", "setNegativeButton", "setContentDescription", "Snackbar") } ||
                            parents.filterIsInstance<KtValueArgument>().any { it.getArgumentName()?.asName?.asString() in setOf("contentDescription", "label", "title", "placeholder") } ||
                            parents.filterIsInstance<KtBinaryExpression>().any { it.left?.text?.endsWith("contentDescription") == true }
                        if (!allowed && (uiFile || sink)) {
                            val line = parsed.text.take(expression.textOffset).count { it == '\n' } + 1
                            errors += "${file.relativeTo(root)}:$line hardcoded UI text: ${expression.text.take(100)}"
                        }
                    }
                })
            }
            // Android XML UI text/contentDescription and dialog resources are covered too.
            root.resolve("res").walkTopDown().filter { it.extension == "xml" && !it.parentFile.name.startsWith("values") }.forEach { file ->
                val factoryXml = DocumentBuilderFactory.newInstance()
                factoryXml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                val nodes = factoryXml.newDocumentBuilder().parse(file).getElementsByTagName("*")
                for (i in 0 until nodes.length) {
                    val attrs = nodes.item(i).attributes
                    for (j in 0 until attrs.length) {
                        val attr = attrs.item(j)
                        if (attr.nodeName.substringAfter(':') in setOf("text", "hint", "title", "summary", "contentDescription") &&
                            attr.nodeValue.isNotEmpty() && !attr.nodeValue.startsWith("@") && !attr.nodeValue.startsWith("?"))
                            errors += "${file.relativeTo(root)} hardcoded XML UI text: ${attr.nodeName}"
                    }
                }
            }
        } finally { Disposer.dispose(disposable) }
        check(errors.isEmpty()) { errors.joinToString("\n") }
        println("UI resources verified: ${english.size} English/Chinese keys; Kotlin PSI + XML checks passed")
    }
}
