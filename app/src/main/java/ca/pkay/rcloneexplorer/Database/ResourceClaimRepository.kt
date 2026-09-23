package ca.pkay.rcloneexplorer.Database

import android.content.ContentValues
import android.content.Context
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.CLAIM_COLUMN_CREATED_AT
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.CLAIM_COLUMN_ID
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.CLAIM_COLUMN_OPERATION
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.CLAIM_COLUMN_RESOURCES
import ca.pkay.rcloneexplorer.Database.DatabaseInfo.Companion.CLAIM_TABLE_NAME
import ca.pkay.rcloneexplorer.util.EndpointResource
import ca.pkay.rcloneexplorer.util.FLog
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Durable resource claims. Unclosed claims survive process death and are never auto-expired. */
class ResourceClaimRepository(context: Context) {
    private val context = context.applicationContext

    fun acquire(operation: String, resources: List<EndpointResource>): ResourceClaimLease {
        require(operation.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid operation label" }
        val serialized = EndpointResource.serializeAll(resources)
        val claimId = UUID.randomUUID().toString()
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        db.beginTransaction()
        try {
            val cursor = db.query(
                CLAIM_TABLE_NAME,
                arrayOf(CLAIM_COLUMN_RESOURCES),
                null,
                null,
                null,
                null,
                null
            )
            try {
                while (cursor.moveToNext()) {
                    if (EndpointResource.overlapsStored(resources, cursor.getString(0))) {
                        throw ResourceClaimConflictException()
                    }
                }
            } finally {
                cursor.close()
            }
            val values = ContentValues().apply {
                put(CLAIM_COLUMN_ID, claimId)
                put(CLAIM_COLUMN_OPERATION, operation)
                put(CLAIM_COLUMN_RESOURCES, serialized)
                put(CLAIM_COLUMN_CREATED_AT, System.currentTimeMillis())
            }
            db.insertOrThrow(CLAIM_TABLE_NAME, null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        return ResourceClaimLease.create(context, claimId)
    }

    /** Records a fail-closed quarantine when an already-started process violates launch ownership. */
    fun recordUnresolvedGlobal(operation: String): ResourceClaimLease {
        require(operation.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid operation label" }
        val claimId = UUID.randomUUID().toString()
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put(CLAIM_COLUMN_ID, claimId)
                put(CLAIM_COLUMN_OPERATION, operation)
                put(CLAIM_COLUMN_RESOURCES, EndpointResource.serializeAll(listOf(EndpointResource.global())))
                put(CLAIM_COLUMN_CREATED_AT, System.currentTimeMillis())
            }
            db.insertOrThrow(CLAIM_TABLE_NAME, null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        return ResourceClaimLease.create(context, claimId)
    }

    fun activeCount(): Int {
        val handler = DatabaseHandler(context)
        val db = handler.readableDatabase
        return try {
            db.rawQuery("SELECT COUNT(*) FROM $CLAIM_TABLE_NAME", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } finally {
            db.close()
        }
    }

    internal fun release(claimId: String) {
        val handler = DatabaseHandler(context)
        val db = handler.writableDatabase
        try {
            db.delete(CLAIM_TABLE_NAME, "$CLAIM_COLUMN_ID = ?", arrayOf(claimId))
        } finally {
            db.close()
        }
    }
}

class ResourceClaimConflictException : IllegalStateException("An overlapping app operation is active or requires recovery")

class ResourceClaimLease private constructor(context: Context?, private val claimId: String?) : AutoCloseable {
    private val repository = context?.let(::ResourceClaimRepository)
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (claimId == null || closed.get()) return
        synchronized(this) {
            if (closed.get()) return
            try {
                repository?.release(claimId)
                closed.set(true)
            } catch (failure: RuntimeException) {
                FLog.e("ResourceClaim", "Unable to release a confirmed endpoint claim; it remains fail-closed", failure)
            }
        }
    }

    companion object {
        internal fun create(context: Context, claimId: String) = ResourceClaimLease(context, claimId)
        internal fun noop() = ResourceClaimLease(null, null)
    }
}
