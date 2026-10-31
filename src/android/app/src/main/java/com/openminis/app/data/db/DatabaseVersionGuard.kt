package com.openminis.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * [T-android-downgrade-compat] Detects a database written by a NEWER build
 * than the one now running, before Room ever opens it.
 *
 * ## The failure this prevents
 *
 * Room resolves a downgrade by calling `onUpgrade(from, to)` and looking for a
 * migration path. Finding none it consults `isMigrationRequired`, which either
 * throws `IllegalStateException` or — with a destructive fallback enabled —
 * calls `dropAllTables`. We enable no fallback (deliberately: dropping would
 * destroy the user's entire chat history), so an unmatched downgrade means the
 * app throws at first database access and simply will not start.
 *
 * [AppDatabase.MIGRATION_12_11] handles the 12 → 11 case, and the same no-op
 * pattern extends to any future ADD COLUMN / ADD TABLE change. But it cannot
 * cover everything: renames, drops, type changes and — most dangerously —
 * changes to the MEANING of existing data have no safe automatic downgrade,
 * and a missing link anywhere in the chain (12 → 11 → 10) breaks the whole
 * path. This guard is the backstop for those, and for the plain human case of
 * forgetting to add the downgrade migration at all.
 *
 * ## Why a pre-check instead of catching Room's exception
 *
 * `Room.databaseBuilder(...).build()` is lazy: nothing opens until the first
 * DAO call, so the exception surfaces from an arbitrary coroutine somewhere
 * deep in the app. Every one of those call sites would need a catch, and one
 * miss is still a crash. Reading the version first is deterministic and
 * happens once, in one place.
 *
 * Crucially, this runs BEFORE Room is constructed — so at the moment we
 * decide, no migration and no `dropAllTables` can possibly have executed. The
 * database file is guaranteed untouched.
 */
object DatabaseVersionGuard {

    private const val TAG = "DbVersionGuard"

    /**
     * The schema version this build understands. MUST equal the `version` in
     * [AppDatabase]'s `@Database` annotation.
     *
     * Kept as a separate constant because the annotation value is not readable
     * at runtime without reflection. [DatabaseVersionGuardTest] asserts the two
     * agree, so they cannot drift apart silently — a stale copy here would
     * either disable the guard or trip it on every launch.
     *
     * 1.31 bumped [AppDatabase] 12 → 14 (code graph + kanban) but left this at
     * 12. After Room migrated on-disk `user_version` to 14, the next launch
     * treated the user's own database as "from a newer build" and blocked
     * opening. Keep this equal to `@Database(version=...)`.
     */
    const val CODE_DB_VERSION = 24 // messages(role, created_at) index 23 -> 24

    /** Filename must match the one passed to `Room.databaseBuilder`. */
    private const val DB_NAME = "minis.db"

    /**
     * Read `user_version` without going through Room.
     *
     * Opened READ-ONLY on purpose: it makes it impossible for the probe itself
     * to modify, migrate or corrupt the file. Returns null when the database
     * does not exist yet (a fresh install) or cannot be read — in both cases
     * the caller should just proceed and let Room do its normal thing.
     */
    fun readOnDiskVersion(context: Context): Int? {
        val file = context.getDatabasePath(DB_NAME)
        if (!file.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                .use { it.version }
        }.getOrElse {
            // A file we cannot even open read-only is not a downgrade signal;
            // let Room report whatever the real problem is.
            AppLogger.warning(TAG, "could not probe db version: ${it.javaClass.simpleName}")
            null
        }
    }

    /**
     * True when the on-disk database is newer than this build supports, i.e.
     * the user has downgraded and Room may be unable to open it.
     *
     * Note this returns true even when a no-op downgrade migration WOULD have
     * handled it. That is intentional for versions we cannot vouch for: see
     * [isHandledDowngrade].
     */
    fun isFromNewerBuild(context: Context): Boolean {
        val onDisk = readOnDiskVersion(context) ?: return false
        val newer = onDisk > CODE_DB_VERSION
        if (newer) {
            AppLogger.warning(
                TAG,
                "database is from a newer build: onDisk=$onDisk code=$CODE_DB_VERSION",
            )
        }
        return newer
    }

    /**
     * On-disk schema versions whose jump down to [CODE_DB_VERSION] is covered
     * END-TO-END by registered downgrade migrations that keep the file openable
     * for the older build (pure no-ops that leave extra columns/tables in
     * place, or index drops like [AppDatabase.MIGRATION_16_15] — anything that
     * lands the schema exactly on the older build's expected shape).
     *
     * Membership in this set is what makes [evaluate] return PROCEED instead
     * of SHOW_NEWER_DB_GUIDANCE for a downgraded database.
     *
     * EMPTY today — and that is the honest answer, not a regression: every
     * downgrade migration this build registers starts at [CODE_DB_VERSION] or
     * below, and an on-disk version ≤ CODE_DB_VERSION is an upgrade, never a
     * downgrade. The previous expression — `onDiskVersion == 12 &&
     * CODE_DB_VERSION == 11` — encoded the same answer for the v11 build that
     * shipped [AppDatabase.MIGRATION_12_11], but as a constant comparison that
     * rotted to permanently-false the moment 1.31 bumped past 11 and nobody
     * could extend it. When a future version V > CODE_DB_VERSION ships a
     * verified no-op/keep-compatible downgrade chain back to CODE_DB_VERSION,
     * add V here; [DatabaseVersionGuardTest] pins the wiring.
     */
    private val handledNoOpDowngrades: Set<Int> = emptySet()

    /**
     * Whether a registered downgrade migration covers this jump, in which case
     * the app can open the database normally and the guidance screen is
     * unnecessary.
     *
     * Only versions with an explicit, verified no-op downgrade belong in
     * [handledNoOpDowngrades]. Anything else falls through to the guidance
     * screen rather than being optimistically opened — being wrong in that
     * direction costs the user a screen they can dismiss by upgrading; being
     * wrong the other way costs them a crash.
     */
    fun isHandledDowngrade(onDiskVersion: Int): Boolean =
        onDiskVersion in handledNoOpDowngrades

    /** Result of the launch-time check. */
    enum class Decision {
        /** Normal path — open the database. */
        PROCEED,

        /**
         * Database is from a newer build with no downgrade path. Show guidance
         * and do NOT touch the file; upgrading restores everything.
         */
        SHOW_NEWER_DB_GUIDANCE,
    }

    fun evaluate(context: Context): Decision {
        val onDisk = readOnDiskVersion(context) ?: return Decision.PROCEED
        if (onDisk <= CODE_DB_VERSION) return Decision.PROCEED
        if (isHandledDowngrade(onDisk)) return Decision.PROCEED
        return Decision.SHOW_NEWER_DB_GUIDANCE
    }

    /**
     * [T-perf-launch-probe-async] Same probe as [evaluate], dispatched onto
     * [scope]'s IO dispatcher so Application.onCreate never opens the database
     * file on the main thread.
     *
     * The contract that keeps this behaviour-identical to the inline call: the
     * first consumer (MinisApp.onCreate, which refuses to build repositories
     * on SHOW_NEWER_DB_GUIDANCE) blocks on the returned deferred at the exact
     * program position where the synchronous call used to sit. That barrier
     * preserves both old invariants — the decision is final before any
     * downstream reader runs, and the probe still completes before Room's
     * first open, because that consumer sits upstream of
     * MinisAppAsyncInit.startWarmup on the main thread. Exceptions surface
     * from `await()` exactly where the inline call would have thrown.
     */
    fun evaluateAsync(context: Context, scope: CoroutineScope): CompletableDeferred<Decision> {
        val result = CompletableDeferred<Decision>()
        scope.launch(Dispatchers.IO) {
            try {
                result.complete(evaluate(context))
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        return result
    }
}
