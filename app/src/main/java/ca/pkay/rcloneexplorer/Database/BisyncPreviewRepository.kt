package ca.pkay.rcloneexplorer.Database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_COMPARISON
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_COMPLETED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_CONFLICTS_KNOWN
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_ENGINE_REF
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_ERROR_COUNT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_FAILURE_CODE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_FILTER
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_ID
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_IDENTITY_FINGERPRINT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_INITIALIZATION_MODE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_LEFT_ACCOUNT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_LEFT_SCOPE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_MAX_DELETE_COUNT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_MAX_DELETE_PERCENT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_NATIVE_STATE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_OWNER_GENERATION
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_OWNER_TOKEN
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_PLANNED_BYTES
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_PROFILE_FINGERPRINT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_PROFILE_ID
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_PROFILE_REVISION
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_REQUESTED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_RIGHT_ACCOUNT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_RIGHT_SCOPE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_STARTED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_STATE_VERSION
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_STATUS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_TABLE_NAME
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREVIEW_COLUMN_UPDATED_AT
import java.util.UUID

/** Durable, profile-scoped owner and path-free result history for Bisync preview operations. */
class BisyncPreviewRepository(context: Context) {
    private val context = context.applicationContext

    /**
     * Persists a preview request only for an already resolved, current Bisync snapshot. The
     * partial unique index is the final cross-process guard against a second owner.
     */
    fun queue(identity: BisyncPreviewIdentity, requestedAt: Long = System.currentTimeMillis()): BisyncPreviewOperation {
        require(requestedAt > 0L) { "Preview request time must be positive" }
        require(hasExplicitPreviewPolicy(identity)) {
            "An absent-state preview must bind an explicit initialization preference"
        }
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        val previewId = UUID.randomUUID().toString()
        val ownerToken = UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            val profile = ProfileStore.getById(db, identity.profileId)
                ?: throw BisyncPreviewRejectedException("Bisync profile no longer exists")
            validateCurrentProfile(profile, identity)
            if (profile.readiness != ProfileReadiness.READY &&
                profile.readiness != ProfileReadiness.INITIALIZATION_REQUIRED) {
                throw BisyncPreviewRejectedException("Bisync profile requires preflight or recovery before preview")
            }
            if (activePreviewExists(db, identity.profileId)) {
                throw BisyncPreviewRejectedException("A preview already owns this profile")
            }
            val values = identityValues(identity).apply {
                put(BISYNC_PREVIEW_COLUMN_ID, previewId)
                put(BISYNC_PREVIEW_COLUMN_STATUS, BisyncPreviewOperationState.QUEUED.wireValue)
                put(BISYNC_PREVIEW_COLUMN_OWNER_TOKEN, ownerToken)
                put(BISYNC_PREVIEW_COLUMN_OWNER_GENERATION, 0L)
                put(BISYNC_PREVIEW_COLUMN_REQUESTED_AT, requestedAt)
                put(BISYNC_PREVIEW_COLUMN_UPDATED_AT, requestedAt)
                putNull(BISYNC_PREVIEW_COLUMN_STARTED_AT)
                putNull(BISYNC_PREVIEW_COLUMN_COMPLETED_AT)
                putNull(BISYNC_PREVIEW_COLUMN_FAILURE_CODE)
                putNull(BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS)
                putNull(BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS)
                putNull(BISYNC_PREVIEW_COLUMN_PLANNED_BYTES)
                putNull(BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES)
                putNull(BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES)
                putNull(BISYNC_PREVIEW_COLUMN_ERROR_COUNT)
                put(BISYNC_PREVIEW_COLUMN_CONFLICTS_KNOWN, 0)
            }
            try {
                db.insertOrThrow(BISYNC_PREVIEW_TABLE_NAME, null, values)
            } catch (failure: android.database.sqlite.SQLiteConstraintException) {
                if (activePreviewExists(db, identity.profileId)) {
                    throw BisyncPreviewRejectedException("A preview already owns this profile")
                }
                throw failure
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
            handler.close()
        }
        return get(previewId) ?: throw BisyncPreviewRejectedException("Queued preview could not be read back")
    }

    /** Claims a queued owner only after a fresh preflight has rebuilt the exact captured identity. */
    fun claim(
        previewId: String,
        ownerToken: String,
        currentIdentity: BisyncPreviewIdentity,
        claimedAt: Long = System.currentTimeMillis()
    ): BisyncPreviewOperation {
        require(claimedAt > 0L)
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        var rejected: String? = null
        db.beginTransaction()
        try {
            val operation = get(db, previewId) ?: throw BisyncPreviewRejectedException("Preview no longer exists")
            if (operation.ownerToken != ownerToken || operation.state != BisyncPreviewOperationState.QUEUED) {
                throw BisyncPreviewRejectedException("Preview owner is stale")
            }
            val profile = ProfileStore.getById(db, operation.identity.profileId)
            val mismatch = profile == null || !profileMatches(profile, operation.identity) ||
                !hasExplicitPreviewPolicy(operation.identity) ||
                operation.identity != currentIdentity
            if (mismatch) {
                writeTerminal(db, operation, BisyncPreviewOperationState.STALE,
                    BisyncPreviewFailureCode.IDENTITY_CHANGED, claimedAt)
                rejected = "Profile or preflight identity changed before preview start"
            } else {
                val values = ContentValues().apply {
                    put(BISYNC_PREVIEW_COLUMN_STATUS, BisyncPreviewOperationState.RUNNING.wireValue)
                    put(BISYNC_PREVIEW_COLUMN_OWNER_GENERATION, operation.ownerGeneration + 1L)
                    put(BISYNC_PREVIEW_COLUMN_STARTED_AT, claimedAt)
                    put(BISYNC_PREVIEW_COLUMN_UPDATED_AT, claimedAt)
                }
                val updated = db.update(
                    BISYNC_PREVIEW_TABLE_NAME,
                    values,
                    "$BISYNC_PREVIEW_COLUMN_ID = ? AND $BISYNC_PREVIEW_COLUMN_OWNER_TOKEN = ? AND $BISYNC_PREVIEW_COLUMN_STATUS = ?",
                    arrayOf(previewId, ownerToken, BisyncPreviewOperationState.QUEUED.wireValue)
                )
                if (updated != 1) throw BisyncPreviewRejectedException("Preview was claimed by another owner")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
            handler.close()
        }
        rejected?.let { throw BisyncPreviewRejectedException(it) }
        return get(previewId) ?: throw BisyncPreviewRejectedException("Claimed preview could not be read back")
    }

    /**
     * Finalizes only the exact running owner generation. A lost process completion signal leaves
     * the profile locked in RECOVERY_REQUIRED; it is never converted into a cancellation/success.
     */
    fun finish(
        previewId: String,
        ownerToken: String,
        ownerGeneration: Long,
        currentIdentity: BisyncPreviewIdentity?,
        parsed: BisyncPreviewParseResult,
        processStoppedConfirmed: Boolean,
        completedAt: Long = System.currentTimeMillis()
    ): Boolean {
        require(completedAt > 0L)
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        db.beginTransaction()
        return try {
            val operation = get(db, previewId) ?: return false
            if (operation.state != BisyncPreviewOperationState.RUNNING ||
                operation.ownerToken != ownerToken || operation.ownerGeneration != ownerGeneration) {
                return false
            }
            val values = ContentValues()
            values.put(BISYNC_PREVIEW_COLUMN_UPDATED_AT, completedAt)
            if (!processStoppedConfirmed) {
                values.put(BISYNC_PREVIEW_COLUMN_STATUS, BisyncPreviewOperationState.RECOVERY_REQUIRED.wireValue)
                values.put(BISYNC_PREVIEW_COLUMN_FAILURE_CODE, BisyncPreviewFailureCode.CANCELLED_UNCONFIRMED.wireValue)
                values.put(BISYNC_PREVIEW_COLUMN_OWNER_GENERATION, ownerGeneration + 1L)
                values.putNull(BISYNC_PREVIEW_COLUMN_COMPLETED_AT)
                clearSummary(values)
            } else {
                val currentProfile = ProfileStore.getById(db, operation.identity.profileId)
                val identityMatches = currentProfile != null && currentIdentity == operation.identity &&
                    profileMatches(currentProfile, operation.identity)
                val targetState: BisyncPreviewOperationState
                val failureCode: BisyncPreviewFailureCode?
                val summary: BisyncPreviewSummary?
                if (!identityMatches) {
                    targetState = BisyncPreviewOperationState.STALE
                    failureCode = BisyncPreviewFailureCode.IDENTITY_CHANGED
                    summary = (parsed as? BisyncPreviewParseResult.Available)?.summary
                } else when (parsed) {
                    is BisyncPreviewParseResult.Available -> {
                        summary = parsed.summary
                        if (summary.status == BisyncPreviewStatus.COMPLETE) {
                            targetState = BisyncPreviewOperationState.COMPLETE
                            failureCode = null
                        } else {
                            targetState = BisyncPreviewOperationState.INCOMPLETE
                            failureCode = null
                        }
                    }
                    is BisyncPreviewParseResult.Unavailable -> {
                        targetState = BisyncPreviewOperationState.UNAVAILABLE
                        failureCode = BisyncPreviewFailureCode.values()
                            .firstOrNull { it.wireValue == parsed.reason.name }
                            ?: BisyncPreviewFailureCode.PROCESS_FAILED
                        summary = null
                    }
                }
                values.put(BISYNC_PREVIEW_COLUMN_STATUS, targetState.wireValue)
                if (failureCode == null) values.putNull(BISYNC_PREVIEW_COLUMN_FAILURE_CODE)
                else values.put(BISYNC_PREVIEW_COLUMN_FAILURE_CODE, failureCode.wireValue)
                values.put(BISYNC_PREVIEW_COLUMN_COMPLETED_AT, completedAt)
                writeSummary(values, summary)
            }
            val updated = db.update(
                BISYNC_PREVIEW_TABLE_NAME,
                values,
                "$BISYNC_PREVIEW_COLUMN_ID = ? AND $BISYNC_PREVIEW_COLUMN_OWNER_TOKEN = ? AND $BISYNC_PREVIEW_COLUMN_OWNER_GENERATION = ? AND $BISYNC_PREVIEW_COLUMN_STATUS = ?",
                arrayOf(previewId, ownerToken, ownerGeneration.toString(), BisyncPreviewOperationState.RUNNING.wireValue)
            )
            db.setTransactionSuccessful()
            updated == 1
        } finally {
            db.endTransaction()
            db.close()
            handler.close()
        }
    }

    /** Mark an in-flight preview uncertain after process restart; it continues to block a retry. */
    fun reconcileInterruptedRuns(at: Long = System.currentTimeMillis()): Int {
        require(at > 0L)
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        db.beginTransaction()
        return try {
            // ContentValues cannot express an SQL increment; perform the state transition row by row.
            val cursor = db.query(
                BISYNC_PREVIEW_TABLE_NAME,
                arrayOf(BISYNC_PREVIEW_COLUMN_ID, BISYNC_PREVIEW_COLUMN_OWNER_GENERATION),
                "$BISYNC_PREVIEW_COLUMN_STATUS = ?",
                arrayOf(BisyncPreviewOperationState.RUNNING.wireValue),
                null, null, null
            )
            val rows = ArrayList<Pair<String, Long>>()
            try {
                while (cursor.moveToNext()) rows.add(Pair(cursor.getString(0), cursor.getLong(1)))
            } finally {
                cursor.close()
            }
            for ((id, generation) in rows) {
                val row = ContentValues().apply {
                    put(BISYNC_PREVIEW_COLUMN_STATUS, BisyncPreviewOperationState.INTERRUPTED.wireValue)
                    put(BISYNC_PREVIEW_COLUMN_FAILURE_CODE, BisyncPreviewFailureCode.OWNER_LOST.wireValue)
                    put(BISYNC_PREVIEW_COLUMN_OWNER_GENERATION, generation + 1L)
                    put(BISYNC_PREVIEW_COLUMN_UPDATED_AT, at)
                }
                db.update(
                    BISYNC_PREVIEW_TABLE_NAME,
                    row,
                    "$BISYNC_PREVIEW_COLUMN_ID = ? AND $BISYNC_PREVIEW_COLUMN_STATUS = ?",
                    arrayOf(id, BisyncPreviewOperationState.RUNNING.wireValue)
                )
            }
            db.setTransactionSuccessful()
            rows.size
        } finally {
            db.endTransaction()
            db.close()
            handler.close()
        }
    }

    fun get(previewId: String): BisyncPreviewOperation? {
        val handler = DatabaseHandler(context)
        val db = handler.readableDatabase
        return try {
            get(db, previewId)
        } finally {
            db.close()
            handler.close()
        }
    }

    fun latest(profileId: String): BisyncPreviewOperation? {
        val handler = DatabaseHandler(context)
        val db = handler.readableDatabase
        return try {
            val cursor = db.query(
                BISYNC_PREVIEW_TABLE_NAME,
                projection,
                "$BISYNC_PREVIEW_COLUMN_PROFILE_ID = ?",
                arrayOf(profileId),
                null,
                null,
                "$BISYNC_PREVIEW_COLUMN_REQUESTED_AT DESC",
                "1"
            )
            try {
                if (cursor.moveToFirst()) fromCursor(cursor) else null
            } finally {
                cursor.close()
            }
        } finally {
            db.close()
            handler.close()
        }
    }

    private fun activePreviewExists(db: SQLiteDatabase, profileId: String): Boolean {
        val cursor = db.query(
            BISYNC_PREVIEW_TABLE_NAME,
            arrayOf(BISYNC_PREVIEW_COLUMN_ID),
            "$BISYNC_PREVIEW_COLUMN_PROFILE_ID = ? AND $BISYNC_PREVIEW_COLUMN_STATUS IN ('QUEUED','RUNNING','INTERRUPTED','RECOVERY_REQUIRED')",
            arrayOf(profileId), null, null, null, "1"
        )
        return try { cursor.moveToFirst() } finally { cursor.close() }
    }

    private fun validateCurrentProfile(profile: ProfileRecord, identity: BisyncPreviewIdentity) {
        if (profile.mode != ProfileMode.BISYNC || !profileMatches(profile, identity)) {
            throw BisyncPreviewRejectedException("Preview identity does not match the current Bisync profile")
        }
    }

    private fun profileMatches(profile: ProfileRecord, identity: BisyncPreviewIdentity): Boolean =
        profile.mode == ProfileMode.BISYNC &&
            profile.profileId == identity.profileId &&
            profile.revision == identity.profileRevision &&
            profile.fingerprint == identity.profileFingerprint &&
            profile.engineRef == identity.engineRef &&
            profile.readiness == when (identity.nativeState) {
                BisyncNativeState.COMPATIBLE -> ProfileReadiness.READY
                BisyncNativeState.ABSENT -> ProfileReadiness.INITIALIZATION_REQUIRED
                else -> ProfileReadiness.RECOVERY_REQUIRED
            }

    private fun identityValues(identity: BisyncPreviewIdentity) = ContentValues().apply {
        put(BISYNC_PREVIEW_COLUMN_PROFILE_ID, identity.profileId)
        put(BISYNC_PREVIEW_COLUMN_PROFILE_REVISION, identity.profileRevision)
        put(BISYNC_PREVIEW_COLUMN_PROFILE_FINGERPRINT, identity.profileFingerprint)
        put(BISYNC_PREVIEW_COLUMN_ENGINE_REF, identity.engineRef)
        put(BISYNC_PREVIEW_COLUMN_STATE_VERSION, identity.stateVersion)
        put(BISYNC_PREVIEW_COLUMN_LEFT_ACCOUNT, identity.leftAccountFingerprint)
        put(BISYNC_PREVIEW_COLUMN_LEFT_SCOPE, identity.leftScopeFingerprint)
        put(BISYNC_PREVIEW_COLUMN_RIGHT_ACCOUNT, identity.rightAccountFingerprint)
        put(BISYNC_PREVIEW_COLUMN_RIGHT_SCOPE, identity.rightScopeFingerprint)
        put(BISYNC_PREVIEW_COLUMN_FILTER, identity.filterFingerprint)
        put(BISYNC_PREVIEW_COLUMN_COMPARISON, identity.comparisonMode.wireValue)
        put(BISYNC_PREVIEW_COLUMN_MAX_DELETE_PERCENT, identity.maxDeletePercent)
        put(BISYNC_PREVIEW_COLUMN_MAX_DELETE_COUNT, identity.maxDeleteCount)
        put(BISYNC_PREVIEW_COLUMN_NATIVE_STATE, identity.nativeState.name)
        if (identity.initializationMode == null) putNull(BISYNC_PREVIEW_COLUMN_INITIALIZATION_MODE)
        else put(BISYNC_PREVIEW_COLUMN_INITIALIZATION_MODE, identity.initializationMode.wireValue)
        if (identity.acceptedBaselineFingerprint == null) putNull(BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE)
        else put(BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE, identity.acceptedBaselineFingerprint)
        put(BISYNC_PREVIEW_COLUMN_IDENTITY_FINGERPRINT, identity.fingerprint)
    }

    private fun writeTerminal(
        db: SQLiteDatabase,
        operation: BisyncPreviewOperation,
        state: BisyncPreviewOperationState,
        failure: BisyncPreviewFailureCode,
        at: Long
    ) {
        val values = ContentValues().apply {
            put(BISYNC_PREVIEW_COLUMN_STATUS, state.wireValue)
            put(BISYNC_PREVIEW_COLUMN_FAILURE_CODE, failure.wireValue)
            put(BISYNC_PREVIEW_COLUMN_COMPLETED_AT, at)
            put(BISYNC_PREVIEW_COLUMN_UPDATED_AT, at)
        }
        db.update(
            BISYNC_PREVIEW_TABLE_NAME,
            values,
            "$BISYNC_PREVIEW_COLUMN_ID = ? AND $BISYNC_PREVIEW_COLUMN_STATUS = ?",
            arrayOf(operation.previewId, BisyncPreviewOperationState.QUEUED.wireValue)
        )
    }

    private fun writeSummary(values: ContentValues, summary: BisyncPreviewSummary?) {
        if (summary == null) {
            clearSummary(values)
            return
        }
        values.put(BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS, summary.status.name)
        values.put(BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS, summary.plannedTransfers)
        values.put(BISYNC_PREVIEW_COLUMN_PLANNED_BYTES, summary.plannedBytes)
        values.put(BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES, summary.plannedFileDeletes)
        values.put(BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES, summary.plannedDirectoryDeletes)
        values.put(BISYNC_PREVIEW_COLUMN_ERROR_COUNT, summary.errorCount)
        values.put(BISYNC_PREVIEW_COLUMN_CONFLICTS_KNOWN, 0)
    }

    private fun clearSummary(values: ContentValues) {
        values.putNull(BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS)
        values.putNull(BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS)
        values.putNull(BISYNC_PREVIEW_COLUMN_PLANNED_BYTES)
        values.putNull(BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES)
        values.putNull(BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES)
        values.putNull(BISYNC_PREVIEW_COLUMN_ERROR_COUNT)
        values.put(BISYNC_PREVIEW_COLUMN_CONFLICTS_KNOWN, 0)
    }

    private fun get(db: SQLiteDatabase, previewId: String): BisyncPreviewOperation? {
        val cursor = db.query(
            BISYNC_PREVIEW_TABLE_NAME,
            projection,
            "$BISYNC_PREVIEW_COLUMN_ID = ?",
            arrayOf(previewId), null, null, null, "1"
        )
        return try { if (cursor.moveToFirst()) fromCursor(cursor) else null } finally { cursor.close() }
    }

    private fun fromCursor(cursor: Cursor): BisyncPreviewOperation {
        val identity = BisyncPreviewIdentity(
            profileId = cursor.getString(1),
            profileRevision = cursor.getLong(2),
            profileFingerprint = cursor.getString(3),
            engineRef = cursor.getString(4),
            stateVersion = cursor.getInt(5),
            leftAccountFingerprint = cursor.getString(6),
            leftScopeFingerprint = cursor.getString(7),
            rightAccountFingerprint = cursor.getString(8),
            rightScopeFingerprint = cursor.getString(9),
            filterFingerprint = cursor.getString(10),
            comparisonMode = BisyncComparisonMode.values().firstOrNull { it.wireValue == cursor.getString(11) }
                ?: throw IllegalStateException("Stored preview comparison mode is invalid"),
            maxDeletePercent = cursor.getInt(12),
            maxDeleteCount = cursor.getInt(13),
            nativeState = BisyncNativeState.values().firstOrNull { it.name == cursor.getString(14) }
                ?: throw IllegalStateException("Stored preview native state is invalid"),
            acceptedBaselineFingerprint = if (cursor.isNull(15)) null else cursor.getString(15),
            initializationMode = if (cursor.isNull(32)) null else
                BisyncPreviewResyncMode.fromWireValue(cursor.getString(32))
                    ?: throw IllegalStateException("Stored preview initialization preference is invalid")
        )
        if (identity.fingerprint != cursor.getString(16)) {
            throw IllegalStateException("Stored preview identity digest does not match its fields")
        }
        val state = BisyncPreviewOperationState.fromWireValue(cursor.getString(17))
        val summary = summaryFromCursor(cursor)
        return BisyncPreviewOperation(
            previewId = cursor.getString(0),
            identity = identity,
            state = state,
            ownerToken = cursor.getString(18),
            ownerGeneration = cursor.getLong(19),
            requestedAt = cursor.getLong(20),
            startedAt = if (cursor.isNull(21)) null else cursor.getLong(21),
            completedAt = if (cursor.isNull(22)) null else cursor.getLong(22),
            updatedAt = cursor.getLong(31),
            failureCode = BisyncPreviewFailureCode.fromWireValue(if (cursor.isNull(23)) null else cursor.getString(23)),
            summary = summary
        )
    }

    private fun summaryFromCursor(cursor: Cursor): BisyncPreviewSummary? {
        val nullFields = (24..29).count { cursor.isNull(it) }
        if (nullFields == 6) return null
        if (nullFields != 0) throw IllegalStateException("Stored preview summary is incomplete")
        val status = when (cursor.getString(24)) {
            "COMPLETE" -> BisyncPreviewStatus.COMPLETE
            "INCOMPLETE" -> BisyncPreviewStatus.INCOMPLETE
            else -> throw IllegalStateException("Stored preview summary status is invalid")
        }
        if (cursor.getInt(30) != 0) throw IllegalStateException("Stored preview cannot claim known conflict counts")
        return BisyncPreviewSummary(
            status = status,
            plannedTransfers = cursor.getLong(25),
            plannedBytes = cursor.getLong(26),
            plannedFileDeletes = cursor.getLong(27),
            plannedDirectoryDeletes = cursor.getLong(28),
            errorCount = cursor.getLong(29),
            conflictsKnown = false
        )
    }

    private fun hasExplicitPreviewPolicy(identity: BisyncPreviewIdentity): Boolean = when (identity.nativeState) {
        BisyncNativeState.ABSENT -> identity.initializationMode != null && identity.acceptedBaselineFingerprint == null
        BisyncNativeState.COMPATIBLE -> identity.initializationMode == null &&
            BisyncPreviewIdentity.isDigest(identity.acceptedBaselineFingerprint)
        else -> false
    }

    private companion object {
        val projection = arrayOf(
            BISYNC_PREVIEW_COLUMN_ID,
            BISYNC_PREVIEW_COLUMN_PROFILE_ID,
            BISYNC_PREVIEW_COLUMN_PROFILE_REVISION,
            BISYNC_PREVIEW_COLUMN_PROFILE_FINGERPRINT,
            BISYNC_PREVIEW_COLUMN_ENGINE_REF,
            BISYNC_PREVIEW_COLUMN_STATE_VERSION,
            BISYNC_PREVIEW_COLUMN_LEFT_ACCOUNT,
            BISYNC_PREVIEW_COLUMN_LEFT_SCOPE,
            BISYNC_PREVIEW_COLUMN_RIGHT_ACCOUNT,
            BISYNC_PREVIEW_COLUMN_RIGHT_SCOPE,
            BISYNC_PREVIEW_COLUMN_FILTER,
            BISYNC_PREVIEW_COLUMN_COMPARISON,
            BISYNC_PREVIEW_COLUMN_MAX_DELETE_PERCENT,
            BISYNC_PREVIEW_COLUMN_MAX_DELETE_COUNT,
            BISYNC_PREVIEW_COLUMN_NATIVE_STATE,
            BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE,
            BISYNC_PREVIEW_COLUMN_IDENTITY_FINGERPRINT,
            BISYNC_PREVIEW_COLUMN_STATUS,
            BISYNC_PREVIEW_COLUMN_OWNER_TOKEN,
            BISYNC_PREVIEW_COLUMN_OWNER_GENERATION,
            BISYNC_PREVIEW_COLUMN_REQUESTED_AT,
            BISYNC_PREVIEW_COLUMN_STARTED_AT,
            BISYNC_PREVIEW_COLUMN_COMPLETED_AT,
            BISYNC_PREVIEW_COLUMN_FAILURE_CODE,
            BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS,
            BISYNC_PREVIEW_COLUMN_PLANNED_TRANSFERS,
            BISYNC_PREVIEW_COLUMN_PLANNED_BYTES,
            BISYNC_PREVIEW_COLUMN_PLANNED_FILE_DELETES,
            BISYNC_PREVIEW_COLUMN_PLANNED_DIRECTORY_DELETES,
            BISYNC_PREVIEW_COLUMN_ERROR_COUNT,
            BISYNC_PREVIEW_COLUMN_CONFLICTS_KNOWN,
            BISYNC_PREVIEW_COLUMN_UPDATED_AT,
            BISYNC_PREVIEW_COLUMN_INITIALIZATION_MODE
        )
    }
}

class BisyncPreviewRejectedException(message: String) : IllegalStateException(message)
