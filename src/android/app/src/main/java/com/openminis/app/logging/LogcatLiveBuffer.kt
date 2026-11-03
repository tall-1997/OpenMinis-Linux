package com.openminis.app.logging

/**
 * Opaque handle on the child process that streams logcat into the panel.
 *
 * Exists so [LogcatLiveBuffer] can be driven by a fake in unit tests — the
 * real implementation spawns `logcat --pid=<self>`, which no JVM test can do.
 */
internal interface LogcatTailSource {
    fun start()
    fun stop()
}

/** Production binding: one [LogcatTailer] child process. */
internal class ProcessLogcatTailSource(sink: (String) -> Unit) : LogcatTailSource {
    private val tailer = LogcatTailer(sink)
    override fun start() = tailer.start()
    override fun stop() = tailer.stop()
}

/**
 * Lifecycle + buffering for the in-app live log panel (T-logcat-panel).
 *
 * Owns one tail source and one [LogcatRing]. The screen polls [version] on a
 * short cadence and re-reads [snapshot] only when it moved — pushing every
 * incoming line into Compose state directly would recompose the list dozens
 * of times a second on a chatty process.
 *
 * Deliberately independent of `AppLogger.enabled`: the file-based logger is a
 * user-facing recording switch, while a person opening the live panel wants
 * to see output *now*, without first turning recording on and then hunting
 * the file.
 */
internal class LogcatLiveBuffer(
    capacity: Int = LogcatRing.DEFAULT_CAPACITY,
    private val tailSourceFactory: (((String) -> Unit) -> LogcatTailSource) = { sink ->
        ProcessLogcatTailSource(sink)
    },
) {

    private val ring = LogcatRing(capacity)
    private var source: LogcatTailSource? = null
    private var seq: Long = 0L

    /** Bumped on every stored line and on [clear]; polled by the UI. */
    @Volatile
    var version: Long = 0L
        private set

    /** True while a tail source is attached. Reference read; no lock needed. */
    val isRunning: Boolean get() = source != null

    /** Idempotent: a second call while running is a no-op. */
    @Synchronized
    fun start() {
        if (source != null) return
        val created = tailSourceFactory(::ingest)
        source = created
        created.start()
    }

    @Synchronized
    fun stop() {
        source?.stop()
        source = null
    }

    @Synchronized
    fun clear() {
        ring.clear()
        seq = 0L
        version += 1
    }

    /** Current rows, oldest first. Cheap enough to call a few times a second. */
    @Synchronized
    fun snapshot(): List<LogcatLine> = ring.snapshot()

    /**
     * Evictions so far for the current buffer contents.
     *
     * Read under the same monitor as [snapshot] so the count a caller shows
     * always belongs to the list it just read, never to a newer ring state.
     */
    @get:Synchronized
    val droppedCount: Int get() = ring.dropped

    /**
     * One incoming line. The `--------- beginning of …` banners logcat emits
     * on attach carry no information the panel can show (no level, no tag) and
     * only push real output out of the ring, so they are dropped here rather
     * than in the parser.
     */
    @Synchronized
    private fun ingest(raw: String) {
        if (raw.startsWith("---------")) return
        seq += 1
        ring.add(LogcatLineParser.parse(raw).copy(seq = seq))
        version += 1
    }
}
