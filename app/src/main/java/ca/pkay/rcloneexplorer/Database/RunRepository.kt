package ca.pkay.rcloneexplorer.Database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import ca.pkay.rcloneexplorer.BuildConfig
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_CANCEL_REQUESTED
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_CONFLICT_ITEMS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_CREATED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_DUE_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_ENDPOINT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_ENGINE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_FAILED_ITEMS
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
    private val engineRef = "rclone:${BuildConfig.RCLONE_ENGINE_VERSION}"

    fun queueLegacyTask(taskId: Long, requestedAt: Long = System.currentTimeMillis(), dueAt: Long? = null): RunRecord {
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        val runId = UUID.randomUUID().toString()
        val ownerToken = UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            val task = handler.getTaskInTransaction(db, taskId)
                ?: throw RunRejectedException("Task no longer exists")
            val profile = ProfileStore.upsertLegacyTask(db, task, engineRef)
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
    fun claim(runId: String, ownerToken: String): RunRecord {
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        var rejection: String? = null
        db.beginTransaction()
        try {
            val run = get(db, runId) ?: throw RunRejectedException("Run no longer exists")
            if (run.ownerToken != ownerToken || run.state != RunState.QUEUED) {
                throw RunRejectedException("Run owner is stale")
            }
            val profile = ProfileStore.getById(db, run.profileId)
            if (profile == null || profile.revision != run.profileRevision ||
                profile.fingerprint != run.profileFingerprint ||
                profile.readiness == ProfileReadiness.RECOVERY_REQUIRED ||
                profile.readiness == ProfileReadiness.BLOCKED ||
                profile.readiness == ProfileReadiness.INITIALIZATION_REQUIRED) {
                moveToRecovery(db, run, "Profile changed or is not ready for execution")
                rejection = "Profile changed or requires recovery"
            } else {
                val values = ContentValues()
                values.put(RUN_COLUMN_STATE, RunState.PREFLIGHT.wireValue)
                values.put(RUN_COLUMN_OWNER_GENERATION, run.ownerGeneration + 1)
                values.put(RUN_COLUMN_UPDATED_AT, System.currentTimeMillis())
                val updated = db.update(
                    RUN_TABLE_NAME,
                    values,
                    "$RUN_COLUMN_ID = ? AND $RUN_COLUMN_OWNER_TOKEN = ? AND $RUN_COLUMN_STATE = ?",
                    arrayOf(runId, ownerToken, RunState.QUEUED.wireValue)
                )
                if (updated != 1) throw RunRejectedException("Run was claimed by another owner")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        if (rejection != null) throw RunRejectedException(rejection!!)
        return get(runId) ?: throw RunRejectedException("Claimed run could not be read back")
    }

    fun markRunning(runId: String, ownerToken: String): Boolean = updateOwned(
        runId,
        ownerToken,
        RunState.PREFLIGHT,
        RunState.RUNNING,
        ContentValues().apply {
            put(RUN_COLUMN_STARTED_AT, System.currentTimeMillis())
            put(RUN_COLUMN_UPDATED_AT, System.currentTimeMillis())
        }
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
            val values = ContentValues()
            values.put(RUN_COLUMN_STATE, state.wireValue)
            if (reason == null) values.putNull(RUN_COLUMN_REASON) else values.put(RUN_COLUMN_REASON, reason)
            values.put(RUN_COLUMN_FINISHED_AT, System.currentTimeMillis())
            values.put(RUN_COLUMN_UPDATED_AT, System.currentTimeMillis())
            putNullableLong(values, RUN_COLUMN_SUCCESSFUL_ITEMS, successfulItems)
            putNullableLong(values, RUN_COLUMN_FAILED_ITEMS, failedItems)
            putNullableLong(values, RUN_COLUMN_CONFLICT_ITEMS, conflictItems)
            putNullableLong(values, RUN_COLUMN_UNKNOWN_ITEMS, unknownItems)
            val updated = db.update(
                RUN_TABLE_NAME,
                values,
                "$RUN_COLUMN_ID = ? AND $RUN_COLUMN_OWNER_TOKEN = ? AND " +
                        "$RUN_COLUMN_STATE IN ('PREFLIGHT','RUNNING','QUEUED')",
                arrayOf(runId, ownerToken)
            )
            if (updated == 1 && state == RunState.RECOVERY_REQUIRED) {
                val run = get(db, runId)
                if (run != null) {
                    val profileValues = ContentValues()
                    profileValues.put(DatabaseInfo.PROFILE_COLUMN_READINESS, ProfileReadiness.RECOVERY_REQUIRED.wireValue)
                    profileValues.put(DatabaseInfo.PROFILE_COLUMN_REASON, reason ?: "Native completion was not confirmed")
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
            val values = ContentValues()
            values.put(RUN_COLUMN_CANCEL_REQUESTED, 1)
            values.put(RUN_COLUMN_UPDATED_AT, System.currentTimeMillis())
            val updated = db.update(
                RUN_TABLE_NAME,
                values,
                "$RUN_COLUMN_ID = ? AND $RUN_COLUMN_OWNER_TOKEN = ? AND " +
                        "$RUN_COLUMN_STATE IN ('QUEUED','PREFLIGHT','RUNNING')",
                arrayOf(runId, ownerToken)
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
                arrayOf(RUN_COLUMN_ID, RUN_COLUMN_PROFILE_ID, RUN_COLUMN_OWNER_GENERATION),
                "$RUN_COLUMN_STATE IN ('PREFLIGHT','RUNNING')",
                null,
                null,
                null,
                null
            )
            val rows = ArrayList<Triple<String, String, Long>>()
            try {
                while (cursor.moveToNext()) {
                    rows.add(Triple(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
                }
            } finally {
                cursor.close()
            }
            for ((runId, profileId, generation) in rows) {
                val values = ContentValues()
                values.put(RUN_COLUMN_STATE, RunState.INTERRUPTED.wireValue)
                values.put(RUN_COLUMN_REASON, "Application restarted before native completion was confirmed")
                values.put(RUN_COLUMN_FINISHED_AT, System.currentTimeMillis())
                values.put(RUN_COLUMN_OWNER_GENERATION, generation + 1)
                values.put(RUN_COLUMN_UPDATED_AT, System.currentTimeMillis())
                db.update(RUN_TABLE_NAME, values, "$RUN_COLUMN_ID = ?", arrayOf(runId))

                val profileValues = ContentValues()
                profileValues.put(DatabaseInfo.PROFILE_COLUMN_READINESS, ProfileReadiness.RECOVERY_REQUIRED.wireValue)
                profileValues.put(DatabaseInfo.PROFILE_COLUMN_REASON, "Interrupted run requires reconciliation")
                profileValues.put(DatabaseInfo.PROFILE_COLUMN_UPDATED_AT, System.currentTimeMillis())
                db.update(
                    DatabaseInfo.PROFILE_TABLE_NAME,
                    profileValues,
                    "${DatabaseInfo.PROFILE_COLUMN_ID} = ?",
                    arrayOf(profileId)
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
        values: ContentValues
    ): Boolean {
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        db.beginTransaction()
        return try {
            values.put(RUN_COLUMN_STATE, next.wireValue)
            val updated = db.update(
                RUN_TABLE_NAME,
                values,
                "$RUN_COLUMN_ID = ? AND $RUN_COLUMN_OWNER_TOKEN = ? AND $RUN_COLUMN_STATE = ?",
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
        values.put(RUN_COLUMN_FINISHED_AT, System.currentTimeMillis())
        values.put(RUN_COLUMN_OWNER_GENERATION, run.ownerGeneration + 1)
        values.put(RUN_COLUMN_UPDATED_AT, System.currentTimeMillis())
        db.update(RUN_TABLE_NAME, values, "$RUN_COLUMN_ID = ?", arrayOf(run.runId))
    }

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
        updatedAt = cursor.getLong(22)
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
            RUN_COLUMN_UPDATED_AT
        )
    }
}
