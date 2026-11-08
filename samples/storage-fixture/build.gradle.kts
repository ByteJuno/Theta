plugins { alias(libs.plugins.android.application) }

// Independent disposable APK. Compile these exact host files, not a copied implementation
// or a dependency on :app. Keep the package for access to existing internal test seams.
val productionStorageFiles = listOf(
    "PluginDataStore.kt", "PluginDataCatalog.kt", "PluginPublication.kt",
    "PluginDataManifest.kt", "PluginDataZip.kt", "PluginDataState.kt",
    "PluginDataInventory.kt", "ManagedPluginData.kt", "PluginState.kt",
    "PluginStageCleanup.kt", "PluginInstallRecovery.kt", "PluginTrustStore.kt",
    "PluginSignatureVerifier.kt", "PluginManifest.kt",
)
val productionStorageDir = rootProject.file("app/src/main/kotlin/io/github/bytejuno/thetacore/plugin")
// AGP 9 owns Android source sets; its built-in Kotlin has no KotlinSourceSet "main".
// Append individual files to app compilation only. Do not replace the fixture's sources
// or recompile host classes in unit tests (which consume the main compilation).
val productionStorageSources = productionStorageFiles.map { productionStorageDir.resolve(it) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name in setOf("compileDebugKotlin", "compileReleaseKotlin")) {
        productionStorageSources.forEach { source(it) }
    }
}

android {
    namespace = "io.github.bytejuno.thetacore.sample.storagefixture"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "io.github.bytejuno.thetacore.sample.storagefixture"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation("junit:junit:4.13.2")
}

tasks.register("verifyStorageFixtureSources") {
    group = "verification"
    description = "Verify direct production source inclusion and fixture isolation"
    // Inspect the realized compiler inputs during configuration, not from the task action:
    // configuration-cache actions cannot access tasks, configurations or the build script.
    // Querying file trees here also makes source additions/removals invalidate the cache.
    // Keep only plain values in the action, with no dependency on compilation (preBuild
    // depends on this task, so compiler task dependencies would create a cycle).
    val productionFiles = productionStorageSources.map { it.canonicalFile }
    val expectedProduction = productionFiles.toSet()
    val rootDir = rootProject.projectDir.canonicalFile
    val fixturePath = project.path
    val manifests = layout.buildDirectory.dir("reports/storage-fixture-sources").get().asFile
    val compilerSources = listOf("Debug", "Release").flatMap { variant ->
        listOf(false, true).mapNotNull { unitTest ->
            val compileName = "compile$variant${if (unitTest) "UnitTest" else ""}Kotlin"
            // Release unit tests are disabled; inspect them too if enabled later.
            if (unitTest && variant == "Release" && compileName !in tasks.names) return@mapNotNull null
            val compile = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(compileName).get()
            val actual = (compile.sources.files + compile.javaSources.files).map { it.canonicalFile }.toSet()
            val sourceSets = if (unitTest) listOf("test", "test$variant")
                else listOf("main", variant.lowercase())
            val ownSources = sourceSets.flatMap { sourceSet ->
                fileTree("src/$sourceSet") { include("**/*.kt", "**/*.java") }.files
            }.map { it.canonicalFile }.toSet()
            Triple(compileName, actual, ownSources + if (unitTest) emptySet() else expectedProduction)
        }
    }
    // AGP wires unit-test compilation to this application's own main variant using
    // a self ProjectDependency. Only that self edge is allowed, never :app or :sdk.
    val projectEdges = configurations.flatMap { configuration ->
        configuration.dependencies.filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
            .map { configuration.name to it.path }
    }
    doLast {
        check(productionFiles.size == 14 && expectedProduction.size == 14)
        expectedProduction.forEach { check(it.isFile) { "Missing production source: $it" } }
        check(manifests.isDirectory || manifests.mkdirs())
        for ((compileName, actual, expected) in compilerSources) {
            val ownSources = expected - expectedProduction
            check(ownSources.isNotEmpty()) { "$compileName lost fixture sources" }
            check(actual == expected) {
                "$compileName source mismatch: missing=${expected - actual}, unexpected=${actual - expected}"
            }
            // These manifests are derived from KotlinCompile, not the hand-maintained allowlist.
            manifests.resolve("$compileName.txt").writeText(actual.map {
                it.relativeTo(rootDir).invariantSeparatorsPath
            }.sorted().joinToString("\n", postfix = "\n"))
            logger.lifecycle("$compileName verified: ${actual.size} compiler sources, " +
                "${actual.intersect(expectedProduction).size} production, ${ownSources.size} fixture")
        }
        check(projectEdges.all { (_, target) -> target == fixturePath }) {
            "Fixture must not depend on host application or SDK modules: $projectEdges"
        }
        logger.lifecycle("Storage fixture project edges (self only): $projectEdges")
    }
}
tasks.named("preBuild") { dependsOn("verifyStorageFixtureSources") }
