package ca.pkay.rcloneexplorer.Database

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_CREATED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_ENDPOINT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_ENGINE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_FINGERPRINT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_ID
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_LEGACY_TASK_ID
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_MODE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_REASON
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_READINESS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_REVISION
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_SETTINGS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_TITLE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_UPDATED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_TABLE_NAME
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_FINISHED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_ID
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_OWNER_GENERATION
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_PROFILE_ID
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_REASON
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_STATE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_COLUMN_UPDATED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.RUN_TABLE_NAME
import ca.pkay.rcloneexplorer.Items.Task
import java.util.UUID

/** Low-level profile writes shared by the legacy adapter and repository. */
internal object ProfileStore {
    private val projection = arrayOf(
        PROFILE_COLUMN_ID,
        PROFILE_COLUMN_LEGACY_TASK_ID,
        PROFILE_COLUMN_REVISION,
        PROFILE_COLUMN_TITLE,
        PROFILE_COLUMN_MODE,
        PROFILE_COLUMN_ENDPOINT,
        PROFILE_COLUMN_SETTINGS,
        PROFILE_COLUMN_FINGERPRINT,
        PROFILE_COLUMN_ENGINE,
        PROFILE_COLUMN_READINESS,
        PROFILE_COLUMN_REASON,
        PROFILE_COLUMN_CREATED_AT,
        PROFILE_COLUMN_UPDATED_AT
    )

    fun upsertLegacyTask(db: SQLiteDatabase, task: Task, engineRef: String): ProfileRecord {
        val spec = LegacyProfileMapper.fromTask(task, engineRef)
        val existing = getByLegacyTaskId(db, task.id)
        if (existing == null) {
            var profileId = LegacyProfileMapper.stableIdForLegacyTask(task.id)
            if (getById(db, profileId) != null) {
                // A deleted legacy task keeps its old UUID as historical recovery state. Do not
                // let a reused numeric task id retarget that historical profile.
                profileId = UUID.randomUUID().toString()
            }
            val now = System.currentTimeMillis()
            db.insertOrThrow(
                PROFILE_TABLE_NAME,
                null,
                valuesFor(profileId, task.id, 1L, spec, now, now)
            )
        } else if (existing.fingerprint != spec.fingerprint) {
            val now = System.currentTimeMillis()
            val invalidated = invalidateActiveRuns(
                db,
                existing.profileId,
                "Profile changed; queued ownership was invalidated"
            )
            val readiness = if (invalidated > 0) {
                ProfileReadiness.RECOVERY_REQUIRED
            } else {
                spec.readiness
            }
            val reason = if (invalidated > 0) {
                "Profile changed while a run was owned; review the interrupted run"
            } else {
                spec.reason
            }
            val values = valuesFor(
                existing.profileId,
                task.id,
                existing.revision + 1,
                spec.copy(readiness = readiness, reason = reason),
                existing.createdAt,
                now
            )
            db.update(
                PROFILE_TABLE_NAME,
                values,
                "$PROFILE_COLUMN_ID = ?",
                arrayOf(existing.profileId)
            )
        }
        return requireNotNull(getByLegacyTaskId(db, task.id))
    }

    fun retireLegacyTask(db: SQLiteDatabase, taskId: Long) {
        val existing = getByLegacyTaskId(db, taskId) ?: return
        retireProfile(db, existing, "Legacy task was deleted; recovery is required")
    }

    fun reconcileImportedTasks(db: SQLiteDatabase, tasks: List<Task>, engineRef: String) {
        val retainedIds = tasks.map { it.id }.toHashSet()
        val cursor = db.query(
            PROFILE_TABLE_NAME,
            arrayOf(PROFILE_COLUMN_ID, PROFILE_COLUMN_LEGACY_TASK_ID),
            "$PROFILE_COLUMN_LEGACY_TASK_ID IS NOT NULL",
            null,
            null,
            null,
            null
        )
        val retired = ArrayList<ProfileRecord>()
        try {
            while (cursor.moveToNext()) {
                val taskId = cursor.getLong(1)
                if (!retainedIds.contains(taskId)) {
                    getById(db, cursor.getString(0))?.let { retired.add(it) }
                }
            }
        } finally {
            cursor.close()
        }
        for (profile in retired) {
            retireProfile(db, profile, "Legacy task was replaced by an import; recovery is required")
        }
        for (task in tasks) {
            upsertLegacyTask(db, task, engineRef)
        }
    }

    private fun retireProfile(db: SQLiteDatabase, existing: ProfileRecord, reason: String) {
        val now = System.currentTimeMillis()
        invalidateActiveRuns(db, existing.profileId, reason)
        val values = ContentValues()
        values.putNull(PROFILE_COLUMN_LEGACY_TASK_ID)
        values.put(PROFILE_COLUMN_REVISION, existing.revision + 1)
        values.put(PROFILE_COLUMN_READINESS, ProfileReadiness.RECOVERY_REQUIRED.wireValue)
        values.put(PROFILE_COLUMN_REASON, reason)
        values.put(PROFILE_COLUMN_UPDATED_AT, now)
        db.update(
            PROFILE_TABLE_NAME,
            values,
            "$PROFILE_COLUMN_ID = ?",
            arrayOf(existing.profileId)
        )
    }

    fun getByLegacyTaskId(db: SQLiteDatabase, taskId: Long): ProfileRecord? = query(
        db,
        "$PROFILE_COLUMN_LEGACY_TASK_ID = ?",
        arrayOf(taskId.toString())
    )

    fun getById(db: SQLiteDatabase, profileId: String): ProfileRecord? = query(
        db,
        "$PROFILE_COLUMN_ID = ?",
        arrayOf(profileId)
    )

    fun invalidateActiveRuns(db: SQLiteDatabase, profileId: String, reason: String): Int {
        val values = ContentValues()
        values.put(RUN_COLUMN_STATE, RunState.RECOVERY_REQUIRED.wireValue)
        values.put(RUN_COLUMN_REASON, reason)
        values.put(RUN_COLUMN_FINISHED_AT, System.currentTimeMillis())
        values.put(RUN_COLUMN_UPDATED_AT, System.currentTimeMillis())
        val cursor = db.query(
            RUN_TABLE_NAME,
            arrayOf(RUN_COLUMN_ID, RUN_COLUMN_OWNER_GENERATION),
            "$RUN_COLUMN_PROFILE_ID = ? AND $RUN_COLUMN_STATE IN ('QUEUED','PREFLIGHT','RUNNING')",
            arrayOf(profileId),
            null,
            null,
            null
        )
        val generations = ArrayList<Pair<String, Long>>()
        try {
            while (cursor.moveToNext()) {
                generations.add(Pair(cursor.getString(0), cursor.getLong(1) + 1))
            }
        } finally {
            cursor.close()
        }
        if (generations.isEmpty()) {
            return 0
        }
        // Update state/reason first; the owner generation is advanced conservatively in a
        // second statement so a late worker callback cannot claim the invalidated row.
        db.update(
            RUN_TABLE_NAME,
            values,
            "$RUN_COLUMN_PROFILE_ID = ? AND $RUN_COLUMN_STATE IN ('QUEUED','PREFLIGHT','RUNNING')",
            arrayOf(profileId)
        )
        for ((runId, generation) in generations) {
            val generationValues = ContentValues()
            generationValues.put(RUN_COLUMN_OWNER_GENERATION, generation)
            db.update(
                RUN_TABLE_NAME,
                generationValues,
                "$RUN_COLUMN_ID = ?",
                arrayOf(runId)
            )
        }
        return generations.size
    }

    private fun valuesFor(
        profileId: String,
        legacyTaskId: Long?,
        revision: Long,
        spec: ProfileSpec,
        createdAt: Long,
        updatedAt: Long
    ): ContentValues {
        val values = ContentValues()
        values.put(PROFILE_COLUMN_ID, profileId)
        if (legacyTaskId == null) values.putNull(PROFILE_COLUMN_LEGACY_TASK_ID)
        else values.put(PROFILE_COLUMN_LEGACY_TASK_ID, legacyTaskId)
        values.put(PROFILE_COLUMN_REVISION, revision)
        values.put(PROFILE_COLUMN_TITLE, spec.title)
        values.put(PROFILE_COLUMN_MODE, spec.mode.wireValue)
        values.put(PROFILE_COLUMN_ENDPOINT, spec.endpointIdentity)
        values.put(PROFILE_COLUMN_SETTINGS, spec.settings)
        values.put(PROFILE_COLUMN_FINGERPRINT, spec.fingerprint)
        values.put(PROFILE_COLUMN_ENGINE, spec.engineRef)
        values.put(PROFILE_COLUMN_READINESS, spec.readiness.wireValue)
        if (spec.reason == null) values.putNull(PROFILE_COLUMN_REASON)
        else values.put(PROFILE_COLUMN_REASON, spec.reason)
        values.put(PROFILE_COLUMN_CREATED_AT, createdAt)
        values.put(PROFILE_COLUMN_UPDATED_AT, updatedAt)
        return values
    }

    private fun query(db: SQLiteDatabase, selection: String, args: Array<String>): ProfileRecord? {
        val cursor = db.query(
            PROFILE_TABLE_NAME,
            projection,
            selection,
            args,
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

    private fun fromCursor(cursor: Cursor): ProfileRecord = ProfileRecord(
        profileId = cursor.getString(0),
        legacyTaskId = if (cursor.isNull(1)) null else cursor.getLong(1),
        revision = cursor.getLong(2),
        title = cursor.getString(3),
        mode = ProfileMode.fromWireValue(cursor.getString(4)),
        endpointIdentity = cursor.getString(5),
        settings = cursor.getString(6),
        fingerprint = cursor.getString(7),
        engineRef = cursor.getString(8),
        readiness = ProfileReadiness.fromWireValue(cursor.getString(9)),
        reason = if (cursor.isNull(10)) null else cursor.getString(10),
        createdAt = cursor.getLong(11),
        updatedAt = cursor.getLong(12)
    )
}
