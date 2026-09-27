package ca.pkay.rcloneexplorer.Database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_CANCEL_REQUESTED
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_CONFLICT_ITEMS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_CREATED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_DUE_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_ENDPOINT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_ENGINE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_FAILED_ITEMS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_FILTER_SNAPSHOT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_FINISHED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_ID
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_OWNER_GENERATION
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_OWNER_TOKEN
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_PROFILE_FINGERPRINT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_PROFILE_ID
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_PROFILE_REVISION
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_REASON
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_REQUESTED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_REQUESTED_MODE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_SETTINGS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_STARTED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_STATE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_SUCCESSFUL_ITEMS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_UNKNOWN_ITEMS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_UPDATED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_TABLE_NAME
import ca.pkay.rcloneexplorer.Items.Task
import java.util.UUID

/** Durable run owner and immutable execution snapshot. */
class RunRepository(context: Context) {
    private val context = context.applicationContext
    private val engineRef = EngineIdentity.current

    fun queueLegacyTask(
        taskId: Long,
        requestedAt: Long = System.currentTimeMillis(),
        dueAt: Long? = null
    ): RunRecord = queueRun(requestedAt, dueAt) { handler, db ->
        val task = handler.getTaskInTransaction(db, taskId)
            ?: throw RunRejectedException("Task no longer exists")
        Pair(
            ProfileStore.upsertLegacyTask(db, task, engineRef),
            ProfileStore.filterSnapshot(db, task.filterId)
        )
    }

    fun queueEphemeralTask(
        task: Task,
        requestedAt: Long = System.currentTimeMillis(),
        dueAt: Long? = null
    ): RunRecord = queueRun(requestedAt, dueAt) { _, db ->
        Pair(
            ProfileStore.upsertEphemeralTask(db, task, engineRef),
            ProfileStore.filterSnapshot(db, task.filterId)
        )
    }

    /** Defers a dispatch failure only while this exact owner is still queued. */
    fun deferQueuedDispatch(
        runId: String,
        ownerToken: String,
        reason: String,
        finishedAt: Long = System.currentTimeMillis()
    ): Boolean = finishQueuedBeforeExecution(
        runId,
        ownerToken,
        RunState.DEFERRED,
        reason,
        finishedAt
    )

    /** Completes only a still-queued owner when its request cannot safely be started. */
    fun finishQueuedBeforeExecution(
        runId: String,
        ownerToken: String,
        state: RunState,
        reason: String,
        finishedAt: Long = System.currentTimeMillis()
    ): Boolean {
        require(state == RunState.FAILED || state == RunState.CANCELLED ||
            state == RunState.DEFERRED || state == RunState.AUTH_REQUIRED ||
            state == RunState.RATE_LIMITED || state == RunState.BLOCKED) {
            "A queued run can only stop before execution with a non-success outcome"
        }
        require(reason.isNotBlank())
        require(finishedAt > 0L)
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        db.beginTransaction()
        return try {
            val values = ContentValues().apply {
                put(RUN_COLUMN_STATE, state.wireValue)
                put(RUN_COLUMN_REASON, reason)
                putNull(RUN_COLUMN_FILTER_SNAPSHOT)
                put(RUN_COLUMN_FINISHED_AT, finishedAt)
                put(RUN_COLUMN_UPDATED_AT, finishedAt)
            }
            val updated = db.update(
                RUN_TABLE_NAME,
                values,
                "$RUN_COLUMN_ID = ? AND $RUN_COLUMN_OWNER_TOKEN = ? AND $RUN_COLUMN_STATE = ?",
                arrayOf(runId, ownerToken, RunState.QUEUED.wireValue)
            )
            db.setTransactionSuccessful()
            updated == 1
        } finally {
            db.endTransaction()
            db.close()
            handler.close()
        }
    }

    private fun queueRun(
        requestedAt: Long,
        dueAt: Long?,
        resolveProfile: (DatabaseHandler, SQLiteDatabase) -> Pair<ProfileRecord, String?>
    ): RunRecord {
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        val runId = UUID.randomUUID().toString()
        val ownerToken = UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            val (profile, filterSnapshot) = resolveProfile(handler, db)
            validateQueueEligibility(profile)
            if (activeRunExists(db, profile.profileId)) {
                throw RunRejectedException("A run already owns this profile")
            }
            val now = System.currentTimeMillis()
            val values = ContentValues()
            values.put(RUN_COLUMN_ID, runId)
            values.put(RUN_COLUMN_PROFILE_ID, profile.profileId)
            values.put(RUN_COLUMN_PROFILE_REVISION, profile.revision)
            values.put(RUN_COLUMN_PROFILE_FINGERPRINT, profile.fingerprint)
            values.put(RUN_COLUMN_REQUESTED_MODE, profile.mode.wireValue)
            values.put(RUN_COLUMN_ENDPOINT, profile.endpointIdentity)
            values.put(RUN_COLUMN_SETTINGS, profile.settings)
            if (filterSnapshot == null) values.putNull(RUN_COLUMN_FILTER_SNAPSHOT)
            else values.put(RUN_COLUMN_FILTER_SNAPSHOT, filterSnapshot)
            values.put(RUN_COLUMN_ENGINE, profile.engineRef)
            values.put(RUN_COLUMN_STATE, RunState.QUEUED.wireValue)
            values.putNull(RUN_COLUMN_REASON)
            values.put(RUN_COLUMN_REQUESTED_AT, requestedAt)
            if (dueAt == null) values.putNull(RUN_COLUMN_DUE_AT) else values.put(RUN_COLUMN_DUE_AT, dueAt)
            values.putNull(RUN_COLUMN_STARTED_AT)
            values.putNull(RUN_COLUMN_FINISHED_AT)
            values.put(RUN_COLUMN_OWNER_TOKEN, ownerToken)
            values.put(RUN_COLUMN_OWNER_GENERATION, 0L)
            values.put(RUN_COLUMN_CANCEL_REQUESTED, 0)
            values.putNull(RUN_COLUMN_SUCCESSFUL_ITEMS)
            values.putNull(RUN_COLUMN_FAILED_ITEMS)
            values.putNull(RUN_COLUMN_CONFLICT_ITEMS)
            values.putNull(RUN_COLUMN_UNKNOWN_ITEMS)
            values.put(RUN_COLUMN_CREATED_AT, now)
            values.put(RUN_COLUMN_UPDATED_AT, now)
            db.insertOrThrow(RUN_TABLE_NAME, null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        return get(runId) ?: throw RunRejectedException("Queued run could not be read back")
    }

    /** Claim the queued row and verify the profile has not been edited or retired. */
    fun claim(runId: String, ownerToken: String, taskIdentity: RunTaskIdentity): RunRecord {
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        var rejection: String? = null
        var transactionStarted = false
        try {
            db.beginTransaction()
            transactionStarted = true
            val run = get(db, runId) ?: throw RunRejectedException("Run no longer exists")
            if (run.ownerToken != ownerToken || run.state != RunState.QUEUED) {
                throw RunRejectedException("Run owner is stale")
            }
            if (run.cancellationRequested) {
                if (!cancelQueuedRun(db, run, System.currentTimeMillis())) {
                    throw RunRejectedException("Queued cancellation could not be recorded")
                }
                rejection = "Run was cancelled before execution"
            } else {
                val profile = ProfileStore.getById(db, run.profileId)
                if (profile == null || profile.revision != run.profileRevision ||
                    profile.fingerprint != run.profileFingerprint ||
                    profile.readiness == ProfileReadiness.RECOVERY_REQUIRED ||
                    profile.readiness == ProfileReadiness.BLOCKED ||
                    profile.readiness == ProfileReadiness.INITIALIZATION_REQUIRED) {
                    moveToRecovery(db, run, "Profile changed or is not ready for execution")
                    rejection = "Profile changed or requires recovery"
                } else {
                    val identityMatch = requestMatchesRun(db, handler, run, profile, taskIdentity)
                    if (!identityMatch.matches) {
                        // Roll back the QUEUED row unchanged. A stale or substituted payload must never
                        // terminalize some other run merely because it carries that run's owner token.
                        throw RunRejectedException("Task payload does not match its durable run identity")
                    }
                    val values = ContentValues()
                    values.put(RUN_COLUMN_STATE, RunState.PREFLIGHT.wireValue)
                    values.put(RUN_COLUMN_OWNER_GENERATION, run.ownerGeneration + 1)
                    values.put(RUN_COLUMN_UPDATED_AT, System.currentTimeMillis())
                    if (run.filterSnapshot == null && identityMatch.filterSnapshot != null) {
                        // v16 queued rows have no historical rules. Backfill only when the current
                        // task/filter identity reproduces this run's original fingerprint.
                        values.put(RUN_COLUMN_FILTER_SNAPSHOT, identityMatch.filterSnapshot)
                    }
                    val updated = db.update(
                        RUN_TABLE_NAME,
                        values,
                        "$RUN_COLUMN_ID = ? AND $RUN_COLUMN_OWNER_TOKEN = ? AND $RUN_COLUMN_STATE = ? AND $RUN_COLUMN_OWNER_GENERATION = ? AND $RUN_COLUMN_CANCEL_REQUESTED = 0",
                        arrayOf(runId, ownerToken, RunState.QUEUED.wireValue, run.ownerGeneration.toString())
                    )
                    if (updated != 1) throw RunRejectedException("Run was claimed by another owner")
                }
            }
            db.setTransactionSuccessful()
        } finally {
            try {
                if (transactionStarted) db.endTransaction()
            } finally {
                // Close the helper even if transaction finalization fails; it owns the DB handle.
                handler.close()
            }
        }
        if (rejection != null) throw RunRejectedException(rejection!!)
        return get(runId) ?: throw RunRejectedException("Claimed run could not be read back")
    }

    private fun requestMatchesRun(
        db: SQLiteDatabase,
        handler: DatabaseHandler,
        run: RunRecord,
        profile: ProfileRecord,
        taskIdentity: RunTaskIdentity
    ): RequestIdentityMatch {
        return when (taskIdentity) {
            is RunTaskIdentity.LegacyTask -> {
                val task = handler.getTaskInTransaction(db, taskIdentity.taskId)
                    ?: return RequestIdentityMatch(false, null)
                val filterSnapshot = run.filterSnapshot
                    ?: ProfileStore.filterSnapshot(db, task.filterId)
                val requested = ProfileStore.profileSpecForFilterSnapshot(
                    task,
                    run.engineRef,
                    filterSnapshot
                )
                val matches = RunTaskIdentityPolicy.matchesLegacyTask(
                    profile.legacyTaskId,
                    taskIdentity.taskId,
                    profile.fingerprint,
                    run.profileFingerprint,
                    requested.fingerprint
                )
                RequestIdentityMatch(matches, filterSnapshot.takeIf { matches && task.filterId != null })
            }
            is RunTaskIdentity.EphemeralTask -> {
                val task = taskIdentity.task
                val filterSnapshot = run.filterSnapshot
                    ?: ProfileStore.filterSnapshot(db, task.filterId)
                val requested = ProfileStore.profileSpecForFilterSnapshot(
                    task,
                    run.engineRef,
                    filterSnapshot
                )
                val requestedProfileId = LegacyProfileMapper.stableIdForEphemeralTask(requested.fingerprint)
                val matches = RunTaskIdentityPolicy.matchesEphemeralTask(
                    profile.legacyTaskId,
                    profile.profileId,
                    run.profileId,
                    profile.fingerprint,
                    run.profileFingerprint,
                    requestedProfileId,
                    requested.fingerprint
                )
                RequestIdentityMatch(matches, filterSnapshot.takeIf { matches && task.filterId != null })
            }
        }
    }

    private data class RequestIdentityMatch(
        val matches: Boolean,
        val filterSnapshot: String?
    )

    fun markRunning(runId: String, ownerToken: String): Boolean = updateOwned(
        runId,
        ownerToken,
        RunState.PREFLIGHT,
        RunState.RUNNING,
        ContentValues().apply {
            put(RUN_COLUMN_STARTED_AT, System.currentTimeMillis())
            put(RUN_COLUMN_UPDATED_AT, System.currentTimeMillis())
        },
        "$RUN_COLUMN_CANCEL_REQUESTED = 0"
    )

    fun finish(
        runId: String,
        ownerToken: String,
        state: RunState,
        reason: String? = null,
        successfulItems: Long? = null,
        failedItems: Long? = null,
        conflictItems: Long? = null,
        unknownItems: Long? = null
    ): Boolean {
        require(state != RunState.QUEUED && state != RunState.PREFLIGHT && state != RunState.RUNNING) {
            "finish requires a terminal or conservative recovery state"
        }
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        db.beginTransaction()
        return try {
            val run = get(db, runId)
            if (run == null || run.ownerToken != ownerToken ||
                (run.state != RunState.PREFLIGHT && run.state != RunState.RUNNING)) {
                db.setTransactionSuccessful()
                return false
            }
            // A cancellation observed in PREFLIGHT means markRunning did not acquire the
            // execution phase, so native work was not admitted. Preserve that outcome even
            // when the caller reports the failed phase transition as a generic failure.
            val cancelledBeforeExecution = run.state == RunState.PREFLIGHT &&
                run.cancellationRequested && state != RunState.RECOVERY_REQUIRED
            val finalState = if (cancelledBeforeExecution) RunState.CANCELLED else state
            val finalReason = if (cancelledBeforeExecution) {
                "Cancellation requested before execution"
            } else {
                reason
            }
            val values = ContentValues()
            values.put(RUN_COLUMN_STATE, finalState.wireValue)
            if (finalReason == null) values.putNull(RUN_COLUMN_REASON) else values.put(RUN_COLUMN_REASON, finalReason)
            values.putNull(RUN_COLUMN_FILTER_SNAPSHOT)
            val finishedAt = System.currentTimeMillis()
            values.put(RUN_COLUMN_FINISHED_AT, finishedAt)
            values.put(RUN_COLUMN_UPDATED_AT, finishedAt)
            putNullableLong(values, RUN_COLUMN_SUCCESSFUL_ITEMS, successfulItems)
            putNullableLong(values, RUN_COLUMN_FAILED_ITEMS, failedItems)
            putNullableLong(values, RUN_COLUMN_CONFLICT_ITEMS, conflictItems)
            putNullableLong(values, RUN_COLUMN_UNKNOWN_ITEMS, unknownItems)
            val updated = db.update(
                RUN_TABLE_NAME,
                values,
                "$RUN_COLUMN_ID = ? AND $RUN_COLUMN_OWNER_TOKEN = ? AND $RUN_COLUMN_STATE = ? AND $RUN_COLUMN_OWNER_GENERATION = ?",
                arrayOf(runId, ownerToken, run.state.wireValue, run.ownerGeneration.toString())
            )
            if (updated == 1 && finalState == RunState.RECOVERY_REQUIRED) {
                val run = get(db, runId)
                if (run != null) {
                    val profileValues = ContentValues()
                    profileValues.put(DatabaseInfo.PROFILE_COLUMN_READINESS, ProfileReadiness.RECOVERY_REQUIRED.wireValue)
                    profileValues.put(DatabaseInfo.PROFILE_COLUMN_REASON, finalReason ?: "Native completion was not confirmed")
                    profileValues.put(DatabaseInfo.PROFILE_COLUMN_UPDATED_AT, System.currentTimeMillis())
                    db.update(
                        DatabaseInfo.PROFILE_TABLE_NAME,
                        profileValues,
                        "${DatabaseInfo.PROFILE_COLUMN_ID} = ?",
                        arrayOf(run.profileId)
                    )
                }
            }
            db.setTransactionSuccessful()
            updated == 1
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    fun requestCancellation(runId: String, ownerToken: String): Boolean {
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        db.beginTransaction()
        return try {
            val run = get(db, runId)
            if (run == null || run.ownerToken != ownerToken) {
                db.setTransactionSuccessful()
                return false
            }
            val now = System.currentTimeMillis()
            if (run.state == RunState.QUEUED) {
                val updated = cancelQueuedRun(db, run, now)
                db.setTransactionSuccessful()
                return updated
            }
            if (run.state != RunState.PREFLIGHT && run.state != RunState.RUNNING) {
                db.setTransactionSuccessful()
                return false
            }
            val values = ContentValues()
            values.put(RUN_COLUMN_CANCEL_REQUESTED, 1)
            values.put(RUN_COLUMN_UPDATED_AT, now)
            val updated = db.update(
                RUN_TABLE_NAME,
                values,
                "$RUN_COLUMN_ID = ? AND $RUN_COLUMN_OWNER_TOKEN = ? AND $RUN_COLUMN_STATE = ? AND $RUN_COLUMN_OWNER_GENERATION = ?",
                arrayOf(runId, ownerToken, run.state.wireValue, run.ownerGeneration.toString())
            )
            db.setTransactionSuccessful()
            updated == 1
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    /** A process restart cannot prove that a native process stopped; retain conservative ownership. */
    fun reconcileInterruptedRuns() {
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        db.beginTransaction()
        try {
            val cursor = db.query(
                RUN_TABLE_NAME,
                arrayOf(
                    RUN_COLUMN_ID,
                    RUN_COLUMN_PROFILE_ID,
                    RUN_COLUMN_OWNER_GENERATION,
                    RUN_COLUMN_STATE,
                    RUN_COLUMN_CANCEL_REQUESTED
                ),
                "$RUN_COLUMN_STATE IN ('PREFLIGHT','RUNNING') OR " +
                    "($RUN_COLUMN_STATE = 'QUEUED' AND $RUN_COLUMN_CANCEL_REQUESTED = 1)",
                null,
                null,
                null,
                null
            )
            val rows = ArrayList<RunReconciliationTarget>()
            try {
                while (cursor.moveToNext()) {
                    rows.add(
                        RunReconciliationTarget(
                            runId = cursor.getString(0),
                            profileId = cursor.getString(1),
                            ownerGeneration = cursor.getLong(2),
                            state = RunState.fromWireValue(cursor.getString(3)),
                            cancellationRequested = cursor.getInt(4) != 0
                        )
                    )
                }
            } finally {
                cursor.close()
            }
            for (target in rows) {
                val queuedCancellation = target.state == RunState.QUEUED && target.cancellationRequested
                val nextState = if (queuedCancellation) RunState.CANCELLED else RunState.INTERRUPTED
                val reason = if (queuedCancellation) {
                    "Cancellation requested before execution"
                } else {
                    "Application restarted before native completion was confirmed"
                }
                val now = System.currentTimeMillis()
                val values = ContentValues()
                values.put(RUN_COLUMN_STATE, nextState.wireValue)
                values.put(RUN_COLUMN_REASON, reason)
                values.put(RUN_COLUMN_FINISHED_AT, now)
                values.putNull(RUN_COLUMN_FILTER_SNAPSHOT)
                values.put(RUN_COLUMN_OWNER_GENERATION, target.ownerGeneration + 1)
                values.put(RUN_COLUMN_UPDATED_AT, now)
                val updated = db.update(
                    RUN_TABLE_NAME,
                    values,
                    "$RUN_COLUMN_ID = ? AND $RUN_COLUMN_STATE = ? AND $RUN_COLUMN_OWNER_GENERATION = ?",
                    arrayOf(target.runId, target.state.wireValue, target.ownerGeneration.toString())
                )
                if (updated != 1 || queuedCancellation) continue

                val profileValues = ContentValues()
                profileValues.put(DatabaseInfo.PROFILE_COLUMN_READINESS, ProfileReadiness.RECOVERY_REQUIRED.wireValue)
                profileValues.put(DatabaseInfo.PROFILE_COLUMN_REASON, "Interrupted run requires reconciliation")
                profileValues.put(DatabaseInfo.PROFILE_COLUMN_UPDATED_AT, now)
                db.update(
                    DatabaseInfo.PROFILE_TABLE_NAME,
                    profileValues,
                    "${DatabaseInfo.PROFILE_COLUMN_ID} = ?",
                    arrayOf(target.profileId)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    fun get(runId: String): RunRecord? {
        val handler = DatabaseHandler(context)
        val db = handler.readableDatabase
        return try {
            get(db, runId)
        } finally {
            db.close()
        }
    }

    private fun updateOwned(
        runId: String,
        ownerToken: String,
        expected: RunState,
        next: RunState,
        values: ContentValues,
        additionalSelection: String? = null
    ): Boolean {
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        db.beginTransaction()
        return try {
            values.put(RUN_COLUMN_STATE, next.wireValue)
            val selection = "$RUN_COLUMN_ID = ? AND $RUN_COLUMN_OWNER_TOKEN = ? AND $RUN_COLUMN_STATE = ?" +
                (additionalSelection?.let { " AND ($it)" } ?: "")
            val updated = db.update(
                RUN_TABLE_NAME,
                values,
                selection,
                arrayOf(runId, ownerToken, expected.wireValue)
            )
            db.setTransactionSuccessful()
            updated == 1
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    private fun validateQueueEligibility(profile: ProfileRecord) {
        if (profile.mode == ProfileMode.BISYNC || profile.mode == ProfileMode.UNKNOWN ||
            profile.readiness == ProfileReadiness.RECOVERY_REQUIRED ||
            profile.readiness == ProfileReadiness.BLOCKED ||
            profile.readiness == ProfileReadiness.INITIALIZATION_REQUIRED) {
            throw RunRejectedException(profile.reason ?: "Profile requires repair before execution")
        }
    }

    private fun activeRunExists(db: SQLiteDatabase, profileId: String): Boolean {
        val cursor = db.query(
            RUN_TABLE_NAME,
            arrayOf(RUN_COLUMN_ID),
            "$RUN_COLUMN_PROFILE_ID = ? AND $RUN_COLUMN_STATE IN ('QUEUED','PREFLIGHT','RUNNING')",
            arrayOf(profileId),
            null,
            null,
            null,
            "1"
        )
        return try {
            cursor.moveToFirst()
        } finally {
            cursor.close()
        }
    }

    private fun moveToRecovery(db: SQLiteDatabase, run: RunRecord, reason: String) {
        val values = ContentValues()
        values.put(RUN_COLUMN_STATE, RunState.RECOVERY_REQUIRED.wireValue)
        values.put(RUN_COLUMN_REASON, reason)
        values.putNull(RUN_COLUMN_FILTER_SNAPSHOT)
        values.put(RUN_COLUMN_FINISHED_AT, System.currentTimeMillis())
        values.put(RUN_COLUMN_OWNER_GENERATION, run.ownerGeneration + 1)
        values.put(RUN_COLUMN_UPDATED_AT, System.currentTimeMillis())
        db.update(RUN_TABLE_NAME, values, "$RUN_COLUMN_ID = ?", arrayOf(run.runId))
    }

    private fun cancelQueuedRun(db: SQLiteDatabase, run: RunRecord, cancelledAt: Long): Boolean {
        val values = ContentValues().apply {
            put(RUN_COLUMN_STATE, RunState.CANCELLED.wireValue)
            put(RUN_COLUMN_REASON, "Cancellation requested before execution")
            put(RUN_COLUMN_CANCEL_REQUESTED, 1)
            putNull(RUN_COLUMN_FILTER_SNAPSHOT)
            put(RUN_COLUMN_FINISHED_AT, cancelledAt)
            put(RUN_COLUMN_OWNER_GENERATION, run.ownerGeneration + 1)
            put(RUN_COLUMN_UPDATED_AT, cancelledAt)
        }
        return db.update(
            RUN_TABLE_NAME,
            values,
            "$RUN_COLUMN_ID = ? AND $RUN_COLUMN_OWNER_TOKEN = ? AND $RUN_COLUMN_STATE = ? AND $RUN_COLUMN_OWNER_GENERATION = ?",
            arrayOf(run.runId, run.ownerToken, RunState.QUEUED.wireValue, run.ownerGeneration.toString())
        ) == 1
    }

    private data class RunReconciliationTarget(
        val runId: String,
        val profileId: String,
        val ownerGeneration: Long,
        val state: RunState,
        val cancellationRequested: Boolean
    )

    private fun get(db: SQLiteDatabase, runId: String): RunRecord? {
        val cursor = db.query(
            RUN_TABLE_NAME,
            projection,
            "$RUN_COLUMN_ID = ?",
            arrayOf(runId),
            null,
            null,
            null,
            "1"
        )
        return try {
            if (cursor.moveToFirst()) fromCursor(cursor) else null
        } finally {
            cursor.close()
        }
    }

    private fun putNullableLong(values: ContentValues, key: String, value: Long?) {
        if (value == null) values.putNull(key) else values.put(key, value)
    }

    private fun fromCursor(cursor: Cursor): RunRecord = RunRecord(
        runId = cursor.getString(0),
        profileId = cursor.getString(1),
        profileRevision = cursor.getLong(2),
        profileFingerprint = cursor.getString(3),
        requestedMode = ProfileMode.fromWireValue(cursor.getString(4)),
        endpointIdentity = cursor.getString(5),
        settings = cursor.getString(6),
        engineRef = cursor.getString(7),
        state = RunState.fromWireValue(cursor.getString(8)),
        reason = if (cursor.isNull(9)) null else cursor.getString(9),
        requestedAt = cursor.getLong(10),
        dueAt = if (cursor.isNull(11)) null else cursor.getLong(11),
        startedAt = if (cursor.isNull(12)) null else cursor.getLong(12),
        finishedAt = if (cursor.isNull(13)) null else cursor.getLong(13),
        ownerToken = cursor.getString(14),
        ownerGeneration = cursor.getLong(15),
        cancellationRequested = cursor.getInt(16) != 0,
        successfulItems = if (cursor.isNull(17)) null else cursor.getLong(17),
        failedItems = if (cursor.isNull(18)) null else cursor.getLong(18),
        conflictItems = if (cursor.isNull(19)) null else cursor.getLong(19),
        unknownItems = if (cursor.isNull(20)) null else cursor.getLong(20),
        createdAt = cursor.getLong(21),
        updatedAt = cursor.getLong(22),
        filterSnapshot = if (cursor.isNull(23)) null else cursor.getString(23)
    )

    private companion object {
        val projection = arrayOf(
            RUN_COLUMN_ID,
            RUN_COLUMN_PROFILE_ID,
            RUN_COLUMN_PROFILE_REVISION,
            RUN_COLUMN_PROFILE_FINGERPRINT,
            RUN_COLUMN_REQUESTED_MODE,
            RUN_COLUMN_ENDPOINT,
            RUN_COLUMN_SETTINGS,
            RUN_COLUMN_ENGINE,
            RUN_COLUMN_STATE,
            RUN_COLUMN_REASON,
            RUN_COLUMN_REQUESTED_AT,
            RUN_COLUMN_DUE_AT,
            RUN_COLUMN_STARTED_AT,
            RUN_COLUMN_FINISHED_AT,
            RUN_COLUMN_OWNER_TOKEN,
            RUN_COLUMN_OWNER_GENERATION,
            RUN_COLUMN_CANCEL_REQUESTED,
            RUN_COLUMN_SUCCESSFUL_ITEMS,
            RUN_COLUMN_FAILED_ITEMS,
            RUN_COLUMN_CONFLICT_ITEMS,
            RUN_COLUMN_UNKNOWN_ITEMS,
            RUN_COLUMN_CREATED_AT,
            RUN_COLUMN_UPDATED_AT,
            RUN_COLUMN_FILTER_SNAPSHOT
        )
    }
}
