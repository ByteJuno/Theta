package io.github.bytejuno.thetacore.plugin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

/** Public fixture-only local interface; accepts no paths, URIs, package names or payloads. */
class StorageFixtureActivity : Activity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { textSize = 14f; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(output) })
        // Configuration recreation must not repeat a destructive prepare/crash command.
        if (savedInstanceState == null) submit(intent)
        else output.text = savedInstanceState.getString("output", "Send an explicit fixture command.")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("output", output.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        submit(intent)
    }

    private fun submit(request: Intent) {
        // Copy only bounded enum/boolean arguments; never forward an external URI or file path.
        val command = request.getStringExtra("command") ?: "REPORT"
        val pointText = request.getStringExtra("stage") ?: "INTENT"
        val enabled = request.getBooleanExtra("enabled", false)
        val record = request.getStringExtra("record") ?: "RECOVER"
        val requestId = request.getStringExtra("requestId") ?: UUID.randomUUID().toString()
        worker.execute {
            val scenario = StorageFixtureScenario(filesDir, processToken, Process.myPid())
            try {
                require(command in COMMANDS)
                require(requestId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
                val point = PluginPublicationBoundary.valueOf(pointText)
                val name = StorageFixtureScenario.caseName(point, enabled)
                if (command == "REPORT") {
                    require(record in (COMMANDS - "REPORT") + "ERROR")
                    val file = File(evidenceDir(), "$name-$record.json")
                    check(file.isFile && file.length() <= 8192) { "No bounded evidence for $name/$record" }
                    display(file.readText()) // No storage recovery and no mutation while reporting.
                } else {
                    val result = scenario.execute(command, point, enabled,
                        beforeKill = { persist(name, "CRASH", it + ("requestId" to requestId)) },
                        killSelf = { Process.killProcess(Process.myPid()) },
                    )
                    persist(name, command, result + ("requestId" to requestId))
                }
            } catch (failure: Throwable) {
                val error = linkedMapOf<String, Any>("schema" to 1, "status" to "FAIL", "scope" to "DATA",
                    "command" to command.take(32), "stage" to pointText.take(32), "requestId" to requestId.take(80),
                    "phase" to scenario.phase, "pid" to Process.myPid(), "processToken" to processToken,
                    "errorClass" to failure.javaClass.name, "error" to (failure.message ?: "Unknown failure").take(1500))
                // Always make failures visible, including malformed enum input (never a file path).
                try {
                    val point = PluginPublicationBoundary.valueOf(pointText)
                    if (command in COMMANDS - "REPORT") persist(StorageFixtureScenario.caseName(point, enabled), "ERROR", error)
                    else display(JSONObject(error).toString())
                } catch (outputFailure: Throwable) {
                    Log.e(TAG, JSONObject(error).toString())
                    runOnUiThread { output.text = JSONObject(error).toString() }
                }
            }
        }
    }

    private fun evidenceDir() = File(filesDir.canonicalFile, "s1-storage-evidence").apply {
        check(isDirectory || mkdirs())
    }

    private fun persist(name: String, command: String, result: Map<String, Any>) {
        val json = JSONObject(result).toString()
        check(json.toByteArray(Charsets.UTF_8).size <= 8192)
        FileOutputStream(File(evidenceDir(), "$name-$command.json")).use {
            it.write(json.toByteArray(Charsets.UTF_8)); it.write('\n'.code); it.fd.sync()
        }
        // killSelf is called only after this returns. Durable file is authoritative if logcat drops a line.
        display(json)
    }

    private fun display(json: String) {
        Log.i(TAG, json)
        runOnUiThread { output.text = json }
    }

    companion object {
        private const val TAG = "S1StorageFixture"
        private val COMMANDS = setOf("PREPARE", "MISMATCH", "CRASH", "RECOVER", "REPORT")
        // Shared across Activity recreations, changed only by real process restart.
        private val processToken = UUID.randomUUID().toString()
        // One gate for every fixture operation, matching the production caller contract.
        private val worker = Executors.newSingleThreadExecutor()
    }
}
