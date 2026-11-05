package com.openminis.app.ui.chat

/**
 * [T-job-awareness] Background jobs, rendered from the registry into every turn.
 *
 * The prototype's rule is that a job's end wakes the agent rather than being
 * polled (`docs/003-execution.md:33`). We have the registry and the tools, but a
 * job that left the transcript after a compaction is invisible: the model either
 * forgets it exists or re-runs the same command. Rendering the live list from the
 * registry makes it structural — the same reasoning as the plan board and the
 * in-flight lanes.
 *
 * Only as much as is needed to decide: id, state, exit code and a clipped command.
 * Output itself stays behind `job_output`, so this block costs a handful of lines
 * rather than megabytes of logs.
 */
internal object BackgroundJobsPrompt {

    /** Jobs listed before the summary line takes over. */
    const val MAX_JOBS = 5

    /** Command text kept per job. */
    const val MAX_COMMAND_CHARS = 80

    data class Row(val id: String, val state: String, val exitCode: Int?, val command: String)

    fun render(rows: List<Row>): String? {
        if (rows.isEmpty()) return null
        return buildString {
            append("<jobs>\n")
            append("Background jobs for this chat. Do not poll them; read output with job_output when it matters:\n")
            rows.take(MAX_JOBS).forEach { row ->
                append("- ").append(row.id)
                append(" [").append(row.state)
                row.exitCode?.let { append(' ').append(it) }
                append("] ").append(row.command.replace('\n', ' ').take(MAX_COMMAND_CHARS))
                append("\n")
            }
            if (rows.size > MAX_JOBS) append("- ... and ${rows.size - MAX_JOBS} more\n")
            append("</jobs>")
        }
    }
}
