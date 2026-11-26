package io.github.bytejuno.thetacore.plugin

/** Process-local run bindings. Cold-recovered calls without a binding fail closed. */
internal class RunSkillReader(private val read: suspend (SelectedPluginData, String, String) -> String) {
    private data class Snapshot(val skills: Set<String>, val selections: Map<String, SelectedPluginData>)
    private val runs = mutableMapOf<String, Snapshot>()

    @Synchronized fun bind(runId: String, data: PluginPromptData) {
        val snapshot = Snapshot(data.skills.map { it.id }.toSet(), data.selections.toMap())
        val previous = runs[runId]
        check(previous == null || previous == snapshot) { "Run skill snapshot changed" }
        runs[runId] = snapshot
    }

    @Synchronized fun release(runId: String) { runs.remove(runId) }

    suspend fun read(runId: String, id: String, path: String): String {
        val pluginId = skillLocation(id, path).first
        val selection = synchronized(this) {
            val snapshot = runs[runId]
            check(snapshot != null && id in snapshot.skills) { "Run skill snapshot unavailable" }
            checkNotNull(snapshot.selections[pluginId]) { "Run skill package unavailable" }
        }
        return read(selection, id, path)
    }
}
