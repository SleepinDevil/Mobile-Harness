package com.jarves.mh.runtime

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** One recorded agent-harness run, as listed by Settings → Harness settings. */
data class RuntimeLogEntry(
    val id: String,
    val agentId: String,
    val projectSlug: String,
    val startedAtMillis: Long,
    val exitCode: Int?,
    val failed: Boolean,
    val bytes: Long,
)

/**
 * Keeps the last few raw agent-harness transcripts (stdout+stderr of every PRoot
 * session) on disk so the user can read the errors that made a harness stop,
 * including messages the UI collapses into "Task stopped".
 *
 * Files live in `filesDir/runtime-logs/`; [newLogFile] is handed to
 * `RuntimeInstaller.process(outputFile = …)` and [record] appends a footer,
 * indexes the run in `index.jsonl`, and prunes old runs.
 */
class RuntimeLogStore(private val root: File) {
    private val indexFile = File(root, "index.jsonl")
    private val recorded = mutableSetOf<String>()

    fun newLogFile(agentId: String, projectSlug: String, sessionId: String): File {
        root.mkdirs()
        return File(root, "${System.currentTimeMillis()}-$agentId-${sessionId.take(8)}.log")
    }

    /**
     * Finalizes [file] once its process ended: appends a summary footer, appends an
     * index row, and prunes to [MAX_LOGS] entries. Idempotent per file so a failure
     * that unwinds through several handlers records the run exactly once (the first
     * outcome wins).
     */
    @Synchronized
    fun record(file: File, agentId: String, projectSlug: String, startedAtMillis: Long, exitCode: Int?, failed: Boolean, reason: String? = null) {
        if (!file.isFile || !recorded.add(file.name)) return
        if (file.length() > MAX_LOG_BYTES) return
        runCatching {
            file.appendText(
                "\n---- PocketDev ${FOOTER_TIME.format(Date())} agent=$agentId exit=${exitCode ?: "n/a"} " +
                    (if (failed) "FAILED" else "OK") +
                    reason?.take(300)?.replace('\n', ' ')?.let { " — $it" }.orEmpty() + "\n",
            )
            val row = JSONObject()
                .put("id", file.name)
                .put("agent", agentId)
                .put("project", projectSlug)
                .put("startedAt", startedAtMillis)
                .put("exit", exitCode ?: JSONObject.NULL)
                .put("failed", failed)
                .put("bytes", file.length())
            indexFile.parentFile?.mkdirs()
            indexFile.appendText("$row\n")
        }
        prune()
    }

    /** Recorded runs, newest first; index rows whose file was deleted vanish. */
    @Synchronized
    fun entries(): List<RuntimeLogEntry> {
        val text = indexFile.takeIf(File::isFile)?.readText().orEmpty()
        return text.lineSequence().mapNotNull { line ->
            runCatching {
                val row = JSONObject(line)
                val log = fileFor(row.optString("id")) ?: return@runCatching null
                RuntimeLogEntry(
                    id = row.getString("id"),
                    agentId = row.optString("agent"),
                    projectSlug = row.optString("project"),
                    startedAtMillis = row.optLong("startedAt"),
                    exitCode = if (row.isNull("exit")) null else row.optInt("exit"),
                    failed = row.optBoolean("failed"),
                    bytes = log.length(),
                )
            }.getOrNull()
        }.toList().sortedByDescending(RuntimeLogEntry::startedAtMillis)
    }

    /** Tail of one recorded transcript (ANSI-stripped, bounded), or null when gone. */
    fun read(id: String): String? {
        val file = fileFor(id) ?: return null
        return file.readTailText(MAX_TAIL_BYTES)
            .replace(ANSI_ESCAPE, "")
            .replace("\r\n", "\n")
    }

    @Synchronized
    fun clear() {
        recorded.clear()
        indexFile.delete()
        root.listFiles()?.forEach { file ->
            if (file.name.endsWith(".log")) runCatching { file.delete() }
        }
    }

    private fun fileFor(id: String): File? =
        id.takeIf { it.isNotBlank() && it.endsWith(".log") && !it.contains('/') && !it.contains('\\') }
            ?.let(::File)?.let { candidate ->
                File(root, candidate.name).takeIf { it.isFile }
            }

    private fun prune() {
        val rows = runCatching {
            indexFile.readLines().mapNotNull { line ->
                runCatching { line to JSONObject(line) }.getOrNull()
            }
        }.getOrDefault(emptyList())
        if (rows.size <= MAX_LOGS) return
        val ordered = rows.sortedByDescending { (_, row) -> row.optLong("startedAt") }
        ordered.drop(MAX_LOGS).forEach { (_, row) ->
            val name = row.optString("id")
            recorded.remove(name)
            runCatching { File(root, name).delete() }
        }
        runCatching {
            indexFile.writeText(ordered.take(MAX_LOGS).joinToString("\n") { (line, _) -> line } + "\n")
        }
    }

    private companion object {
        const val MAX_LOGS = 12
        const val MAX_TAIL_BYTES = 256 * 1024
        const val MAX_LOG_BYTES = 16L shl 20
        val FOOTER_TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
    }
}
