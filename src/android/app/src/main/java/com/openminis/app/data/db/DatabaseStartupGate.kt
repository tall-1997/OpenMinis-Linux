package com.openminis.app.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * [T-db-gate-async] Startup integrity gate for `minis.db`, OFF the main thread.
 *
 * History: [DatabaseHealthCheck.ensureHealthyOrQuarantined] used to run INLINE
 * inside `AppDatabase.getInstance`, which MinisApp.onCreate calls on the MAIN
 * thread. A `PRAGMA quick_check` is O(database size) — on a large chat history
 * it reads the whole file, so cold start paid seconds of main-thread I/O
 * before the first frame (review v3 P0-4).
 *
 * Now: [start] KICKS the gate onto a dedicated single-thread executor and
 * returns immediately; Room's build() is lazy and opens nothing, so no file is
 * touched until the gate has spoken. The ordering constraints that keep the
 * quarantine / degrade semantics IDENTICAL to the inline version:
 *
 *  1. **The gate completes BEFORE Room's first open.** Enforced twice: by
 *     [GatedOpenHelper] (every readable/writableDatabase call awaits the
 *     verdict — the "await before the first DAO access" barrier, structural
 *     for ANY caller: warmup, BackupViewModel, tool execution, provider
 *     scrub, voice correction) and explicitly by
 *     `MinisAppAsyncInit.startWarmup`, which awaits before the warmup open
 *     (the MinisAppAsyncInit warmup barrier). A corrupt database is therefore
 *     renamed aside before anything opens it, exactly as before.
 *  2. **The gate only starts after MinisApp's DatabaseVersionGuard decision**
 *     (onCreate awaits the probe before calling getInstance). A database from
 *     a newer build is still never touched: SHOW_NEWER_DB_GUIDANCE refuses to
 *     reach getInstance, so the gate cannot quarantine a "newer build"
 *     database out from under the guidance screen.
 *
 * A QuarantineFailed outcome no longer throws from getInstance (that would
 * put the main thread back on the hook); instead every open attempt throws
 * the SAME IllegalStateException via [awaitOrThrow], so the database is never
 * opened and the degraded-mode paths handle the failure.
 *
 * Kept out of AppDatabase.kt on purpose: that file sits just under the
 * architecture policy's 800-line new-file cap.
 */
internal object DatabaseStartupGate {

    private const val TAG = "DbHealth"

    @Volatile
    private var startupGate: CompletableDeferred<DatabaseHealthCheck.Outcome>? = null

    /**
     * Single daemon worker: quick_check runs at most once per process, so
     * there is no throughput need — the dedicated thread exists only to keep
     * the check off whoever calls `AppDatabase.getInstance` (the main thread
     * on cold start) and to serialize it against a hypothetical re-entry
     * after a failed build().
     */
    private val gateExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "minis-db-integrity-gate").apply { isDaemon = true }
        }

    /** Idempotent: starts the integrity gate for `minis.db` on the gate thread. */
    fun start(appContext: Context) {
        if (startupGate != null) return
        val gate = CompletableDeferred<DatabaseHealthCheck.Outcome>()
        startupGate = gate
        gateExecutor.execute {
            try {
                gate.complete(
                    DatabaseHealthCheck.ensureHealthyOrQuarantined(appContext, "minis.db")
                )
            } catch (t: Throwable) {
                // ensureHealthyOrQuarantined is internally defensive; this
                // catch is the belt to those braces. Surface as the same
                // outcome an unquarantiable corruption produces — the DB must
                // not be opened while its state is unknown.
                AppLogger.error(TAG, "[T-db-gate] integrity gate crashed: $t")
                gate.complete(
                    DatabaseHealthCheck.Outcome.QuarantineFailed(
                        "integrity gate crashed: ${t.javaClass.simpleName}: ${t.message}"
                    )
                )
            }
        }
    }

    /**
     * Blocks the caller until the startup gate has landed, then returns its
     * outcome — throwing the SAME IllegalStateException the old inline call
     * threw when the corrupt database could not be quarantined.
     *
     * Returns [DatabaseHealthCheck.Outcome.Healthy] when no gate is running
     * (an AppDatabase built without `getInstance`, e.g. an in-memory test
     * database) — nothing to gate there.
     */
    fun awaitOrThrow(): DatabaseHealthCheck.Outcome {
        val gate = startupGate ?: return DatabaseHealthCheck.Outcome.Healthy
        val outcome = kotlinx.coroutines.runBlocking { gate.await() }
        if (outcome is DatabaseHealthCheck.Outcome.QuarantineFailed) {
            throw IllegalStateException(
                "minis.db failed integrity check and could not be " +
                    "quarantined: ${outcome.reason}"
            )
        }
        return outcome
    }

    /**
     * Wraps the framework open helper so that NO open can precede the
     * integrity gate — this is what makes "await before the first DAO access"
     * structural rather than a promise about call order: any DAO call, from
     * any thread, blocks here until the gate has spoken, and an unquarantiable
     * database throws instead of opening.
     */
    private class GatedOpenHelper(
        private val delegate: SupportSQLiteOpenHelper,
    ) : SupportSQLiteOpenHelper by delegate {
        private fun gateThenOpen(): SupportSQLiteDatabase {
            awaitOrThrow()
            return delegate.writableDatabase
        }

        override val writableDatabase: SupportSQLiteDatabase
            get() = gateThenOpen()

        override val readableDatabase: SupportSQLiteDatabase
            get() = gateThenOpen()
    }

    fun openHelperFactory(): SupportSQLiteOpenHelper.Factory =
        object : SupportSQLiteOpenHelper.Factory {
            override fun create(
                configuration: SupportSQLiteOpenHelper.Configuration,
            ): SupportSQLiteOpenHelper =
                GatedOpenHelper(FrameworkSQLiteOpenHelperFactory().create(configuration))
        }
}
