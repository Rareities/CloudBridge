package ca.pkay.rcloneexplorer.Database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_CHECKED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_COMPARISON
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_ENGINE_REF
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_FILTER
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_FINGERPRINT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_LEFT_ACCOUNT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_LEFT_SCOPE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE_REASON
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_PROFILE_FINGERPRINT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_PROFILE_ID
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_PROFILE_REVISION
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_READINESS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_RECOVERY_LISTINGS_VALID
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_REASON
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_RIGHT_ACCOUNT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_RIGHT_SCOPE
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_COLUMN_STATE_VERSION
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.BISYNC_PREFLIGHT_TABLE_NAME
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_FINGERPRINT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_ID
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_READINESS
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_REASON
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_REVISION
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_COLUMN_UPDATED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.PROFILE_TABLE_NAME

data class BisyncPreflightStatus(
    val acceptedBaseline: BisyncPreflightBaseline?,
    val readiness: ProfileReadiness,
    val reasonCode: String?,
    val checkedAt: Long,
    val nativeState: BisyncNativeState,
    val nativeStateReason: String,
    val recoveryListingsValid: Boolean
)

class StaleBisyncPreflightException(message: String) : IllegalStateException(message)

/** Stores hash-only accepted identities and the latest fail-closed result. */
class BisyncPreflightRepository(context: Context) {
    private val context = context.applicationContext
    private val projection = arrayOf(
        BISYNC_PREFLIGHT_COLUMN_PROFILE_ID,
        BISYNC_PREFLIGHT_COLUMN_PROFILE_REVISION,
        BISYNC_PREFLIGHT_COLUMN_PROFILE_FINGERPRINT,
        BISYNC_PREFLIGHT_COLUMN_ENGINE_REF,
        BISYNC_PREFLIGHT_COLUMN_STATE_VERSION,
        BISYNC_PREFLIGHT_COLUMN_LEFT_ACCOUNT,
        BISYNC_PREFLIGHT_COLUMN_LEFT_SCOPE,
        BISYNC_PREFLIGHT_COLUMN_RIGHT_ACCOUNT,
        BISYNC_PREFLIGHT_COLUMN_RIGHT_SCOPE,
        BISYNC_PREFLIGHT_COLUMN_FILTER,
        BISYNC_PREFLIGHT_COLUMN_COMPARISON,
        BISYNC_PREFLIGHT_COLUMN_FINGERPRINT,
        BISYNC_PREFLIGHT_COLUMN_READINESS,
        BISYNC_PREFLIGHT_COLUMN_REASON,
        BISYNC_PREFLIGHT_COLUMN_CHECKED_AT,
        BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE,
        BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE_REASON,
        BISYNC_PREFLIGHT_COLUMN_RECOVERY_LISTINGS_VALID
    )

    fun get(profileId: String): BisyncPreflightStatus? {
        val handler = DatabaseHandler(context)
        val db = handler.readableDatabase
        return try {
            val cursor = db.query(
                BISYNC_PREFLIGHT_TABLE_NAME,
                projection,
                "$BISYNC_PREFLIGHT_COLUMN_PROFILE_ID = ?",
                arrayOf(profileId),
                null,
                null,
                null,
                "1"
            )
            try {
                if (cursor.moveToFirst()) statusFromCursor(cursor) else null
            } finally {
                cursor.close()
            }
        } finally {
            db.close()
        }
    }

    /** Writes only when the profile snapshot observed by the probes is still current. */
    fun recordAttempt(
        profileId: String,
        expectedRevision: Long,
        expectedProfileFingerprint: String,
        input: BisyncPreflightInput,
        result: BisyncPreflightResult,
        checkedAt: Long = System.currentTimeMillis()
    ) {
        if (input.profileRevision != expectedRevision || input.profileFingerprint != expectedProfileFingerprint) {
            throw StaleBisyncPreflightException("Preflight profile snapshot does not match its request")
        }
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        db.beginTransaction()
        try {
            val current = ProfileStore.getById(db, profileId)
                ?: throw StaleBisyncPreflightException("Profile no longer exists")
            if (current.mode != ProfileMode.BISYNC || current.revision != expectedRevision ||
                current.fingerprint != expectedProfileFingerprint) {
                throw StaleBisyncPreflightException("Profile changed during Bisync preflight")
            }

            val prior = getStatus(db, profileId)
            val baseline = if (result.readiness == ProfileReadiness.READY && result.reason == null) {
                val candidate = result.candidateBaseline
                    ?: throw IllegalArgumentException("A ready preflight must include its accepted identity baseline")
                validateCandidate(input, result, candidate, current)
                candidate
            } else {
                prior?.acceptedBaseline
            }
            val values = valuesFor(profileId, baseline, input, result, checkedAt)
            db.insertWithOnConflict(
                BISYNC_PREFLIGHT_TABLE_NAME,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )

            val readinessValues = ContentValues().apply {
                put(PROFILE_COLUMN_READINESS, result.readiness.wireValue)
                val reason = result.reason?.wireValue ?: result.comparisonDisclosure?.let {
                    "SIZE_ONLY_CONTENT_CHANGES_UNDETECTED"
                }
                if (reason == null) putNull(PROFILE_COLUMN_REASON) else put(PROFILE_COLUMN_REASON, reason)
                put(PROFILE_COLUMN_UPDATED_AT, checkedAt)
            }
            val updated = db.update(
                PROFILE_TABLE_NAME,
                readinessValues,
                "$PROFILE_COLUMN_ID = ? AND $PROFILE_COLUMN_REVISION = ? AND $PROFILE_COLUMN_FINGERPRINT = ?",
                arrayOf(profileId, expectedRevision.toString(), expectedProfileFingerprint)
            )
            if (updated != 1) throw StaleBisyncPreflightException("Profile changed while storing preflight result")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    /** Do not let a caller persist a ready baseline that was built from another snapshot. */
    private fun validateCandidate(
        input: BisyncPreflightInput,
        result: BisyncPreflightResult,
        candidate: BisyncPreflightBaseline,
        current: ProfileRecord
    ) {
        val leftScope = input.left.scope.fingerprint()
        val rightScope = input.right.scope.fingerprint()
        if (input.nativeState != BisyncNativeState.COMPATIBLE ||
            candidate.profileRevision != current.revision ||
            candidate.profileFingerprint != current.fingerprint ||
            candidate.engineRef != input.engineRef || candidate.stateVersion != input.stateVersion ||
            candidate.leftAccountFingerprint != input.left.accountFingerprint ||
            candidate.leftScopeFingerprint != leftScope ||
            candidate.rightAccountFingerprint != input.right.accountFingerprint ||
            candidate.rightScopeFingerprint != rightScope ||
            candidate.filterFingerprint != input.filterFingerprint ||
            candidate.comparisonMode != input.comparisonMode ||
            result.identityFingerprint != candidate.preflightFingerprint ||
            !Regex("^[0-9a-f]{64}$").matches(candidate.preflightFingerprint)) {
            throw IllegalArgumentException("Ready Bisync baseline does not match the verified preflight snapshot")
        }
    }

    /**
     * Records a confirmed reinitialization request without discarding the last accepted identity.
     * The caller must not invoke this as a substitute for preservation or native execution. A
     * failed/abandoned reinitialization must leave the previous baseline available for review.
     */
    fun resetForConfirmedReinitialization(
        profileId: String,
        expectedRevision: Long,
        expectedProfileFingerprint: String,
        userConfirmed: Boolean
    ) {
        if (!userConfirmed) throw SecurityException("Explicit reinitialization confirmation is required")
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        db.beginTransaction()
        try {
            val current = ProfileStore.getById(db, profileId)
                ?: throw StaleBisyncPreflightException("Profile no longer exists")
            if (current.mode != ProfileMode.BISYNC || current.revision != expectedRevision ||
                current.fingerprint != expectedProfileFingerprint) {
                throw StaleBisyncPreflightException("Profile changed before reinitialization was confirmed")
            }
            val checkedAt = System.currentTimeMillis()
            val preflightValues = ContentValues().apply {
                put(BISYNC_PREFLIGHT_COLUMN_READINESS, ProfileReadiness.INITIALIZATION_REQUIRED.wireValue)
                put(BISYNC_PREFLIGHT_COLUMN_REASON, "EXPLICIT_REINITIALIZATION_REQUIRES_PRESERVATION")
                put(BISYNC_PREFLIGHT_COLUMN_CHECKED_AT, checkedAt)
            }
            db.update(
                BISYNC_PREFLIGHT_TABLE_NAME,
                preflightValues,
                "$BISYNC_PREFLIGHT_COLUMN_PROFILE_ID = ?",
                arrayOf(profileId)
            )
            val values = ContentValues().apply {
                put(PROFILE_COLUMN_READINESS, ProfileReadiness.INITIALIZATION_REQUIRED.wireValue)
                put(PROFILE_COLUMN_REASON, "EXPLICIT_REINITIALIZATION_REQUIRES_PRESERVATION")
                put(PROFILE_COLUMN_UPDATED_AT, checkedAt)
            }
            val updated = db.update(
                PROFILE_TABLE_NAME,
                values,
                "$PROFILE_COLUMN_ID = ? AND $PROFILE_COLUMN_REVISION = ? AND $PROFILE_COLUMN_FINGERPRINT = ?",
                arrayOf(profileId, expectedRevision.toString(), expectedProfileFingerprint)
            )
            if (updated != 1) throw StaleBisyncPreflightException("Profile changed during reinitialization reset")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    private fun getStatus(db: SQLiteDatabase, profileId: String): BisyncPreflightStatus? {
        val cursor = db.query(
            BISYNC_PREFLIGHT_TABLE_NAME,
            projection,
            "$BISYNC_PREFLIGHT_COLUMN_PROFILE_ID = ?",
            arrayOf(profileId),
            null,
            null,
            null,
            "1"
        )
        return try {
            if (cursor.moveToFirst()) statusFromCursor(cursor) else null
        } finally {
            cursor.close()
        }
    }

    private fun statusFromCursor(cursor: Cursor): BisyncPreflightStatus {
        val baseline = baselineFromCursor(cursor)
        val nativeState = BisyncNativeState.fromStoredValue(cursor.getString(15))
        return BisyncPreflightStatus(
            baseline,
            ProfileReadiness.fromWireValue(cursor.getString(12)),
            if (cursor.isNull(13)) null else cursor.getString(13),
            cursor.getLong(14),
            nativeState,
            safeNativeStateReason(if (cursor.isNull(16)) null else cursor.getString(16)),
            cursor.getInt(17) != 0 && nativeState == BisyncNativeState.INTERRUPTED
        )
    }

    private fun baselineFromCursor(cursor: Cursor): BisyncPreflightBaseline? {
        for (index in 5..11) if (cursor.isNull(index)) return null
        val comparison = BisyncComparisonMode.values().firstOrNull { it.wireValue == cursor.getString(10) }
            ?: return null
        return BisyncPreflightBaseline(
            profileRevision = cursor.getLong(1),
            profileFingerprint = cursor.getString(2),
            engineRef = cursor.getString(3),
            leftAccountFingerprint = cursor.getString(5),
            leftScopeFingerprint = cursor.getString(6),
            rightAccountFingerprint = cursor.getString(7),
            rightScopeFingerprint = cursor.getString(8),
            filterFingerprint = cursor.getString(9),
            comparisonMode = comparison,
            stateVersion = cursor.getInt(4),
            preflightFingerprint = cursor.getString(11)
        )
    }

    private fun valuesFor(
        profileId: String,
        baseline: BisyncPreflightBaseline?,
        input: BisyncPreflightInput,
        result: BisyncPreflightResult,
        checkedAt: Long
    ): ContentValues {
        val accepted = baseline
        return ContentValues().apply {
            put(BISYNC_PREFLIGHT_COLUMN_PROFILE_ID, profileId)
            put(BISYNC_PREFLIGHT_COLUMN_PROFILE_REVISION, accepted?.profileRevision ?: input.profileRevision)
            put(BISYNC_PREFLIGHT_COLUMN_PROFILE_FINGERPRINT, accepted?.profileFingerprint ?: input.profileFingerprint)
            put(BISYNC_PREFLIGHT_COLUMN_ENGINE_REF, accepted?.engineRef ?: input.engineRef)
            put(BISYNC_PREFLIGHT_COLUMN_STATE_VERSION, accepted?.stateVersion ?: input.stateVersion)
            if (accepted == null) {
                putNull(BISYNC_PREFLIGHT_COLUMN_LEFT_ACCOUNT)
                putNull(BISYNC_PREFLIGHT_COLUMN_LEFT_SCOPE)
                putNull(BISYNC_PREFLIGHT_COLUMN_RIGHT_ACCOUNT)
                putNull(BISYNC_PREFLIGHT_COLUMN_RIGHT_SCOPE)
                putNull(BISYNC_PREFLIGHT_COLUMN_FILTER)
                putNull(BISYNC_PREFLIGHT_COLUMN_COMPARISON)
                putNull(BISYNC_PREFLIGHT_COLUMN_FINGERPRINT)
            } else {
                put(BISYNC_PREFLIGHT_COLUMN_LEFT_ACCOUNT, accepted.leftAccountFingerprint)
                put(BISYNC_PREFLIGHT_COLUMN_LEFT_SCOPE, accepted.leftScopeFingerprint)
                put(BISYNC_PREFLIGHT_COLUMN_RIGHT_ACCOUNT, accepted.rightAccountFingerprint)
                put(BISYNC_PREFLIGHT_COLUMN_RIGHT_SCOPE, accepted.rightScopeFingerprint)
                put(BISYNC_PREFLIGHT_COLUMN_FILTER, accepted.filterFingerprint)
                put(BISYNC_PREFLIGHT_COLUMN_COMPARISON, accepted.comparisonMode.wireValue)
                put(BISYNC_PREFLIGHT_COLUMN_FINGERPRINT, accepted.preflightFingerprint)
            }
            put(BISYNC_PREFLIGHT_COLUMN_READINESS, result.readiness.wireValue)
            val reason = result.reason?.wireValue ?: result.comparisonDisclosure?.let {
                "SIZE_ONLY_CONTENT_CHANGES_UNDETECTED"
            }
            if (reason == null) putNull(BISYNC_PREFLIGHT_COLUMN_REASON) else put(BISYNC_PREFLIGHT_COLUMN_REASON, reason)
            put(BISYNC_PREFLIGHT_COLUMN_CHECKED_AT, checkedAt)
            put(BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE, input.nativeState.name)
            put(BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE_REASON, safeNativeStateReason(input.nativeStateReason))
            put(
                BISYNC_PREFLIGHT_COLUMN_RECOVERY_LISTINGS_VALID,
                input.nativeState == BisyncNativeState.INTERRUPTED && input.nativeRecoveryListingsValid
            )
        }
    }

    private fun safeNativeStateReason(reason: String?): String =
        reason?.takeIf { Regex("^[A-Z0-9_]{1,64}$").matches(it) } ?: "INVALID_NATIVE_RESULT"
}
