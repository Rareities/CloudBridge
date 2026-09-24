package ca.pkay.rcloneexplorer.Database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Persists a paired, run-owned backup-location proposal. This repository does not contact providers,
 * prove that locations are fresh, start rclone, restore files, or authorize mutation.
 */
class BisyncBackupManifestRepository(context: Context) {
    private val context = context.applicationContext

    /**
     * Atomically records both backup locators as PENDING_VALIDATION after checking the live run,
     * fresh persisted preflight, completed preview, selected mode, and caller-supplied timestamp
     * ordering. The timestamp is not proof of explicit confirmation. A pending record is durable
     * evidence, never permission to run `--resync`.
     */
    fun createPending(request: BisyncBackupManifestRequest): BisyncBackupManifestRecord {
        val prepared = prepare(request)
        val operationId = UUID.randomUUID().toString()
        val handler = DatabaseHandler(context)
        try {
            val db = handler.writableDatabase
            try {
                db.beginTransaction()
                try {
                    val verified = requireCurrentOwner(db, request, prepared)
                    requireNoPriorBackupOverlap(db, prepared)
                    val manifestValues = ContentValues().apply {
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OPERATION_ID, operationId)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_RUN_ID, request.run.runId)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_ID, request.run.profileId)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_REVISION, request.run.profileRevision)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_FINGERPRINT, request.run.profileFingerprint)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_ENGINE_REF, request.run.engineRef)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_STATE_VERSION, request.preflight.stateVersion)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREFLIGHT_FINGERPRINT, prepared.preflightFingerprint)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OBSERVATION_FINGERPRINT, prepared.observationFingerprint)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREVIEW_ID, request.previewId)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREVIEW_FINGERPRINT, verified.previewFingerprint)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREFLIGHT_CHECKED_AT, verified.preflightCheckedAt)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_INITIALIZATION_MODE, verified.initializationMode.wireValue)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_FILTER_FINGERPRINT, requireNotNull(request.preflight.filterFingerprint))
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_COMPARISON_MODE, request.preflight.comparisonMode.wireValue)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_MAX_DELETE_PERCENT, request.preflight.maxDeletePercent)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_MAX_DELETE_COUNT, request.preflight.maxDeleteCount)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_USER_CONFIRMED_AT, request.userConfirmedAt)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OWNER_TOKEN, request.run.ownerToken)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OWNER_GENERATION, request.run.ownerGeneration)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_STATUS, BisyncBackupManifestState.PENDING_VALIDATION.wireValue)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_CREATED_AT, request.createdAt)
                        put(DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_UPDATED_AT, request.createdAt)
                    }
                    db.insertOrThrow(DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME, null, manifestValues)
                    insertLocation(db, operationId, prepared.left, BisyncBackupSide.LEFT, request.createdAt)
                    insertLocation(db, operationId, prepared.right, BisyncBackupSide.RIGHT, request.createdAt)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                    db.close()
                }
            } catch (_: SQLiteConstraintException) {
                // Keep provider paths, run IDs and unique-index details out of surfaced errors/logs.
                throw BisyncBackupManifestConstraintException()
            }
        } finally {
            handler.close()
        }
        return getForRun(request.run.runId)
            ?: throw BisyncBackupManifestCorruptException("Committed backup manifest could not be read")
    }

    fun getForRun(runId: String): BisyncBackupManifestRecord? {
        require(canonicalUuid(runId)) { "Invalid run identity" }
        val handler = DatabaseHandler(context)
        val db = handler.readableDatabase
        return try {
            val cursor = db.query(
                DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME,
                manifestProjection,
                "${DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_RUN_ID} = ?",
                arrayOf(runId), null, null, null, "1"
            )
            try {
                if (cursor.moveToFirst()) readManifest(db, cursor) else null
            } finally {
                cursor.close()
            }
        } finally {
            db.close()
            handler.close()
        }
    }

    /** Returns every unresolved record; corrupt or unknown rows fail closed instead of being skipped. */
    fun unresolvedForProfile(profileId: String): List<BisyncBackupManifestRecord> {
        require(canonicalUuid(profileId)) { "Invalid profile identity" }
        val handler = DatabaseHandler(context)
        val db = handler.readableDatabase
        return try {
            val cursor = db.query(
                DatabaseInfo.BISYNC_BACKUP_MANIFEST_TABLE_NAME,
                manifestProjection,
                "${DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_ID} = ? AND " +
                    "${DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_STATUS} NOT IN (?, ?)",
                arrayOf(
                    profileId,
                    BisyncBackupManifestState.RESTORE_VERIFIED.wireValue,
                    BisyncBackupManifestState.RETAINED.wireValue
                ),
                null,
                null,
                "${DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_CREATED_AT} ASC"
            )
            try {
                buildList {
                    while (cursor.moveToNext()) add(readManifest(db, cursor))
                }
            } finally {
                cursor.close()
            }
        } finally {
            db.close()
            handler.close()
        }
    }

    private fun prepare(request: BisyncBackupManifestRequest): PreparedPair {
        val run = request.run
        val input = request.preflight
        if (!canonicalUuid(run.runId) || !canonicalUuid(run.profileId) || !canonicalUuid(run.ownerToken) ||
            !canonicalUuid(request.previewId)) {
            throw BisyncBackupManifestRejectedException("Run ownership identity is invalid")
        }
        if (run.requestedMode != ProfileMode.BISYNC ||
            run.state != RunState.PREFLIGHT ||
            run.cancellationRequested || run.profileRevision <= 0L || run.ownerGeneration < 0L) {
            throw BisyncBackupManifestRejectedException("Run is not an active Bisync owner")
        }
        if (request.createdAt <= 0L || request.createdAt > System.currentTimeMillis() ||
            request.userConfirmedAt <= 0L || request.userConfirmedAt > request.createdAt ||
            input.nativeState != BisyncNativeState.ABSENT ||
            input.stateVersion != BisyncPreflightPolicy.CURRENT_STATE_VERSION ||
            input.profileRevision != run.profileRevision ||
            input.profileFingerprint != run.profileFingerprint || input.engineRef != run.engineRef) {
            throw BisyncBackupManifestRejectedException("Preflight identity does not match this initialization run")
        }

        val preflight = BisyncPreflightPolicy.evaluate(input)
        val baseline = preflight.candidateBaseline
        val preflightFingerprint = preflight.identityFingerprint
        val observationFingerprint = BisyncPreflightPolicy.observationFingerprint(input, preflightFingerprint)
        if (preflight.readiness != ProfileReadiness.INITIALIZATION_REQUIRED ||
            baseline == null || preflightFingerprint == null || observationFingerprint == null) {
            throw BisyncBackupManifestRejectedException("Bisync preflight does not permit an initialization proposal")
        }

        val leftLocator = validateLocator(request.leftBackupLocator)
        val rightLocator = validateLocator(request.rightBackupLocator)
        val leftAccount = requireNotNull(input.left.accountFingerprint)
        val rightAccount = requireNotNull(input.right.accountFingerprint)
        val leftBackupScope = BisyncEndpointScope.from(leftAccount, leftLocator)
        val rightBackupScope = BisyncEndpointScope.from(rightAccount, rightLocator)
        val assessment = BisyncBackupPlacementPolicy.assess(
            input.left,
            input.right,
            BisyncBackupCandidateEvidence(leftAccount, leftBackupScope),
            BisyncBackupCandidateEvidence(rightAccount, rightBackupScope)
        )
        if (!assessment.placementShapeValid) {
            throw BisyncBackupManifestRejectedException("Backup locations do not have a valid separated shape")
        }

        return PreparedPair(
            preflightFingerprint = preflightFingerprint,
            observationFingerprint = observationFingerprint,
            left = PreparedLocation(
                accountFingerprint = leftAccount,
                endpointScopeFingerprint = baseline.leftScopeFingerprint,
                endpointScope = input.left.scope,
                backupScopeFingerprint = requireNotNull(leftBackupScope.fingerprint()),
                locator = leftLocator
            ),
            right = PreparedLocation(
                accountFingerprint = rightAccount,
                endpointScopeFingerprint = baseline.rightScopeFingerprint,
                endpointScope = input.right.scope,
                backupScopeFingerprint = requireNotNull(rightBackupScope.fingerprint()),
                locator = rightLocator
            )
        )
    }

    private fun requireCurrentOwner(
        db: SQLiteDatabase,
        request: BisyncBackupManifestRequest,
        prepared: PreparedPair
    ): VerifiedSnapshot {
        val requested = request.run
        val runCursor = db.query(
            DatabaseInfo.RUN_TABLE_NAME,
            arrayOf(
                DatabaseInfo.RUN_COLUMN_PROFILE_ID,
                DatabaseInfo.RUN_COLUMN_PROFILE_REVISION,
                DatabaseInfo.RUN_COLUMN_PROFILE_FINGERPRINT,
                DatabaseInfo.RUN_COLUMN_REQUESTED_MODE,
                DatabaseInfo.RUN_COLUMN_ENGINE,
                DatabaseInfo.RUN_COLUMN_STATE,
                DatabaseInfo.RUN_COLUMN_OWNER_TOKEN,
                DatabaseInfo.RUN_COLUMN_OWNER_GENERATION,
                DatabaseInfo.RUN_COLUMN_CANCEL_REQUESTED
            ),
            "${DatabaseInfo.RUN_COLUMN_ID} = ?",
            arrayOf(requested.runId), null, null, null, "1"
        )
        val runMatches = try {
            runCursor.moveToFirst() &&
                runCursor.getString(0) == requested.profileId &&
                runCursor.getLong(1) == requested.profileRevision &&
                runCursor.getString(2) == requested.profileFingerprint &&
                runCursor.getString(3) == ProfileMode.BISYNC.wireValue &&
                runCursor.getString(4) == requested.engineRef &&
                RunState.fromWireValue(runCursor.getString(5)) == requested.state &&
                runCursor.getString(6) == requested.ownerToken &&
                runCursor.getLong(7) == requested.ownerGeneration &&
                runCursor.getInt(8) == 0
        } finally {
            runCursor.close()
        }
        if (!runMatches || requested.state != RunState.PREFLIGHT) {
            throw BisyncBackupManifestRejectedException("Run owner changed or was cancelled before backup-manifest persistence")
        }

        val profileCursor = db.query(
            DatabaseInfo.PROFILE_TABLE_NAME,
            arrayOf(
                DatabaseInfo.PROFILE_COLUMN_MODE,
                DatabaseInfo.PROFILE_COLUMN_REVISION,
                DatabaseInfo.PROFILE_COLUMN_FINGERPRINT,
                DatabaseInfo.PROFILE_COLUMN_ENGINE,
                DatabaseInfo.PROFILE_COLUMN_READINESS
            ),
            "${DatabaseInfo.PROFILE_COLUMN_ID} = ?",
            arrayOf(requested.profileId), null, null, null, "1"
        )
        val profileMatches = try {
            profileCursor.moveToFirst() &&
                profileCursor.getString(0) == ProfileMode.BISYNC.wireValue &&
                profileCursor.getLong(1) == requested.profileRevision &&
                profileCursor.getString(2) == requested.profileFingerprint &&
                profileCursor.getString(3) == requested.engineRef &&
                ProfileReadiness.fromWireValue(profileCursor.getString(4)) in setOf(
                    ProfileReadiness.INITIALIZATION_REQUIRED,
                    ProfileReadiness.RUNNING
                )
        } finally {
            profileCursor.close()
        }
        if (!profileMatches) {
            throw BisyncBackupManifestRejectedException("Profile changed before backup-manifest persistence")
        }

        val now = System.currentTimeMillis()
        val preflightCursor = db.query(
            DatabaseInfo.BISYNC_PREFLIGHT_TABLE_NAME,
            arrayOf(
                DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_REVISION,
                DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_FINGERPRINT,
                DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_ENGINE_REF,
                DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_STATE_VERSION,
                DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_READINESS,
                DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_REASON,
                DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_CHECKED_AT,
                DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_NATIVE_STATE,
                DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_RECOVERY_LISTINGS_VALID,
                DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_FINGERPRINT,
                DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_OBSERVATION_FINGERPRINT
            ),
            "${DatabaseInfo.BISYNC_PREFLIGHT_COLUMN_PROFILE_ID} = ?",
            arrayOf(requested.profileId), null, null, null, "1"
        )
        val checkedAt = try {
            val hasRow = preflightCursor.moveToFirst()
            val persistedCheckedAt = if (hasRow) preflightCursor.getLong(6) else 0L
            val expectedReason = if (request.preflight.comparisonMode == BisyncComparisonMode.SIZE_ONLY) {
                "SIZE_ONLY_CONTENT_CHANGES_UNDETECTED"
            } else null
            val actualReason = if (!hasRow || preflightCursor.isNull(5)) null else preflightCursor.getString(5)
            val current = hasRow &&
                preflightCursor.getLong(0) == requested.profileRevision &&
                preflightCursor.getString(1) == requested.profileFingerprint &&
                preflightCursor.getString(2) == requested.engineRef &&
                preflightCursor.getInt(3) == request.preflight.stateVersion &&
                preflightCursor.getString(4) == ProfileReadiness.INITIALIZATION_REQUIRED.wireValue &&
                actualReason == expectedReason &&
                persistedCheckedAt > 0L && persistedCheckedAt <= now &&
                now - persistedCheckedAt < BisyncPreflightPolicy.MAX_EVIDENCE_AGE_MILLIS &&
                preflightCursor.getString(7) == BisyncNativeState.ABSENT.name &&
                preflightCursor.getInt(8) == 0 &&
                !preflightCursor.isNull(9) &&
                preflightCursor.getString(9) == prepared.preflightFingerprint &&
                !preflightCursor.isNull(10) &&
                preflightCursor.getString(10) == prepared.observationFingerprint
            if (!current) {
                throw BisyncBackupManifestRejectedException("Persisted Bisync preflight is missing, stale, or does not match this run")
            }
            persistedCheckedAt
        } finally {
            preflightCursor.close()
        }

        val previewCursor = db.query(
            DatabaseInfo.BISYNC_PREVIEW_TABLE_NAME,
            arrayOf(
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_ID,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_REVISION,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_PROFILE_FINGERPRINT,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_ENGINE_REF,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATE_VERSION,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_LEFT_ACCOUNT,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_LEFT_SCOPE,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_RIGHT_ACCOUNT,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_RIGHT_SCOPE,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_FILTER,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPARISON,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_MAX_DELETE_PERCENT,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_MAX_DELETE_COUNT,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_NATIVE_STATE,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_ACCEPTED_BASELINE,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_IDENTITY_FINGERPRINT,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_STATUS,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_COMPLETED_AT,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_INITIALIZATION_MODE,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_SUMMARY_STATUS,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_ERROR_COUNT,
                DatabaseInfo.BISYNC_PREVIEW_COLUMN_REQUESTED_AT
            ),
            "${DatabaseInfo.BISYNC_PREVIEW_COLUMN_ID} = ?",
            arrayOf(request.previewId), null, null, null, "1"
        )
        return try {
            if (!previewCursor.moveToFirst()) {
                throw BisyncBackupManifestRejectedException("A completed initialization preview is required")
            }
            val mode = if (previewCursor.isNull(18)) null else
                BisyncPreviewResyncMode.fromWireValue(previewCursor.getString(18))
            val comparisonMode = BisyncComparisonMode.values().firstOrNull {
                it.wireValue == previewCursor.getString(10)
            }
            if (mode == null || comparisonMode == null || previewCursor.getString(13) != BisyncNativeState.ABSENT.name ||
                !previewCursor.isNull(14)) {
                throw BisyncBackupManifestRejectedException("Preview does not bind an explicit absent-state initialization policy")
            }
            val identity = try {
                BisyncPreviewIdentity(
                    profileId = previewCursor.getString(0),
                    profileRevision = previewCursor.getLong(1),
                    profileFingerprint = previewCursor.getString(2),
                    engineRef = previewCursor.getString(3),
                    stateVersion = previewCursor.getInt(4),
                    leftAccountFingerprint = previewCursor.getString(5),
                    leftScopeFingerprint = previewCursor.getString(6),
                    rightAccountFingerprint = previewCursor.getString(7),
                    rightScopeFingerprint = previewCursor.getString(8),
                    filterFingerprint = previewCursor.getString(9),
                    comparisonMode = comparisonMode,
                    initializationMode = mode,
                    maxDeletePercent = previewCursor.getInt(11),
                    maxDeleteCount = previewCursor.getInt(12),
                    nativeState = BisyncNativeState.ABSENT,
                    acceptedBaselineFingerprint = null
                )
            } catch (_: IllegalArgumentException) {
                throw BisyncBackupManifestRejectedException("Stored preview identity is invalid")
            }
            val input = request.preflight
            val expectedIdentity = BisyncPreviewIdentity(
                profileId = requested.profileId,
                profileRevision = requested.profileRevision,
                profileFingerprint = requested.profileFingerprint,
                engineRef = requested.engineRef,
                stateVersion = input.stateVersion,
                leftAccountFingerprint = requireNotNull(input.left.accountFingerprint),
                leftScopeFingerprint = requireNotNull(input.left.scope.fingerprint()),
                rightAccountFingerprint = requireNotNull(input.right.accountFingerprint),
                rightScopeFingerprint = requireNotNull(input.right.scope.fingerprint()),
                filterFingerprint = requireNotNull(input.filterFingerprint),
                comparisonMode = input.comparisonMode,
                initializationMode = mode,
                maxDeletePercent = input.maxDeletePercent,
                maxDeleteCount = input.maxDeleteCount,
                nativeState = BisyncNativeState.ABSENT,
                acceptedBaselineFingerprint = null
            )
            val completedAt = if (previewCursor.isNull(17)) 0L else previewCursor.getLong(17)
            val requestedAt = previewCursor.getLong(21)
            if (identity != expectedIdentity || previewCursor.getString(15) != identity.fingerprint ||
                previewCursor.getString(16) != BisyncPreviewOperationState.COMPLETE.wireValue ||
                previewCursor.getString(19) != "COMPLETE" || previewCursor.getLong(20) != 0L ||
                requestedAt <= checkedAt || completedAt < requestedAt ||
                completedAt <= 0L || completedAt > now || now - completedAt >= BisyncPreflightPolicy.MAX_EVIDENCE_AGE_MILLIS ||
                completedAt > request.userConfirmedAt) {
                throw BisyncBackupManifestRejectedException("Initialization preview is stale or does not match persisted preflight evidence")
            }
            VerifiedSnapshot(checkedAt, mode, identity.fingerprint)
        } finally {
            previewCursor.close()
        }
    }

    private fun insertLocation(
        db: SQLiteDatabase,
        operationId: String,
        location: PreparedLocation,
        side: BisyncBackupSide,
        createdAt: Long
    ) {
        val values = ContentValues().apply {
            put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_OPERATION_ID, operationId)
            put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_SIDE, side.wireValue)
            put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_ACCOUNT_FINGERPRINT, location.accountFingerprint)
            put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_ENDPOINT_SCOPE_FINGERPRINT, location.endpointScopeFingerprint)
            put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_BACKUP_SCOPE_FINGERPRINT, location.backupScopeFingerprint)
            put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_LOCATOR, location.locator)
            put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_LOCATOR_FINGERPRINT, locatorFingerprint(
                location.accountFingerprint, location.backupScopeFingerprint, location.locator
            ))
            put(DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_CREATED_AT, createdAt)
        }
        db.insertOrThrow(DatabaseInfo.BISYNC_BACKUP_LOCATION_TABLE_NAME, null, values)
    }

    /**
     * Reserves non-overlapping scopes for the lifetime of every persisted backup record. This
     * check is inside the same SQLite write transaction as both inserts, so concurrent proposals
     * cannot pass the scan together. It also prevents a later profile from adopting an existing
     * backup reservation as one of its sync roots. Provider existence/emptiness is not inferred.
     */
    private fun requireNoPriorBackupOverlap(db: SQLiteDatabase, prepared: PreparedPair) {
        val candidates = listOf(prepared.left, prepared.right).map { location ->
            val scope = BisyncEndpointScope.from(location.accountFingerprint, location.locator)
            if (!scope.isResolved || scope.fingerprint() != location.backupScopeFingerprint) {
                throw BisyncBackupManifestRejectedException("Backup location identity is unresolved")
            }
            location to scope
        }
        val cursor = db.query(
            DatabaseInfo.BISYNC_BACKUP_LOCATION_TABLE_NAME,
            arrayOf(
                DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_ACCOUNT_FINGERPRINT,
                DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_BACKUP_SCOPE_FINGERPRINT,
                DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_LOCATOR
            ),
            null, null, null, null, null
        )
        try {
            while (cursor.moveToNext()) {
                val accountFingerprint = cursor.getString(0)
                val storedFingerprint = cursor.getString(1)
                val storedLocator = cursor.getString(2)
                val priorScope = BisyncEndpointScope.from(accountFingerprint, storedLocator)
                if (!priorScope.isResolved || priorScope.fingerprint() != storedFingerprint) {
                    throw BisyncBackupManifestCorruptException("Stored backup reservation scope is inconsistent")
                }
                val conflictsWithBackup = candidates.any { (candidate, scope) ->
                    candidate.accountFingerprint == accountFingerprint && scope.overlaps(priorScope)
                }
                val conflictsWithNewSyncRoot = listOf(prepared.left, prepared.right).any { endpoint ->
                    endpoint.accountFingerprint == accountFingerprint && endpoint.endpointScope.overlaps(priorScope)
                }
                if (conflictsWithBackup || conflictsWithNewSyncRoot) {
                    throw BisyncBackupManifestRejectedException("Backup location overlaps an existing reservation")
                }
            }
        } finally {
            cursor.close()
        }
    }

    private fun readManifest(db: SQLiteDatabase, cursor: Cursor): BisyncBackupManifestRecord {
        val operationId = cursor.getString(0)
        val runId = cursor.getString(1)
        val profileId = cursor.getString(2)
        val profileRevision = cursor.getLong(3)
        val profileFingerprint = cursor.getString(4)
        val engineRef = cursor.getString(5)
        val stateVersion = cursor.getInt(6)
        val preflightFingerprint = cursor.getString(7)
        val observationFingerprint = cursor.getString(22)
        val previewId = cursor.getString(8)
        val previewFingerprint = cursor.getString(9)
        val preflightCheckedAt = cursor.getLong(10)
        val initializationMode = BisyncPreviewResyncMode.fromWireValue(cursor.getString(11))
            ?: throw BisyncBackupManifestCorruptException("Stored initialization preference is invalid")
        val filterFingerprint = cursor.getString(12)
        val comparisonMode = BisyncComparisonMode.values().firstOrNull { it.wireValue == cursor.getString(13) }
            ?: throw BisyncBackupManifestCorruptException("Stored comparison mode is invalid")
        val maxDeletePercent = cursor.getInt(14)
        val maxDeleteCount = cursor.getInt(15)
        val userConfirmedAt = cursor.getLong(16)
        val ownerToken = cursor.getString(17)
        val ownerGeneration = cursor.getLong(18)
        val state = BisyncBackupManifestState.fromWireValue(cursor.getString(19))
        val createdAt = cursor.getLong(20)
        val updatedAt = cursor.getLong(21)

        if (!canonicalUuid(operationId) || !canonicalUuid(runId) || !canonicalUuid(profileId) ||
            !canonicalUuid(ownerToken) || !canonicalUuid(previewId) || profileRevision <= 0L || stateVersion <= 0 ||
            ownerGeneration < 0L || !isDigest(profileFingerprint) || !isDigest(preflightFingerprint) ||
            !isDigest(observationFingerprint) ||
            !isDigest(previewFingerprint) || !isDigest(filterFingerprint) ||
            !PINNED_ENGINE_PATTERN.matches(engineRef) || preflightCheckedAt <= 0L ||
            userConfirmedAt < preflightCheckedAt || userConfirmedAt > createdAt || createdAt <= 0L || updatedAt < createdAt) {
            throw BisyncBackupManifestCorruptException("Stored backup-manifest owner snapshot is invalid")
        }

        val locations = readLocations(db, operationId)
        val left = locations.singleOrNull { it.side == BisyncBackupSide.LEFT }
            ?: throw BisyncBackupManifestCorruptException("Stored backup manifest is missing its left location")
        val right = locations.singleOrNull { it.side == BisyncBackupSide.RIGHT }
            ?: throw BisyncBackupManifestCorruptException("Stored backup manifest is missing its right location")
        if (locations.size != 2) {
            throw BisyncBackupManifestCorruptException("Stored backup manifest has an unexpected location count")
        }
        val previewIdentity = try {
            BisyncPreviewIdentity(
                profileId = profileId,
                profileRevision = profileRevision,
                profileFingerprint = profileFingerprint,
                engineRef = engineRef,
                stateVersion = stateVersion,
                leftAccountFingerprint = left.accountFingerprint,
                leftScopeFingerprint = left.endpointScopeFingerprint,
                rightAccountFingerprint = right.accountFingerprint,
                rightScopeFingerprint = right.endpointScopeFingerprint,
                filterFingerprint = filterFingerprint,
                comparisonMode = comparisonMode,
                initializationMode = initializationMode,
                maxDeletePercent = maxDeletePercent,
                maxDeleteCount = maxDeleteCount,
                nativeState = BisyncNativeState.ABSENT,
                acceptedBaselineFingerprint = null
            )
        } catch (_: IllegalArgumentException) {
            throw BisyncBackupManifestCorruptException("Stored preview policy snapshot is invalid")
        }
        val expectedPreflightFingerprint = BisyncPreflightPolicy.identityFingerprint(
            profileFingerprint, engineRef,
            left.accountFingerprint, left.endpointScopeFingerprint,
            right.accountFingerprint, right.endpointScopeFingerprint,
            filterFingerprint, comparisonMode, maxDeletePercent, maxDeleteCount, stateVersion
        )
        if (previewIdentity.fingerprint != previewFingerprint || expectedPreflightFingerprint != preflightFingerprint) {
            throw BisyncBackupManifestCorruptException("Stored preview and preflight identities are inconsistent")
        }

        return BisyncBackupManifestRecord(
            operationId = operationId,
            runId = runId,
            profileId = profileId,
            profileRevision = profileRevision,
            profileFingerprint = profileFingerprint,
            engineRef = engineRef,
            stateVersion = stateVersion,
            preflightFingerprint = preflightFingerprint,
            observationFingerprint = observationFingerprint,
            previewId = previewId,
            previewFingerprint = previewFingerprint,
            preflightCheckedAt = preflightCheckedAt,
            initializationMode = initializationMode,
            filterFingerprint = filterFingerprint,
            comparisonMode = comparisonMode,
            maxDeletePercent = maxDeletePercent,
            maxDeleteCount = maxDeleteCount,
            userConfirmedAt = userConfirmedAt,
            ownerToken = ownerToken,
            ownerGeneration = ownerGeneration,
            state = state,
            createdAt = createdAt,
            updatedAt = updatedAt,
            left = left,
            right = right
        )
    }

    private fun readLocations(db: SQLiteDatabase, operationId: String): List<BisyncBackupLocationRecord> {
        val cursor = db.query(
            DatabaseInfo.BISYNC_BACKUP_LOCATION_TABLE_NAME,
            locationProjection,
            "${DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_OPERATION_ID} = ?",
            arrayOf(operationId), null, null, DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_SIDE
        )
        return try {
            buildList {
                while (cursor.moveToNext()) {
                    val side = BisyncBackupSide.values().firstOrNull {
                        it.wireValue == cursor.getString(0)
                    } ?: throw BisyncBackupManifestCorruptException("Stored backup side is unknown")
                    val accountFingerprint = cursor.getString(1)
                    val endpointScopeFingerprint = cursor.getString(2)
                    val backupScopeFingerprint = cursor.getString(3)
                    val locator = cursor.getString(4)
                    val storedLocatorFingerprint = cursor.getString(5)
                    val createdAt = cursor.getLong(6)
                    validateLocator(locator, corruption = true)
                    if (!isDigest(accountFingerprint) || !isDigest(endpointScopeFingerprint) ||
                        !isDigest(backupScopeFingerprint) || createdAt <= 0L ||
                        BisyncEndpointScope.from(accountFingerprint, locator).fingerprint() != backupScopeFingerprint ||
                        locatorFingerprint(accountFingerprint, backupScopeFingerprint, locator) != storedLocatorFingerprint) {
                        throw BisyncBackupManifestCorruptException("Stored backup location identity is inconsistent")
                    }
                    add(BisyncBackupLocationRecord(
                        side, accountFingerprint, endpointScopeFingerprint, backupScopeFingerprint,
                        locator, storedLocatorFingerprint, createdAt
                    ))
                }
            }
        } finally {
            cursor.close()
        }
    }

    private fun validateLocator(value: String, corruption: Boolean = false): String {
        val valid = value.isNotBlank() && value.length <= MAX_LOCATOR_CHARS && '\u0000' !in value
        if (!valid) {
            if (corruption) throw BisyncBackupManifestCorruptException("Stored backup locator is invalid")
            throw BisyncBackupManifestRejectedException("Backup locator is invalid")
        }
        return value
    }

    private fun canonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun isDigest(value: String): Boolean = DIGEST_PATTERN.matches(value)

    private fun locatorFingerprint(accountFingerprint: String, scopeFingerprint: String, locator: String): String =
        sha256(canonical(listOf("bisync-backup-locator-v1", accountFingerprint, scopeFingerprint, locator)))

    private fun canonical(fields: List<String>): String = fields.joinToString(separator = "") { "${it.length}:$it" }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class PreparedLocation(
        val accountFingerprint: String,
        val endpointScopeFingerprint: String,
        val endpointScope: BisyncEndpointScope,
        val backupScopeFingerprint: String,
        val locator: String
    )

    private data class PreparedPair(
        val preflightFingerprint: String,
        val observationFingerprint: String,
        val left: PreparedLocation,
        val right: PreparedLocation
    )

    private data class VerifiedSnapshot(
        val preflightCheckedAt: Long,
        val initializationMode: BisyncPreviewResyncMode,
        val previewFingerprint: String
    )

    companion object {
        private const val MAX_LOCATOR_CHARS = 4096
        private val DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")
        private val PINNED_ENGINE_PATTERN = Regex("^rclone:[A-Za-z0-9._+-]+@[0-9a-f]{40}$")
        private val manifestProjection = arrayOf(
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OPERATION_ID,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_RUN_ID,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_ID,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_REVISION,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PROFILE_FINGERPRINT,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_ENGINE_REF,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_STATE_VERSION,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREFLIGHT_FINGERPRINT,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREVIEW_ID,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREVIEW_FINGERPRINT,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_PREFLIGHT_CHECKED_AT,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_INITIALIZATION_MODE,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_FILTER_FINGERPRINT,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_COMPARISON_MODE,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_MAX_DELETE_PERCENT,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_MAX_DELETE_COUNT,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_USER_CONFIRMED_AT,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OWNER_TOKEN,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OWNER_GENERATION,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_STATUS,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_CREATED_AT,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_UPDATED_AT,
            DatabaseInfo.BISYNC_BACKUP_MANIFEST_COLUMN_OBSERVATION_FINGERPRINT
        )
        private val locationProjection = arrayOf(
            DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_SIDE,
            DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_ACCOUNT_FINGERPRINT,
            DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_ENDPOINT_SCOPE_FINGERPRINT,
            DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_BACKUP_SCOPE_FINGERPRINT,
            DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_LOCATOR,
            DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_LOCATOR_FINGERPRINT,
            DatabaseInfo.BISYNC_BACKUP_LOCATION_COLUMN_CREATED_AT
        )
    }
}
